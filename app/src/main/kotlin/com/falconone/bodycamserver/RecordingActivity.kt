package com.falconone.bodycamserver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.io.File

private const val TAG = "FalconCamera"

/** Estados de la captura. Ver [RecordingActivity]. */
enum class CaptureState { IDLE, ARMED, RECORDING }

/**
 * Superficie de captura de la bodycam.
 *
 *     IDLE ──arm()──> ARMED ──start()──> RECORDING
 *                       ^                    │
 *                       └────── stop() ──────┘
 *
 * En ARMED la cámara está abierta y MediaRecorder rota segmentos sobre el anillo
 * de [EvidenceStore], que se va autodescartando. Al pulsar grabar, los segmentos
 * que cubren el pre-roll se promueven al incidente y el anillo queda congelado:
 * a partir de ahí cada segmento sellado va directo al incidente.
 *
 * Al detener se vuelve a ARMED, no a IDLE. Es deliberado: el momento más probable
 * para un segundo incidente es justo después del primero, y volver a IDLE dejaría
 * al agente sin buffer exactamente entonces. Es también lo que hace el firmware
 * del fabricante (RecordControl.endAVRecord llama de nuevo a openPreRecord).
 *
 * Sigue siendo una Activity porque este HAL exige una surface de app en primer
 * plano; una sesión de Camera2 desde un Service con SurfaceTexture suelta no era
 * fiable en este dispositivo.
 */
class RecordingActivity : Activity() {

    companion object {
        const val ACTION_STOP    = "com.falconone.STOP_RECORDING"   // detiene el incidente
        const val ACTION_DISARM  = "com.falconone.DISARM"           // apaga el servicio entero
        const val ACTION_REC     = "com.falconone.START_RECORDING"  // inicia incidente estando armado

        /** Solo aviso de repintado. Separado de ACTION_STOP a propósito: si el
         *  aviso viajara en ACTION_STOP, nuestro propio receptor lo leería como
         *  una orden de detener. */
        const val ACTION_STATE_CHANGED = "com.falconone.CAPTURE_STATE"

        private const val EXTRA_RECORD_NOW = "record_now"

        @Volatile var state: CaptureState = CaptureState.IDLE
            private set

        /**
         * Mantenido para los llamantes que solo distinguen "grabando o no":
         * botones físicos, comandos BT, livestream, visor, linterna y foto.
         */
        val isRecording get() = state == CaptureState.RECORDING

        /** ARMED también ocupa la cámara, aunque no haya incidente en curso. */
        val isHoldingCamera get() = state != CaptureState.IDLE

        /** El agente pidió el servicio continuo; al detener un incidente se vuelve a ARMED. */
        @Volatile var serviceRequested = false
            private set

        /** Último fallo de captura, para que la UI no muestre un estado sano cuando no lo está. */
        @Volatile var lastError: String? = null
            private set

        @Volatile private var monitorJpeg: ByteArray? = null

        /** Frame para el monitor remoto. Disponible también en ARMED: el teléfono
         *  puede encuadrar antes de que haya incidente. */
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

        /** Cierra el incidente en curso. Vuelve a ARMED si el servicio sigue pedido. */
        fun stop(context: Context) {
            context.sendBroadcast(Intent(ACTION_STOP).setPackage(context.packageName))
        }

        private fun launch(context: Context, recordNow: Boolean) {
            context.startActivity(
                Intent(context, RecordingActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(EXTRA_RECORD_NOW, recordNow)
            )
        }
    }

    private var mediaRecorder: MediaRecorder? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    private val cameraThread = HandlerThread("FalconCamThread").also { it.start() }
    private val cameraHandler = Handler(cameraThread.looper)

    private lateinit var textureView: TextureView
    private var sensorOrientation = 0

    // ── Estado del anillo y del incidente ─────────────────────────────────────

    /** Segmento que MediaRecorder tiene abierto ahora mismo. Nunca se borra ni se mueve. */
    @Volatile private var inFlight: File? = null

    /**
     * Segmento ya entregado con setNextOutputFile. MediaRecorder puede haberlo
     * abierto ya: el aviso de rotación llega después del cambio real, así que
     * durante esa ventana este es el fichero que se está escribiendo.
     */
    @Volatile private var pendingNext: File? = null

    /** Ficheros que el grabador puede tener abiertos. Intocables para anillo y promoción. */
    private fun activeSegments(): Set<File> = setOfNotNull(inFlight, pendingNext)

    private var incidentId: String? = null
    private var armedAtMillis = 0L
    private var triggerMillis = 0L
    private var preRollCount = 0

    /** Petición de grabar llegada antes de que la cámara estuviera lista. La
     *  cámara abre de forma asíncrona, así que en arranque en frío el incidente
     *  no puede empezar hasta que se alcanza ARMED. */
    @Volatile private var recordWhenReady = false

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_STOP   -> stopIncident()
                ACTION_REC    -> startIncident()
                ACTION_DISARM -> { disarmAndFinish() }
            }
        }
    }

    // Bucle del monitor remoto: copia el preview del TextureView a un bitmap
    // reutilizable (getBitmap exige el hilo de UI) y lo comprime a JPEG. Sigue
    // siendo una copia del preview y no un stream propio porque el presupuesto
    // del HAL es de 2 streams no-stalling y ya están ocupados por preview y
    // grabación (maxNumOutputStreams = [0,2,1], nivel LIMITED).
    private val mainHandler = Handler(Looper.getMainLooper())
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

    // ── Ciclo de vida ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        recordWhenReady = intent?.getBooleanExtra(EXTRA_RECORD_NOW, false) == true

        textureView = TextureView(this)
        setContentView(textureView)

        registerReceiver(stopReceiver, IntentFilter().apply {
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
                // false = conservamos la SurfaceTexture. Si la soltáramos, apagar la
                // pantalla destruiría la sesión y el anillo dejaría de grabar.
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = false
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
    }

    /** Segunda pulsación de grabar mientras la activity ya está viva. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra(EXTRA_RECORD_NOW, false) != true) return
        if (state == CaptureState.ARMED) startIncident() else recordWhenReady = true
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
            Log.d(TAG, "Sensor orientation: $sensorOrientation°")
            applyPreviewTransform(sensorOrientation)

            // Restos de una sesión anterior no tienen continuidad con esta.
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
                    Log.d(TAG, "Camera opened")
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
     * MediaRecorder configurado para rotar solo. La rotación es por tamaño porque
     * la API no ofrece aviso por duración: solo existe MAX_FILESIZE_APPROACHING.
     * La duración de cada segmento sale de ahí y varía con la escena, pero el
     * cálculo del pre-roll usa los instantes de inicio reales, no la duración.
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
                // Hay que entregar el siguiente fichero ANTES de que se alcance el
                // límite; si no llegamos, salta MAX_FILESIZE_REACHED y la captura para.
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
                // Se alcanzó el tope sin siguiente fichero: la captura se ha detenido.
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
                val id = incidentId
                if (id != null) EvidenceStore.adoptIntoIncident(id, sealed)
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
            // En ARMED la pantalla puede dormirse: el anillo sigue porque
            // BtServerService mantiene un PARTIAL_WAKE_LOCK y no soltamos la
            // SurfaceTexture al destruirse la vista.
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        mainHandler.post(monitorTick)
        HardwareController.ledGreen()
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
        val now = System.currentTimeMillis()
        val id = EvidenceStore.newIncidentId()

        // Congelar el anillo antes de promover: a partir de este cambio de estado
        // onSegmentSealed deja de recortar y empieza a adoptar.
        state = CaptureState.RECORDING
        incidentId = id
        triggerMillis = now

        val promoted = EvidenceStore.promotePreRoll(id, now, activeSegments())
        preRollCount = promoted.size

        mainHandler.post {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        HardwareController.ledRedBlink()
        notifyStateChanged()
        Log.d(TAG, "RECORDING $id — pre-roll de $preRollCount segmentos")
    }

    private fun stopIncident() {
        if (state != CaptureState.RECORDING) {
            // No había incidente: si además nadie pidió el servicio, esto es un
            // "detener" a secas y hay que cerrar del todo.
            if (!serviceRequested) disarmAndFinish()
            return
        }
        val id = incidentId ?: return
        val stopped = System.currentTimeMillis()

        // La cola del incidente sigue abierta: cerrar el grabador la sella, y lo
        // que quede en el anillo es por definición parte de este incidente.
        teardownCapture()
        EvidenceStore.drainBufferInto(id)

        EvidenceStore.writeManifest(id, armedAtMillis, triggerMillis, stopped)

        val segments = EvidenceStore.incidentSegments(id)
        Log.d(TAG, "incidente $id cerrado: ${segments.size} segmentos")

        // Que aparezcan en la galería del dispositivo.
        if (segments.isNotEmpty()) {
            MediaScannerConnection.scanFile(
                applicationContext,
                segments.map { it.absolutePath }.toTypedArray(),
                Array(segments.size) { "video/mp4" },
                null
            )
        }
        UploadService.startIncident(applicationContext, id)

        incidentId = null
        state = CaptureState.IDLE
        notifyStateChanged()

        if (serviceRequested) {
            // Volver a ARMED: reabrir cámara y anillo limpio.
            mainHandler.post { openCamera() }
        } else {
            finish()
        }
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

    // F4 alterna incidente; el servicio sigue armado por debajo.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_F4) {
            if (state == CaptureState.RECORDING) stopIncident() else startIncident()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(monitorTick)
        monitorJpeg = null
        try { unregisterReceiver(stopReceiver) } catch (_: Exception) {}
        // Si se destruye con un incidente vivo, sellarlo antes de soltar nada.
        val id = incidentId
        if (state == CaptureState.RECORDING && id != null) {
            teardownCapture()
            EvidenceStore.drainBufferInto(id)
            EvidenceStore.writeManifest(id, armedAtMillis, triggerMillis, System.currentTimeMillis())
        } else {
            teardownCapture()
        }
        state = CaptureState.IDLE
        incidentId = null
        cameraThread.quitSafely()
        HardwareController.ledGreen()
        super.onDestroy()
    }
}
