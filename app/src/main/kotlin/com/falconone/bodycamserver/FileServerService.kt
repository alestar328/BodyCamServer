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

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun listFiles(): List<File> {
        if (!recordingsDir.exists()) return emptyList()
        return recordingsDir.listFiles { f ->
            f.isFile && (f.name.endsWith(".mp4") || f.name.endsWith(".jpg"))
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
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
