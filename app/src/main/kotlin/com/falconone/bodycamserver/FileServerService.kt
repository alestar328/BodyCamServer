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
            uri == "/incidents"         -> handleIncidentList()
            uri.startsWith("/incidents/") -> {
                val rest = uri.removePrefix("/incidents/")
                if (rest.contains('/')) handleDownload(rest.substringAfterLast('/'))
                else handleIncident(rest)
            }
            uri == "/status"            -> handleStatus()
            uri == "/preview"           -> handlePreview()
            uri == "/preview/stream"    -> handlePreviewStream()
            else -> newFixedLengthResponse(
                Response.Status.NOT_FOUND, MIME_JSON,
                """{"error":"Not found","path":"$uri"}""" as String
            )
        }
    }

    // GET /recordings
    // Lista plana de ficheros descargables, más reciente primero. El anillo
    // pre-evento nunca se expone: es material descartable, no evidencia.
    private fun handleList(): Response {
        val arr = JSONArray()
        listFiles().forEach { arr.put(describe(it)) }
        return jsonResponse(arr.toString())
    }

    // GET /recordings/latest
    private fun handleLatest(): Response {
        val latest = listFiles().firstOrNull()
            ?: return notFound("No recordings found")
        return jsonResponse(describe(latest).toString())
    }

    // GET /incidents — incidentes agrupados, con su manifest
    private fun handleIncidentList(): Response {
        val arr = JSONArray()
        EvidenceStore.incidentIds().forEach { id ->
            val segments = EvidenceStore.incidentSegments(id)
            arr.put(JSONObject().apply {
                put("incident_id", id)
                put("segment_count", segments.size)
                put("size_bytes", segments.sumOf { it.length() })
                put("size_mb", String.format("%.2f", segments.sumOf { it.length() } / 1_048_576.0))
                put("has_manifest", EvidenceStore.manifestOf(id) != null)
                put("url", "/incidents/$id")
            })
        }
        return jsonResponse(arr.toString())
    }

    // GET /incidents/{id} — manifest completo si existe, o los segmentos tal cual
    private fun handleIncident(incidentId: String): Response {
        val segments = EvidenceStore.incidentSegments(incidentId)
        if (segments.isEmpty()) return notFound("Incident not found")

        EvidenceStore.manifestOf(incidentId)?.let { manifest ->
            return try {
                jsonResponse(manifest.readText())
            } catch (e: Exception) {
                Log.w(TAG, "manifest ilegible de $incidentId: ${e.message}")
                jsonResponse(fallbackIncident(incidentId, segments).toString())
            }
        }
        // Sin manifest: incidente cortado a medias (batería, cierre forzado).
        // Se sirve igualmente para no dejar la evidencia inaccesible.
        return jsonResponse(fallbackIncident(incidentId, segments).toString())
    }

    private fun fallbackIncident(incidentId: String, segments: List<File>): JSONObject {
        val arr = JSONArray()
        segments.forEachIndexed { i, seg ->
            arr.put(describe(seg).apply { put("index", i) })
        }
        return JSONObject().apply {
            put("incident_id", incidentId)
            put("segment_count", segments.size)
            put("incomplete", true)
            put("segments", arr)
        }
    }

    // GET /recordings/{filename} | /incidents/{id}/{filename}
    private fun handleDownload(filename: String): Response {
        val file = resolve(filename) ?: return notFound("File not found")
        val mime = if (filename.endsWith(".jpg")) "image/jpeg" else "video/mp4"
        Log.d(TAG, "Serving ${file.name} (${file.length() / 1024}KB)")
        return newFixedLengthResponse(
            Response.Status.OK, mime, FileInputStream(file) as java.io.InputStream, file.length()
        )
    }

    /**
     * Localiza un fichero descargable por nombre. Los segmentos llevan el
     * instante de inicio en el nombre, así que son únicos entre incidentes.
     * Se comprueba la ruta canónica para que un nombre con ".." no salga del
     * árbol, y el anillo queda fuera del alcance por no mirarse nunca.
     */
    private fun resolve(filename: String): File? {
        val candidates = mutableListOf(File(recordingsDir, filename))
        EvidenceStore.incidentIds().forEach {
            candidates += File(EvidenceStore.incidentDir(it), filename)
        }
        val rootPath = recordingsDir.canonicalPath
        return candidates.firstOrNull { f ->
            f.isFile && f.canonicalPath.startsWith(rootPath) &&
                !f.canonicalPath.startsWith(EvidenceStore.bufferDir.canonicalPath)
        }
    }

    private fun describe(f: File) = JSONObject().apply {
        put("filename", f.name)
        put("size_bytes", f.length())
        put("size_mb", String.format("%.2f", f.length() / 1_048_576.0))
        put("type", if (f.name.endsWith(".jpg")) "photo" else "video")
        put("created_at", isoDate(f.lastModified()))
        put("url", "/recordings/${f.name}")
        // El teléfono ignora estas claves; están para poder reagrupar segmentos.
        f.parentFile?.takeIf { it.parentFile == EvidenceStore.incidentsDir }?.let {
            put("incident_id", it.name)
        }
    }

    private fun notFound(msg: String): Response = newFixedLengthResponse(
        Response.Status.NOT_FOUND, MIME_JSON, """{"error":"$msg"}""" as String
    )

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

    // isHoldingCamera y no isRecording: con el servicio armado la cámara ya está
    // abierta, y el teléfono puede encuadrar antes de que haya incidente.
    private fun liveSourceActive(): Boolean =
        PreviewController.isActive || RecordingActivity.isHoldingCamera

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

    /**
     * Todo lo descargable: ficheros del modelo anterior en la raíz, más los
     * segmentos de cada incidente. El anillo se excluye deliberadamente — se
     * borra solo y no debe llegar ni al teléfono ni al repositorio.
     */
    private fun listFiles(): List<File> {
        if (!recordingsDir.exists()) return emptyList()
        val incidentSegments = EvidenceStore.incidentIds()
            .flatMap { EvidenceStore.incidentSegments(it) }
        return (EvidenceStore.legacyFiles() + incidentSegments)
            .sortedByDescending { it.lastModified() }
    }

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
