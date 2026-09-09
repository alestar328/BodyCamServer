package com.falconone.bodycamserver

import android.content.Context
import android.util.Log
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.video.CameraCapturerConfiguration
import io.agora.rtc2.video.VideoEncoderConfiguration
import io.agora.rtc2.video.VideoEncoderConfiguration.VideoDimensions
import java.util.concurrent.Executors

private const val TAG = "FalconLive"

const val AGORA_APP_ID  = "ff51540c357447f7bf060b3150bf6a3e"
const val AGORA_CHANNEL = "falcon_group_channel"
const val BODYCAM_UID   = 9001

object LivestreamService {

    @Volatile var isStreaming = false
        private set

    @Volatile private var _micEnabled = false
    val isMicEnabled get() = _micEnabled

    /**
     * La sesión de Agora abierta la abrió el PTT en modo solo-audio, no el
     * livestream. Distingue el caso "hay engine pero no hay vídeo": no marca
     * isStreaming, no enciende el LED de emisión y no levanta el SOS en los
     * teléfonos — que reaccionan al vídeo de uid 9001 (onRemoteVideoStateChanged),
     * no a su mera presencia en el canal.
     */
    @Volatile private var pttOwnsSession = false

    /** El anillo estaba armado cuando el PTT le quitó el micro; hay que rearmarlo. */
    @Volatile private var pttResumeService = false

    /** Agora ha confirmado que está capturando voz en la sesión de PTT en curso. */
    @Volatile private var capturaConfirmada = false

    /** Motivo del último PTT que no se pudo abrir, para poder decírselo al teléfono. */
    @Volatile var lastPttError: String? = null
        private set

    /**
     * Aviso de que el PTT se ha cerrado solo (la captura de audio falló). Lo pone
     * BtServerService para notificar al teléfono: un PTT que se cae en silencio es
     * peor que uno que no abre, porque el agente cree que le están oyendo.
     */
    @Volatile var onPttDropped: ((String) -> Unit)? = null

    /**
     * Cerrar el engine desde un callback del SDK lo bloquea, así que el cierre de
     * emergencia del PTT se hace fuera del hilo de Agora.
     */
    private val pttWatchdog = Executors.newSingleThreadExecutor()

    private var engine: RtcEngine? = null

    /** Contexto de aplicación, para poder rearmar el servicio al cerrar el stream. */
    private var appContext: Context? = null

    /** El servicio de grabación continua estaba armado cuando el SOS tomó la cámara. */
    private var resumeServiceAfter = false

    /**
     * El SOS tiene prioridad sobre la grabación: Agora abre la cámara por su
     * cuenta y el HAL no da para las dos cosas a la vez. Se cede la cámara, se
     * emite, y al terminar se rearma el anillo si estaba armado.
     *
     * Durante la emisión hay un hueco en el buffer pre-evento, pero lo que ocurre
     * en ese hueco se está transmitiendo en directo: la evidencia no se pierde,
     * cambia de sitio.
     */
    private fun yieldCamera(context: Context) {
        if (!RecordingActivity.isHoldingCamera) {
            resumeServiceAfter = false
            return
        }
        // Leer la intención del agente ANTES de desarmar: disarm() la borra.
        resumeServiceAfter = RecordingActivity.serviceRequested
        Log.d(TAG, "Cediendo cámara al livestream (rearmar después: $resumeServiceAfter)")
        RecordingActivity.disarm(context)

        // Esperar a que el HAL suelte de verdad, en vez de dormir a ciegas.
        val deadline = System.currentTimeMillis() + 3_000
        while (RecordingActivity.isHoldingCamera && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        if (RecordingActivity.isHoldingCamera) {
            Log.w(TAG, "La cámara sigue ocupada tras 3 s — se intenta emitir igualmente")
        }
    }

    private fun createEngine(context: Context): RtcEngine {
        val handler = object : IRtcEngineEventHandler() {
            override fun onJoinChannelSuccess(channel: String, uid: Int, elapsed: Int) {
                Log.d(TAG, "Joined $channel uid=$uid (solo audio: $pttOwnsSession)")
                // La sesión del PTT entra en el mismo canal pero no es una emisión:
                // no toca isStreaming ni el LED, o el agente vería el amarillo de
                // SOS solo por haber abierto el micro.
                if (pttOwnsSession) return
                isStreaming = true
                // Amarillo parpadeando = emitiendo (SOS). El azul fijo pasó a
                // significar "en buffer" (ver enterArmed) y no pueden compartir
                // color: emitir es precisamente cuando NO hay anillo.
                HardwareController.ledYellowBlink()
            }
            override fun onLeaveChannel(stats: IRtcEngineEventHandler.RtcStats?) {
                Log.d(TAG, "Left channel")
                isStreaming = false
            }
            override fun onError(err: Int) {
                Log.e(TAG, "Agora error code: $err")
            }
            /**
             * Único juez de si el PTT está transmitiendo de verdad. Medido en la
             * unidad el 2026-09-08: con el micro ocupado, Agora entra en el canal
             * y publica silencio, así que sin esto el PTT diría ON sin que se oiga
             * nada. Al fallar la captura se cierra el PTT y se avisa.
             */
            override fun onLocalAudioStateChanged(state: Int, error: Int) {
                // Confirmación positiva: solo cuando Agora dice RECORDING está
                // entrando voz de verdad. Se apunta aquí y lo comprueba
                // vigilarCaptura(), porque el micro ocupado NO produce un estado
                // FAILED — llega como warning 1033 del módulo de audio, que el
                // SDK 4.3.0 ya no expone por callback. Fiarse solo de FAILED fue
                // el motivo de que el 2026-09-08 el PTT dijera ON sin transmitir.
                if (state == Constants.LOCAL_AUDIO_STREAM_STATE_RECORDING) {
                    capturaConfirmada = true
                }
                if (state != Constants.LOCAL_AUDIO_STREAM_STATE_FAILED) return
                if (!_micEnabled) return
                val motivo = when (error) {
                    Constants.LOCAL_AUDIO_STREAM_REASON_DEVICE_BUSY ->
                        "El micrófono está ocupado — el PTT no puede transmitir"
                    Constants.LOCAL_AUDIO_STREAM_REASON_DEVICE_NO_PERMISSION ->
                        "Sin permiso de micrófono"
                    else -> "La captura de audio falló (código $error)"
                }
                Log.e(TAG, "PTT caído: $motivo")
                lastPttError = motivo
                pttWatchdog.execute {
                    if (pttOwnsSession) closePttSession() else silenciarMicro()
                    // El micro ya está cerrado: el zumbido no viaja por el canal y
                    // el agente deja de hablarle a nadie.
                    PttTones.denegado()
                    onPttDropped?.invoke(motivo)
                }
            }
        }

        val config = RtcEngineConfig().apply {
            mAppId     = AGORA_APP_ID
            mContext   = context
            mEventHandler = handler
        }
        return RtcEngine.create(config)
    }

    fun start(context: Context): Boolean {
        if (isStreaming) return true
        appContext = context.applicationContext

        // El PTT pudo abrir ya el canal en modo solo-audio. Esa sesión se asciende
        // a vídeo en vez de rehacerla: un leave/rejoin cortaría el audio que el
        // agente está usando en ese mismo momento.
        engine?.let { eng -> if (pttOwnsSession) return upgradePttSessionToVideo(context, eng) }

        yieldCamera(context)

        return try {
            val eng = createEngine(context)
            engine = eng

            eng.setChannelProfile(Constants.CHANNEL_PROFILE_LIVE_BROADCASTING)
            eng.setClientRole(Constants.CLIENT_ROLE_BROADCASTER)
            eng.enableVideo()

            // Bodycam sensor is 90° — use rear camera and let Agora auto-detect orientation
            eng.setCameraCapturerConfiguration(
                CameraCapturerConfiguration(CameraCapturerConfiguration.CAMERA_DIRECTION.CAMERA_REAR)
            )
            eng.setVideoEncoderConfiguration(
                VideoEncoderConfiguration(
                    VideoDimensions(640, 480),
                    VideoEncoderConfiguration.FRAME_RATE.FRAME_RATE_FPS_15,
                    700,
                    VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_ADAPTIVE
                )
            )

            val options = ChannelMediaOptions().apply {
                channelProfile         = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
                clientRoleType         = Constants.CLIENT_ROLE_BROADCASTER
                publishCameraTrack     = true
                // El micro lo abre el PTT (botón F2), no el livestream.
                publishMicrophoneTrack = false
                autoSubscribeVideo     = false  // bodycam only sends, doesn't receive
                autoSubscribeAudio     = false
            }

            // null token — only works if App Certificate is NOT enabled in Agora console.
            // If you see error code 101/110, enable "No Auth" in the Agora project settings.
            eng.joinChannel(null, AGORA_CHANNEL, BODYCAM_UID, options)
            true
        } catch (e: Exception) {
            Log.e(TAG, "start failed: ${e.message}")
            false
        }
    }

    /**
     * Añade vídeo a la sesión que el PTT ya tenía abierta, sin salir del canal.
     * Como no se vuelve a entrar, onJoinChannelSuccess no se repite y el estado de
     * emisión hay que marcarlo aquí a mano.
     */
    private fun upgradePttSessionToVideo(context: Context, eng: RtcEngine): Boolean {
        Log.d(TAG, "Ascendiendo la sesión de PTT a livestream (micro abierto: $_micEnabled)")
        // El PTT ya había cortado el anillo, así que yieldCamera no lo verá y
        // perdería la intención de rearmarlo. El livestream la hereda: a partir de
        // aquí es él quien devuelve el pre-roll al terminar.
        val anilloPendiente = pttResumeService
        pttResumeService = false
        yieldCamera(context)
        if (anilloPendiente) resumeServiceAfter = true
        return try {
            eng.enableVideo()
            eng.setCameraCapturerConfiguration(
                CameraCapturerConfiguration(CameraCapturerConfiguration.CAMERA_DIRECTION.CAMERA_REAR)
            )
            eng.setVideoEncoderConfiguration(
                VideoEncoderConfiguration(
                    VideoDimensions(640, 480),
                    VideoEncoderConfiguration.FRAME_RATE.FRAME_RATE_FPS_15,
                    700,
                    VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_ADAPTIVE
                )
            )
            eng.updateChannelMediaOptions(
                ChannelMediaOptions().apply {
                    publishCameraTrack     = true
                    publishMicrophoneTrack = _micEnabled
                }
            )
            pttOwnsSession = false
            isStreaming    = true
            HardwareController.ledYellowBlink()
            true
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo ascender la sesión de PTT: ${e.message}")
            false
        }
    }

    /**
     * Conmuta el micro del PTT (botón físico F2) y devuelve el estado resultante.
     *
     * El firmware de la unidad solo avisa al SOLTAR el botón, nunca al pulsarlo
     * (SIDE_KEY_INTENT, con key_status siempre -1), así que el PTT no puede ser de
     * mantener-para-hablar: es un conmutador.
     *
     * Con livestream abierto NO se toca la sesión: solo cambia
     * publishMicrophoneTrack en caliente, sin leave/rejoin y sin rozar la cámara,
     * para que el vídeo siga exactamente igual. Sin livestream se abre el canal en
     * modo solo-audio, sin enableVideo() y sin pedirle la cámara al anillo, para
     * que la grabación continua no se entere.
     */
    fun togglePtt(context: Context): Boolean {
        // Cada pulsación empieza sin arrastrar el fallo de la anterior, o cerrar el
        // micro se reportaría como un error que ya no existe.
        lastPttError = null
        val eng = engine ?: return openPttSession(context)

        _micEnabled = !_micEnabled
        // El tono arranca antes de publicar el micro y después de cerrarlo, para
        // que el pitido se quede en la unidad y no en lo que se transmite; del
        // solape que quede se encarga el cancelador de eco de Agora.
        if (_micEnabled) {
            PttTones.abrir()
            eng.enableAudio()
        }
        eng.updateChannelMediaOptions(
            ChannelMediaOptions().apply { publishMicrophoneTrack = _micEnabled }
        )
        if (!_micEnabled) {
            eng.disableAudio()
            PttTones.cerrar()
        }
        Log.d(TAG, "PTT mic ${if (_micEnabled) "ON" else "OFF"}")

        // El canal lo abrió el propio PTT: al cerrar el micro se sale, para soltar
        // micrófono y red en vez de quedarse dentro sin publicar nada. Si el canal
        // es de un livestream se conserva: cerrarlo sería precisamente interferir.
        if (!_micEnabled && pttOwnsSession) closePttSession() else if (_micEnabled) vigilarCaptura()
        return _micEnabled
    }

    /**
     * Un PTT abierto que no captura es peor que uno que no abre: el agente habla
     * creyendo que le oyen. Si Agora no confirma la captura en 3 s, se cierra y se
     * avisa.
     */
    private fun vigilarCaptura() {
        capturaConfirmada = false
        pttWatchdog.execute {
            val deadline = System.currentTimeMillis() + 3_000
            while (!capturaConfirmada && System.currentTimeMillis() < deadline) {
                Thread.sleep(100)
            }
            if (capturaConfirmada || !_micEnabled) return@execute
            val motivo = "El micrófono no está capturando — el PTT no transmite"
            Log.e(TAG, "PTT sin captura tras 3 s: se cierra")
            lastPttError = motivo
            if (pttOwnsSession) closePttSession() else silenciarMicro()
            PttTones.denegado()
            onPttDropped?.invoke(motivo)
        }
    }

    /**
     * El PTT necesita el micrófono, y en Android 9 solo lo tiene un cliente a la
     * vez. Medido en la unidad el 2026-09-08: con el anillo grabando, Agora entra
     * en el canal igual pero su pista de captura queda inactiva y avisa con el
     * warning 1033 ("recording device is occupied") — o sea, transmite silencio.
     *
     * Decisión de producto (2026-09-08): **el pre-roll se corta mientras se habla
     * y se rearma al cerrar el PTT**. Lo que NO se corta es un incidente en curso:
     * partir en dos el vídeo de una evidencia por una transmisión de radio no
     * compensa, así que ahí el PTT se niega y lo dice.
     *
     * Devuelve false si no consigue el micrófono.
     */
    private fun yieldMicForPtt(context: Context): Boolean {
        if (RecordingActivity.isRecording) {
            lastPttError = "Hay un incidente grabando — el micrófono es suyo"
            Log.w(TAG, "PTT rechazado: incidente en curso")
            return false
        }
        if (!RecordingActivity.isHoldingCamera) {
            pttResumeService = false
            return true
        }

        pttResumeService = RecordingActivity.serviceRequested
        Log.d(TAG, "Cortando el anillo para el PTT (rearmar después: $pttResumeService)")
        RecordingActivity.disarm(context)

        // Esperar a que el HAL suelte el micro de verdad: abrir Agora antes es
        // exactamente lo que producía el 1033.
        val deadline = System.currentTimeMillis() + 3_000
        while (RecordingActivity.isHoldingCamera && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        if (RecordingActivity.isHoldingCamera) {
            Log.w(TAG, "El anillo no soltó el micro en 3 s — no se abre el PTT")
            lastPttError = "El micrófono sigue ocupado — no se abrió el PTT"
            if (pttResumeService) {
                pttResumeService = false
                RecordingActivity.arm(context)
            }
            return false
        }
        return true
    }

    /** Cierra el micro sin tocar el canal (el canal es de un livestream). */
    private fun silenciarMicro() {
        val eng = engine ?: return
        _micEnabled = false
        eng.updateChannelMediaOptions(
            ChannelMediaOptions().apply { publishMicrophoneTrack = false }
        )
        eng.disableAudio()
    }

    /** Entra en el canal solo con audio. No toca la cámara ni el LED. */
    private fun openPttSession(context: Context): Boolean {
        appContext = context.applicationContext
        lastPttError = null
        // No se consiguió el micro (incidente en curso, o el anillo sin soltarlo):
        // el agente tiene que OÍR que no se ha abierto antes de ponerse a hablar.
        if (!yieldMicForPtt(context)) {
            PttTones.denegado()
            return false
        }
        // Suena en cuanto el micro es nuestro y antes de levantar el engine: es la
        // confirmación de la pulsación y no se solapa con la transmisión.
        PttTones.abrir()
        return try {
            pttOwnsSession = true
            val eng = createEngine(context)
            engine = eng

            eng.setChannelProfile(Constants.CHANNEL_PROFILE_LIVE_BROADCASTING)
            eng.setClientRole(Constants.CLIENT_ROLE_BROADCASTER)
            eng.enableAudio()

            val options = ChannelMediaOptions().apply {
                channelProfile         = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
                clientRoleType         = Constants.CLIENT_ROLE_BROADCASTER
                // Sin vídeo: el anillo de grabación conserva la cámara.
                publishCameraTrack     = false
                publishMicrophoneTrack = true
                autoSubscribeVideo     = false
                autoSubscribeAudio     = false
            }
            eng.joinChannel(null, AGORA_CHANNEL, BODYCAM_UID, options)
            _micEnabled = true
            Log.d(TAG, "PTT mic ON (sesión solo-audio)")
            vigilarCaptura()
            true
        } catch (e: Exception) {
            Log.e(TAG, "El PTT no pudo abrir el canal: ${e.message}")
            lastPttError = "Agora no pudo abrir el canal"
            engine = null
            pttOwnsSession = false
            _micEnabled = false
            PttTones.denegado()
            rearmarAnilloSiTocaba()
            false
        }
    }

    private fun closePttSession() {
        try {
            engine?.leaveChannel()
            RtcEngine.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "closePttSession: ${e.message}")
        } finally {
            engine = null
            pttOwnsSession = false
            _micEnabled = false
            Log.d(TAG, "Sesión de PTT cerrada")
            rearmarAnilloSiTocaba()
        }
    }

    /**
     * Devuelve el pre-roll al agente en cuanto el PTT suelta el micrófono.
     *
     * `RtcEngine.destroy()` vuelve antes de que el módulo de audio de Agora haya
     * soltado del todo la entrada, así que rearmar de inmediato pilla el micro a
     * medio liberar: el 2026-09-08 eso dejó una sesión de captura colgada a 8 kHz
     * que sobrevivió incluso a un `force-stop` y bloqueó a la vez el anillo y el
     * PTT hasta reiniciar la unidad. Por eso se reintenta hasta que el anillo
     * confirme que está capturando, en vez de dar el rearme por bueno.
     */
    private fun rearmarAnilloSiTocaba() {
        if (!pttResumeService) return
        pttResumeService = false
        val ctx = appContext ?: return

        val deadline = System.currentTimeMillis() + 6_000
        var intento = 0
        while (System.currentTimeMillis() < deadline) {
            intento++
            Log.d(TAG, "Rearmando el anillo tras el PTT (intento $intento)")
            RecordingActivity.arm(ctx)

            // Esperar a que el anillo confirme captura, no a que pase un rato.
            val espera = System.currentTimeMillis() + 1_500
            while (!RecordingActivity.isHoldingCamera && System.currentTimeMillis() < espera) {
                Thread.sleep(50)
            }
            if (RecordingActivity.isHoldingCamera) {
                Log.d(TAG, "Anillo rearmado en el intento $intento")
                return
            }
        }
        Log.e(TAG, "El anillo NO recuperó el micrófono tras el PTT — unidad sin pre-roll")
    }

    fun stop() {
        try {
            engine?.leaveChannel()
            RtcEngine.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "stop: ${e.message}")
        } finally {
            engine = null
            isStreaming = false
            // El PTT se apoyaba en esta sesión: al cerrarla el micro se va con ella.
            // El anillo lo rearma resumeServiceAfter, más abajo, así que aquí solo
            // se descarta la intención heredada para no rearmar dos veces.
            pttOwnsSession = false
            pttResumeService = false
            // Cerrar la emisión cierra también el micro del PTT: si estaba abierto,
            // el agente tiene que oír que ha dejado de transmitir.
            if (_micEnabled) PttTones.cerrar()
            _micEnabled = false
            LedSignals.refresh()
            Log.d(TAG, "Livestream stopped")

            // Devolver la cámara al anillo si el agente tenía el servicio activo.
            if (resumeServiceAfter) {
                resumeServiceAfter = false
                appContext?.let {
                    Log.d(TAG, "Rearmando el servicio de grabación continua")
                    RecordingActivity.arm(it)
                }
            }
        }
    }
}
