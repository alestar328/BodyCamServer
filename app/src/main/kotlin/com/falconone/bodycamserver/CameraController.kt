package com.falconone.bodycamserver

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "FalconCamera"

class CameraController(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null
    private var recorderSurface: Surface? = null

    private val cameraThread = HandlerThread("FalconCameraThread").also { it.start() }
    private val cameraHandler = Handler(cameraThread.looper)

    @Volatile var isRecording = false
        private set

    @Volatile private var cameraReleased = true

    private val outputDir: File
        get() {
            val dir = File(Environment.getExternalStorageDirectory(), "FalconOne")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    private fun timestamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    // ── Grabación de video (Camera2 + SurfaceTexture dummy) ──────────────────

    fun startRecording(): Boolean {
        if (isRecording) return false
        if (!cameraReleased) {
            Log.w(TAG, "Camera still releasing — waiting 800ms")
            Thread.sleep(800)
        }
        Log.d(TAG, "startRecording called")
        cameraReleased = false
        return try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = manager.cameraIdList.firstOrNull() ?: run {
                Log.e(TAG, "No cameras found"); cameraReleased = true; return false
            }
            Log.d(TAG, "Using camera: $cameraId, outputDir writable=${outputDir.canWrite()}")

            val file = File(outputDir, "VID_${timestamp()}.mp4")
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
                setOutputFile(file.absolutePath)
                prepare()
            }
            recorderSurface = recorder.surface

            // Dummy preview — Camera2 needs at least one surface even from a service
            val st = SurfaceTexture(1).also { it.setDefaultBufferSize(1280, 720) }
            val dummy = Surface(st)
            surfaceTexture = st
            previewSurface = dummy

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.d(TAG, "Camera opened")
                    cameraDevice = camera
                    try {
                        camera.createCaptureSession(
                            listOf(dummy, recorder.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    Log.d(TAG, "Session configured — starting recorder")
                                    captureSession = session
                                    try {
                                        val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                            addTarget(dummy)
                                            addTarget(recorder.surface)
                                        }.build()
                                        session.setRepeatingRequest(req, null, cameraHandler)
                                        recorder.start()
                                        mediaRecorder = recorder
                                        isRecording = true
                                        HardwareController.ledRedBlink()
                                        Log.d(TAG, "Recording STARTED — file: ${file.name}")
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Could not start repeating request: ${e.message}")
                                        releaseAll(recorder)
                                    }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    Log.e(TAG, "Session configure FAILED")
                                    releaseAll(recorder)
                                }

                                // Called when session is closed by the system
                                override fun onClosed(session: CameraCaptureSession) {
                                    Log.w(TAG, "Session closed externally")
                                    if (isRecording) {
                                        Log.d(TAG, "Saving file after external session close")
                                        safeStopRecorder()
                                    }
                                }
                            },
                            cameraHandler
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "createCaptureSession failed: ${e.message}")
                        releaseAll(recorder)
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera disconnected")
                    camera.close()
                    if (cameraDevice === camera) cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    camera.close()
                    if (cameraDevice === camera) cameraDevice = null
                }
            }, cameraHandler)

            true
        } catch (e: CameraAccessException) {
            Log.e(TAG, "CameraAccessException: ${e.message}"); cameraReleased = true; false
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission denied: ${e.message}"); cameraReleased = true; false
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed: ${e.message}"); cameraReleased = true; false
        }
    }

    fun stopRecording(): Boolean {
        if (!isRecording) {
            Log.w(TAG, "stopRecording: not recording"); return false
        }
        Log.d(TAG, "stopRecording called")
        isRecording = false
        // Stop session — ignore errors if system already closed it
        try { captureSession?.stopRepeating() } catch (e: Exception) { Log.w(TAG, "stopRepeating: ${e.message}") }
        try { captureSession?.close() } catch (e: Exception) { Log.w(TAG, "session.close: ${e.message}") }
        captureSession = null
        // Stop recorder — this is what flushes and saves the mp4 file
        return safeStopRecorder()
    }

    private fun safeStopRecorder(): Boolean {
        val rec = mediaRecorder ?: run { cameraReleased = true; return false }
        return try {
            rec.stop()
            Log.d(TAG, "Recorder stopped — file saved")
            true
        } catch (e: Exception) {
            Log.e(TAG, "recorder.stop failed: ${e.message}")
            false
        } finally {
            try { rec.release() } catch (_: Exception) {}
            mediaRecorder = null
            // Register a close callback so we know when hardware is truly free
            val dev = cameraDevice
            cameraDevice = null
            if (dev != null) {
                dev.close()
                // Give HAL 500ms to release hardware before marking available
                cameraHandler.postDelayed({ cameraReleased = true }, 500)
            } else {
                cameraReleased = true
            }
            try { previewSurface?.release() } catch (_: Exception) {}
            previewSurface = null
            try { surfaceTexture?.release() } catch (_: Exception) {}
            surfaceTexture = null
            recorderSurface = null
            HardwareController.ledGreen()
        }
    }

    private fun releaseAll(recorder: MediaRecorder) {
        isRecording = false
        try { recorder.release() } catch (_: Exception) {}
        mediaRecorder = null
        val dev = cameraDevice
        cameraDevice = null
        if (dev != null) {
            dev.close()
            cameraHandler.postDelayed({ cameraReleased = true }, 500)
        } else {
            cameraReleased = true
        }
        try { previewSurface?.release() } catch (_: Exception) {}
        previewSurface = null
        try { surfaceTexture?.release() } catch (_: Exception) {}
        surfaceTexture = null
    }

    // ── Foto ──────────────────────────────────────────────────────────────────

    fun takePhoto(): Boolean {
        return try {
            val intent = android.content.Intent("android.hardware.action.NEW_PICTURE")
            context.sendBroadcast(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "takePhoto failed: ${e.message}"); false
        }
    }

    // ── Estado ────────────────────────────────────────────────────────────────

    fun availableStorageMb(): Long {
        val stat = android.os.StatFs(outputDir.absolutePath)
        return stat.availableBlocksLong * stat.blockSizeLong / (1024 * 1024)
    }

    fun batteryLevel(): Int {
        val intent = context.registerReceiver(
            null,
            android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
        )
        val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
        return if (level >= 0) (level * 100 / scale) else -1
    }

    fun release() {
        stopRecording()
        cameraThread.quitSafely()
    }
}
