package com.falconone.bodycamserver

import android.os.Handler
import android.os.HandlerThread
import android.util.Log

private const val TAG = "FalconNoche"

/**
 * Modo noche automático: con poca luz, fuera el filtro IR-CUT y LEDs infrarrojos
 * encendidos; con luz, al revés. El agente no toca nada, igual que en el SOS.
 *
 * El hardware estaba entero y sin usar (comprobado por adb el 2026-09-18): sensor de
 * luz ALPS que mide de verdad (~175 lux en interior), motor del filtro y LEDs IR, los
 * tres en nodos sysfs escribibles. Ni la cámara del fabricante ni esta app los movían.
 *
 * ── Cuándo ────────────────────────────────────────────────────────────────────
 *
 * Solo con la cámara en uso: anillo armado, grabando o emitiendo. Con la cámara
 * parada no hay imagen que mejorar, y los LEDs IR gastan batería; una unidad metida
 * en un cajón a oscuras los encendería para nada.
 *
 * ── Histéresis ────────────────────────────────────────────────────────────────
 *
 * Dos umbrales separados y varias lecturas seguidas para cambiar. Con uno solo, pasar
 * bajo una farola haría clic-clac con el motor del filtro, y cada cambio deja un
 * salto de exposición en el vídeo que es evidencia.
 *
 * ── El sensor ve su propio infrarrojo ────────────────────────────────────────
 *
 * Medido en la unidad el 2026-09-18, a oscuras: con los LEDs IR encendidos el sensor
 * marca ~470 lux, más que la luz de una habitación (175-257). Con esa lectura el modo
 * entraba y salía cada 11 s. Ningún umbral separa "hay luz" de "es mi IR", así que de
 * noche la luz se mide **con el IR apagado un instante**: el sensor promedia unos
 * 0,7 s (tarda eso en caer de 463 a 0), y de ahí los [OSCURO_PARA_MEDIR_MILLIS].
 *
 * El precio es un parpadeo oscuro de menos de un segundo cada
 * [INTERVALO_NOCHE_MILLIS] en el vídeo nocturno. Se eligió eso frente a dejar la
 * unidad clavada en noche al volver a la luz: de día en blanco y negro se sigue
 * viendo, pero con los IR gastando batería sin necesidad.
 *
 * Los umbrales de lux son de partida. Cada cambio deja en logcat los lux que lo
 * provocaron (`adb logcat -s FalconNoche`) para ajustarlos con la unidad delante.
 */
object ModoNoche {

    /** Por debajo de esto, noche. */
    private const val LUX_ENTRAR = 10

    /** Por encima de esto, día. Muy por encima de [LUX_ENTRAR] a propósito. */
    private const val LUX_SALIR = 40

    /** Lecturas seguidas por debajo de [LUX_ENTRAR] para pasar a noche: ~9 s. */
    private const val LECTURAS_PARA_ENTRAR = 3

    /** Medidas limpias seguidas por encima de [LUX_SALIR] para volver a día: 20-40 s. */
    private const val MEDIDAS_PARA_SALIR = 2

    private const val INTERVALO_MILLIS = 3_000L

    /** De noche se mide menos a menudo: cada medida apaga el IR. */
    private const val INTERVALO_NOCHE_MILLIS = 20_000L

    /** IR apagado antes de leer. Algo más que los ~0,7 s que promedia el sensor. */
    private const val OSCURO_PARA_MEDIR_MILLIS = 800L

    @Volatile var esDeNoche = false
        private set

    /**
     * Aviso a la cámara abierta para que grabe en blanco y negro de noche. Lo pone
     * RecordingActivity al configurar la sesión y lo quita al cerrarla: el sensor y
     * los LEDs son de este objeto, pero la petición de captura es de ella.
     */
    @Volatile var alCambiar: ((Boolean) -> Unit)? = null

    private var hilo: HandlerThread? = null
    private var handler: Handler? = null
    private var sensorEncendido = false
    private var lecturasSeguidas = 0

    private val comprobar = object : Runnable {
        override fun run() {
            revisar()
            handler?.postDelayed(this, if (esDeNoche) INTERVALO_NOCHE_MILLIS else INTERVALO_MILLIS)
        }
    }

    /** Lo llama BtServerService al crearse. La unidad arranca siempre en modo día. */
    fun arrancar() {
        if (hilo != null) return
        ponerDeDia()
        hilo = HandlerThread("ModoNoche").also { it.start() }
        handler = Handler(hilo!!.looper).also { it.post(comprobar) }
    }

    fun parar() {
        handler?.removeCallbacksAndMessages(null)
        hilo?.quitSafely()
        hilo = null
        handler = null
        ponerDeDia()
        apagarSensor()
    }

    private fun camaraEnUso(): Boolean =
        RecordingActivity.isHoldingCamera || LivestreamService.isStreaming

    private fun revisar() {
        if (!camaraEnUso()) {
            if (esDeNoche) {
                Log.i(TAG, "cámara parada: vuelta a modo día para no gastar batería en los IR")
                ponerDeDia()
            }
            apagarSensor()
            lecturasSeguidas = 0
            return
        }

        if (!sensorEncendido) {
            sensorEncendido = HardwareController.lightSensorOn()
            // La primera lectura tras encender puede ser 0; se espera a la siguiente vuelta.
            return
        }

        val lux = if (esDeNoche) luxSinInfrarrojo() else HardwareController.readLux()
        if (lux < 0) return   // lectura fallida: no cuenta ni a favor ni en contra

        val alOtroLado = if (esDeNoche) lux > LUX_SALIR else lux < LUX_ENTRAR
        lecturasSeguidas = if (alOtroLado) lecturasSeguidas + 1 else 0
        val necesarias = if (esDeNoche) MEDIDAS_PARA_SALIR else LECTURAS_PARA_ENTRAR
        if (lecturasSeguidas < necesarias) return

        lecturasSeguidas = 0
        if (esDeNoche) {
            Log.i(TAG, "$lux lux sin IR durante $necesarias medidas: modo día")
            ponerDeDia()
        } else {
            Log.i(TAG, "$lux lux durante $necesarias lecturas: modo noche")
            ponerDeNoche()
        }
    }

    /**
     * La luz de verdad, con el IR apagado el tiempo justo para que el sensor deje de
     * verlo. Bloquea este hilo, que es solo del modo noche. Si la cámara se suelta
     * durante la espera, el IR no se vuelve a encender: la siguiente vuelta pone el
     * modo día.
     */
    private fun luxSinInfrarrojo(): Int {
        HardwareController.irOff()
        try {
            Thread.sleep(OSCURO_PARA_MEDIR_MILLIS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return -1
        }
        val lux = HardwareController.readLux()
        if (camaraEnUso()) HardwareController.irOn()
        return lux
    }

    private fun ponerDeNoche() {
        HardwareController.filtroIrQuitado()
        HardwareController.irOn()
        esDeNoche = true
        alCambiar?.invoke(true)
    }

    private fun ponerDeDia() {
        HardwareController.irOff()
        HardwareController.filtroIrPuesto()
        esDeNoche = false
        alCambiar?.invoke(false)
    }

    private fun apagarSensor() {
        if (!sensorEncendido) return
        HardwareController.lightSensorOff()
        sensorEncendido = false
    }
}
