package com.falconone.bodycamserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log

private const val TAG = "FalconApagado"

/**
 * Deja la unidad a oscuras cuando el agente la apaga o la reinicia.
 *
 * **El problema.** Los LEDs de color y los infrarrojos no son estado del proceso: son
 * un valor escrito en un nodo sysfs que mantiene el kernel. Apagar la unidad grabando
 * de noche no pasa por `BtServerService.onDestroy` ni por `ModoNoche.parar()`, así que
 * nadie baja el nodo y **las linternas IR se quedan encendidas con la unidad apagada**,
 * alumbrando dentro de la funda hasta que se agote la batería (visto por el usuario el
 * 2026-09-20). El DEVLOG del 21-sep daba el caso por no arreglable desde la app; lo es
 * para el apagado ordenado, que es el que se hace en mano desde el menú de encendido.
 *
 * **Por qué se registra en código y no en el manifest.** `ACTION_SHUTDOWN` es un
 * broadcast implícito y no está en la lista de excepciones de Android 8: con
 * `targetSdk 28`, un receiver declarado en el manifest no lo recibiría. Lo registra
 * [BtServerService], que es el proceso que está vivo siempre (foreground, START_STICKY).
 *
 * **Lo que NO cubre.** Un corte seco —batería fuera, `force-stop`, kill por memoria,
 * crash— no emite ningún broadcast y deja el nodo como estuviera. Para eso está el
 * apagado de `BootReceiver` y el `ponerDeDia()` con el que arranca [ModoNoche]: la
 * unidad se enciende siempre a oscuras aunque se apagara iluminando.
 */
class ApagadoReceiver : BroadcastReceiver() {

    companion object {
        /**
         * Ensayo del apagado en compilaciones de depuración:
         *
         * ```
         * adb shell am broadcast -a com.falconone.bodycamserver.APAGAR_LUCES -p com.falconone.bodycamserver
         * ```
         *
         * Hace falta porque `ACTION_SHUTDOWN` es un broadcast **protegido**: solo lo
         * manda el sistema, `adb shell` (uid 2000) se lleva un `SecurityException`, y
         * la unidad es una build `user` sin root. Sin esto, la única forma de ensayar
         * el apagado es apagarla de verdad y perder el cable (2026-09-21).
         *
         * Se comprueba con el sensor de luz, que ve el propio infrarrojo: a 265 lux de
         * ambiente, con las linternas puestas marca 1441. Ver [ModoNoche].
         *
         * **Trampa al comprobarlo:** el apagado para también el sensor, y con el
         * sensor parado el nodo `lux` devuelve un valor rancio que cae 180 por lectura
         * (1441, 1261, 1081…) y parece un infrarrojo apagándose despacio. Hay que
         * encender el sensor otra vez antes de leer:
         *
         * ```
         * adb shell 'echo 1 > /sys/class/input/input0/driver/enable; sleep 3; cat /sys/class/input/input0/driver/lux'
         * ```
         *
         * OJO: el ensayo deja el modo noche parado hasta que se reinicie el servicio.
         * `parar()` es definitivo a propósito; esto es un ensayo, no un comando de
         * producto.
         */
        const val ENSAYO_APAGADO = "com.falconone.bodycamserver.APAGAR_LUCES"

        /** ACTION_REBOOT también: un reinicio deja el nodo igual de encendido. */
        fun filtro() = IntentFilter().apply {
            addAction(Intent.ACTION_SHUTDOWN)
            addAction(Intent.ACTION_REBOOT)
            if (BuildConfig.DEBUG) addAction(ENSAYO_APAGADO)
        }

        /**
         * Todo lo que ilumina, apagado, en el orden que aguanta las carreras.
         *
         * **Los dos vetos van primero** porque los dos sitios que pintan hardware por
         * su cuenta siguen vivos mientras la unidad se apaga: el hilo de [ModoNoche]
         * puede estar a mitad de una medida (800 ms con el IR bajado y un `aplicarIr()`
         * detrás) y [LedSignals.refresh] corre en cada cambio de estado de captura,
         * incluido el que provoca el propio apagado al parar las activities. Sin los
         * vetos, cualquiera de los dos vuelve a encender justo después de que esto
         * hubiera apagado, y lo escrito en sysfs sobrevive a la app.
         *
         * Idempotente: se puede llamar desde donde sea y las veces que haga falta.
         */
        fun apagarTodasLasLuces(motivo: String) {
            val t0 = System.currentTimeMillis()
            LedSignals.apagando = true
            // Sin escribir: los nodos los baja apagarTodo() de una vez, más abajo.
            ModoNoche.parar(apagarNodos = false)
            val tNoche = System.currentTimeMillis()
            TorchController.turnOff()
            val tLinterna = System.currentTimeMillis()
            val ok = HardwareController.apagarTodo()
            val fin = System.currentTimeMillis()
            // El reparto se registra porque es el presupuesto: el apagado ordenado da
            // ~10 s para TODOS los receptores del sistema, no para el nuestro. Medido
            // en la unidad el 2026-09-21: 2070 ms, y 2070 de ellos son el motor del
            // filtro. Si algún día se dispara otro, aquí está el culpable sin tener
            // que volver a instrumentar con la unidad delante.
            Log.i(TAG, "$motivo: luces, infrarrojos y linterna apagados en ${fin - t0} ms" +
                " (noche ${tNoche - t0}, linterna ${tLinterna - tNoche}," +
                " nodos ${fin - tLinterna} ok=$ok)")
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        apagarTodasLasLuces(intent.action ?: "apagado")
        if (intent.action == ENSAYO_APAGADO) {
            Log.w(TAG, "era el ENSAYO: el modo noche se queda parado hasta reiniciar el servicio")
        }
    }
}
