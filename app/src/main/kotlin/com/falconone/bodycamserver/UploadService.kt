package com.falconone.bodycamserver

import android.app.IntentService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private const val TAG = "FalconUpload"
private const val UPLOAD_URL = "https://nexus.aeriaone.com/api/incidents/upload/"
private const val NOTIF_CHANNEL = "falcon_upload"
private const val NOTIF_ID = 2

class UploadService : IntentService("FalconUploadService") {

    companion object {
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_LATITUDE  = "latitude"
        const val EXTRA_LONGITUDE = "longitude"

        fun start(context: Context, filePath: String, lat: Double = 0.0, lon: Double = 0.0) {
            context.startService(
                Intent(context, UploadService::class.java).apply {
                    putExtra(EXTRA_FILE_PATH, filePath)
                    putExtra(EXTRA_LATITUDE, lat)
                    putExtra(EXTRA_LONGITUDE, lon)
                }
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Subiendo video…"))
    }

    override fun onHandleIntent(intent: Intent?) {
        val filePath = intent?.getStringExtra(EXTRA_FILE_PATH) ?: return
        val lat = intent.getDoubleExtra(EXTRA_LATITUDE, 0.0)
        val lon = intent.getDoubleExtra(EXTRA_LONGITUDE, 0.0)

        val file = File(filePath)
        if (!file.exists()) {
            Log.e(TAG, "File not found: $filePath")
            return
        }

        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?: "unknown-device"

        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())
        val metadata = buildMetadata(deviceId, lat, lon, timestamp)

        Log.d(TAG, "Uploading ${file.name} (${file.length() / 1024}KB) device=$deviceId")
        notify("Subiendo ${file.name}…")

        val success = uploadMultipart(file, deviceId, metadata)

        if (success) {
            Log.d(TAG, "Upload OK — ${file.name}")
            notify("Subido correctamente: ${file.name}")
        } else {
            Log.e(TAG, "Upload FAILED — ${file.name}")
            notify("Error al subir: ${file.name}")
        }
    }

    private fun buildMetadata(deviceId: String, lat: Double, lon: Double, ts: String): String {
        val loc = if (lat != 0.0 || lon != 0.0) ""","location":{"lat":$lat,"lon":$lon}""" else ""
        return """{"device_id":"$deviceId","device_model":"YIMAO W1"$loc,"timestamp":"$ts"}"""
    }

    private fun uploadMultipart(file: File, deviceId: String, metadata: String): Boolean {
        val boundary = UUID.randomUUID().toString()
        val crlf = "\r\n"
        val twoHyphens = "--"

        return try {
            val url = URL(UPLOAD_URL)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                doOutput = true
                doInput = true
                useCaches = false
                requestMethod = "POST"
                setRequestProperty("Connection", "Keep-Alive")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                connectTimeout = 15_000
                readTimeout = 120_000
            }

            conn.outputStream.buffered().use { out ->
                val writer = OutputStreamWriter(out, Charsets.UTF_8)

                // Field: officer_code (required by Nexus API)
                writer.write("$twoHyphens$boundary$crlf")
                writer.write("Content-Disposition: form-data; name=\"officer_code\"$crlf$crlf")
                writer.write("off-001")
                writer.write(crlf)

                // Field: raw_metadata
                writer.write("$twoHyphens$boundary$crlf")
                writer.write("Content-Disposition: form-data; name=\"raw_metadata\"$crlf$crlf")
                writer.write(metadata)
                writer.write(crlf)

                // File: video or photo
                val isPhoto = file.name.lowercase().endsWith(".jpg") || file.name.lowercase().endsWith(".jpeg")
                val fieldName = if (isPhoto) "photo_file" else "video_file"
                val mimeType  = if (isPhoto) "image/jpeg" else "video/mp4"
                writer.write("$twoHyphens$boundary$crlf")
                writer.write("Content-Disposition: form-data; name=\"$fieldName\"; filename=\"${file.name}\"$crlf")
                writer.write("Content-Type: $mimeType$crlf$crlf")
                writer.flush()

                FileInputStream(file).use { fis ->
                    val buf = ByteArray(4096)
                    var read: Int
                    while (fis.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                    }
                }

                writer.write(crlf)
                writer.write("$twoHyphens$boundary$twoHyphens$crlf")
                writer.flush()
            }

            val code = conn.responseCode
            val body = try {
                BufferedReader(InputStreamReader(
                    if (code in 200..299) conn.inputStream else conn.errorStream
                )).readText()
            } catch (_: Exception) { "" }

            Log.d(TAG, "Response $code: $body")
            conn.disconnect()
            code in 200..299
        } catch (e: Exception) {
            Log.e(TAG, "Upload exception: ${e.message}")
            false
        }
    }

    private fun notify(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(NOTIF_CHANNEL, "FalconOne Upload", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("FalconOne — Subida")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .build()
}
