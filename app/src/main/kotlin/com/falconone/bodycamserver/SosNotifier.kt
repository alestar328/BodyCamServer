package com.falconone.bodycamserver

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private const val TAG = "FalconSos"

/**
 * Avisa al backend de que la unidad empieza, sigue y termina un SOS, para que lo
 * grabe con Agora Cloud Recording. Contrato: docs/BACKEND-PROXY-AND-SOS.md del repo
 * de Nexus, §2. Es el gemelo del SosNotifier del teléfono.
 *
 * Hace falta porque la unidad no puede grabar su propio SOS: la cámara es exclusiva
 * y el livestream se la quita al anillo (ver LivestreamService.yieldCamera). Lo que
 * dura la emisión solo queda guardado si lo graba el backend, y el backend solo sabe
 * que tiene que hacerlo por estos avisos: Agora no avisa de que alguien empieza a
 * publicar vídeo.
 *
 * Nunca frena el SOS: todo va en su propio hilo, con pocos reintentos.
 */
object SosNotifier {

    private const val REINTENTOS = 3
    private const val ESPERA_INICIAL_MILLIS = 2_000L
    private const val LATIDO_SEGUNDOS = 10L
    private const val TIMEOUT_MILLIS = 10_000

    private val hilo = Executors.newSingleThreadScheduledExecutor()
    private var sosId: String? = null
    private var latido: ScheduledFuture<*>? = null

    /**
     * Idempotente: el SOS puede arrancar por dos caminos (entrar al canal, o
     * ascender la sesión del PTT a vídeo) y el backend no debe ver dos emergencias.
     */
    @Synchronized
    fun inicio(context: Context) {
        if (sosId != null) return
        val id = UUID.randomUUID().toString()
        sosId = id
        val cuerpo = JSONObject()
            .put("sos_id", id)
            .put("source", "bodycam")
            .put("channel", AGORA_CHANNEL)
            .put("uid", BODYCAM_UID)
            .put("officer_code", HardcodedOfficer.badge)
            .put("device_id", androidId(context))
            .put("device_model", Build.MODEL)
            .put("started_at", ahoraIso())
        hilo.execute { enviar("sos/start", cuerpo, REINTENTOS) }
        latido = hilo.scheduleWithFixedDelay(
            { enviar("sos/heartbeat", JSONObject().put("sos_id", id).put("at", ahoraIso()), 0) },
            LATIDO_SEGUNDOS,
            LATIDO_SEGUNDOS,
            TimeUnit.SECONDS,
        )
    }

    /** Sin SOS avisado no hace nada: una sesión solo de PTT nunca fue una emergencia. */
    @Synchronized
    fun fin() {
        val id = sosId ?: return
        sosId = null
        latido?.cancel(false)
        latido = null
        val cuerpo = JSONObject()
            .put("sos_id", id)
            .put("stopped_at", ahoraIso())
            .put("reason", "cancelled")
        hilo.execute { enviar("sos/stop", cuerpo, REINTENTOS) }
    }

    private fun enviar(ruta: String, cuerpo: JSONObject, reintentos: Int) {
        val base = UploadConfig.apiUrl()
        if (base.isBlank()) return
        for (intento in 0..reintentos) {
            if (post(base + ruta, cuerpo)) return
            if (intento < reintentos) Thread.sleep(ESPERA_INICIAL_MILLIS shl intento)
        }
        // Solo la ruta: el cuerpo lleva la placa del oficial.
        Log.w(TAG, "$ruta: el backend no ha contestado")
    }

    private fun post(url: String, cuerpo: JSONObject): Boolean {
        val conexion = URL(url).openConnection() as HttpURLConnection
        return try {
            conexion.requestMethod = "POST"
            conexion.connectTimeout = TIMEOUT_MILLIS
            conexion.readTimeout = TIMEOUT_MILLIS
            conexion.doOutput = true
            conexion.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conexion.setRequestProperty("Authorization", "Bearer ${UploadConfig.token()}")
            conexion.outputStream.use { it.write(cuerpo.toString().toByteArray(Charsets.UTF_8)) }
            conexion.responseCode in 200..299
        } catch (e: IOException) {
            false
        } finally {
            conexion.disconnect()
        }
    }

    // El mismo identificador que ya va en las subidas de la unidad.
    @SuppressLint("HardwareIds")
    private fun androidId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown-device"

    private fun ahoraIso(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
}
