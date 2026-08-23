package com.falconone.bodycamserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "FalconCamera"

/** Margen para contestar a "Send to the server?" antes de dar por hecho que no. */
private const val PROMPT_TIMEOUT_MILLIS = 30_000L


/**
 * Ventana en la que se descarta el rebote del botón que acaba de parar la
 * grabación. Ver RecordingActivity.ignoreRecordKey().
 */
private const val PROMPT_GUARD_MILLIS = 1_500L

class RecordingActivity : ComponentActivity() {

    companion object {
        const val ACTION_STOP = "com.falconone.STOP_RECORDING"
        const val EXTRA_ASK_UPLOAD = "ask_upload"

        @Volatile var isRecording = false
            private set

        /**
         * La unidad está esperando respuesta a "Send to the server?".
         *
         * Es un estado propio, y no simplemente "no grabando": entre la parada y la
         * respuesta `isRecording` ya vale false, y los botones físicos leían eso como
         * "empieza otra grabación".
         */
        @Volatile var isAwaitingUploadAnswer = false
            private set

        @Volatile private var promptOpenedAt = 0L

        /**
         * ¿Hay que descartar esta pulsación del botón de grabar?
         *
         * Cierto durante los primeros [PROMPT_GUARD_MILLIS] con la pregunta abierta:
         * esa ventana es justo el rebote del mismo botón que acaba de parar la
         * grabación. `ButtonDebounce` no lo caza porque `mediaRecorder.stop()` bloquea
         * el hilo principal escribiendo el índice del MP4 bastante más que sus 300 ms,
         * así que el segundo evento llega con el debounce ya caducado y `isRecording`
         * en false — y arrancaba una grabación nueva con la pantalla apagada.
         *
         * Pasada la ventana, una pulsación deliberada sí vale y cuenta como "no
         * enviar" (ver onNewIntent).
         */
        fun ignoreRecordKey(): Boolean =
            isAwaitingUploadAnswer &&
            SystemClock.elapsedRealtime() - promptOpenedAt < PROMPT_GUARD_MILLIS

        // Monitor remoto de la grabación: último frame del preview como JPEG,
        // servido por FileServerService en /preview y /preview/stream mientras
        // se graba. Se alimenta copiando el TextureView de esta activity — sin
        // abrir un tercer stream de cámara, que este HAL no garantiza.
        @Volatile private var monitorJpeg: ByteArray? = null

        /** Frame para el monitor remoto, o null si no se está grabando. */
        fun latestJpeg(): ByteArray? = if (isRecording) monitorJpeg else null

        private const val MONITOR_FRAME_MILLIS = 250L
        private const val MONITOR_WIDTH = 640
        private const val MONITOR_HEIGHT = 360
        private const val MONITOR_JPEG_QUALITY = 60

        fun start(context: Context) {
            context.startActivity(
                Intent(context, RecordingActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }

        /**
         * @param askUpload pregunta en la pantalla de la unidad si el video se
         *   envía a Nexus. Solo para las paradas que nacen del botón de
         *   grabación: las que ordena el teléfono o las que preceden a un
         *   livestream siguen subiendo solas, que ahí no hay nadie mirando esta
         *   pantalla.
         */
        fun stop(context: Context, askUpload: Boolean = false) {
            context.sendBroadcast(
                Intent(ACTION_STOP)
                    .setPackage(context.packageName)
                    .putExtra(EXTRA_ASK_UPLOAD, askUpload)
            )
        }
    }

    private var mediaRecorder: MediaRecorder? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    private val cameraThread = HandlerThread("FalconCamThread").also { it.start() }
    private val cameraHandler = Handler(cameraThread.looper)

    private lateinit var textureView: TextureView
    private var outputFile: File? = null
    private var sensorOrientation = 0

    /** Instrumentación de la prueba de cifrado — ver RecorderWatch. */
    private var watch: RecorderWatch? = null

    // ── Pantalla en reposo ───────────────────────────────────────────────────
    // Al empezar a grabar la pantalla se apaga y un toque la alterna. La
    // grabación no depende de esto: sigue igual con la pantalla negra.
    //
    // "Apagar" aquí es bajar el brillo a cero y tapar la vista con una capa
    // opaca, no dormir el panel: Android no deja apagarlo de verdad sin
    // permisos de administrador de dispositivo, y por esa vía el despertar
    // pasaría por la pantalla de bloqueo, que es justo lo que no queremos en
    // una bodycam. Así el táctil sigue vivo y un toque devuelve la imagen al
    // instante.
    /**
     * Estado del overlay (contador, capa de reposo y pregunta de envío).
     *
     * La UI de encima del preview vive en [RecordingOverlay]; aquí solo se publica
     * el estado. La cámara se queda en Views porque el `TextureView` es la
     * superficie real de Camera2 y de él se alimenta el monitor remoto.
     */
    private var overlayState by mutableStateOf(OverlayState())
    private lateinit var rootLayout: FrameLayout
    private var screenAwake = true

    // ── Confirmación de envío ─────────────────────────────────────
    // Al parar con el botón de grabación se pregunta si el video va a Nexus. El
    // fichero se queda en la unidad en los dos casos: la respuesta decide la
    // subida, nunca si se guarda.
    //
    // Sin respuesta se deja de subir, y no lo contrario: subir es la irreversible
    // de las dos acciones, y el video sigue en la unidad para mandarlo luego
    // desde el teléfono.
    private var promptFile: File? = null
    /**
     * Mientras la pregunta está en pantalla nada puede dormirla ni bajarle el
     * brillo. Sin esto la capa volvía a ponerse encima de Yes/No y el vídeo se
     * resolvía solo, por vencimiento del contador, sin que nadie contestara.
     */
    private var promptHoldsScreen = false
    private var promptDeadline = 0L

    /**
     * Origen del contador, en reloj monótono.
     *
     * elapsedRealtime() y no currentTimeMillis() porque el segundo salta si la
     * unidad sincroniza la hora por red a mitad de grabación, y el contador
     * daría un salto o se iría hacia atrás.
     */
    private var recordingStartElapsed = 0L

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_STOP) {
                stopAndFinish(intent.getBooleanExtra(EXTRA_ASK_UPLOAD, false))
            }
        }
    }

    // Bucle del monitor remoto: cada tick copia el preview del TextureView a
    // un bitmap reutilizable (getBitmap exige el hilo de UI) y lo comprime a
    // JPEG para que el teléfono lo vea mientras se graba. ~4 fps es suficiente
    // para monitorear sin robarle CPU al encoder de video.
    private val mainHandler = Handler(Looper.getMainLooper())
    private var monitorBitmap: Bitmap? = null
    private val monitorTick = object : Runnable {
        override fun run() {
            if (!isRecording) return
            if (textureView.isAvailable) {
                try {
                    val bmp = monitorBitmap ?: Bitmap.createBitmap(
                        MONITOR_WIDTH, MONITOR_HEIGHT, Bitmap.Config.ARGB_8888
                    ).also { monitorBitmap = it }
                    textureView.getBitmap(bmp)
                    val out = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, MONITOR_JPEG_QUALITY, out)
                    monitorJpeg = out.toByteArray()
                } catch (e: Exception) {
                    Log.w(TAG, "monitor frame failed: ${e.message}")
                }
            }
            mainHandler.postDelayed(this, MONITOR_FRAME_MILLIS)
        }
    }

    // Contador de grabación. Solo corre con la pantalla despierta: con la capa
    // puesta nadie lo lee, y el origen es un instante fijo, así que al volver
    // muestra el valor correcto sin haber ido contando.
    private val elapsedTick = object : Runnable {
        override fun run() {
            if (!isRecording) return
            updateElapsedLabel()
            mainHandler.postDelayed(this, 1000L)
        }
    }

    private fun updateElapsedLabel() {
        val millis = SystemClock.elapsedRealtime() - recordingStartElapsed
        val total = (millis / 1000).coerceAtLeast(0)
        overlayState = overlayState.copy(
            elapsed = String.format(
                Locale.US, "● REC  %02d:%02d:%02d", total / 3600, (total / 60) % 60, total % 60
            )
        )
    }

    /**
     * Alterna entre la pantalla en reposo y el preview con el contador.
     *
     * El TextureView se queda como está, tapado por la capa: si se ocultara,
     * el monitor remoto (/preview) dejaría de tener frames que copiar, porque
     * se alimenta justamente de esta vista.
     */
    private fun setScreenAwake(awake: Boolean) {
        // Con la pregunta abierta el reposo queda vetado: un toque perdido no
        // puede dejar los botones debajo de la capa.
        if (promptHoldsScreen && !awake) {
            Log.d(TAG, "Reposo ignorado: la pregunta de envío está en pantalla")
            return
        }
        screenAwake = awake
        overlayState = overlayState.copy(
            covered = !awake,
            // Con la capa puesta nadie lee el contador, y su origen es un instante
            // fijo: al volver muestra el valor correcto sin haber ido contando.
            elapsed = if (awake && isRecording) overlayState.elapsed else null,
        )

        window.attributes = window.attributes.apply {
            // Para la pregunta se fuerza el brillo al máximo en vez de devolvérselo
            // al sistema: con auto-brillo en un entorno oscuro, BRIGHTNESS_OVERRIDE_NONE
            // deja la pantalla casi negra y el popup se da por invisible.
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

    // Un toque alterna reposo ↔ preview. Se consume aquí porque en esta
    // pantalla no hay nada más con lo que interactuar, y así el toque que la
    // despierta no dispara nada de debajo.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Con la pregunta en pantalla los toques son de sus botones: si los
        // consumiéramos aquí, Yes/No no se podrían pulsar.
        if (overlayState.prompt != null) return super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) setScreenAwake(!screenAwake)
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // KEEP_SCREEN_ON se mantiene a propósito aun con la pantalla en reposo:
        // el apagado lo gestionamos nosotros con el brillo, y si dejáramos
        // dormir el panel al sistema, despertarlo exigiría desbloquear.
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        // Sin esto el sistema le reserva sitio a la barra de navegación y la
        // ventana queda más estrecha que la pantalla: la capa girada cubría bien
        // la ventana, pero se veía un margen lateral junto a la pregunta de envío.
        goImmersive()

        // TextureView real — HAL de cámara necesita surface de foreground app
        textureView = TextureView(this)

        val root = FrameLayout(this).also { rootLayout = it }
        root.addView(textureView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        // Todo lo que va encima del preview (contador, capa de reposo, pregunta de
        // envío) es Compose y vive en RecordingOverlay. El TextureView se queda
        // como View: es la superficie real de Camera2 y de ella copia sus frames
        // el monitor remoto, así que no gana nada envuelta en un AndroidView.
        val overlayView = ComposeView(this)
        overlayView.setContent {
            RecordingOverlay(
                state = overlayState,
                onAnswer = { send ->
                    resolveUploadPrompt(send)
                    finish()
                },
            )
        }
        root.addView(overlayView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        setContentView(root)

        registerReceiver(stopReceiver, IntentFilter(ACTION_STOP))

        if (textureView.isAvailable) {
            openCamera()
        } else {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = openCamera()
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
    }

    private fun openCamera() {
        Log.d(TAG, "openCamera")
        try {
            val manager = getSystemService(CAMERA_SERVICE) as CameraManager
            val cameraId = manager.cameraIdList.firstOrNull() ?: run {
                Log.e(TAG, "No cameras found"); finish(); return
            }

            // Read sensor orientation so video and preview are correctly rotated
            sensorOrientation = manager
                .getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            Log.d(TAG, "Sensor orientation: $sensorOrientation°")
            applyPreviewTransform(sensorOrientation)

            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val dir = File(Environment.getExternalStorageDirectory(), "FalconOne").also { it.mkdirs() }
            val file = File(dir, "VID_$ts.mp4").also { outputFile = it }
            Log.d(TAG, "Output: ${file.absolutePath}")

            val watch = RecorderWatch(file, cameraHandler).also { this.watch = it }

            val recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setVideoSize(1280, 720)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(4_000_000)
                setOrientationHint((sensorOrientation + 270) % 360)  // 90°→0° ajuste bodycam
                setOutputFile(file.absolutePath)
                watch.attach(this)
                prepare()
            }

            val st = textureView.surfaceTexture ?: run {
                Log.e(TAG, "SurfaceTexture null"); recorder.release(); finish(); return
            }
            st.setDefaultBufferSize(1280, 720)
            val previewSurface = Surface(st)
            val recorderSurface = recorder.surface

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.d(TAG, "Camera opened")
                    cameraDevice = camera
                    try {
                        camera.createCaptureSession(
                            listOf(previewSurface, recorderSurface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    Log.d(TAG, "Session configured — starting recorder")
                                    captureSession = session
                                    try {
                                        val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                            addTarget(previewSurface)
                                            addTarget(recorderSurface)
                                        }.build()
                                        session.setRepeatingRequest(req, null, cameraHandler)
                                        recorder.start()
                                        mediaRecorder = recorder
                                        isRecording = true
                                        watch.onStarted()
                                        // El origen del contador se fija aquí, no al
                                        // abrir la cámara: entre openCamera() y este
                                        // punto se van cientos de ms configurando la
                                        // sesión, y no son grabación.
                                        recordingStartElapsed = SystemClock.elapsedRealtime()
                                        mainHandler.post(monitorTick)
                                        mainHandler.post { setScreenAwake(false) }
                                        HardwareController.ledRedBlink()
                                        Log.d(TAG, "Recording STARTED — ${file.name}")
                                    } catch (e: Exception) {
                                        Log.e(TAG, "start failed: ${e.message}")
                                        recorder.release(); camera.close(); finish()
                                    }
                                }
                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    Log.e(TAG, "Session configure FAILED")
                                    recorder.release(); camera.close(); finish()
                                }
                            },
                            cameraHandler
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "createCaptureSession: ${e.message}")
                        recorder.release(); camera.close(); finish()
                    }
                }
                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera disconnected"); camera.close(); finish()
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error $error"); camera.close(); finish()
                }
            }, cameraHandler)

        } catch (e: Exception) {
            Log.e(TAG, "openCamera failed: ${e.message}"); finish()
        }
    }

    // Corrects TextureView preview rotation to match physical camera orientation
    private fun applyPreviewTransform(sensorDegrees: Int) {
        textureView.post {
            val w = textureView.width.takeIf { it > 0 } ?: return@post
            val h = textureView.height.takeIf { it > 0 } ?: return@post
            val matrix = Matrix()
            val cx = w / 2f
            val cy = h / 2f
            // Sensor is 90°, physical mounting adds ~45° more → use 135° for live preview
            val previewRotation = (sensorDegrees + 90f)
            matrix.postRotate(previewRotation, cx, cy)
            textureView.setTransform(matrix)
        }
    }

    private fun stopAndFinish(askUpload: Boolean = false) {
        if (!isRecording) {
            // Parada repetida: rebote del botón físico, o el teléfono mandando su
            // propio REC_STOP. Con la pregunta abierta, finish() se la llevaba por
            // delante antes de que se pudiera pulsar Yes/No — este era el motivo
            // real de que el popup "desapareciera" al cortar la grabación.
            if (overlayState.prompt != null) {
                Log.d(TAG, "Parada redundante con la pregunta abierta → ignorada")
                return
            }
            finish(); return
        }
        Log.d(TAG, "stopAndFinish askUpload=$askUpload")
        isRecording = false
        mainHandler.removeCallbacks(monitorTick)
        mainHandler.removeCallbacks(elapsedTick)
        monitorJpeg = null
        try { captureSession?.stopRepeating() } catch (_: Exception) {}
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        // Fichero cerrado y utilizable. Si stop() lanza se queda a null: ese MP4
        // sale sin índice y no vale ni para subirlo ni para ofrecerlo.
        var savedFile: File? = null
        try {
            mediaRecorder?.stop()
            watch?.onStopped()   // tras stop() el tamaño del fichero ya es el definitivo
            Log.d(TAG, "File saved: ${outputFile?.name}")
            outputFile?.let { file ->
                // Notify gallery so the video appears immediately
                MediaScannerConnection.scanFile(
                    applicationContext, arrayOf(file.absolutePath), arrayOf("video/mp4"), null
                )
                savedFile = file
            }
        } catch (e: Exception) { Log.e(TAG, "recorder.stop: ${e.message}") }
        try { mediaRecorder?.release() } catch (_: Exception) {}
        mediaRecorder = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        HardwareController.ledGreen()

        val file = savedFile
        if (askUpload && file != null) {
            // La cámara ya está suelta: la activity sigue viva solo por la
            // pregunta, así que el visor o el livestream pueden arrancar igual.
            showUploadPrompt(file)
        } else {
            file?.let { UploadService.start(applicationContext, it.absolutePath) }
            finish()
        }
    }

    // ── Pregunta de envío ───────────────────────────────────────

    private val promptCountdown = object : Runnable {
        override fun run() {
            val left = ((promptDeadline - SystemClock.elapsedRealtime()) / 1000).toInt()
            if (left <= 0) {
                Log.d(TAG, "Prompt sin respuesta → no se envía")
                resolveUploadPrompt(send = false)
                finish()
                return
            }
            overlayState = overlayState.copy(prompt = overlayState.prompt?.copy(secondsLeft = left))
            mainHandler.postDelayed(this, 1000L)
        }
    }

    private fun showUploadPrompt(file: File) {
        promptFile = file
        promptHoldsScreen = true
        isAwaitingUploadAnswer = true
        promptOpenedAt = SystemClock.elapsedRealtime()
        setScreenAwake(true)  // la pregunta no sirve de nada con la capa puesta

        overlayState = overlayState.copy(
            prompt = PromptState(
                fileName = file.name,
                secondsLeft = (PROMPT_TIMEOUT_MILLIS / 1000).toInt(),
            )
        )

        promptDeadline = SystemClock.elapsedRealtime() + PROMPT_TIMEOUT_MILLIS
        mainHandler.post(promptCountdown)
        Log.d(TAG, "Preguntando envio de ${file.name}")
    }

    /** Cierra la pregunta. Idempotente: la segunda llamada ya no encuentra fichero. */
    private fun resolveUploadPrompt(send: Boolean) {
        val file = promptFile ?: return
        promptFile = null
        promptHoldsScreen = false
        isAwaitingUploadAnswer = false
        mainHandler.removeCallbacks(promptCountdown)
        overlayState = overlayState.copy(prompt = null)
        if (send) {
            Log.d(TAG, "Prompt → SÍ, subiendo ${file.name}")
            UploadService.start(applicationContext, file.absolutePath)
        } else {
            Log.d(TAG, "Prompt → NO, ${file.name} se queda solo en la unidad")
        }
    }

    /**
     * Volver a pulsar grabar con la pregunta abierta.
     *
     * La activity es SINGLE_TOP, así que ese segundo arranque entra por aquí en
     * vez de crear otra instancia: sin esto la unidad se quedaría con la pregunta
     * puesta y sin grabar. Empezar otra grabación cuenta como no enviar — el
     * video anterior sigue en la unidad de todas formas.
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (overlayState.prompt == null) return
        Log.d(TAG, "Nueva grabación con la pregunta abierta → no se envía")
        resolveUploadPrompt(send = false)
        openCamera()
    }

    // El sistema restaura las barras al recuperar el foco (vuelta del diálogo de
    // permisos, o de otra activity): hay que volver a esconderlas.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    // F4 while recording → stop. All other buttons pass through.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_F4 && isRecording) {
            Log.d(TAG, "F4 → stop recording")
            stopAndFinish(askUpload = true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        isRecording = false
        // Si la activity muere con la pregunta puesta, el flag se quedaría a true y
        // el botón de grabar dejaría de responder.
        isAwaitingUploadAnswer = false
        // Red de seguridad: si stop() lanzó, el latido seguiría vivo sobre un
        // grabador ya muerto.
        watch?.onStopped()
        watch = null
        mainHandler.removeCallbacks(monitorTick)
        mainHandler.removeCallbacks(elapsedTick)
        mainHandler.removeCallbacks(promptCountdown)
        monitorJpeg = null
        // Devolver el brillo: es un ajuste de ventana, pero si la activity se
        // destruye con la capa puesta conviene no dejar rastro.
        try {
            window.attributes = window.attributes.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        } catch (_: Exception) {}
        try { unregisterReceiver(stopReceiver) } catch (_: Exception) {}
        // Ensure resources freed even if stopAndFinish wasn't called
        try { captureSession?.close() } catch (_: Exception) {}
        try { mediaRecorder?.release() } catch (_: Exception) {}
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraThread.quitSafely()
        HardwareController.ledGreen()
        super.onDestroy()
    }
}
