package com.falconone.bodycamserver

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.sin

private const val TAG = "FalconTone"

/**
 * Señal sonora del PTT, como la de un walkie de verdad.
 *
 * El PTT de la unidad es un conmutador (el firmware solo avisa al soltar el
 * botón, ver [LivestreamService.togglePtt]), así que sin sonido el agente no
 * tiene forma de saber si acaba de abrir o de cerrar el micro: la unidad se
 * lleva en el pecho y la pantalla de 3 cm no se ve al hablar. El tono es la
 * única confirmación que le llega.
 *
 *   • [abrir]     subida  (880 → 1320 Hz)  "canal abierto, habla"
 *   • [cerrar]    bajada  (1320 → 880 Hz)  "canal cerrado, has soltado"
 *   • [denegado]  zumbido grave doble      "NO se abrió — no te están oyendo"
 *   • [entra]     pitido agudo suelto      "otro ha abierto el canal"
 *   • [sale]      pitido medio suelto      "el otro ha soltado, canal libre"
 *
 * Los cinco se distinguen sin mirar y sin aprenderlos, que es justo lo que hace
 * falta con el equipo puesto. La regla que los separa: **dos notas son tuyas,
 * una nota es de otro.**
 *
 * [entra] y [sale] no los usa la bodycam hoy: entra al canal con
 * `autoSubscribeAudio = false` y no oye a nadie — es una cámara, no una radio.
 * Viven aquí para que el vocabulario de tonos del sistema sea uno solo y las dos
 * copias sigan cuadrando; el día que se decida que la unidad reproduzca la voz de
 * los demás por su altavoz, el tono ya está.
 *
 * Se sintetizan en PCM en vez de tirar de ToneGenerator o de un .ogg: los tonos
 * de ToneGenerator son de telefonía (DTMF y supervisión) y no permiten la subida
 * y la bajada que hacen reconocible el par abrir/cerrar.
 */
object PttTones {

    private const val SAMPLE_RATE = 44_100
    private const val AMPLITUD    = 0.35   // el volumen se fija en la propia onda
    private const val AMPLITUD_RX = 0.22   // lo que llega de fuera suena más bajo: el
                                           // pitido no debe tapar la primera palabra

    /**
     * Los tonos se reproducen fuera del hilo que los pide y de uno en uno. Que no
     * bloqueen importa: [LivestreamService.stop] puede venir del hilo principal,
     * y un pitido de 200 ms ahí sería un tirón visible. Que vayan en serie
     * también: dos pulsaciones seguidas del botón sonarían encimadas.
     */
    private val altavoz = Executors.newSingleThreadExecutor()

    /** Micro abierto: ya se puede hablar. */
    fun abrir() = reproducir(listOf(880 to 70, 1320 to 110))

    /** Micro cerrado: el canal se ha soltado. */
    fun cerrar() = reproducir(listOf(1320 to 70, 880 to 110))

    /** El PTT no se abrió, o se ha caído solo. Nadie está oyendo al agente. */
    fun denegado() = reproducir(listOf(300 to 160, 0 to 70, 300 to 220))

    /**
     * Otro ha abierto el canal: su voz empieza a sonar. Una sola nota, no dos:
     * es lo que distingue de un plumazo la radio de los demás de la propia, y no
     * hay que aprendérselo.
     */
    fun entra() = reproducir(listOf(1568 to 90), AMPLITUD_RX)

    /** El que hablaba ha soltado: el canal queda libre. */
    fun sale() = reproducir(listOf(1046 to 90), AMPLITUD_RX)

    /** tramos = pares (frecuencia en Hz, duración en ms). Frecuencia 0 = silencio. */
    private fun reproducir(tramos: List<Pair<Int, Int>>, amplitud: Double = AMPLITUD) {
        altavoz.execute {
            val pcm = sintetizar(tramos, amplitud)
            var track: AudioTrack? = null
            try {
                track = construirTrack(pcm.size * 2)
                track.play()
                track.write(pcm, 0, pcm.size)
                // write() vuelve al copiar, no al sonar: sin esta espera el
                // release() de abajo cortaría el pitido por la mitad.
                Thread.sleep(tramos.sumOf { it.second }.toLong() + 80)
                track.stop()
            } catch (e: Exception) {
                // Un altavoz que falla no puede tumbar el PTT: se pierde el aviso
                // sonoro, no la transmisión.
                Log.w(TAG, "No se pudo emitir el tono del PTT: ${e.message}")
            } finally {
                try { track?.release() } catch (_: Exception) {}
            }
        }
    }

    /**
     * USAGE_ALARM a propósito: en la unidad los volúmenes de multimedia y
     * notificación se quedan a cero de fábrica y el pitido no se oiría. Si en
     * campo resulta demasiado fuerte, se baja bajando [AMPLITUD], no cambiando de
     * canal.
     */
    private fun construirTrack(bytes: Int): AudioTrack {
        val minimo = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(bytes, minimo))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun sintetizar(tramos: List<Pair<Int, Int>>, amplitud: Double): ShortArray {
        val muestras = tramos.map { (hz, ms) -> hz to ms * SAMPLE_RATE / 1000 }
        val pcm = ShortArray(muestras.sumOf { it.second })
        var i = 0
        for ((hz, n) in muestras) {
            // Rampa de 5 ms a la entrada y a la salida de cada tramo: cortar una
            // senoidal en seco suena a chasquido, no a walkie.
            val rampa = (SAMPLE_RATE * 5 / 1000).coerceAtMost(n / 2).coerceAtLeast(1)
            for (j in 0 until n) {
                if (hz > 0) {
                    val ganancia = when {
                        j < rampa      -> j.toDouble() / rampa
                        j >= n - rampa -> (n - j).toDouble() / rampa
                        else           -> 1.0
                    }
                    val v = sin(2 * PI * hz * j / SAMPLE_RATE) * amplitud * ganancia
                    pcm[i] = (v * Short.MAX_VALUE).toInt().toShort()
                }
                i++
            }
        }
        return pcm
    }
}
