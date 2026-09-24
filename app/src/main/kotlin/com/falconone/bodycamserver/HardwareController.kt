package com.falconone.bodycamserver

import java.io.File

/**
 * Único punto que decide el color del LED.
 *
 * Antes cada sitio escribía su color por su cuenta y se pisaban: conectar el
 * teléfono ponía verde "standby" encima del azul de buffer, aunque el anillo
 * siguiera armado. Aquí el color sale del estado real de la unidad, así que da
 * igual quién llame ni en qué orden — el resultado es siempre el correcto.
 *
 * **El LED es dos cosas a la vez** (decidido con el cliente el 2026-09-22): dice si
 * la unidad está grabando y, cuando no lo está, cuánta batería le queda. No caben
 * las dos señales en un solo LED, y la paleta del nodo sysfs no da para separarlas
 * (hay rojo y amarillo parpadeando, pero **no hay verde ni azul parpadeando**), así
 * que se reparten por prioridad:
 *
 * ```
 * batería < 40  -> rojo            (parpadeando si además graba)
 * grabando      -> rojo parpadeo
 * emitiendo     -> amarillo parpadeo
 * batería >= 80 -> verde
 * batería >= 40 -> azul
 * ```
 *
 * La batería baja es lo único que se impone al estado: una unidad que se va a
 * quedar sin corriente a mitad de un incidente es peor noticia que no saber si
 * está grabando, y el rojo sigue parpadeando mientras graba, así que la señal de
 * grabación no se pierde del todo.
 *
 * **Lo que se pierde:** el azul fijo ya no significa "anillo armado". En cuanto se
 * conoce el nivel de batería, reposo y armado se ven igual; la distinción sigue en
 * el panel de la pantalla y en el STATUS que lee el teléfono. Mientras el nivel es
 * desconocido ([nivelBateria] < 0, entre el arranque y el primer
 * `ACTION_BATTERY_CHANGED`) se pinta con el código de estado de siempre.
 */
object LedSignals {

    /**
     * Umbrales de batería en tanto por ciento, tal y como los pidió el cliente:
     * de 80 para arriba verde, de 40 a 79 azul y por debajo de 40 rojo.
     */
    const val BATERIA_VERDE = 80
    const val BATERIA_AZUL  = 40

    /** Todavía no se ha escrito ningún color, o el último intento falló. */
    private const val SIN_COLOR = -1

    /**
     * Apagado en curso: a partir de aquí el LED ya no se repinta.
     *
     * Mismo veto que el de `ModoNoche.parando`, y por la misma razón. Al apagar la
     * unidad, el sistema para las activities y los servicios, y **cada** cambio de
     * estado de captura pasa por aquí: sin el veto, el `refresh()` de un `onDestroy`
     * volvería a poner el LED verde justo después de que [ApagadoReceiver] lo hubiera
     * bajado, y el valor vive en sysfs — se quedaría encendido con la unidad apagada.
     *
     * Lo levanta [ApagadoReceiver] y lo quita `BtServerService.onCreate`, que es
     * quien vuelve a pintar el LED cuando la unidad arranca de nuevo. Tocarlo olvida
     * el color cacheado: [apagarTodo] baja el nodo por detrás de [refresh], y sin
     * olvidarlo el primer `refresh()` del arranque creería que ya está pintado.
     */
    @Volatile var apagando = false
        set(value) {
            field = value
            ultimoColor = SIN_COLOR
        }

    /**
     * Último nivel de batería conocido, 0-100, o -1 mientras no haya llegado ninguno.
     *
     * Lo alimenta el receptor de `ACTION_BATTERY_CHANGED` de [BtServerService], que
     * es el proceso vivo siempre. Aquí es un campo y no una consulta porque
     * [refresh] lo llama todo el mundo —receivers, `onDestroy`, el hilo de captura—
     * y ninguno de esos sitios tiene un `Context` a mano.
     */
    @Volatile var nivelBateria = -1
        private set

    /**
     * El último color escrito, para no repetir la escritura.
     *
     * `ACTION_BATTERY_CHANGED` no llega solo al cambiar el porcentaje —también con
     * la temperatura o el voltaje—, y cada escritura del nodo cuesta ~44 ms en la
     * unidad, o ~0,7 s si cae al `sh -c`. Solo se guarda cuando la escritura ha ido
     * bien: si falló, el siguiente `refresh()` vuelve a intentarlo.
     */
    @Volatile private var ultimoColor = SIN_COLOR

    /** Nuevo nivel de batería; repinta solo si el color cambia. */
    fun actualizarBateria(nivel: Int) {
        if (nivel == nivelBateria) return
        nivelBateria = nivel
        refresh()
    }

    fun refresh() {
        if (apagando) return
        val color = colorActual()
        if (color == ultimoColor) return
        ultimoColor = if (HardwareController.setLed(color)) color else SIN_COLOR
    }

    /**
     * El color que le toca a la unidad ahora mismo, sin escribir nada.
     *
     * Separado de [refresh] para poder razonarlo —y probarlo— sin hardware delante.
     */
    fun colorActual(): Int {
        val grabando = RecordingActivity.isRecording
        return when {
            // La batería baja se impone al estado: si además graba, el rojo parpadea,
            // que es justo el color de grabar. Las dos señales caben en una.
            bateriaBaja() -> if (grabando) HardwareController.LED_ROJO_PARPADEO
                             else HardwareController.LED_ROJO
            grabando -> HardwareController.LED_ROJO_PARPADEO
            LivestreamService.isStreaming -> HardwareController.LED_AMARILLO_PARPADEO
            nivelBateria >= BATERIA_VERDE -> HardwareController.LED_VERDE
            nivelBateria >= BATERIA_AZUL  -> HardwareController.LED_AZUL
            // Sin nivel conocido todavía: código de estado de siempre.
            RecordingActivity.state == CaptureState.ARMED -> HardwareController.LED_AZUL
            else -> HardwareController.LED_VERDE
        }
    }

    /** Ojo con el -1: "desconocido" no es "vacía". */
    private fun bateriaBaja() = nivelBateria in 0 until BATERIA_AZUL
}

/**
 * Controla el hardware de la bodycam vía nodos sysfs.
 *
 * Las rutas salen del perfil del modelo ([PerfilDispositivo]); las de la W1-4G son
 * las de su documentación. Una pieza sin ruta (`null`) es una pieza que el modelo no
 * tiene o que no sabemos mover: su función devuelve `false` y no escribe nada, y
 * quien la llama sigue igual que si la escritura hubiera fallado.
 */
object HardwareController {

    private fun nodo(ruta: String?): File? = ruta?.let(::File)
    private val nodos get() = PerfilDispositivo.actual.nodos

    // ── Luz infrarroja ────────────────────────────────────────────────────────
    private val IR_NODE get() = nodo(nodos.ir)

    fun irOn()  = writeNode(IR_NODE, nodos.irValor)
    fun irOff() = writeNode(IR_NODE, "0")

    // ── LEDs RGB ──────────────────────────────────────────────────────────────
    // 0=off | 1-6=rojo creciente | 7=verde fijo | 8=rojo parpadeo
    // 9=amarillo parpadeo | 10=azul fijo
    //
    // Quién usa cada color lo decide [LedSignals]; esto es solo la tabla del nodo.
    // La tabla de valores es la del aw2013 de la W1. En otro modelo con LED en otra
    // ruta, los números pueden significar otra cosa: hay que revisarla con él delante.
    private val LED_NODE get() = nodo(nodos.led)

    const val LED_APAGADO           = 0
    /**
     * Rojo fijo = batería por debajo del 40 %.
     *
     * **Sin verificar en la unidad** (2026-09-22: no había ninguna conectada). Los
     * valores 1-6 son rojo de brillo creciente según la tabla del fabricante, y 6 es
     * el más vivo, pero los únicos comprobados a mano son 0, 7, 8, 9 y 10. Si en el
     * aparato se ve apagado o demasiado débil, es este número y se cambia aquí solo.
     */
    const val LED_ROJO              = 6
    const val LED_VERDE             = 7
    const val LED_ROJO_PARPADEO     = 8
    const val LED_AMARILLO_PARPADEO = 9
    const val LED_AZUL              = 10

    fun setLed(value: Int) = writeNode(LED_NODE, value.toString())
    fun ledOff()           = setLed(LED_APAGADO)
    fun ledRed()           = setLed(LED_ROJO)              // batería baja
    fun ledGreen()         = setLed(LED_VERDE)             // batería llena
    fun ledRedBlink()      = setLed(LED_ROJO_PARPADEO)     // grabando
    fun ledYellowBlink()   = setLed(LED_AMARILLO_PARPADEO) // emitiendo
    fun ledBlue()          = setLed(LED_AZUL)              // batería media

    // ── Sensor de luz ─────────────────────────────────────────────────────────
    private val LIGHT_ENABLE get() = nodo(nodos.luzActivar)
    private val LIGHT_VALUE  get() = nodo(nodos.luzValor)

    fun lightSensorOn()  = writeNode(LIGHT_ENABLE, "1")
    fun lightSensorOff() = writeNode(LIGHT_ENABLE, "0")
    fun readLux(): Int = try { LIGHT_VALUE?.readText()?.trim()?.toInt() ?: -1 } catch (_: Exception) { -1 }

    // ── Motor IR-CUT (filtro día/noche) ───────────────────────────────────────
    // Sentido medido con la cámara el 2026-09-18, sacando un fotograma del visor con
    // cada valor: "0" da colores normales y "1" la imagen magenta de un sensor sin
    // filtro. Hasta entonces los comentarios decían lo contrario (1 = día), sin que
    // nada lo usara todavía.
    private val MOTOR_NODE get() = nodo(nodos.filtroIr)

    fun filtroIrPuesto()  = writeNode(MOTOR_NODE, "0")  // modo día
    fun filtroIrQuitado() = writeNode(MOTOR_NODE, "1")  // modo noche, deja pasar el IR

    // ── GPS BeiDou ────────────────────────────────────────────────────────────
    private val GPS_NODE get() = nodo(nodos.gps)

    fun gpsOn()  = writeNode(GPS_NODE, "1")
    fun gpsOff() = writeNode(GPS_NODE, "0")

    // ── Apagado ───────────────────────────────────────────────────────────────

    /**
     * Todos los nodos que iluminan, a cero, el filtro IR-CUT en su sitio y el sensor
     * de luz parado. **En una sola llamada al shell**: ver [writeNodes].
     *
     * Existe porque estos valores viven en sysfs y **sobreviven al proceso**: lo que
     * no se baje antes de que la unidad se apague se queda encendido hasta el
     * siguiente arranque. Lo llaman [ApagadoReceiver] (apagado y reinicio ordenados)
     * y [BootReceiver] (por si el apagado fue seco y no dio tiempo a nada).
     *
     * El filtro va con las luces por lo mismo: el motor se queda donde lo dejaron, y
     * arrancar con el filtro fuera da la imagen magenta de un sensor sin filtro hasta
     * que el modo noche decide. Devolverlo a modo día es el estado neutro.
     */
    fun apagarTodo(): Boolean = writeNodes(
        // El orden no es cosmético. Medido en la unidad el 2026-09-20: el infrarrojo
        // tarda 27 ms, el LED 44 y el sensor 29, pero **el motor del filtro bloquea
        // 2070 ms** porque la escritura espera a que la pieza acabe de moverse. Las
        // luces van delante para que estén apagadas en el primer décimo de segundo
        // aunque el sistema nos corte el apagado por la mitad; el filtro, que no
        // alumbra y que `ModoNoche.arrancar()` vuelve a poner en cada encendido, va
        // el último y es lo único prescindible de esta lista.
        IR_NODE to "0",
        LED_NODE to LED_APAGADO.toString(),
        LIGHT_ENABLE to "0",
        MOTOR_NODE to "0",
    )

    /**
     * Escribe en una ruta que todavía no está en el perfil. Solo para la autoprueba
     * del asistente, que prueba nodos candidatos antes de darlos por buenos.
     */
    fun escribirCandidato(ruta: String, valor: String): Boolean = writeNode(File(ruta), valor)

    // ── Helper ────────────────────────────────────────────────────────────────
    private fun writeNode(file: File?, value: String): Boolean {
        if (file == null) return false
        if (escrituraDirecta(file, value)) return true
        // Requiere permisos root para algunos nodos — intentar vía shell.
        val ok = shell("echo $value > ${file.absolutePath}")
        if (!ok) android.util.Log.w("FalconHW", "sysfs rechazado: $value -> ${file.absolutePath}")
        return ok
    }

    /**
     * Varios nodos de golpe, con **una sola** llamada al shell para los que no se
     * dejen escribir directamente.
     *
     * En esta unidad la escritura directa no cuela para ninguno de estos nodos y cada
     * `sh -c` cuesta ~0,7 s (medido el 2026-09-20: cuatro nodos uno a uno, 4,1 s).
     * Eso importa solo en el apagado, donde el sistema da ~10 s para **todos** los
     * receptores de ACTION_SHUTDOWN, no para el nuestro; agrupadas son ~0,7 s.
     */
    private fun writeNodes(vararg nodos: Pair<File?, String>): Boolean {
        val pendientes = nodos.mapNotNull { (file, value) -> file?.let { it to value } }
            .filterNot { (file, value) -> escrituraDirecta(file, value) }
        if (pendientes.isEmpty()) return true
        val guion = pendientes.joinToString("; ") { (file, value) -> "echo $value > ${file.absolutePath}" }
        val ok = shell(guion)
        if (!ok) android.util.Log.w("FalconHW", "sysfs rechazado en bloque: $guion")
        return ok
    }

    private fun escrituraDirecta(file: File, value: String): Boolean = try {
        file.writeText(value)
        true
    } catch (_: Exception) { false }

    /**
     * Antes se devolvía true sin mirar: exec() no falla aunque el echo muera por
     * permisos, y el fallo quedaba invisible. Ahora el exit code decide.
     */
    private fun shell(guion: String): Boolean = try {
        Runtime.getRuntime().exec(arrayOf("sh", "-c", guion)).waitFor() == 0
    } catch (_: Exception) { false }
}
