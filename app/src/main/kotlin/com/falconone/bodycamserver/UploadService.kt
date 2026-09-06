package com.falconone.bodycamserver

import android.app.IntentService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
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
         * Sube un incidente. Con [UploadConfig] configurado va por bloques y
         * reanudable ([ChunkedUploader]); si no, cae al multipart de siempre para
         * no dejar de subir en unidades que aún no tengan destino nuevo.
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

        /**
         * Reencola los incidentes que quedaron a medio subir.
         *
         * Es la otra mitad de la reanudación: [UploadSessions] recuerda por dónde
         * iba cada fichero, pero alguien tiene que volver a intentarlo. Se llama al
         * arrancar la unidad, que es justo cuando se pierde una subida en vuelo.
         *
         * Va con startForegroundService porque desde un receptor de arranque la
         * unidad no permite iniciar servicios en background.
         */
        fun resumePending(context: Context) {
            if (!UploadConfig.enabled()) return
            val pending = EvidenceStore.incidentIds().filter { !isDelivered(it) }
            if (pending.isEmpty()) return
            Log.d(TAG, "reanudando ${pending.size} incidente(s) sin entregar")
            pending.forEach { id ->
                context.startForegroundService(
                    Intent(context, UploadService::class.java)
                        .putExtra(EXTRA_INCIDENT_ID, id)
                )
            }
        }

        /**
         * Un incidente está entregado cuando existe su recibo y dice que el
         * servidor lo aceptó. El recibo lo escribe [writeReceipt] y es lo que
         * impide volver a subir lo mismo en cada arranque.
         */
        fun isDelivered(incidentId: String): Boolean = try {
            File(EvidenceStore.incidentDir(incidentId), RECEIPT_NAME)
                .takeIf { it.isFile }
                ?.let { JSONObject(it.readText()).optBoolean("delivered") } ?: false
        } catch (_: Exception) { false }

        const val RECEIPT_NAME = "upload.json"
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
            if (UploadConfig.enabled()) {
                uploadIncidentChunked(incidentId, lat, lon)
            } else {
                Log.w(TAG, "sin subida por bloques configurada — se usa el multipart anterior")
                uploadIncidentLegacy(incidentId, lat, lon)
            }
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
     * Sube un incidente por bloques, reanudable.
     *
     * ── Qué se sube ───────────────────────────────────────────────────────────
     *
     * El **`.fev` cifrado** si existe, y el MP4 en claro solo si el cifrado falló.
     * Esto invierte el comportamiento anterior, que mandaba siempre el claro: sin
     * la pública de Nexus el servidor no habría podido abrir un `.fev`, pero con
     * el destinatario `srv:` activo —hoy con clave de desarrollo, ver
     * [NexusKeyWrapper]— ya sí, y no hay razón para que la evidencia viaje en claro.
     *
     * Primero el `manifest.json` y después el vídeo, en ese orden a propósito: el
     * manifest es pequeño, llega en un momento y lleva dentro el `sha256_cipher`
     * que el servidor va a necesitar para verificar lo que viene detrás. Si la
     * subida del vídeo se corta y no vuelve en horas, al menos el repositorio sabe
     * qué incidente existe y qué debería estar recibiendo.
     *
     * ── Cuándo se da por entregado ────────────────────────────────────────────
     *
     * Solo cuando el servidor **no contradice** el hash. `verified == false` es un
     * incidente de integridad, no un fallo de red: se deja el recibo con la marca
     * y no se borra nada.
     */
    private fun uploadIncidentChunked(incidentId: String, lat: Double, lon: Double) {
        if (isDelivered(incidentId)) {
            Log.d(TAG, "$incidentId ya entregado — nada que hacer")
            return
        }

        val payload = payloadOf(incidentId)
        if (payload == null) {
            Log.e(TAG, "$incidentId no tiene fichero que subir")
            return
        }
        if (!payload.encrypted) {
            // Merece un aviso: significa que el cifrado falló al cerrar y que la
            // evidencia está saliendo de la unidad en claro.
            Log.w(TAG, "$incidentId se sube SIN cifrar — no hay .fev")
        }

        val base = mutableMapOf(
            "incident_id" to incidentId,
            "officer_code" to HardcodedOfficer.badge,
            "officer_name" to HardcodedOfficer.name,
            "device_id" to (Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                ?: "unknown-device"),
            "device_model" to android.os.Build.MODEL,
            "uploaded_at" to SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date()),
        )
        if (lat != 0.0 || lon != 0.0) {
            base["latitude"] = lat.toString()
            base["longitude"] = lon.toString()
        }

        // 1. Manifest. Si falla no se aborta: el vídeo es la evidencia, el manifest
        //    se puede reenviar después y el servidor ya tiene el hash en el propio
        //    Upload-Metadata del vídeo.
        EvidenceStore.manifestOf(incidentId)?.let { manifest ->
            notify("$incidentId — manifest")
            // El hash va también en la metadata, no solo como huella local: sin
            // declararlo el servidor no tiene contra qué comparar y el manifest
            // se queda sin verificar, que es medio incidente sin confirmar.
            val manifestSha = EvidenceCrypto.sha256(manifest)
            val outcome = ChunkedUploader.upload(
                file = manifest,
                fingerprint = manifestSha,
                metadata = base + mapOf(
                    "kind" to "manifest",
                    "filename" to manifest.name,
                    "sha256_cipher" to manifestSha,
                    "encrypted" to "false",
                    "crypto_format" to "none",
                ),
            )
            if (!outcome.delivered) Log.w(TAG, "$incidentId: manifest no subido (${outcome.error})")
        }

        // 2. Evidencia.
        notify("$incidentId — ${payload.file.name}")
        val outcome = ChunkedUploader.upload(
            file = payload.file,
            fingerprint = payload.fingerprint,
            metadata = base + mapOf(
                "kind" to "evidence",
                "filename" to payload.file.name,
                "encrypted" to payload.encrypted.toString(),
                "crypto_format" to if (payload.encrypted) "FEVD1" else "none",
                "sha256_cipher" to payload.fingerprint,
                "sha256_plain" to (payload.plainSha256 ?: ""),
            ),
        ) { sent, total ->
            notify("$incidentId — ${100 * sent / total}%")
        }

        writeReceipt(incidentId, payload, outcome)

        when {
            outcome.delivered && outcome.verified == false -> {
                Log.e(TAG, "$incidentId: el servidor NO confirma el hash — revisar")
                notify("$incidentId: hash no coincide")
            }
            outcome.delivered -> {
                Log.d(TAG, "$incidentId entregado (${outcome.bytesSent / 1024} KB enviados)")
                notify("$incidentId subido")
            }
            else -> {
                Log.e(TAG, "$incidentId sin entregar: ${outcome.error}")
                notify("$incidentId pendiente — se reanudará")
            }
        }
    }

    /**
     * Qué fichero representa al incidente y con qué huella se identifica.
     *
     * La huella es el SHA-256 del contenido que se va a subir, y viene ya calculado
     * del cifrado (`sha256_cipher` en el manifest) — no se vuelve a leer el fichero.
     * En el camino degradado sin `.fev` hay que calcularlo aquí; cuesta una pasada
     * de lectura, y es aceptable porque es la excepción.
     */
    private data class Payload(
        val file: File,
        val fingerprint: String,
        val encrypted: Boolean,
        val plainSha256: String?,
    )

    private fun payloadOf(incidentId: String): Payload? {
        val dir = EvidenceStore.incidentDir(incidentId)
        val crypto = EvidenceStore.manifestOf(incidentId)
            ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
            ?.optJSONObject("crypto")

        if (crypto != null) {
            val fev = File(dir, crypto.optString("encrypted_filename"))
            val sha = crypto.optString("sha256_cipher")
            if (fev.isFile && sha.isNotBlank()) {
                return Payload(fev, sha, true, crypto.optString("sha256_plain").takeIf { it.isNotBlank() })
            }
            Log.w(TAG, "$incidentId: el manifest declara cifrado pero falta el .fev")
        }

        val plain = EvidenceStore.incidentSegments(incidentId).firstOrNull() ?: return null
        val sha = EvidenceCrypto.sha256(plain)
        return Payload(plain, sha, false, sha)
    }

    /**
     * Recibo de entrega, junto a la evidencia. Es lo que consulta [resumePending]
     * para no volver a subir lo mismo, y lo que deja constancia en la propia unidad
     * de qué se mandó, cuándo y si el servidor confirmó el hash.
     */
    private fun writeReceipt(incidentId: String, payload: Payload, outcome: ChunkedUploader.Outcome) {
        val receipt = JSONObject().apply {
            put("delivered", outcome.delivered)
            put("verified_by_server", outcome.verified ?: JSONObject.NULL)
            put("filename", payload.file.name)
            put("encrypted", payload.encrypted)
            put("sha256_uploaded", payload.fingerprint)
            put("bytes", payload.file.length())
            put("session_url", outcome.sessionUrl ?: JSONObject.NULL)
            put("endpoint", UploadConfig.baseUrl())
            put("at_epoch_ms", System.currentTimeMillis())
            outcome.error?.let { put("error", it) }
        }
        try {
            File(EvidenceStore.incidentDir(incidentId), RECEIPT_NAME).writeText(receipt.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "no se pudo escribir el recibo de $incidentId: ${e.message}")
        }
    }

    /**
     * Camino anterior: cada segmento en un POST multipart propio, sin sesión ni
     * reanudación. Se conserva para las unidades que todavía no tengan destino de
     * subida por bloques configurado, para no dejarlas sin subir nada.
     */
    private fun uploadIncidentLegacy(incidentId: String, lat: Double, lon: Double) {
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
