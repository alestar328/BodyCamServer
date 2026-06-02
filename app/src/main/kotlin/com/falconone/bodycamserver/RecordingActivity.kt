package com.falconone.bodycamserver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.util.Log
import android.view.KeyEvent
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "FalconCamera"

class RecordingActivity : Activity() {

    companion object {
        const val ACTION_STOP = "com.falconone.STOP_RECORDING"

        @Volatile var isRecording = false
            private set

        fun start(context: Context) {
            context.startActivity(
                Intent(context, RecordingActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }

        fun stop(context: Context) {
            context.sendBroadcast(Intent(ACTION_STOP).setPackage(context.packageName))
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

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_STOP) stopAndFinish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        // TextureView real — HAL de cámara necesita surface de foreground app
        textureView = TextureView(this)
        setContentView(textureView)

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

    private fun stopAndFinish() {
        if (!isRecording) { finish(); return }
        Log.d(TAG, "stopAndFinish")
        isRecording = false
        try { captureSession?.stopRepeating() } catch (_: Exception) {}
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try {
            mediaRecorder?.stop()
            Log.d(TAG, "File saved: ${outputFile?.name}")
            // Notify gallery so the video appears immediately
            outputFile?.let { file ->
                // Notify gallery
                MediaScannerConnection.scanFile(
                    applicationContext, arrayOf(file.absolutePath), arrayOf("video/mp4"), null
                )
                // Auto-upload to server
                UploadService.start(applicationContext, file.absolutePath)
            }
        } catch (e: Exception) { Log.e(TAG, "recorder.stop: ${e.message}") }
        try { mediaRecorder?.release() } catch (_: Exception) {}
        mediaRecorder = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        HardwareController.ledGreen()
        finish()
    }

    // F4 while recording → stop. All other buttons pass through.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_F4 && isRecording) {
            Log.d(TAG, "F4 → stop recording")
            stopAndFinish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        isRecording = false
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
