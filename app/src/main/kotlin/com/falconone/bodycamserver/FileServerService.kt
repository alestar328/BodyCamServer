package com.falconone.bodycamserver

import android.os.Environment
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "FalconHTTP"

class FileServerService : NanoHTTPD(FILE_SERVER_PORT) {

    private val recordingsDir = File(
        Environment.getExternalStorageDirectory(), "FalconOne"
    )

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/')
        Log.d(TAG, "${session.method} $uri")

        return when {
            uri == "/recordings"        -> handleList()
            uri == "/recordings/latest" -> handleLatest()
            uri.startsWith("/recordings/") -> {
                val filename = uri.removePrefix("/recordings/")
                handleDownload(filename)
            }
            uri == "/status"            -> handleStatus()
            uri == "/benchmark"         -> handleBenchmark(session)
            uri == "/preview"           -> handlePreview()
            uri == "/preview/stream"    -> handlePreviewStream()
            else -> newFixedLengthResponse(
                Response.Status.NOT_FOUND, MIME_JSON,
                """{"error":"Not found","path":"$uri"}""" as String
            )
        }
    }

    // GET /recordings
    // Returns JSON array of all recordings sorted newest first
    private fun handleList(): Response {
        val files = listFiles()
        val arr = JSONArray()
        files.forEach { f ->
            arr.put(JSONObject().apply {
                put("filename", f.name)
                put("size_bytes", f.length())
                put("size_mb", String.format("%.2f", f.length() / 1_048_576.0))
                put("type", if (f.name.endsWith(".jpg")) "photo" else "video")
                put("created_at", isoDate(f.lastModified()))
                put("url", "/recordings/${f.name}")
            })
        }
        return jsonResponse(arr.toString())
    }

    // GET /recordings/latest
    // Returns metadata of the most recently created file
    private fun handleLatest(): Response {
        val latest = listFiles().firstOrNull()
            ?: return newFixedLengthResponse(
                Response.Status.NOT_FOUND, MIME_JSON,
                """{"error":"No recordings found"}""" as String
            )
        val obj = JSONObject().apply {
            put("filename", latest.name)
            put("size_bytes", latest.length())
            put("size_mb", String.format("%.2f", latest.length() / 1_048_576.0))
            put("type", if (latest.name.endsWith(".jpg")) "photo" else "video")
            put("created_at", isoDate(latest.lastModified()))
            put("url", "/recordings/${latest.name}")
        }
        return jsonResponse(obj.toString())
    }

    // GET /recordings/{filename}
    // Streams the file binary — supports range requests for video seeking
    private fun handleDownload(filename: String): Response {
        val file = File(recordingsDir, filename)
        if (!file.exists() || !file.canonicalPath.startsWith(recordingsDir.canonicalPath)) {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND, MIME_JSON,
                """{"error":"File not found"}""" as String
            )
        }
        val mime = if (filename.endsWith(".jpg")) "image/jpeg" else "video/mp4"
        Log.d(TAG, "Serving ${file.name} (${file.length() / 1024}KB)")
        return newFixedLengthResponse(
            Response.Status.OK, mime, FileInputStream(file) as java.io.InputStream, file.length()
        )
    }

    /**
     * GET /benchmark[?file=<nombre>][&repeats=N] — coste de hashear y cifrar en
     * esta unidad. Sin parámetro mide sobre el segmento más reciente.
     *
     * Es una herramienta de medición, no parte del producto: no la llama nadie
     * y se quita borrando esta función, su línea en serve() y CryptoBenchmark.kt.
     */
    private fun handleBenchmark(session: IHTTPSession): Response {
        val name = session.parameters["file"]?.firstOrNull()
        val repeats = session.parameters["repeats"]?.firstOrNull()?.toIntOrNull()?.coerceIn(1, 10) ?: 3

        val target = if (name != null) resolve(name)
                     else listFiles().firstOrNull { it.name.endsWith(".mp4") }
        if (target == null || !target.isFile) {
            return notFound(if (name != null) "File not found" else "No hay ningún .mp4 sobre el que medir")
        }

        return try {
            jsonResponse(CryptoBenchmark.run(target, repeats).toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "benchmark falló: ${e.message}")
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, MIME_JSON,
                """{"error":"benchmark failed","detail":"${e.message}"}""" as String
            )
        }
    }

    // GET /status  — quick health check
    private fun handleStatus(): Response {
        val obj = JSONObject().apply {
            put("service", "FalconOne File Server")
            put("port", FILE_SERVER_PORT)
            put("recording_count", listFiles().size)
            put("storage_free_mb", storageFree())
            put("timestamp", isoDate(System.currentTimeMillis()))
        }
        return jsonResponse(obj.toString())
    }

    // Fuente viva de frames: el visor de foto (PREVIEW_START) o, si se está
    // grabando, el monitor de RecordingActivity. Nunca están activos a la vez
    // (la cámara es exclusiva), así que el orden solo resuelve el desempate.
    private fun liveJpeg(): ByteArray? =
        PreviewController.latestJpeg() ?: RecordingActivity.latestJpeg()

    private fun liveSourceActive(): Boolean =
        PreviewController.isActive || RecordingActivity.isRecording

    // GET /preview — último frame JPEG del visor remoto o de la grabación.
    // Un solo frame; útil para diagnóstico con curl. La app usa /preview/stream.
    private fun handlePreview(): Response {
        val jpeg = liveJpeg()
            ?: return newFixedLengthResponse(
                Response.Status.NOT_FOUND, MIME_JSON,
                """{"error":"Preview not active"}""" as String
            )
        return newFixedLengthResponse(
            Response.Status.OK, "image/jpeg",
            java.io.ByteArrayInputStream(jpeg) as java.io.InputStream, jpeg.size.toLong()
        )
    }

    // GET /preview/stream — MJPEG continuo (multipart/x-mixed-replace): un hilo
    // empuja el frame actual cada STREAM_FRAME_MILLIS por la misma conexión.
    // Termina solo cuando el visor se apaga o el cliente corta la conexión
    // (la escritura falla y el hilo muere).
    private fun handlePreviewStream(): Response {
        if (!liveSourceActive()) {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND, MIME_JSON,
                """{"error":"Preview not active"}""" as String
            )
        }
        val entrada = java.io.PipedInputStream(STREAM_PIPE_BYTES)
        val salida = java.io.PipedOutputStream(entrada)
        Thread {
            try {
                while (liveSourceActive()) {
                    // Sin frame todavía (la grabación acaba de arrancar): se
                    // espera al siguiente tick en vez de cortar el stream.
                    val jpeg = liveJpeg()
                    if (jpeg == null) {
                        Thread.sleep(STREAM_FRAME_MILLIS)
                        continue
                    }
                    salida.write(
                        ("--$STREAM_BOUNDARY\r\n" +
                            "Content-Type: image/jpeg\r\n" +
                            "Content-Length: ${jpeg.size}\r\n\r\n").toByteArray()
                    )
                    salida.write(jpeg)
                    salida.write("\r\n".toByteArray())
                    salida.flush()
                    Thread.sleep(STREAM_FRAME_MILLIS)
                }
            } catch (_: Exception) {
                // Cliente desconectado o visor apagado: fin normal del stream.
            } finally {
                try { salida.close() } catch (_: Exception) {}
            }
        }.start()
        return newChunkedResponse(
            Response.Status.OK,
            "multipart/x-mixed-replace; boundary=$STREAM_BOUNDARY",
            entrada
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun listFiles(): List<File> {
        if (!recordingsDir.exists()) return emptyList()
        return recordingsDir.listFiles { f ->
            f.isFile && (f.name.endsWith(".mp4") || f.name.endsWith(".jpg"))
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /**
     * Ruta dentro de FalconOne/ a partir de un nombre recibido por HTTP.
     *
     * Devuelve null si el nombre se sale del directorio: sin esta comprobación
     * un "../../" en la URL serviría cualquier fichero del almacenamiento.
     */
    private fun resolve(name: String): File? {
        val file = File(recordingsDir, name)
        val root = recordingsDir.canonicalPath
        val path = file.canonicalPath
        return if (path == root || path.startsWith(root + File.separator)) file else null
    }

    private fun notFound(message: String) =
        newFixedLengthResponse(
            Response.Status.NOT_FOUND, MIME_JSON,
            """{"error":"$message"}""" as String
        )

    private fun isoDate(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date(ms))

    private fun storageFree(): Long {
        if (!recordingsDir.exists()) recordingsDir.mkdirs()
        val stat = android.os.StatFs(recordingsDir.absolutePath)
        return stat.availableBlocksLong * stat.blockSizeLong / 1_048_576
    }

    private fun jsonResponse(body: String) =
        newFixedLengthResponse(Response.Status.OK, MIME_JSON, body as String)

    companion object {
        const val MIME_JSON = "application/json"

        // ~6-7 fps: fluido para encuadrar sin saturar la radio 2.4 GHz que
        // el WiFi comparte con el enlace BT del teléfono.
        private const val STREAM_FRAME_MILLIS = 150L
        private const val STREAM_BOUNDARY = "falconframe"
        private const val STREAM_PIPE_BYTES = 128 * 1024

        @Volatile private var instance: FileServerService? = null

        fun start(): Boolean {
            return try {
                if (instance?.isAlive == true) return true
                instance = FileServerService().also { it.start(5000, false) }
                Log.d(TAG, "HTTP server started on port $FILE_SERVER_PORT")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start HTTP server: ${e.message}")
                false
            }
        }

        fun stop() {
            instance?.stop()
            instance = null
            Log.d(TAG, "HTTP server stopped")
        }

        fun isRunning() = instance?.isAlive == true
    }
}
