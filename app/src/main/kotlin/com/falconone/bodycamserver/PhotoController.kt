package com.falconone.bodycamserver

import android.content.Context
import android.hardware.Camera
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "FalconPhoto"

object PhotoController {

    fun takePhoto(context: Context): Boolean {
        if (RecordingActivity.isRecording) {
            Log.w(TAG, "Cannot take photo while recording (camera in use)")
            return false
        }
        TorchController.release()

        return try {
            val camera = Camera.open(0)
            val params = camera.parameters

            val sizes = params.supportedPictureSizes
            val best = sizes?.maxByOrNull { it.width * it.height }
            if (best != null) {
                params.setPictureSize(best.width, best.height)
            }
            params.jpegQuality = 90
            camera.parameters = params
            camera.startPreview()

            camera.takePicture(null, null) { data, _ ->
                camera.stopPreview()
                camera.release()
                if (data != null) {
                    saveAndUpload(context, data)
                } else {
                    Log.e(TAG, "takePicture returned null data")
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "takePhoto failed: ${e.message}")
            false
        }
    }

    private fun saveAndUpload(context: Context, data: ByteArray) {
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
