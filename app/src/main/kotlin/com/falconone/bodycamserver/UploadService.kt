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
        const val EXTRA_FILE_PATH   = "file_path"
        const val EXTRA_INCIDENT_ID = "incident_id"
        const val EXTRA_LATITUDE    = "latitude"
        const val EXTRA_LONGITUDE   = "longitude"

        /** Sube un fichero suelto (foto, o grabación del modelo anterior). */
        fun start(context: Context, filePath: String, lat: Double = 0.0, lon: Double = 0.0) {
            context.startService(
                Intent(context, UploadService::class.java).apply {
                    putExtra(EXTRA_FILE_PATH, filePath)
                    putExtra(EXTRA_LATITUDE, lat)
                    putExtra(EXTRA_LONGITUDE, lon)
                }
            )
        }

        /**
         * Sube todos los segmentos de un incidente. Cada segmento viaja como una
         * petición propia —el endpoint actual no tiene sesión ni reanudación— pero
         * llevan incident_id e índice para que el repositorio pueda reagruparlos.
         */
        fun startIncident(context: Context, incidentId: String, lat: Double = 0.0, lon: Double = 0.0) {
            context.startService(
                Intent(context, UploadService::class.java).apply {
                    putExtra(EXTRA_INCIDENT_ID, incidentId)
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
        intent ?: return
        val lat = intent.getDoubleExtra(EXTRA_LATITUDE, 0.0)
        val lon = intent.getDoubleExtra(EXTRA_LONGITUDE, 0.0)

        val incidentId = intent.getStringExtra(EXTRA_INCIDENT_ID)
        if (incidentId != null) {
            uploadIncident(incidentId, lat, lon)
            return
        }

        val filePath = intent.getStringExtra(EXTRA_FILE_PATH) ?: return
        val file = File(filePath)
        if (!file.exists()) {
            Log.e(TAG, "File not found: $filePath")
            return
        }
        uploadOne(file, lat, lon, null, 0, 1)
    }

    /**
     * Segmentos en orden. Se sube cada uno por separado en vez de concatenar el
     * incidente: un fallo solo cuesta reintentar un segmento, y el repositorio
     * puede empezar a transcribir antes de que llegue el resto.
     */
    private fun uploadIncident(incidentId: String, lat: Double, lon: Double) {
        val segments = EvidenceStore.incidentSegments(incidentId)
        if (segments.isEmpty()) {
            Log.e(TAG, "Incidente sin segmentos: $incidentId")
            return
        }
        Log.d(TAG, "Subiendo $incidentId — ${segments.size} segmentos")

        var ok = 0
        segments.forEachIndexed { index, seg ->
            notify("$incidentId — segmento ${index + 1}/${segments.size}")
            if (uploadOne(seg, lat, lon, incidentId, index, segments.size)) ok++
        }

        if (ok == segments.size) {
            Log.d(TAG, "Incidente $incidentId subido completo ($ok/${segments.size})")
            notify("$incidentId subido ($ok/${segments.size})")
        } else {
            Log.e(TAG, "Incidente $incidentId incompleto ($ok/${segments.size})")
            notify("$incidentId incompleto: $ok de ${segments.size}")
        }
    }

    private fun uploadOne(
        file: File,
        lat: Double,
        lon: Double,
        incidentId: String?,
        index: Int,
        total: Int,
    ): Boolean {
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?: "unknown-device"
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())
        val metadata = buildMetadata(deviceId, lat, lon, timestamp, incidentId, index, total)

        Log.d(TAG, "Uploading ${file.name} (${file.length() / 1024}KB) device=$deviceId")
        val success = uploadMultipart(file, metadata)

        if (success) Log.d(TAG, "Upload OK — ${file.name}")
        else Log.e(TAG, "Upload FAILED — ${file.name}")
        return success
    }

    private fun buildMetadata(
        deviceId: String,
        lat: Double,
        lon: Double,
        ts: String,
        incidentId: String?,
        index: Int,
        total: Int,
    ): String {
        val loc = if (lat != 0.0 || lon != 0.0) ""","location":{"lat":$lat,"lon":$lon}""" else ""
        // incident_id e índice permiten al repositorio reagrupar los segmentos de
        // una misma sesión; sin ellos llegarían como grabaciones independientes.
        val session = if (incidentId != null)
            ""","incident_id":"$incidentId","segment_index":$index,"segment_count":$total"""
        else ""
        return """{"device_id":"$deviceId","device_model":"${android.os.Build.MODEL}"$loc,"timestamp":"$ts"$session}"""
    }

    private fun uploadMultipart(file: File, metadata: String): Boolean {
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
