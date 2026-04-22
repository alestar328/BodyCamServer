package com.falconone.bodycamserver

import android.hardware.Camera
import android.util.Log

private const val TAG = "FalconTorch"

/**
 * Controls the white flash/torch via Camera1 API.
 * Camera2 reports "no flash unit" on UNISOC SL8541E but Camera1 exposes it correctly.
 * Must not be used while RecordingActivity has Camera2 open — call release() first.
 */
object TorchController {

    @Volatile var isOn = false
        private set

    private var camera: Camera? = null

    fun turnOn(): Boolean {
        if (RecordingActivity.isRecording) {
            Log.w(TAG, "Cannot open torch while recording (Camera2 holds hardware)")
            return false
        }
        return try {
            if (camera == null) {
                camera = Camera.open(0)
            }
            val params = camera!!.parameters
            val supported = params.supportedFlashModes
            Log.d(TAG, "Supported flash modes: $supported")
            if (supported != null && supported.contains(Camera.Parameters.FLASH_MODE_TORCH)) {
                params.flashMode = Camera.Parameters.FLASH_MODE_TORCH
                camera!!.parameters = params
                camera!!.startPreview()
                isOn = true
                Log.d(TAG, "Torch ON")
                true
            } else {
                Log.w(TAG, "Torch mode not supported by Camera1")
                release()
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "turnOn failed: ${e.message}")
            release()
            false
        }
    }

    fun turnOff() {
        try {
            val cam = camera ?: return
            val params = cam.parameters
            params.flashMode = Camera.Parameters.FLASH_MODE_OFF
            cam.parameters = params
            cam.stopPreview()
            isOn = false
            Log.d(TAG, "Torch OFF")
        } catch (e: Exception) {
            Log.e(TAG, "turnOff failed: ${e.message}")
        } finally {
            release()
        }
    }

    fun toggle(): Boolean {
        return if (isOn) { turnOff(); false } else turnOn()
    }

    fun release() {
        try { camera?.release() } catch (_: Exception) {}
        camera = null
        isOn = false
    }
}
