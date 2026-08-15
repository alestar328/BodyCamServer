package com.falconone.bodycamserver

import android.media.MediaRecorder
import android.os.Handler
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import java.io.File

private const val TAG = "FalconRecWatch"

/**
 * Vigilancia de una grabación larga: deja en logcat en qué minuto y por qué dejó
 * de escribirse el fichero.
 *
 * Existe porque una grabación de una hora que falla a los cincuenta minutos no
 * deja ninguna pista: MediaRecorder avisa por callbacks que nadie tenía puestos,
 * y el MP4 truncado no dice nada. Repetir la prueba a ciegas cuesta otra hora.
 *
 * Cubre los dos modos de fallo, que se detectan distinto:
 *
 *   - **Fallo anunciado.** El grabador emite error (100 = servidor de media
 *     muerto) o info (802 = tope de tamaño). Lo cazan los listeners.
 *
 *   - **Parada silenciosa.** El grabador se cree vivo pero el encoder ya no
 *     entrega frames. No hay callback: la única señal es que el fichero deja de
 *     crecer, y por eso el latido mide el crecimiento en vez de limitarse a
 *     decir que sigue en marcha.
 *
 * Es instrumentación de la prueba de cifrado, no parte del producto: se quita
 * borrando este fichero y las llamadas a [attach], [onStarted] y [onStopped].
 */
class RecorderWatch(private val output: File, private val handler: Handler) {

    private var startedAt = 0L
    private var lastSize = 0L
    private var lastTickAt = 0L
    @Volatile private var running = false

    fun attach(recorder: MediaRecorder) {
        recorder.setOnErrorListener { _, what, extra ->
            Log.e(TAG, "ERROR ${errorName(what)} (extra=$extra) — ${snapshot()}")
            // No se intenta parar el grabador: tras un error queda en estado
            // Error y stop() vuelve a lanzar. El MP4 se ha quedado sin moov, así
            // que la hora está perdida igual; lo que aporta esto es saberlo.
            Log.e(TAG, "el fichero queda truncado y sin índice — grabación perdida")
        }
        recorder.setOnInfoListener { _, what, extra ->
            Log.w(TAG, "INFO ${infoName(what)} (extra=$extra) — ${snapshot()}")
        }
    }

    fun onStarted() {
        startedAt = SystemClock.elapsedRealtime()
        lastTickAt = startedAt
        lastSize = 0L
        running = true
        Log.d(TAG, "vigilando ${output.name} — ${freeMb()} MB libres")
        handler.postDelayed(tick, HEARTBEAT_MILLIS)
    }

    fun onStopped() {
        if (!running) return
        running = false
        handler.removeCallbacks(tick)
        Log.d(TAG, "fin de ${output.name} — ${snapshot()}")
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return

            val now = SystemClock.elapsedRealtime()
            val size = output.length()
            val grown = size - lastSize
            val minutes = (now - lastTickAt) / 60_000.0
            val rate = if (minutes > 0) grown / MB / minutes else 0.0

            Log.d(TAG, "%s  %.1f MB  (+%.1f MB/min)  libre %d MB"
                .format(clock(now - startedAt), size / MB, rate, freeMb()))

            // Sin crecimiento no hay callback que lo anuncie: es el único aviso
            // de que el encoder se ha parado con el grabador todavía "activo".
            if (grown <= 0L) {
                Log.e(TAG, "el fichero no ha crecido en el último minuto — encoder parado")
            }
            if (freeMb() < LOW_STORAGE_MB) {
                Log.w(TAG, "queda poco espacio (${freeMb()} MB) — la grabación va a cortarse")
            }

            lastSize = size
            lastTickAt = now
            handler.postDelayed(this, HEARTBEAT_MILLIS)
        }
    }

    private fun snapshot(): String =
        "t=${clock(SystemClock.elapsedRealtime() - startedAt)} " +
        "tamaño=%.1f MB libre=${freeMb()} MB".format(output.length() / MB)

    private fun freeMb(): Long {
        val dir = output.parentFile ?: return -1
        return try {
            val stat = StatFs(dir.absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong / (1024 * 1024)
        } catch (e: Exception) {
            -1
        }
    }

    private fun clock(millis: Long): String {
        val s = millis / 1000
        return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    private fun errorName(what: Int): String = when (what) {
        MediaRecorder.MEDIA_RECORDER_ERROR_UNKNOWN -> "desconocido($what)"
        MediaRecorder.MEDIA_ERROR_SERVER_DIED -> "servidor de media caído($what)"
        else -> "código $what"
    }

    private fun infoName(what: Int): String = when (what) {
        MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED -> "duración máxima alcanzada($what)"
        MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING -> "acercándose al tamaño máximo($what)"
        MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED -> "tamaño máximo alcanzado($what)"
        else -> "código $what"
    }

    private companion object {
        const val HEARTBEAT_MILLIS = 60_000L
        const val LOW_STORAGE_MB = 500L
        const val MB = 1024.0 * 1024.0
    }
}
