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
 * Prioridad: grabando > emitiendo > en buffer > reposo.
 */
object LedSignals {

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
     * quien vuelve a pintar el LED cuando la unidad arranca de nuevo.
     */
    @Volatile var apagando = false

    fun refresh() {
        if (apagando) return
        when {
            RecordingActivity.isRecording -> HardwareController.ledRedBlink()
            LivestreamService.isStreaming -> HardwareController.ledYellowBlink()
            RecordingActivity.state == CaptureState.ARMED -> HardwareController.ledBlue()
            else -> HardwareController.ledGreen()
        }
    }
}

// Controla el hardware de la bodycam vía los nodos sysfs documentados en W1-4G
object HardwareController {

    // ── Luz infrarroja ────────────────────────────────────────────────────────
    private val IR_NODE = File("/sys/class/i2c-dev/i2c-2/device/2-0064/ocp_regs")

    fun irOn()  = writeNode(IR_NODE, "1")
    fun irOff() = writeNode(IR_NODE, "0")

    // ── LEDs RGB ──────────────────────────────────────────────────────────────
    // 0=off | 1-6=rojo creciente | 7=verde fijo | 8=rojo parpadeo
    // 9=amarillo parpadeo | 10=azul fijo
    private val LED_NODE = File("/sys/class/i2c-dev/i2c-2/device/2-0045/aw2013_regs")

    fun setLed(value: Int) = writeNode(LED_NODE, value.toString())
    fun ledOff()           = setLed(0)
    fun ledGreen()         = setLed(7)   // standby
    fun ledRedBlink()      = setLed(8)   // grabando
    fun ledYellowBlink()   = setLed(9)   // procesando
    fun ledBlue()          = setLed(10)  // cargando

    // ── Sensor de luz ─────────────────────────────────────────────────────────
    private val LIGHT_ENABLE = File("/sys/class/input/input0/driver/enable")
    private val LIGHT_VALUE  = File("/sys/class/input/input0/driver/lux")

    fun lightSensorOn()  = writeNode(LIGHT_ENABLE, "1")
    fun lightSensorOff() = writeNode(LIGHT_ENABLE, "0")
    fun readLux(): Int = try { LIGHT_VALUE.readText().trim().toInt() } catch (_: Exception) { -1 }

    // ── Motor IR-CUT (filtro día/noche) ───────────────────────────────────────
    // Sentido medido con la cámara el 2026-09-18, sacando un fotograma del visor con
    // cada valor: "0" da colores normales y "1" la imagen magenta de un sensor sin
    // filtro. Hasta entonces los comentarios decían lo contrario (1 = día), sin que
    // nada lo usara todavía.
    private val MOTOR_NODE = File("/sys/class/misc/wiite_con_ctrl/motor_enable")

    fun filtroIrPuesto()  = writeNode(MOTOR_NODE, "0")  // modo día
    fun filtroIrQuitado() = writeNode(MOTOR_NODE, "1")  // modo noche, deja pasar el IR

    // ── GPS BeiDou ────────────────────────────────────────────────────────────
    private val GPS_NODE = File("/sys/class/misc/wiite_con_ctrl/beidou_enable")

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
        LED_NODE to "0",
        LIGHT_ENABLE to "0",
        MOTOR_NODE to "0",
    )

    // ── Helper ────────────────────────────────────────────────────────────────
    private fun writeNode(file: File, value: String): Boolean {
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
    private fun writeNodes(vararg nodos: Pair<File, String>): Boolean {
        val pendientes = nodos.filterNot { (file, value) -> escrituraDirecta(file, value) }
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
