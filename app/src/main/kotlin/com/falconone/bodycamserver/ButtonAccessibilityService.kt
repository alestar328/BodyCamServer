package com.falconone.bodycamserver

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

private const val TAG = "FalconKeys"

/**
 * Captures physical button presses (F2/F3/F4) even when the screen is off
 * or our Activities are not in the foreground.
 *
 * Activation (one-time, user must do this after first install):
 *   Settings → Accessibility → FalconOne → Enable
 *
 * Button layout (YIMAO W1):
 *   F2 = front action button  → toggle recording
 *   F3 = side button top      → IR LED on/off toggle
 *   F4 = side button bottom   → reserved (future: photo)
 */
class ButtonAccessibilityService : AccessibilityService() {

    private var irEnabled = false

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        Log.d(TAG, "KeyEvent: ${event.keyCode}")
        return when (event.keyCode) {
            KeyEvent.KEYCODE_F2 -> {
                if (RecordingActivity.isRecording) {
                    Log.d(TAG, "F2 → STOP recording")
                    RecordingActivity.stop(this)
                } else {
                    Log.d(TAG, "F2 → START recording")
                    RecordingActivity.start(this)
                }
                true
            }
            KeyEvent.KEYCODE_F3 -> {
                irEnabled = !irEnabled
                if (irEnabled) HardwareController.irOn() else HardwareController.irOff()
                Log.d(TAG, "F3 → IR ${if (irEnabled) "ON" else "OFF"}")
                true
            }
            KeyEvent.KEYCODE_F4 -> {
                val on = TorchController.toggle()
                Log.d(TAG, "F4 → Torch ${if (on) "ON" else "OFF"}")
                true
            }
            else -> false
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {}
    override fun onInterrupt() {}
}
