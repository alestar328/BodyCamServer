package com.falconone.bodycamserver

import android.os.PowerManager
import android.util.Log
import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

private const val TAG = "FalconKeys"

/**
 * Teclas físicas por la vía estándar de Android para teclas globales: llegan
 * aunque la app no esté delante.
 *
 * Activación (una vez por unidad): la hace `tools/kiosco.sh poner`. Ni un device
 * owner puede encender un servicio de accesibilidad por su cuenta.
 *
 * Aquí no se decide nada: la pulsación va en bruto a [BotonesFisicos], que sabe por
 * el perfil del modelo qué botón es y si esta vía es la suya. En la W1 no lo es —sus
 * botones van por el broadcast del fabricante— y todo pasa de largo.
 *
 * **Con la pantalla apagada puede no llegar nada.** Android solo pasa a la
 * accesibilidad las teclas que van a llegar al usuario, y con la pantalla apagada
 * el sistema tira las que no despiertan el aparato. Por eso el asistente comprueba
 * cada botón con la pantalla apagada en vez de darlo por hecho.
 *
 * Hasta el 2026-09-24 este servicio les daba a F3 y F4 el IR y la linterna, en
 * contra del mapa del broadcast (SOS y grabar). Con el kiosco, que lo activa, una
 * pulsación de F3 lanzaba el SOS **y** conmutaba el IR. Se quitó: el IR lo lleva
 * [ModoNoche] solo y la linterna, el teléfono.
 */
class ButtonAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        PerfilDispositivo.cargar(this)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            Log.d(TAG, "Accesibilidad: ${event.keyCode} scan=${event.scanCode}")
        }
        val pantalla = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        return BotonesFisicos.recibir(Pulsacion.de(event, Fuente.ACCESIBILIDAD, pantalla))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {}
    override fun onInterrupt() {}
}
