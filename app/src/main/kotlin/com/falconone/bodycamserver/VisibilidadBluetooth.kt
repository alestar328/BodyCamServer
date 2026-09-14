package com.falconone.bodycamserver

import android.bluetooth.BluetoothAdapter
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Hace que los telefonos puedan encontrar la unidad al buscar bodycams cercanas.
 *
 * Por defecto la W1 esta en SCAN_MODE_CONNECTABLE: acepta al telefono que ya conoce
 * su MAC, pero no aparece en una busqueda. Asi un telefono nuevo no la encontraba
 * nunca (verificado el 2026-09-14: solo salia en la lista del Samsung porque ya
 * estaba emparejado).
 *
 * La via publica, ACTION_REQUEST_DISCOVERABLE, saca un dialogo del sistema que alguien
 * tendria que aceptar en la pantalla de la unidad. Por eso se usa el metodo oculto
 * setScanMode, que en Android 9 solo pide el permiso BLUETOOTH. La visibilidad dura
 * un tiempo limitado a proposito: una unidad policial anunciandose todo el dia
 * permitiria seguirla por la calle.
 */
object VisibilidadBluetooth {

    private const val TAG = "VisibilidadBT"

    // Margen para encender la bodycam, abrir la app del telefono y buscar.
    private const val SEGUNDOS_VISIBLE = 5 * 60

    private val handler = Handler(Looper.getMainLooper())

    // La duracion que recibe setScanMode no la hace cumplir la pila Bluetooth: en
    // Android 9 el temporizador lo lleva la app de Ajustes. Sin esto la unidad se
    // quedaba visible indefinidamente (medido el 2026-09-14: seguia visible a los 8 min).
    private val ocultar = Runnable { cambiarModo(BluetoothAdapter.SCAN_MODE_CONNECTABLE) }

    /** Devuelve false si el sistema no lo permite; la unidad sigue siendo conectable. */
    fun hacerVisible(): Boolean {
        handler.removeCallbacks(ocultar)
        val aceptado = cambiarModo(BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE)
        if (aceptado) handler.postDelayed(ocultar, SEGUNDOS_VISIBLE * 1000L)
        return aceptado
    }

    private fun cambiarModo(modo: Int): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth apagado: no se puede cambiar la visibilidad")
            return false
        }
        return try {
            val setScanMode = BluetoothAdapter::class.java.getMethod(
                "setScanMode",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            val aceptado = setScanMode.invoke(adapter, modo, SEGUNDOS_VISIBLE) as Boolean
            Log.i(TAG, "Modo de escaneo $modo: $aceptado")
            aceptado
        } catch (e: Exception) {
            Log.w(TAG, "El sistema no deja cambiar la visibilidad", e)
            false
        }
    }
}
