package com.falconone.bodycamserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import java.io.File
import java.util.Locale

private const val TAG = "FalconCamera"

/** Margen para contestar a "Send to the server?" antes de dar por hecho que no. */
private const val PROMPT_TIMEOUT_MILLIS = 30_000L

/**
 * Ventana en la que se descarta el rebote del botón que acaba de parar la
 * grabación. Ver [RecordingActivity.ignoreRecordKey].
 */
private const val PROMPT_GUARD_MILLIS = 1_500L

/** Estados de la captura. Ver [RecordingActivity]. */
enum class CaptureState { IDLE, ARMED, RECORDING }

/**
 * Superficie de captura de la bodycam: grabación continua con pre-roll.
 *
 *     IDLE ──arm()──> ARMED ──start()──> RECORDING
 *                       ^                    │
 *                       └────── stop() ──────┘
 *
 * En ARMED la cámara está abierta y MediaRecorder rota segmentos sobre el anillo
 * de [EvidenceStore], que se va autodescartando: la unidad "recuerda" los últimos
 * [EvidenceStore.PRE_ROLL_MILLIS] sin guardar nada permanente. Al pulsar grabar, los
 * segmentos que cubren el pre-roll se promueven al incidente (un renameTo, sin
 * copiar bytes) y a partir de ahí cada segmento sellado va directo al incidente.
 *
 * Al detener se vuelve a ARMED, no a IDLE. Es deliberado: el momento más probable
 * para un segundo incidente es justo después del primero, y volver a IDLE dejaría
 * al agente sin pre-roll exactamente entonces.
 *
 * Sigue siendo una Activity porque este HAL exige una surface de app en primer
 * plano; una sesión de Camera2 desde un Service no era fiable en este dispositivo.
 * La UI que va encima del preview (contador, capa de reposo, pregunta de envío)
 * vive en [RecordingOverlay]; aquí solo se publica su estado.
 */
class RecordingActivity : ComponentActivity() {

    companion object {
        const val ACTION_STOP    = "com.falconone.STOP_RECORDING"   // cierra el incidente
        const val ACTION_DISARM  = "com.falconone.DISARM"           // apaga el servicio entero
        const val ACTION_REC     = "com.falconone.START_RECORDING"  // inicia incidente estando armado

        /** Solo aviso de repintado para MainActivity. Separado de ACTION_STOP a
         *  propósito: si viajara en ACTION_STOP, nuestro propio receptor lo
         *  leería como una orden de detener. */
        const val ACTION_STATE_CHANGED = "com.falconone.CAPTURE_STATE"

        private const val EXTRA_RECORD_NOW = "record_now"
        private const val EXTRA_ASK_UPLOAD = "ask_upload"

        @Volatile var state: CaptureState = CaptureState.IDLE
            private set

        /** Para los llamantes que solo distinguen "grabando o no": botones
         *  físicos, comandos BT, livestream, visor, linterna y foto. */
        val isRecording get() = state == CaptureState.RECORDING

        /** ARMED también ocupa la cámara, aunque no haya incidente en curso. */
        val isHoldingCamera get() = state != CaptureState.IDLE

        /** El teléfono pidió el servicio continuo; al cerrar un incidente se vuelve a ARMED. */
        @Volatile var serviceRequested = false
            private set

        /** Último fallo de captura, para que la UI no muestre un estado sano cuando no lo está. */
        @Volatile var lastError: String? = null
            private set

        /**
         * La unidad está esperando respuesta a "Send to the server?".
         *
         * Es un estado propio, y no simplemente "no grabando": entre la parada y
         * la respuesta `isRecording` ya vale false, y los botones físicos leían
         * eso como "empieza otra grabación".
         */
        @Volatile var isAwaitingUploadAnswer = false
            private set

        @Volatile private var promptOpenedAt = 0L

        /**
         * ¿Hay que descartar esta pulsación del botón de grabar?
         *
         * Cierto durante los primeros [PROMPT_GUARD_MILLIS] con la pregunta
         * abierta: esa ventana es el rebote del mismo botón que acaba de parar.
         * `ButtonDebounce` no lo caza porque `mediaRecorder.stop()` bloquea el
         * hilo principal más que sus 300 ms. Pasada la ventana, una pulsación
         * deliberada sí vale y cuenta como "no enviar" (ver [onNewIntent]).
         */
        fun ignoreRecordKey(): Boolean =
            isAwaitingUploadAnswer &&
            SystemClock.elapsedRealtime() - promptOpenedAt < PROMPT_GUARD_MILLIS

        @Volatile private var monitorJpeg: ByteArray? = null

        /** Frame para el monitor remoto del teléfono. Disponible también en
         *  ARMED: se puede encuadrar antes de que haya incidente. */
        fun latestJpeg(): ByteArray? = if (isHoldingCamera) monitorJpeg else null

        private const val MONITOR_FRAME_MILLIS = 250L
        private const val MONITOR_WIDTH = 640
        private const val MONITOR_HEIGHT = 360
        private const val MONITOR_JPEG_QUALITY = 60

        /** Arranca el servicio de grabación continua (anillo pre-evento). */
        fun arm(context: Context) {
            serviceRequested = true
            launch(context, recordNow = false)
        }

        /** Apaga el servicio: cierra la cámara y descarta el anillo. */
        fun disarm(context: Context) {
            serviceRequested = false
            context.sendBroadcast(Intent(ACTION_DISARM).setPackage(context.packageName))
        }

        /**
         * Inicia un incidente. Si el servicio no estaba armado se arma primero:
         * nunca se rechaza una petición de grabar, solo se graba sin pre-roll.
         */
        fun start(context: Context) {
            if (state == CaptureState.ARMED) {
                context.sendBroadcast(Intent(ACTION_REC).setPackage(context.packageName))
            } else {
                launch(context, recordNow = true)
            }
        }

        /**
         * Cierra el incidente en curso. Vuelve a ARMED si el servicio sigue pedido.
         *
         * @param askUpload pregunta en la pantalla de la unidad si el incidente
         *   se envía a Nexus. Solo para las paradas que nacen del botón de la
         *   unidad: las que ordena el teléfono suben solas, que ahí no hay nadie
         *   mirando esta pantalla.
         */
        fun stop(context: Context, askUpload: Boolean = false) {
            context.sendBroadcast(
                Intent(ACTION_STOP)
                    .setPackage(context.packageName)
                    .putExtra(EXTRA_ASK_UPLOAD, askUpload)
            )
        }

        private fun launch(context: Context, recordNow: Boolean) {
            context.startActivity(
                Intent(context, RecordingActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(EXTRA_RECORD_NOW, recordNow)
            )
        }
    }

    // ── Cámara y grabador ─────────────────────────────────────────────────────

    private var mediaRecorder: MediaRecorder? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    private val cameraThread = HandlerThread("FalconCamThread").also { it.start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var textureView: TextureView
    private var sensorOrientation = 0

    // ── Estado del anillo y del incidente ─────────────────────────────────────

    /** Segmento que MediaRecorder tiene abierto ahora mismo. Nunca se borra ni se mueve. */
    @Volatile private var inFlight: File? = null

    /**
     * Segmento ya entregado con setNextOutputFile. MediaRecorder puede haberlo
     * abierto ya: el aviso de rotación llega DESPUÉS del cambio real, así que
     * durante esa ventana este es el fichero que se está escribiendo. Por eso
     * los intocables son dos y no uno — verificado en dispositivo: promover con
     * un solo exclusor movía el fichero recién abierto.
     */
    @Volatile private var pendingNext: File? = null

    /** Ficheros que el grabador puede tener abiertos. Intocables para anillo y promoción. */
    private fun activeSegments(): Set<File> = setOfNotNull(inFlight, pendingNext)

    private var incidentId: String? = null
    private var armedAtMillis = 0L
    private var triggerMillis = 0L

    /** Petición de grabar llegada antes de que la cámara estuviera lista. La
     *  cámara abre de forma asíncrona: en arranque en frío el incidente no
     *  puede empezar hasta alcanzar ARMED. */
    @Volatile private var recordWhenReady = false

    // ── Estado de la UI (RecordingOverlay) ────────────────────────────────────

    private var overlayState by mutableStateOf(OverlayState())

    /**
     * Estado del panel de control que se dibuja sobre el preview en ARMED.
     *
     * Al armar al abrir la app, esta activity tapa a MainActivity durante todo el
     * servicio: si no pintáramos el panel aquí, el SOS y el estado del enlace
     * quedarían inaccesibles justo cuando más tiempo se pasa en la unidad.
     * Compose no observa el companion (state es un @Volatile), así que el ticker
     * vuelca los flags en este estado observable una vez por segundo, igual que
     * hace MainActivity.
     */
    private var panelState by mutableStateOf(PanelState())

    private val panelTick = object : Runnable {
        override fun run() {
            val client = BtServerService.connectedClient
            panelState = PanelState(
                link = when {
                    !BtServerService.isRunning -> Link.OFFLINE
                    client != null -> Link.CONNECTED
                    else -> Link.WAITING
                },
                clientName = client,
                armed = state == CaptureState.ARMED,
                recording = isRecording,
                streaming = LivestreamService.isStreaming,
            )
            mainHandler.postDelayed(this, 1000L)
        }
    }
    private lateinit var rootLayout: FrameLayout
    private var screenAwake = true

    /** Con la pregunta en pantalla nada puede dormirla ni bajarle el brillo. */
    private var promptHoldsScreen = false

    /** Incidente sobre el que pregunta el prompt. Puede ser distinto del incidente
     *  en curso: al responder, la unidad ya puede estar rearmada o grabando otro. */
    private var promptIncidentId: String? = null
    private var promptDeadline = 0L

    /**
     * Origen del contador, en reloj monótono. elapsedRealtime() y no
     * currentTimeMillis() porque el segundo salta si la unidad sincroniza la
     * hora por red a mitad de grabación.
     */
    private var recordingStartElapsed = 0L

    // ── Receptores y ticks ────────────────────────────────────────────────────

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_STOP   -> stopIncident(intent.getBooleanExtra(EXTRA_ASK_UPLOAD, false))
                ACTION_REC    -> startIncident()
                ACTION_DISARM -> disarmAndFinish()
            }
        }
    }

    // Bucle del monitor remoto: copia el preview del TextureView a un bitmap
    // reutilizable (getBitmap exige el hilo de UI) y lo comprime a JPEG. Es una
    // copia del preview y no un stream propio porque este HAL solo garantiza dos
    // streams, y ya los ocupan el preview y el grabador.
    private var monitorBitmap: Bitmap? = null
    private val monitorTick = object : Runnable {
        override fun run() {
            if (!isHoldingCamera) return
            if (textureView.isAvailable) {
                try {
                    val bmp = monitorBitmap ?: Bitmap.createBitmap(
                        MONITOR_WIDTH, MONITOR_HEIGHT, Bitmap.Config.ARGB_8888
                    ).also { monitorBitmap = it }
                    textureView.getBitmap(bmp)
                    val out = java.io.ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, MONITOR_JPEG_QUALITY, out)
                    monitorJpeg = out.toByteArray()
                } catch (e: Exception) {
                    Log.w(TAG, "monitor frame failed: ${e.message}")
                }
            }
            mainHandler.postDelayed(this, MONITOR_FRAME_MILLIS)
        }
    }

    // Contador del incidente. Solo corre con la pantalla despierta: con la capa
    // puesta nadie lo lee, y como el origen es un instante fijo, al volver
    // muestra el valor correcto sin haber ido contando.
    private val elapsedTick = object : Runnable {
        override fun run() {
            if (!isRecording) return
            updateElapsedLabel()
            mainHandler.postDelayed(this, 1000L)
        }
    }

    private fun updateElapsedLabel() {
        val total = ((SystemClock.elapsedRealtime() - recordingStartElapsed) / 1000).coerceAtLeast(0)
        overlayState = overlayState.copy(
            elapsed = String.format(
                Locale.US, "● REC  %02d:%02d:%02d", total / 3600, (total / 60) % 60, total % 60
            )
        )
    }

    private val promptCountdown = object : Runnable {
        override fun run() {
            val left = ((promptDeadline - SystemClock.elapsedRealtime()) / 1000).toInt()
            if (left <= 0) {
                Log.d(TAG, "Prompt sin respuesta → no se envía")
                resolveUploadPrompt(send = false)
                return
            }
            overlayState = overlayState.copy(prompt = overlayState.prompt?.copy(secondsLeft = left))
            mainHandler.postDelayed(this, 1000L)
        }
    }

    // ── Pantalla en reposo ────────────────────────────────────────────────────
    // Durante RECORDING la pantalla "se apaga" bajando el brillo a cero y tapando
    // la vista con una capa opaca, no durmiendo el panel: Android no lo permite
    // sin permisos de administrador, y por esa vía el despertar pasaría por la
    // pantalla de bloqueo. Un toque alterna reposo ↔ preview.
    //
    // En ARMED, en cambio, el panel puede dormirse de verdad: el anillo sigue
    // grabando porque BtServerService mantiene un wake lock parcial y la
    // SurfaceTexture no se suelta al destruirse la vista.

    private fun setScreenAwake(awake: Boolean) {
        if (promptHoldsScreen && !awake) {
            Log.d(TAG, "Reposo ignorado: la pregunta de envío está en pantalla")
            return
        }
        screenAwake = awake
        overlayState = overlayState.copy(
            covered = !awake,
            elapsed = if (awake && isRecording) overlayState.elapsed else null,
        )

        window.attributes = window.attributes.apply {
            // Para la pregunta se fuerza el brillo al máximo: con auto-brillo en
            // un entorno oscuro, BRIGHTNESS_OVERRIDE_NONE deja el panel casi
            // negro y el prompt se da por invisible.
            screenBrightness = when {
                !awake -> 0f
                promptHoldsScreen -> 1f
                else -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }

        mainHandler.removeCallbacks(elapsedTick)
        if (awake && isRecording) {
            updateElapsedLabel()
            mainHandler.post(elapsedTick)
        }
        Log.d(TAG, "Pantalla ${if (awake) "activa" else "en reposo"}")
    }

    // Un toque alterna reposo ↔ preview. Se consume aquí porque en esta pantalla
    // no hay nada más con lo que interactuar — salvo la pregunta: con ella
    // abierta los toques son de sus botones Yes/No.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (overlayState.prompt != null) return super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && isRecording) {
            setScreenAwake(!screenAwake)
        }
        return true
    }

    // ── Ciclo de vida ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        goImmersive()

        recordWhenReady = intent?.getBooleanExtra(EXTRA_RECORD_NOW, false) == true

        // TextureView real — el HAL de cámara necesita una surface de app en
        // primer plano. Encima, un ComposeView con el overlay (RecordingOverlay).
        textureView = TextureView(this)
        val root = FrameLayout(this).also { rootLayout = it }
        root.addView(textureView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        val overlayView = ComposeView(this)
        overlayView.setContent {
            RecordingOverlay(
                state = overlayState,
                // El panel solo se enseña en buffer: grabando mandan el contador
                // y la capa de reposo, y con la pregunta abierta, la pregunta.
                panel = panelState.takeIf { it.armed },
                onAnswer = { send -> resolveUploadPrompt(send) },
                onSos = { toggleSos() },
            )
        }
        root.addView(overlayView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        setContentView(root)

        mainHandler.post(panelTick)
        registerReceiver(commandReceiver, IntentFilter().apply {
            addAction(ACTION_STOP)
            addAction(ACTION_REC)
            addAction(ACTION_DISARM)
        })

        if (textureView.isAvailable) {
            openCamera()
        } else {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = openCamera()
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                // false = conservamos la SurfaceTexture. Si la soltáramos, apagar
                // la pantalla destruiría la sesión y el anillo dejaría de grabar.
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = false
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
    }

    /** Nueva orden de grabar con la activity ya viva (es SINGLE_TOP). */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra(EXTRA_RECORD_NOW, false) != true) return
        if (state == CaptureState.ARMED) startIncident() else recordWhenReady = true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    override fun onDestroy() {
        // Red de seguridad: si la activity muere sin pasar por disarm, que no
        // queden ni la cámara abierta ni los flags de la pregunta puestos.
        isAwaitingUploadAnswer = false
        state = CaptureState.IDLE
        mainHandler.removeCallbacks(monitorTick)
        mainHandler.removeCallbacks(elapsedTick)
        mainHandler.removeCallbacks(promptCountdown)
        mainHandler.removeCallbacks(panelTick)
        monitorJpeg = null
        try {
            window.attributes = window.attributes.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        } catch (_: Exception) {}
        try { unregisterReceiver(commandReceiver) } catch (_: Exception) {}
        teardownCapture()
        cameraThread.quitSafely()
        HardwareController.ledGreen()
        super.onDestroy()
    }

    // ── Cámara ────────────────────────────────────────────────────────────────

    private fun openCamera() {
        Log.d(TAG, "openCamera")
        try {
            val manager = getSystemService(CAMERA_SERVICE) as CameraManager
            val cameraId = manager.cameraIdList.firstOrNull() ?: run {
                fail("No hay cámaras"); return
            }

            sensorOrientation = manager
                .getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            applyPreviewTransform(sensorOrientation)

            // Restos de una sesión anterior no tienen continuidad temporal con
            // esta: presentarlos como pre-roll juntaría dos momentos distintos.
            EvidenceStore.clearRing()
            armedAtMillis = System.currentTimeMillis()

            val recorder = buildRecorder() ?: return

            val st = textureView.surfaceTexture ?: run {
                fail("SurfaceTexture nula"); recorder.release(); return
            }
            st.setDefaultBufferSize(1280, 720)
            val previewSurface = Surface(st)
            val recorderSurface = recorder.surface

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    try {
                        camera.createCaptureSession(
                            listOf(previewSurface, recorderSurface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    captureSession = session
                                    try {
                                        val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                            addTarget(previewSurface)
                                            addTarget(recorderSurface)
                                        }.build()
                                        session.setRepeatingRequest(req, null, cameraHandler)
                                        recorder.start()
                                        mediaRecorder = recorder
                                        enterArmed()
                                    } catch (e: Exception) {
                                        fail("No se pudo arrancar la captura: ${e.message}")
                                        recorder.release(); camera.close(); finish()
                                    }
                                }
                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    fail("Configuración de sesión fallida")
                                    recorder.release(); camera.close(); finish()
                                }
                            },
                            cameraHandler
                        )
                    } catch (e: Exception) {
                        fail("createCaptureSession: ${e.message}")
                        recorder.release(); camera.close(); finish()
                    }
                }
                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera disconnected"); camera.close(); disarmAndFinish()
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    fail("Error de cámara $error"); camera.close(); disarmAndFinish()
                }
            }, cameraHandler)

        } catch (e: Exception) {
            fail("openCamera: ${e.message}")
        }
    }

    /**
     * MediaRecorder configurado para rotar solo. La rotación es por tamaño
     * porque la API no avisa por duración: solo existe MAX_FILESIZE_APPROACHING.
     */
    private fun buildRecorder(): MediaRecorder? {
        val first = EvidenceStore.newBufferSegment()
        return try {
            MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setVideoSize(1280, 720)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(4_000_000)
                setOrientationHint((sensorOrientation + 270) % 360)  // 90°→0° ajuste bodycam
                setMaxFileSize(EvidenceStore.SEGMENT_BYTES)
                setOutputFile(first.absolutePath)
                setOnInfoListener { _, what, _ -> onRecorderInfo(what) }
                setOnErrorListener { _, what, extra ->
                    fail("MediaRecorder error $what (extra=$extra)")
                }
                prepare()
            }.also {
                inFlight = first
                Log.d(TAG, "recorder listo, primer segmento ${first.name}")
            }
        } catch (e: Exception) {
            fail("No se pudo preparar el grabador: ${e.message}")
            null
        }
    }

    private fun onRecorderInfo(what: Int) {
        when (what) {
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING -> {
                // Hay que entregar el siguiente fichero ANTES de llegar al límite;
                // si no llegamos, salta MAX_FILESIZE_REACHED y la captura para.
                val next = EvidenceStore.newBufferSegment()
                try {
                    mediaRecorder?.setNextOutputFile(next)
                    pendingNext = next
                } catch (e: Exception) {
                    fail("setNextOutputFile: ${e.message}")
                }
            }

            MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED -> {
                // Único instante en que el segmento anterior está cerrado y sellado.
                val sealed = inFlight
                inFlight = pendingNext
                pendingNext = null
                if (sealed != null) onSegmentSealed(sealed)
            }

            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED -> {
                fail("Rotación perdida — la captura se ha detenido")
            }
        }
    }

    /**
     * Un segmento acaba de cerrarse. Es el único punto donde se toca el disco:
     * en ARMED recorta el anillo, en RECORDING adopta el segmento al incidente.
     */
    private fun onSegmentSealed(sealed: File) {
        when (state) {
            CaptureState.RECORDING -> {
                incidentId?.let { EvidenceStore.adoptIntoIncident(it, sealed) }
            }
            CaptureState.ARMED -> {
                EvidenceStore.trimRing(System.currentTimeMillis(), activeSegments())
            }
            CaptureState.IDLE -> { /* cerrando; el anillo se descarta entero */ }
        }
    }

    // ── Transiciones ──────────────────────────────────────────────────────────

    private fun enterArmed() {
        state = CaptureState.ARMED
        lastError = null
        incidentId = null
        mainHandler.post {
            // En ARMED la pantalla puede dormirse de verdad (ver nota de arriba).
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            // Y se deshace el reposo fingido de la grabación anterior: si el
            // teléfono paró el incidente con la capa puesta, sin esto la unidad
            // quedaría rearmada pero negra y con el brillo a cero — y en ARMED
            // el toque no despierta, solo despierta durante RECORDING.
            setScreenAwake(true)
        }
        mainHandler.post(monitorTick)
        // Azul fijo = en buffer. Es la señal pedida por producto: la unidad está
        // en servicio y guarda los últimos 20 s aunque nadie haya pulsado grabar.
        HardwareController.ledBlue()
        notifyStateChanged()
        Log.d(TAG, "ARMED — anillo activo")

        // Petición de grabar que llegó con la cámara aún abriéndose. El pre-roll
        // será el que haya dado tiempo a acumular, normalmente ninguno.
        if (recordWhenReady) {
            recordWhenReady = false
            mainHandler.post { startIncident() }
        }
    }

    private fun startIncident() {
        if (state != CaptureState.ARMED) {
            Log.w(TAG, "startIncident ignorado, estado=$state")
            return
        }
        // Empezar a grabar con la pregunta del incidente anterior abierta cuenta
        // como no enviarlo: el vídeo sigue en la unidad de todas formas.
        if (overlayState.prompt != null) resolveUploadPrompt(send = false)

        val now = System.currentTimeMillis()
        val id = EvidenceStore.newIncidentId()

        // Congelar el anillo antes de promover: desde este cambio de estado
        // onSegmentSealed deja de recortar y empieza a adoptar.
        state = CaptureState.RECORDING
        incidentId = id
        triggerMillis = now
        recordingStartElapsed = SystemClock.elapsedRealtime()

        val promoted = EvidenceStore.promotePreRoll(id, now, activeSegments())

        mainHandler.post {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            // Al empezar a grabar la pantalla pasa a reposo; un toque la despierta.
            setScreenAwake(false)
        }
        HardwareController.ledRedBlink()
        notifyStateChanged()
        Log.d(TAG, "RECORDING $id — pre-roll de ${promoted.size} segmentos")
    }

    private fun stopIncident(askUpload: Boolean) {
        if (state != CaptureState.RECORDING) {
            // No había incidente. Con la pregunta abierta esto es el rebote del
            // botón que acaba de parar: se ignora. Sin servicio pedido, es un
            // "detener" a secas y hay que cerrar del todo.
            if (overlayState.prompt != null) {
                Log.d(TAG, "Parada redundante con la pregunta abierta → ignorada")
                return
            }
            if (!serviceRequested) disarmAndFinish()
            return
        }
        val id = incidentId ?: return
        val stopped = System.currentTimeMillis()

        // Cerrar el grabador sella la cola del incidente: lo que quede en el
        // anillo es, por definición, parte de este incidente.
        teardownCapture()
        EvidenceStore.drainBufferInto(id)
        EvidenceStore.writeManifest(id, armedAtMillis, triggerMillis, stopped)

        val segments = EvidenceStore.incidentSegments(id)
        Log.d(TAG, "incidente $id cerrado: ${segments.size} segmentos")
        if (segments.isNotEmpty()) {
            // Que aparezcan en la galería del dispositivo.
            MediaScannerConnection.scanFile(
                applicationContext,
                segments.map { it.absolutePath }.toTypedArray(),
                Array(segments.size) { "video/mp4" },
                null
            )
        }

        incidentId = null
        state = CaptureState.IDLE
        notifyStateChanged()

        if (askUpload) {
            // La respuesta decide la subida, nunca si se guarda: el incidente ya
            // está sellado en disco. Mientras se contesta, el servicio se rearma
            // por debajo — la pregunta flota sobre el preview del anillo.
            showUploadPrompt(id)
        } else {
            UploadService.startIncident(applicationContext, id)
        }

        if (serviceRequested) {
            mainHandler.post { openCamera() }   // volver a ARMED con anillo limpio
        } else if (!askUpload) {
            finish()
        }
        // Con askUpload y sin servicio: la activity vive hasta que se responda.
    }

    private fun disarmAndFinish() {
        serviceRequested = false
        val id = incidentId
        if (state == CaptureState.RECORDING && id != null) {
            // Nunca perder un incidente en curso por un apagado.
            teardownCapture()
            EvidenceStore.drainBufferInto(id)
            EvidenceStore.writeManifest(id, armedAtMillis, triggerMillis, System.currentTimeMillis())
            UploadService.startIncident(applicationContext, id)
        } else {
            teardownCapture()
        }
        EvidenceStore.clearRing()
        incidentId = null
        state = CaptureState.IDLE
        HardwareController.ledGreen()
        notifyStateChanged()
        finish()
    }

    /** Cierra grabador y sesión. Deja el último fichero sellado en disco. */
    private fun teardownCapture() {
        mainHandler.removeCallbacks(monitorTick)
        mainHandler.removeCallbacks(elapsedTick)
        monitorJpeg = null
        try { captureSession?.stopRepeating() } catch (_: Exception) {}
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { mediaRecorder?.stop() } catch (e: Exception) { Log.w(TAG, "recorder.stop: ${e.message}") }
        try { mediaRecorder?.release() } catch (_: Exception) {}
        mediaRecorder = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        pendingNext = null
        inFlight = null
    }

    private fun fail(reason: String) {
        Log.e(TAG, reason)
        lastError = reason
        notifyStateChanged()
    }

    /** MainActivity repinta a partir de esto. */
    private fun notifyStateChanged() {
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    // ── Pregunta de envío ─────────────────────────────────────────────────────

    private fun showUploadPrompt(incidentId: String) {
        promptIncidentId = incidentId
        promptHoldsScreen = true
        isAwaitingUploadAnswer = true
        promptOpenedAt = SystemClock.elapsedRealtime()
        setScreenAwake(true)  // la pregunta no sirve de nada con la capa puesta

        overlayState = overlayState.copy(
            prompt = PromptState(
                label = incidentId,
                secondsLeft = (PROMPT_TIMEOUT_MILLIS / 1000).toInt(),
            )
        )
        promptDeadline = SystemClock.elapsedRealtime() + PROMPT_TIMEOUT_MILLIS
        mainHandler.post(promptCountdown)
        Log.d(TAG, "Preguntando envío de $incidentId")
    }

    /** Cierra la pregunta. Idempotente: la segunda llamada ya no encuentra incidente. */
    private fun resolveUploadPrompt(send: Boolean) {
        val id = promptIncidentId ?: return
        promptIncidentId = null
        promptHoldsScreen = false
        isAwaitingUploadAnswer = false
        mainHandler.removeCallbacks(promptCountdown)
        overlayState = overlayState.copy(prompt = null)
        if (send) {
            Log.d(TAG, "Prompt → SÍ, subiendo $id")
            UploadService.startIncident(applicationContext, id)
        } else {
            Log.d(TAG, "Prompt → NO, $id se queda solo en la unidad")
        }
        // Si el servicio sigue armado la activity continúa (es el anillo); si no,
        // ya no queda nada que hacer aquí.
        if (!isHoldingCamera && !serviceRequested) finish()
    }

    /**
     * SOS desde el panel en buffer. En hilo propio: LivestreamService.start()
     * desarma y espera hasta 3 s a que el HAL suelte la cámara — en el hilo
     * principal eso congelaría la UI justo en el momento más crítico.
     */
    private fun toggleSos() {
        Thread {
            if (LivestreamService.isStreaming) {
                LivestreamService.stop()
            } else {
                PreviewController.stop()
                LivestreamService.start(applicationContext)
            }
        }.start()
    }

    // ── Botones físicos ───────────────────────────────────────────────────────

    // F4 grabando → detener con pregunta. El resto pasa de largo.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_F4 && isRecording) {
            Log.d(TAG, "F4 → stop recording")
            stopIncident(askUpload = true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ── Preview ───────────────────────────────────────────────────────────────

    private fun applyPreviewTransform(sensorDegrees: Int) {
        textureView.post {
            val w = textureView.width.takeIf { it > 0 } ?: return@post
            val h = textureView.height.takeIf { it > 0 } ?: return@post
            val matrix = Matrix()
            // El sensor da 90° y el montaje físico añade otros 45°: 135° en preview.
            matrix.postRotate(sensorDegrees + 90f, w / 2f, h / 2f)
            textureView.setTransform(matrix)
        }
    }
}
