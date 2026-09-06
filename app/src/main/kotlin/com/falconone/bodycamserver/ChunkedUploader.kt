package com.falconone.bodycamserver

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URL

private const val TAG = "FalconChunk"

/**
 * Subida de evidencia por bloques, reanudable.
 *
 * Implementa el cliente del contrato de `docs/UPLOAD-PROTOCOL.md` —tus 1.0.0 core
 * más Creation, más una extensión propia de verificación—. El servidor contra el
 * que se desarrolla hoy es el stub de `tools/tus_stub_server.py`; el día que Nexus
 * exponga el endpoint real solo cambia `base_url` en [UploadConfig].
 *
 * ── Qué resuelve ──────────────────────────────────────────────────────────────
 *
 * [UploadService] sube el fichero entero en un `POST multipart`. Con incidentes de
 * 2 GB sobre red móvil eso significa que un corte a los 2 GB obliga a repetirlo
 * todo. Aquí la subida está partida en peticiones `PATCH` independientes: cada una
 * que responde 204 es un punto de reanudación que sobrevive al corte, al reinicio
 * de la unidad y a reinstalar la app (ver [UploadSessions]).
 *
 * ── Cuatro operaciones ────────────────────────────────────────────────────────
 *
 *   crear      POST base/          → 201 + Location: URL de la sesión
 *   preguntar  HEAD sesión         → 200 + Upload-Offset: por dónde va el servidor
 *   mandar     PATCH sesión        → 204 + Upload-Offset: nuevo punto de reanudación
 *   verificar  GET sesión/status   → 200 + JSON con complete/verified
 *
 * La verificación no es decorativa: el `.fev` viaja cifrado, así que el hash es lo
 * **único** que el servidor puede comprobar sin tener ninguna clave. Hasta que
 * confirma `sha256_cipher`, la evidencia no está entregada — y sin eso no se puede
 * ni plantear borrar el original de la unidad (EVD-007).
 *
 * ── Dos decisiones de transporte, y por qué ───────────────────────────────────
 *
 * 1. Cada PATCH lleva **`Content-Length` real** (`setFixedLengthStreamingMode`), no
 *    `Transfer-Encoding: chunked`. `tus-java-client` usa `setChunkedStreamingMode(0)`,
 *    que además dispara `Expect: 100-continue`; es justo la combinación que rompen
 *    proxies y WAF en redes corporativas. Como el tamaño del bloque se conoce de
 *    antemano, no hay ninguna razón para no declararlo.
 * 2. El cuerpo se lee del fichero con un buffer pequeño y `RandomAccessFile.seek`,
 *    nunca cargando el bloque en memoria. La unidad ya va justa de RAM grabando.
 *
 * No se usa `tus-android-client`: son dos clases, la útil solo persiste
 * `fingerprint → URL` en SharedPreferences (aquí lo hace [UploadSessions], en la
 * sdcard, que sobrevive a más cosas) y la otra envuelve un `content://` Uri, que no
 * aplica — nuestra evidencia son `File`.
 */
object ChunkedUploader {

    /** Lectura de fichero a socket. Nada que ver con el tamaño del bloque. */
    private const val IO_BUFFER = 64 * 1024

    /** Fallos consecutivos tolerados. El contador se reinicia con cada avance. */
    private const val MAX_CONSECUTIVE_FAILURES = 6

    /** Espera entre reintentos; el último valor se repite. */
    private val BACKOFF_MILLIS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000, 30_000)

    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 120_000

    private const val TUS_VERSION = "1.0.0"
    private const val OFFSET_CONTENT_TYPE = "application/offset+octet-stream"

    /**
     * Resultado de un intento completo de subida.
     *
     * [verified] es deliberadamente tri-estado: `true` el servidor confirmó el
     * hash, `false` lo contradijo —eso es un incidente de integridad, no un fallo
     * de red— y `null` no se pronunció, que es lo que hará un backend que aún no
     * implemente la extensión de verificación.
     */
    data class Outcome(
        val delivered: Boolean,
        val verified: Boolean?,
        val sessionUrl: String?,
        val bytesSent: Long,
        val error: String? = null,
    )

    /**
     * Sube [file] y no vuelve hasta entregarlo o agotar los reintentos.
     *
     * [fingerprint] identifica el **contenido** (SHA-256 del ciphertext), no la
     * ruta: ver la nota de [UploadSessions] sobre por qué eso importa.
     * [metadata] viaja en `Upload-Metadata` y es lo que permite al servidor saber
     * de qué incidente es el fichero antes de recibirlo entero.
     */
    fun upload(
        file: File,
        fingerprint: String,
        metadata: Map<String, String>,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): Outcome {
        if (!file.isFile) return Outcome(false, null, null, 0, "no existe ${file.name}")
        if (!UploadConfig.enabled()) return Outcome(false, null, null, 0, "subida no configurada")

        val total = file.length()
        val chunk = UploadConfig.chunkBytes()
        var url = UploadSessions.urlFor(fingerprint, total)
        // null = no sabemos por dónde va el servidor y hay que preguntárselo.
        // Se invalida ante cualquier fallo, que es cuando deja de ser fiable.
        var offset: Long? = null
        var sent = 0L
        var failures = 0

        Log.d(TAG, "subiendo ${file.name} (${total / 1024} KB) — ${UploadConfig.describe()}" +
            if (url != null) " — reanudando sesión existente" else "")

        while (failures < MAX_CONSECUTIVE_FAILURES) {
            try {
                if (url == null) {
                    url = create(total, metadata)
                        ?: throw UploadException("el servidor no devolvió Location")
                    UploadSessions.remember(fingerprint, url, total)
                    offset = 0L
                    Log.d(TAG, "sesión abierta: $url")
                }

                // Se pregunta al empezar y después de cada fallo, no antes de cada
                // bloque: tras un corte a mitad de PATCH el servidor pudo quedarse
                // con una parte que nunca llegó a responderse, y ahí su offset es la
                // única verdad. Mientras los PATCH van respondiendo, el offset que
                // devuelven ya es esa verdad y preguntar otra vez sobra —serían
                // cientos de viajes de más en un incidente de 2 GB.
                val at = offset ?: head(url).also { offset = it }
                if (at < 0) {
                    Log.w(TAG, "la sesión ya no existe en el servidor — se abre otra")
                    UploadSessions.forget(fingerprint)
                    url = null
                    offset = null
                    failures++
                    sleep(failures)
                    continue
                }

                if (at >= total) {
                    val verified = verify(url)
                    UploadSessions.forget(fingerprint)
                    Log.d(TAG, "${file.name} entregado — verificación: ${describe(verified)}")
                    return Outcome(true, verified, url, sent)
                }

                val end = minOf(at + chunk, total)
                offset = patch(url, file, at, end - at)
                sent += end - at
                failures = 0
                UploadSessions.touch(fingerprint)
                onProgress(offset ?: end, total)
            } catch (e: OffsetConflict) {
                // 409: el servidor está en otro sitio. No hay que esperar —basta con
                // adoptar su offset—, pero sí cuenta como fallo: si ese offset no
                // avanza, esto sería un bucle infinito.
                offset = e.serverOffset.takeIf { it >= 0 }
                failures++
                Log.w(TAG, "conflicto de offset, el servidor va por ${e.serverOffset}")
            } catch (e: Exception) {
                offset = null
                failures++
                Log.w(TAG, "fallo ${failures}/$MAX_CONSECUTIVE_FAILURES en ${file.name}: ${e.message}")
                if (failures >= MAX_CONSECUTIVE_FAILURES) {
                    return Outcome(false, null, url, sent, e.message)
                }
                sleep(failures)
            }
        }
        return Outcome(false, null, url, sent, "reintentos agotados")
    }

    // ── Las cuatro operaciones ────────────────────────────────────────────────

    /** POST base/ → 201 con la URL de la sesión en `Location`. */
    private fun create(length: Long, metadata: Map<String, String>): String? {
        val base = UploadConfig.baseUrl()
        val conn = open(URL(base), "POST").apply {
            setRequestProperty("Upload-Length", length.toString())
            setRequestProperty("Upload-Metadata", encodeMetadata(metadata))
            setFixedLengthStreamingMode(0)
            doOutput = true
        }
        return try {
            conn.outputStream.close()
            val code = conn.responseCode
            if (code != 201) throw UploadException("create devolvió $code ${errorBody(conn)}")
            val location = conn.getHeaderField("Location")
                ?: throw UploadException("create sin cabecera Location")
            // Location puede ser relativa —el spec lo permite y el stub la manda
            // así—, y resolverla a mano contra la base es donde se cuelan los bugs.
            URL(URL(base), location).toString()
        } finally {
            conn.disconnect()
        }
    }

    /** HEAD sesión → offset del servidor, o -1 si la sesión ya no existe. */
    private fun head(url: String): Long {
        val conn = open(URL(url), "HEAD")
        return try {
            when (val code = conn.responseCode) {
                200, 204 -> conn.getHeaderField("Upload-Offset")?.toLongOrNull()
                    ?: throw UploadException("HEAD sin Upload-Offset")
                404, 410 -> -1L
                else -> throw UploadException("HEAD devolvió $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * PATCH sesión → manda [length] bytes de [file] desde [offset].
     *
     * `HttpURLConnection` de Android admite PATCH (va sobre OkHttp); el JDK de
     * escritorio no. El fallback a `POST` con `X-HTTP-Method-Override` cubre eso y,
     * de paso, los proxies que filtran verbos poco comunes — el stub lo acepta
     * igual, así que el camino alternativo también se puede probar.
     */
    private fun patch(url: String, file: File, offset: Long, length: Long): Long {
        val conn = try {
            open(URL(url), "PATCH")
        } catch (_: ProtocolException) {
            open(URL(url), "POST").apply {
                setRequestProperty("X-HTTP-Method-Override", "PATCH")
            }
        }.apply {
            setRequestProperty("Upload-Offset", offset.toString())
            setRequestProperty("Content-Type", OFFSET_CONTENT_TYPE)
            // Content-Length real, ni chunked ni Expect: 100-continue. Ver la nota
            // de cabecera de esta clase: es lo que rompe en proxies corporativos.
            setFixedLengthStreamingMode(length)
            doOutput = true
        }

        return try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                conn.outputStream.use { out ->
                    val buf = ByteArray(IO_BUFFER)
                    var remaining = length
                    while (remaining > 0) {
                        val read = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                        if (read <= 0) throw UploadException("el fichero se acabó antes de tiempo")
                        out.write(buf, 0, read)
                        remaining -= read
                    }
                }
            }

            when (val code = conn.responseCode) {
                // El Upload-Offset de la respuesta es el nuevo punto de reanudación.
                // Si el servidor no lo manda —el spec lo exige, pero no cuesta nada
                // ser tolerante— se asume que aceptó el bloque entero.
                204, 200 -> conn.getHeaderField("Upload-Offset")?.toLongOrNull()
                    ?: (offset + length)
                409 -> throw OffsetConflict(
                    conn.getHeaderField("Upload-Offset")?.toLongOrNull() ?: -1L
                )
                else -> throw UploadException("PATCH devolvió $code ${errorBody(conn)}")
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * GET sesión/status → ¿el servidor confirma el hash?
     *
     * Extensión propia, no forma parte de tus. Devuelve null si el servidor no la
     * implementa: un backend que solo hable tus estándar sigue funcionando, solo
     * que sin confirmación de integridad.
     */
    private fun verify(url: String): Boolean? {
        val conn = open(URL("$url/status"), "GET")
        return try {
            if (conn.responseCode != 200) {
                Log.w(TAG, "el servidor no implementa /status (${conn.responseCode})")
                return null
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            if (json.isNull("verified")) null else json.getBoolean("verified")
        } catch (e: Exception) {
            Log.w(TAG, "no se pudo leer el estado de la subida: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    // ── Internos ──────────────────────────────────────────────────────────────

    private fun open(url: URL, method: String): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            useCaches = false
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            setRequestProperty("Tus-Resumable", TUS_VERSION)
            setRequestProperty("Authorization", "Bearer ${UploadConfig.token()}")
        }

    /** `Upload-Metadata: clave <valor en base64>, clave <valor en base64>` */
    private fun encodeMetadata(metadata: Map<String, String>): String =
        metadata.entries.joinToString(",") { (key, value) ->
            "$key ${Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)}"
        }

    private fun errorBody(conn: HttpURLConnection): String = try {
        conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(200).orEmpty()
    } catch (_: Exception) { "" }

    private fun sleep(failures: Int) {
        val wait = BACKOFF_MILLIS[minOf(failures - 1, BACKOFF_MILLIS.lastIndex).coerceAtLeast(0)]
        try {
            Thread.sleep(wait)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun describe(verified: Boolean?): String = when (verified) {
        true -> "hash confirmado"
        false -> "HASH NO COINCIDE"
        null -> "el servidor no verifica"
    }

    private class UploadException(message: String) : Exception(message)

    private class OffsetConflict(val serverOffset: Long) :
        Exception("el servidor va por $serverOffset")
}
