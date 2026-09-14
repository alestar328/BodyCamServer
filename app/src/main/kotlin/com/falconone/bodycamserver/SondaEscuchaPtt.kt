package com.falconone.bodycamserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Process
import android.util.Log
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import java.io.File
import java.util.concurrent.Executors

private const val TAG = "SondaPtt"

/**
 * Sonda de medicion, SOLO en debug: la unidad escuchando el canal de Agora, que es la
 * base del PTT del telefono sonando en la bodycam (requisitos del 2026-09-14).
 *
 * Contesta tres preguntas antes de construir nada:
 *  1. Si Agora escuchando, sin capturar, deja el microfono al anillo: la grabacion
 *     tiene que conservar su audio mientras suena el PTT.
 *  2. Cuanta corriente y cuantos datos cuesta estar en el canal sin que nadie hable.
 *  3. Si el audio sale por el altavoz y en que modo de audio queda la unidad.
 *
 * Se maneja por broadcast desde adb (ver [registrar]). No se puede usar a la vez que
 * el SOS o el PTT de la unidad: Agora solo admite un motor por proceso, y el
 * `RtcEngine.destroy()` de LivestreamService se llevaria tambien este.
 */
object SondaEscuchaPtt {

    private const val ACCION = "com.falconone.bodycamserver.SONDA_PTT"
    private const val MUESTRA_SEGUNDOS = 10L
    private const val FASE_BATERIA_SEGUNDOS = 600

    private val hilo = Executors.newSingleThreadExecutor()
    @Volatile private var engine: RtcEngine? = null
    @Volatile private var midiendo = false

    /**
     * adb shell am broadcast -a com.falconone.bodycamserver.SONDA_PTT --es orden <orden>
     *   escuchar  entra al canal solo a escuchar
     *   parar     sale del canal
     *   bateria   10 min sin escuchar y 10 min escuchando, con muestras cada 10 s
     */
    fun registrar(context: Context) {
        val receptor = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.getStringExtra("orden")) {
                    "escuchar" -> hilo.execute { escuchar(ctx.applicationContext) }
                    "parar" -> hilo.execute { parar() }
                    "bateria" -> Thread { medirBateria(ctx.applicationContext) }.start()
                    else -> Log.w(TAG, "Orden desconocida")
                }
            }
        }
        context.registerReceiver(receptor, IntentFilter(ACCION))
        Log.i(TAG, "Sonda lista")
    }

    private fun escuchar(context: Context) {
        if (engine != null) return
        if (LivestreamService.isStreaming || LivestreamService.isMicEnabled) {
            Log.w(TAG, "SOS o PTT abiertos: la sonda no entra")
            return
        }
        val config = RtcEngineConfig().apply {
            mAppId = AGORA_APP_ID
            mContext = context
            mEventHandler = manejador(context)
        }
        val eng = RtcEngine.create(config)
        eng.setChannelProfile(Constants.CHANNEL_PROFILE_LIVE_BROADCASTING)
        eng.setClientRole(Constants.CLIENT_ROLE_AUDIENCE)
        eng.enableAudio()
        // Antes de entrar: si la captura llega a arrancar, le quita el micro al
        // anillo, que es justo lo que se esta midiendo.
        eng.enableLocalAudio(false)
        eng.setDefaultAudioRoutetoSpeakerphone(true)
        eng.enableAudioVolumeIndication(1000, 3, false)
        val opciones = ChannelMediaOptions().apply {
            channelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
            clientRoleType = Constants.CLIENT_ROLE_AUDIENCE
            publishMicrophoneTrack = false
            publishCameraTrack = false
            autoSubscribeAudio = true
            autoSubscribeVideo = false
        }
        engine = eng
        val resultado = eng.joinChannel(null, AGORA_CHANNEL, BodycamIdentity.uidAgora(context), opciones)
        Log.i(TAG, "Entrando a escuchar: $resultado")
    }

    private fun parar() {
        val eng = engine ?: return
        eng.leaveChannel()
        RtcEngine.destroy()
        engine = null
        Log.i(TAG, "Sonda fuera del canal")
    }

    private fun manejador(context: Context) = object : IRtcEngineEventHandler() {
        override fun onJoinChannelSuccess(channel: String, uid: Int, elapsed: Int) {
            Log.i(TAG, "Escuchando $channel como $uid en $elapsed ms; ${estadoAudio(context)}")
        }

        override fun onUserJoined(uid: Int, elapsed: Int) {
            Log.i(TAG, "Entra $uid")
        }

        override fun onRemoteAudioStateChanged(uid: Int, state: Int, reason: Int, elapsed: Int) {
            Log.i(TAG, "Audio de $uid: estado $state motivo $reason; ${estadoAudio(context)}")
        }

        override fun onLocalAudioStateChanged(state: Int, error: Int) {
            // Cualquier estado distinto de STOPPED aqui significa que Agora ha tocado
            // el microfono, que es el fallo que se busca.
            Log.i(TAG, "Captura local: estado $state error $error")
        }

        override fun onAudioVolumeIndication(speakers: Array<out AudioVolumeInfo>?, totalVolume: Int) {
            if (totalVolume > 0) Log.i(TAG, "Sonando por la unidad: volumen $totalVolume")
        }

        override fun onError(err: Int) {
            Log.e(TAG, "Error de Agora $err")
        }
    }

    private fun estadoAudio(context: Context): String {
        val audio = context.getSystemService(AudioManager::class.java)
        return "modo ${audio.mode}, altavoz ${audio.isSpeakerphoneOn}, " +
            "volumen llamada ${audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL)}, " +
            "volumen medios ${audio.getStreamVolume(AudioManager.STREAM_MUSIC)}"
    }

    private fun medirBateria(context: Context) {
        if (midiendo) return
        midiendo = true
        val fichero = File(context.getExternalFilesDir(null), "sonda_ptt_bateria.csv")
        fichero.writeText("epoch_ms,fase,nivel,corriente_ua,voltaje_uv,rx_bytes,tx_bytes,modo_audio\n")
        Log.i(TAG, "Midiendo bateria en ${fichero.absolutePath}")
        muestrear(context, fichero, "sin_escucha")
        hilo.submit { escuchar(context) }.get()
        muestrear(context, fichero, "escuchando")
        hilo.submit { parar() }.get()
        Log.i(TAG, "Medicion de bateria terminada")
        midiendo = false
    }

    private fun muestrear(context: Context, fichero: File, fase: String) {
        val bateria = context.getSystemService(BatteryManager::class.java)
        val audio = context.getSystemService(AudioManager::class.java)
        val muestras = FASE_BATERIA_SEGUNDOS / MUESTRA_SEGUNDOS
        repeat(muestras.toInt()) {
            val linea = listOf(
                System.currentTimeMillis(),
                fase,
                bateria.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
                leerSys("current_now"),
                leerSys("voltage_now"),
                TrafficStats.getUidRxBytes(Process.myUid()),
                TrafficStats.getUidTxBytes(Process.myUid()),
                audio.mode,
            ).joinToString(",")
            fichero.appendText(linea + "\n")
            Thread.sleep(MUESTRA_SEGUNDOS * 1000)
        }
    }

    private fun leerSys(nombre: String): String =
        runCatching { File("/sys/class/power_supply/battery/$nombre").readText().trim() }
            .getOrDefault("")
}
