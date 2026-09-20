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
 * La luz se **mide** con la cámara en uso: anillo armado, grabando o emitiendo. Con
 * la cámara parada no hay imagen que mejorar, y medir por medir gasta batería; una
 * unidad metida en un cajón a oscuras no tiene nada que iluminar.
 *
 * Los **LEDs** solo se encienden grabando o emitiendo ([irHaceFalta]). Atados al
 * anillo armado se quedaban puestos para siempre: parar una grabación vuelve a
 * ARMED, no a IDLE, y abrir la app arma el anillo para todo el servicio, así que a
 * oscuras las linternas no se apagaban nunca (corregido el 2026-09-20).
 *
 * El filtro IR-CUT sí sigue al modo noche entero, armado incluido: quitarlo y
 * ponerlo mueve un motor, y hacerlo en cada arranque y parada de grabación sería un
 * clic-clac constante. El precio es que el pre-roll nocturno se graba sin LEDs, solo
 * con el sensor a pelo; desde el disparo, el incidente ya va iluminado.
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
 * entraba y salía cada 11 s. Ningún umbral separa "hay luz" de "es mi IR", así que
 * **con los LEDs encendidos** la luz se mide **apagándolos un instante**: el sensor
 * promedia unos 0,7 s (tarda eso en caer de 463 a 0), y de ahí los
 * [OSCURO_PARA_MEDIR_MILLIS].
 *
 * El precio es un parpadeo oscuro de menos de un segundo cada
 * [INTERVALO_NOCHE_MILLIS] en el vídeo nocturno. Se eligió eso frente a dejar la
 * unidad clavada en noche al volver a la luz: de día en blanco y negro se sigue
 * viendo, pero con los IR gastando batería sin necesidad.
 *
 * Con los LEDs apagados (anillo armado sin grabar) la lectura ya sale limpia: ni
 * parpadeo que disimular ni intervalo largo que guardar.
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

    /** Con los LEDs encendidos se mide menos a menudo: cada medida los apaga. */
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

    /** Lo último que esta clase le ha pedido al nodo del IR. Ver [aplicarIr]. */
    @Volatile private var irEncendido = false

    /**
     * Parada en curso: a partir de aquí nadie enciende. [parar] no interrumpe al
     * hilo, así que su `irOff` lo pisaba el `irOn` de un ciclo a medio correr y los
     * LEDs se quedaban encendidos sin nadie que los bajara — el valor vive en sysfs,
     * no en el proceso, y sobrevive a la app.
     */
    @Volatile private var parando = false

    private val comprobar = object : Runnable {
        override fun run() {
            revisar()
            // El intervalo largo lo pide el parpadeo de medir, no la noche: con los
            // LEDs apagados la lectura no cuesta nada y se puede repetir a menudo.
            handler?.postDelayed(this, if (irEncendido) INTERVALO_NOCHE_MILLIS else INTERVALO_MILLIS)
        }
    }

    /** Lo llama BtServerService al crearse. La unidad arranca siempre en modo día. */
    fun arrancar() {
        if (hilo != null) return
        parando = false
        ponerDeDia()
        hilo = HandlerThread("ModoNoche").also { it.start() }
        handler = Handler(hilo!!.looper).also { it.post(comprobar) }
    }

    /**
     * @param apagarNodos si esta función escribe el modo día en sysfs. El apagado de
     *   la unidad lo pone a `false` y baja los cuatro nodos de golpe, en una sola
     *   llamada al shell, porque ahí los segundos cuentan (ver [ApagadoReceiver] y
     *   [HardwareController.apagarTodo]). El estado interno y el aviso a la cámara se
     *   dejan igual en los dos casos: lo único que cambia es quién escribe.
     */
    fun parar(apagarNodos: Boolean = true) {
        // Lo primero de todo: veta cualquier encendido posterior, incluido el del
        // ciclo que pueda estar corriendo ahora mismo en el hilo. Con el veto puesto,
        // ese ciclo a medias solo puede escribir un apagado, nunca un encendido, así
        // que da igual si cae antes o después de quien nos llama.
        parando = true
        handler?.removeCallbacksAndMessages(null)
        hilo?.quitSafely()
        hilo = null
        handler = null
        lecturasSeguidas = 0
        if (apagarNodos) {
            ponerDeDia()
            apagarSensor()
        } else {
            esDeNoche = false
            sensorEncendido = false
            alCambiar?.invoke(false)
        }
    }

    /**
     * Repasa si los LEDs tienen que estar encendidos ahora mismo. Lo llaman los
     * cambios de estado de captura y de emisión: sin esto, parar una grabación de
     * noche dejaba las linternas puestas hasta el siguiente ciclo, 20 s después.
     *
     * Va al hilo del modo noche para que todas las escrituras del nodo salgan del
     * mismo sitio y no se crucen con una medida en curso.
     */
    fun sincronizarIr() {
        handler?.post { aplicarIr() }
    }

    /** Hay imagen que mejorar: anillo armado, grabando o emitiendo. */
    private fun camaraEnUso(): Boolean =
        RecordingActivity.isHoldingCamera || LivestreamService.isStreaming

    /** Hay imagen que iluminar: la que va a evidencia o al teléfono. */
    private fun irHaceFalta(): Boolean =
        esDeNoche && (RecordingActivity.isRecording || LivestreamService.isStreaming)

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

        val lux = if (irEncendido) luxSinInfrarrojo() else HardwareController.readLux()
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
     * verlo. Bloquea este hilo, que es solo del modo noche. Si durante la espera se
     * para la grabación, el IR no se vuelve a encender: [aplicarIr] lo decide por el
     * estado de ese momento, no por el de antes de dormir.
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
        aplicarIr()
        return lux
    }

    private fun ponerDeNoche() {
        esDeNoche = true                      // antes de aplicarIr, que lo consulta
        HardwareController.filtroIrQuitado()
        aplicarIr()
        alCambiar?.invoke(true)
    }

    private fun ponerDeDia() {
        esDeNoche = false
        aplicarIr()
        HardwareController.filtroIrPuesto()
        alCambiar?.invoke(false)
    }

    /**
     * Deja el nodo del IR como pide el estado real. Idempotente y sin orden: se
     * puede llamar desde donde sea y tantas veces como haga falta, igual que
     * [LedSignals.refresh] con el LED de color.
     *
     * Escribe siempre, aunque no crea que cambia nada: el nodo es de solo escritura
     * y lo tocan también los comandos IR_ON/IR_OFF del teléfono, así que lo que vale
     * de verdad no se puede dar por sabido.
     */
    private fun aplicarIr() {
        val encender = !parando && irHaceFalta()
        if (encender) HardwareController.irOn() else HardwareController.irOff()
        irEncendido = encender
    }

    private fun apagarSensor() {
        if (!sensorEncendido) return
        HardwareController.lightSensorOff()
        sensorEncendido = false
    }
}
