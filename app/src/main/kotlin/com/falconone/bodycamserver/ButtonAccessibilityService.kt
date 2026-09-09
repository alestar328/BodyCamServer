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
 *   F2 = PTT                  → NO se toca aquí; lo lleva BtServerService
 *   F3 = side button top      → IR LED on/off toggle
 *   F4 = side button bottom   → linterna
 *
 * OJO: lo que F3 y F4 hacen aquí (IR y linterna) no coincide con el mapa de
 * BtServerService (livestream y grabación). La contradicción es anterior al PTT y
 * sigue abierta; este servicio está desactivado en la unidad, así que hoy manda el
 * broadcast.
 */
class ButtonAccessibilityService : AccessibilityService() {

    private var irEnabled = false

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        Log.d(TAG, "KeyEvent: ${event.keyCode}")
        return when (event.keyCode) {
            // F2 es el PTT y se atiende SOLO en el broadcast SIDE_KEY_INTENT de
            // BtServerService, que llega igual con la pantalla apagada. Si también
            // se conmutase aquí, el broadcast (que el firmware emite al SOLTAR) y
            // este onKeyEvent (que llega al PULSAR) se separarían más que los
            // 300 ms de ButtonDebounce en cualquier pulsación larga, y el micro se
            // abriría y cerraría de golpe. Se deja pasar sin consumir.
            KeyEvent.KEYCODE_F2 -> false
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
