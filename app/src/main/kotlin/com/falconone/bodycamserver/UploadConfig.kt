package com.falconone.bodycamserver

import android.os.Environment
import android.util.Log
import java.io.File

private const val TAG = "FalconUploadCfg"

/**
 * Dónde sube la unidad y con qué credencial.
 *
 * ── Por qué esto existe como fichero de configuración ─────────────────────────
 *
 * Nexus todavía no expone endpoint de subida por bloques. Backend pidió no
 * bloquearse por eso, así que el cliente se desarrolla y valida contra el stub de
 * `tools/tus_stub_server.py`, que implementa el mismo contrato
 * (`docs/UPLOAD-PROTOCOL.md`). Para que cambiar de destino no sea recompilar, la
 * configuración se lee de un fichero en la sdcard:
 *
 *     FalconOne/upload.conf
 *     base_url=http://192.168.1.40:1080/files/
 *     token=stub-token
 *
 * Se pone por adb sin tocar el APK:
 *
 *     adb shell "echo base_url=http://192.168.1.40:1080/files/ > /sdcard/FalconOne/upload.conf"
 *
 * Nota de red: tiene que ser la IP del PC en la LAN. `localhost` desde la unidad
 * es la unidad, no el PC.
 *
 * ── Lo que aquí es stub y lo que no ───────────────────────────────────────────
 *
 * [token] es un stub declarado: hoy vale cualquier cosa no vacía porque el
 * servidor de pruebas no valida nada. Existe para que el cliente ejercite el
 * camino de mandar la credencial, y para que el día que haya login de oficial
 * (AUTH-001) se sustituya la fuente del token y no el transporte.
 *
 * [DEFAULT_BASE_URL] queda vacío a propósito. Un default apuntando a producción
 * haría que una unidad sin `upload.conf` empezara a mandar evidencia a un sitio
 * que nadie ha confirmado; preferimos que no suba y lo diga en el log.
 */
object UploadConfig {

    private const val CONF_NAME = "upload.conf"

    /** TODO: la URL real de Nexus cuando backend confirme el endpoint. */
    private const val DEFAULT_BASE_URL = ""

    private const val DEFAULT_TOKEN = "stub-token"

    /**
     * Texto plano por petición PATCH. Cada PATCH que responde 204 es un punto de
     * reanudación: más pequeño reanuda con menos pérdida, más grande gasta menos
     * peticiones. 8 MB es aproximadamente un segmento del anillo y, a 20 Mbps,
     * unos 3 s de subida — que es lo que como mucho se repite tras un corte.
     */
    private const val DEFAULT_CHUNK_BYTES = 8 * 1024 * 1024

    private val confFile: File
        get() = File(File(Environment.getExternalStorageDirectory(), "FalconOne"), CONF_NAME)

    /**
     * Se relee en cada acceso en vez de cachearse. Una subida puede durar minutos
     * y estar reintentando durante horas; poder corregir la URL por adb sin
     * reiniciar la app ahorra un ciclo entero de prueba en la unidad.
     */
    private fun conf(): Map<String, String> {
        val file = confFile
        if (!file.isFile) return emptyMap()
        return try {
            file.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
                .associate { line ->
                    val i = line.indexOf('=')
                    line.substring(0, i).trim() to line.substring(i + 1).trim()
                }
        } catch (e: Exception) {
            Log.w(TAG, "no se pudo leer $CONF_NAME: ${e.message}")
            emptyMap()
        }
    }

    /** Siempre con barra final: la URL de creación es un directorio, no un recurso. */
    fun baseUrl(): String {
        val raw = conf()["base_url"]?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL
        return if (raw.isBlank() || raw.endsWith("/")) raw else "$raw/"
    }

    /**
     * Base de la API del backend: hoy solo los avisos del SOS (SosNotifier), con la
     * clave `api_url` del mismo upload.conf. Vacía mientras no se configure, por el
     * mismo motivo que [DEFAULT_BASE_URL].
     */
    fun apiUrl(): String {
        val raw = conf()["api_url"].orEmpty()
        return if (raw.isBlank() || raw.endsWith("/")) raw else "$raw/"
    }

    fun token(): String = conf()["token"]?.takeIf { it.isNotBlank() } ?: DEFAULT_TOKEN

    fun chunkBytes(): Int =
        conf()["chunk_bytes"]?.toIntOrNull()?.takeIf { it in 64 * 1024..64 * 1024 * 1024 }
            ?: DEFAULT_CHUNK_BYTES

    /**
     * Si la subida por bloques está operativa. Sin destino no se intenta: dejarlo
     * fallar en bucle llenaría el log y gastaría batería de la unidad.
     */
    fun enabled(): Boolean {
        if (conf()["enabled"] == "false") return false
        if (baseUrl().isBlank()) {
            Log.w(TAG, "sin base_url — configura FalconOne/$CONF_NAME o espera el endpoint real")
            return false
        }
        return true
    }

    fun describe(): String = "base_url=${baseUrl()} chunk=${chunkBytes() / 1024} KB"
}
