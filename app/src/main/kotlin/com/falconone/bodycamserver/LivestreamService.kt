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
import java.util.concurrent.TimeUnit

private const val TAG = "FalconLive"

const val AGORA_APP_ID  = "ff51540c357447f7bf060b3150bf6a3e"
const val AGORA_CHANNEL = "falcon_group_channel"

/** Espera entre intentos de volver a entrar al canal cuando Agora lo da por perdido. */
private const val REINTENTO_CANAL_SEGUNDOS = 10L

/**
 * La unidad en el canal de Agora: escucha siempre y emite cuando toca.
 *
 * Desde el 2026-09-15 la unidad **está en el canal todo el tiempo**, como audiencia y sin
 * capturar, para que el PTT de los teléfonos suene por su altavoz (requisitos del
 * 2026-09-14). El SOS y el PTT propio ya no entran ni salen: suben a broadcaster dentro
 * de la misma sesión y bajan a audiencia al terminar.
 *
 * Un solo motor para todo porque Agora admite uno por proceso: un `RtcEngine.destroy()`
 * del SOS se llevaría la escucha por delante.
 *
 * Medido con `SondaEscuchaPtt` antes de construirlo: como audiencia y con
 * `enableLocalAudio(false)` Agora **no abre el micrófono**, y el anillo sigue grabando
 * con su audio mientras suena la voz de fuera.
 */
object LivestreamService {

    /** Agora está publicando la cámara: el SOS está en el aire. */
    @Volatile var isStreaming = false
        private set

    @Volatile private var _micEnabled = false
    val isMicEnabled get() = _micEnabled

    /** La unidad está dentro del canal (escuchando, o además emitiendo). */
    @Volatile var enCanal = false
        private set

    /**
     * Se ha pedido el SOS. Va por delante de [isStreaming], que solo se marca con la
     * unidad dentro del canal: sin red el SOS espera y sale en cuanto Agora vuelve a
     * entrar, en vez de perderse.
     */
    @Volatile private var sosPedido = false

    /** Hay un SOS pedido, en el aire o esperando al canal. Lo que miran los botones para cortarlo. */
    val sosActivo get() = sosPedido

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
     * Tocar el engine desde un callback del SDK lo bloquea, así que el cierre de
     * emergencia del PTT y los reintentos de entrada se hacen fuera del hilo de Agora.
     */
    private val pttWatchdog = Executors.newSingleThreadExecutor()
    private val reintentos = Executors.newSingleThreadScheduledExecutor()

    @Volatile private var engine: RtcEngine? = null

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
                Log.d(TAG, "Joined $channel uid=$uid en $elapsed ms (escuchando)")
                enCanal = true
                // Un SOS pedido sin red sale ahora. Fuera del hilo de Agora: publicar
                // desde su callback lo bloquea.
                if (sosPedido) pttWatchdog.execute { appContext?.let(::ponerSosEnElAire) }
            }
            override fun onRejoinChannelSuccess(channel: String, uid: Int, elapsed: Int) {
                // Tras un corte de red Agora vuelve solo y conserva lo que se publicaba.
                Log.d(TAG, "De vuelta en $channel tras un corte ($elapsed ms)")
                enCanal = true
            }
            override fun onLeaveChannel(stats: IRtcEngineEventHandler.RtcStats?) {
                Log.d(TAG, "Left channel")
                enCanal = false
                isStreaming = false
            }
            override fun onConnectionStateChanged(state: Int, reason: Int) {
                Log.d(TAG, "Conexión con el canal: estado $state motivo $reason")
                // Los cortes de red los reintenta Agora solo. FAILED es lo único que da
                // por perdido (token, app id, expulsión) y ahí hay que volver a entrar,
                // o la unidad se quedaría sorda hasta reiniciarla.
                if (state != Constants.CONNECTION_STATE_FAILED) return
                enCanal = false
                // Se vuelve a entrar como audiencia: el SOS pedido se republica al
                // entrar, pero un micro abierto ya no llega a nadie y hay que decirlo.
                isStreaming = false
                pttWatchdog.execute {
                    if (!_micEnabled) return@execute
                    val motivo = "La unidad perdió el canal — el PTT no transmite"
                    lastPttError = motivo
                    cerrarMicro()
                    PttTones.denegado()
                    onPttDropped?.invoke(motivo)
                }
                reintentos.schedule(::reentrar, REINTENTO_CANAL_SEGUNDOS, TimeUnit.SECONDS)
            }
            override fun onUserJoined(uid: Int, elapsed: Int) {
                Log.d(TAG, "Entra al canal $uid")
            }
            override fun onRemoteAudioStateChanged(uid: Int, state: Int, reason: Int, elapsed: Int) {
                // 2 = DECODING: la voz de ese uid está sonando por el altavoz.
                Log.d(TAG, "Audio de $uid: estado $state motivo $reason")
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
                    cerrarMicro()
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

    /**
     * Entra al canal a escuchar. Se llama al arrancar BtServerService y no se sale
     * más: sin red Agora sigue intentándolo por su cuenta.
     */
    @Synchronized
    fun escuchar(context: Context) {
        if (engine != null) return
        appContext = context.applicationContext
        try {
            val eng = createEngine(context.applicationContext)
            eng.setChannelProfile(Constants.CHANNEL_PROFILE_LIVE_BROADCASTING)
            eng.enableAudio()
            // Antes de entrar: si la captura llega a arrancar le quita el micro al
            // anillo, y la grabación se quedaría sin sonido.
            eng.enableLocalAudio(false)
            // Sin auricular previsto: la voz de fuera sale por el altavoz de la W1.
            eng.setDefaultAudioRoutetoSpeakerphone(true)
            engine = eng
            val resultado = eng.joinChannel(
                null, AGORA_CHANNEL, BodycamIdentity.uidAgora(context), opcionesDeEscucha()
            )
            Log.d(TAG, "Entrando al canal a escuchar: $resultado")
            if (resultado < 0) reintentos.schedule(::reentrar, REINTENTO_CANAL_SEGUNDOS, TimeUnit.SECONDS)
        } catch (e: Exception) {
            // Sin motor no hay nada que reentrar: se vuelve a crear desde cero.
            Log.e(TAG, "No se pudo entrar al canal a escuchar: ${e.message}")
            engine = null
            val ctx = context.applicationContext
            reintentos.schedule({ escuchar(ctx) }, REINTENTO_CANAL_SEGUNDOS, TimeUnit.SECONDS)
        }
    }

    /**
     * Solo al cerrar el servicio. Es lo único que destruye el motor: a partir de aquí
     * la unidad no oye el PTT de nadie.
     */
    @Synchronized
    fun salirDelCanal() {
        if (sosPedido || _micEnabled) stop()
        try {
            engine?.leaveChannel()
            RtcEngine.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "salirDelCanal: ${e.message}")
        } finally {
            engine = null
            enCanal = false
            Log.d(TAG, "Fuera del canal: la unidad ya no escucha")
        }
    }

    /**
     * Audiencia: no aparece para los demás en onUserJoined, así que los teléfonos no
     * ven a la unidad hasta que emite, igual que antes de estar siempre dentro.
     */
    private fun opcionesDeEscucha() = ChannelMediaOptions().apply {
        channelProfile         = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
        clientRoleType         = Constants.CLIENT_ROLE_AUDIENCE
        publishCameraTrack     = false
        publishMicrophoneTrack = false
        autoSubscribeAudio     = true
        autoSubscribeVideo     = false  // la unidad no pinta el vídeo de nadie
    }

    /** Vuelve a entrar con el mismo motor cuando Agora da la conexión por perdida. */
    private fun reentrar() {
        val eng = engine ?: return
        val ctx = appContext ?: return
        eng.leaveChannel()
        val resultado = eng.joinChannel(null, AGORA_CHANNEL, BodycamIdentity.uidAgora(ctx), opcionesDeEscucha())
        Log.d(TAG, "Reentrando al canal: $resultado")
        if (resultado < 0) reintentos.schedule(::reentrar, REINTENTO_CANAL_SEGUNDOS, TimeUnit.SECONDS)
    }

    /**
     * Sube a broadcaster si hay algo que emitir y baja a audiencia si no. Es el único
     * sitio que decide el rol: SOS y PTT lo comparten y cualquiera de los dos puede
     * terminar antes que el otro.
     */
    private fun aplicarPublicacion(eng: RtcEngine) {
        val emite = isStreaming || _micEnabled
        eng.updateChannelMediaOptions(
            ChannelMediaOptions().apply {
                clientRoleType = if (emite) Constants.CLIENT_ROLE_BROADCASTER else Constants.CLIENT_ROLE_AUDIENCE
                publishCameraTrack     = isStreaming
                publishMicrophoneTrack = _micEnabled
            }
        )
    }

    fun start(context: Context): Boolean {
        if (sosPedido) return true
        appContext = context.applicationContext
        if (engine == null) escuchar(context)
        if (engine == null) return false

        // Si el PTT ya había cortado el anillo, yieldCamera no lo verá y perdería la
        // intención de rearmarlo. El SOS la hereda: a partir de aquí es él quien
        // devuelve el pre-roll al terminar.
        val anilloPendiente = pttResumeService
        pttResumeService = false
        yieldCamera(context)
        if (anilloPendiente) resumeServiceAfter = true

        sosPedido = true
        if (enCanal) return ponerSosEnElAire(context)
        Log.w(TAG, "SOS pedido fuera del canal: sale en cuanto Agora vuelva a entrar")
        return true
    }

    /** Publica la cámara en la sesión de escucha, sin salir ni volver a entrar. */
    @Synchronized
    private fun ponerSosEnElAire(context: Context): Boolean {
        val eng = engine ?: return false
        if (!sosPedido || isStreaming) return true
        return try {
            // Antes de encender el vídeo, para que el primer fotograma de un SOS
            // nocturno ya salga en grises. Registrarlo otra vez lo sustituye.
            eng.registerVideoFrameObserver(MonocromoSos)
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
            // El micro no: lo abre el PTT (botón F2), no el livestream.
            isStreaming = true
            aplicarPublicacion(eng)
            Log.d(TAG, "SOS en el aire (micro abierto: $_micEnabled)")
            // Amarillo parpadeando = emitiendo (SOS). El azul fijo pasó a
            // significar "en buffer" (ver enterArmed) y no pueden compartir
            // color: emitir es precisamente cuando NO hay anillo.
            HardwareController.ledYellowBlink()
            // Un SOS nocturno también necesita los infrarrojos, aunque aquí no se esté
            // grabando: la imagen que hay que ver es la que sale al teléfono.
            ModoNoche.sincronizarIr()
            // Mientras emite, la unidad no graba: que lo grabe el backend.
            SosNotifier.inicio(context)
            true
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo poner el SOS en el aire: ${e.message}")
            isStreaming = false
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
     * No entra ni sale del canal: sube a broadcaster con el micro y vuelve a
     * audiencia. Con SOS en el aire solo cambia el micro, sin rozar la cámara, para
     * que el vídeo siga exactamente igual.
     */
    fun togglePtt(context: Context): Boolean {
        // Cada pulsación empieza sin arrastrar el fallo de la anterior, o cerrar el
        // micro se reportaría como un error que ya no existe.
        lastPttError = null
        if (_micEnabled) {
            cerrarMicro()
            PttTones.cerrar()
            Log.d(TAG, "PTT mic OFF")
            return false
        }
        return abrirMicro(context)
    }

    private fun abrirMicro(context: Context): Boolean {
        appContext = context.applicationContext
        val eng = engine
        // Un micro abierto fuera del canal no llega a nadie: el agente tiene que OÍR
        // que no se ha abierto antes de ponerse a hablar.
        if (eng == null || !enCanal) {
            lastPttError = "La unidad no está en el canal — el PTT no transmite"
            Log.w(TAG, "PTT rechazado: fuera del canal")
            PttTones.denegado()
            return false
        }
        // Con SOS el anillo ya cedió cámara y micro; sin él hay que pedírselo.
        if (!sosPedido && !yieldMicForPtt(context)) {
            PttTones.denegado()
            return false
        }
        // Suena en cuanto el micro es nuestro y antes de publicar: es la confirmación
        // de la pulsación y el pitido se queda en la unidad, no en lo que se transmite.
        PttTones.abrir()
        return try {
            _micEnabled = true
            aplicarPublicacion(eng)
            eng.enableLocalAudio(true)
            Log.d(TAG, "PTT mic ON")
            vigilarCaptura()
            true
        } catch (e: Exception) {
            Log.e(TAG, "El PTT no pudo abrir el micro: ${e.message}")
            lastPttError = "Agora no pudo abrir el micro"
            cerrarMicro()
            PttTones.denegado()
            false
        }
    }

    /**
     * Cierra el micro sin salir del canal y devuelve el pre-roll si el PTT lo cortó.
     * La captura se para antes de dejar de publicar, para soltar el micro cuanto antes.
     */
    private fun cerrarMicro() {
        _micEnabled = false
        engine?.let { eng ->
            try {
                eng.enableLocalAudio(false)
                aplicarPublicacion(eng)
            } catch (e: Exception) {
                Log.e(TAG, "cerrarMicro: ${e.message}")
            }
        }
        rearmarAnilloSiTocaba()
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
            cerrarMicro()
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

    /**
     * Devuelve el pre-roll al agente en cuanto el PTT suelta el micrófono.
     *
     * Agora vuelve de soltar la captura antes de que su módulo de audio haya liberado
     * del todo la entrada, así que rearmar de inmediato pilla el micro a medio
     * liberar: el 2026-09-08 eso dejó una sesión de captura colgada a 8 kHz que
     * sobrevivió incluso a un `force-stop` y bloqueó a la vez el anillo y el PTT
     * hasta reiniciar la unidad. Por eso se reintenta hasta que el anillo confirme
     * que está capturando, en vez de dar el rearme por bueno.
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

    /** Termina el SOS y vuelve a escuchar, sin salir del canal. */
    fun stop() {
        sosPedido = false
        // Cerrar la emisión cierra también el micro del PTT: si estaba abierto,
        // el agente tiene que oír que ha dejado de transmitir.
        val micAbierto = _micEnabled
        _micEnabled = false
        isStreaming = false
        pttResumeService = false
        engine?.let { eng ->
            try {
                eng.enableLocalAudio(false)
                aplicarPublicacion(eng)
                eng.disableVideo()
            } catch (e: Exception) {
                Log.e(TAG, "stop: ${e.message}")
            }
        }
        if (micAbierto) PttTones.cerrar()
        SosNotifier.fin()
        LedSignals.refresh()
        ModoNoche.sincronizarIr()
        Log.d(TAG, "Livestream stopped (sigue escuchando: $enCanal)")

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
