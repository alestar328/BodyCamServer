package com.falconone.bodycamserver

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.Camera
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.math.abs

private const val TAG = "FalconPreview"

// Resolución objetivo del visor: suficiente para encuadrar y ligera para
// servir por WiFi a 1-2 fps sin castigar batería ni CPU.
private const val PREVIEW_TARGET_WIDTH = 640
private const val PREVIEW_TARGET_HEIGHT = 480
private const val PREVIEW_JPEG_QUALITY = 60

/**
 * Visor remoto para la foto a distancia: mantiene la cámara abierta con
 * preview activa, guarda el último frame y lo convierte a JPEG solo cuando
 * el teléfono lo pide por HTTP (GET /preview). La foto se dispara sobre esta
 * misma sesión de cámara, así lo que el agente ve es lo que captura.
 */
object PreviewController {

    @Volatile var isActive = false
        private set

    private var camera: Camera? = null

    // Último frame NV21 tal como llega del callback de preview; la conversión
    // a JPEG se hace bajo demanda para no gastar CPU en frames que nadie pide.
    @Volatile private var lastFrame: ByteArray? = null
    @Volatile private var frameWidth = 0
    @Volatile private var frameHeight = 0

    @Synchronized
    fun start(): Boolean {
        if (isActive) return true
        // Con el servicio armado el monitor sale de la propia sesión de captura
        // (FileServerService.liveJpeg), así que abrir un visor Camera1 aparte
        // solo chocaría con ella.
        if (RecordingActivity.isHoldingCamera) {
            Log.w(TAG, "Cannot start preview while capture is active (camera in use)")
            return false
        }
        TorchController.release()

        return try {
            val cam = Camera.open(0)
            val params = cam.parameters

            val previewSize = params.supportedPreviewSizes
                .minByOrNull { abs(it.width * it.height - PREVIEW_TARGET_WIDTH * PREVIEW_TARGET_HEIGHT) }
            if (previewSize != null) {
                params.setPreviewSize(previewSize.width, previewSize.height)
            }
            // La foto sale a máxima resolución aunque el visor sea pequeño.
            val best = params.supportedPictureSizes?.maxByOrNull { it.width * it.height }
            if (best != null) {
                params.setPictureSize(best.width, best.height)
            }
            params.jpegQuality = 90
            cam.parameters = params

            frameWidth = cam.parameters.previewSize.width
            frameHeight = cam.parameters.previewSize.height

            // Sin surface la preview no arranca en este HAL (mismo motivo que
            // en PhotoController).
            cam.setPreviewTexture(SurfaceTexture(0))
            cam.setPreviewCallback { data, _ -> lastFrame = data }
            cam.startPreview()

            camera = cam
            isActive = true
            Log.d(TAG, "Preview started at ${frameWidth}x$frameHeight")
            true
        } catch (e: Exception) {
            Log.e(TAG, "start failed: ${e.message}")
            releaseCamera()
            false
        }
    }

    @Synchronized
    fun stop() {
        if (camera == null) return
        Log.d(TAG, "Preview stopped")
        releaseCamera()
    }

    /** Último frame como JPEG para GET /preview, o null si el visor no corre. */
    fun latestJpeg(): ByteArray? {
        val frame = lastFrame ?: return null
        val width = frameWidth
        val height = frameHeight
        if (!isActive || width == 0 || height == 0) return null
        return try {
            val out = ByteArrayOutputStream()
            YuvImage(frame, ImageFormat.NV21, width, height, null)
                .compressToJpeg(Rect(0, 0, width, height), PREVIEW_JPEG_QUALITY, out)
            out.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "latestJpeg failed: ${e.message}")
            null
        }
    }

    /**
     * Dispara la foto sobre la cámara ya abierta del visor. takePicture
     * detiene la preview, por eso se rearranca en el callback: el visor
     * sigue vivo para encuadrar la siguiente foto.
     */
    @Synchronized
    fun takePhoto(context: Context): Boolean {
        val cam = camera ?: return false
        return try {
            cam.takePicture(null, null) { data, cameraDelCallback ->
                if (data != null) {
                    PhotoController.saveAndUpload(context, data)
                } else {
                    Log.e(TAG, "takePicture returned null data")
                }
                try { cameraDelCallback.startPreview() } catch (e: Exception) {
                    Log.e(TAG, "preview restart failed: ${e.message}")
                    stop()
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "takePhoto failed: ${e.message}")
            stop()
            false
        }
    }

    private fun releaseCamera() {
        try { camera?.setPreviewCallback(null) } catch (_: Exception) {}
        try { camera?.stopPreview() } catch (_: Exception) {}
        try { camera?.release() } catch (_: Exception) {}
        camera = null
        lastFrame = null
        isActive = false
    }
}
