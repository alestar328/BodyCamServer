package com.falconone.bodycamserver

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TAG = "FalconPhoto"

object PhotoController {

    fun takePhoto(context: Context): Boolean {
        if (RecordingActivity.isHoldingCamera) {
            Log.w(TAG, "Cannot take photo while capture is active (camera in use)")
            return false
        }
        TorchController.release()

        var camera: Camera? = null
        return try {
            camera = Camera.open(0)
            val params = camera.parameters

            val sizes = params.supportedPictureSizes
            val best = sizes?.maxByOrNull { it.width * it.height }
            if (best != null) {
                params.setPictureSize(best.width, best.height)
            }
            params.jpegQuality = 90
            camera.parameters = params

            // El HAL (UNISOC) exige una preview realmente activa antes de
            // takePicture; sin surface asignada la preview no arranca y
            // takePicture lanza RuntimeException.
            camera.setPreviewTexture(SurfaceTexture(0))
            camera.startPreview()

            // Esperar el primer frame confirma que la preview esta viva.
            val firstFrame = CountDownLatch(1)
            camera.setOneShotPreviewCallback { _, _ -> firstFrame.countDown() }
            if (!firstFrame.await(2, TimeUnit.SECONDS)) {
                Log.w(TAG, "Preview frame timeout — trying takePicture anyway")
            }

            val cam = camera
            cam.takePicture(null, null) { data, _ ->
                cam.stopPreview()
                cam.release()
                if (data != null) {
                    saveAndUpload(context, data)
                } else {
                    Log.e(TAG, "takePicture returned null data")
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "takePhoto failed: ${e.message}")
            // Sin esto la camara queda abierta y todos los intentos
            // siguientes fallan hasta reiniciar la app.
            try { camera?.release() } catch (_: Exception) {}
            false
        }
    }

    // Tambien la usa PreviewController: la foto del visor sigue el mismo
    // camino de guardado y auto-upload que la foto a ciegas.
    fun saveAndUpload(context: Context, data: ByteArray) {
        try {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val dir = File(Environment.getExternalStorageDirectory(), "FalconOne").also { it.mkdirs() }
            val file = File(dir, "IMG_$ts.jpg")
            file.writeBytes(data)
            Log.d(TAG, "Photo saved: ${file.absolutePath}")

            android.media.MediaScannerConnection.scanFile(
                context, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null
            )

            UploadService.start(context, file.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "saveAndUpload failed: ${e.message}")
        }
    }
}
