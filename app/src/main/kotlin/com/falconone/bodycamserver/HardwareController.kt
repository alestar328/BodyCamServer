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
    fun refresh() {
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
    private val MOTOR_NODE = File("/sys/class/misc/wiite_con_ctrl/motor_enable")

    fun motorForward() = writeNode(MOTOR_NODE, "1")  // modo día
    fun motorReverse() = writeNode(MOTOR_NODE, "0")  // modo noche / IR

    // ── GPS BeiDou ────────────────────────────────────────────────────────────
    private val GPS_NODE = File("/sys/class/misc/wiite_con_ctrl/beidou_enable")

    fun gpsOn()  = writeNode(GPS_NODE, "1")
    fun gpsOff() = writeNode(GPS_NODE, "0")

    // ── Helper ────────────────────────────────────────────────────────────────
    private fun writeNode(file: File, value: String): Boolean {
        return try {
            file.writeText(value)
            true
        } catch (_: Exception) {
            // Requiere permisos root para algunos nodos — intentar vía shell.
            try {
                val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "echo $value > ${file.absolutePath}"))
                // Antes se devolvía true sin mirar: exec() no falla aunque el
                // echo muera por permisos, y el fallo quedaba invisible. Ahora
                // el exit code decide, y si falla queda rastro en logcat.
                val ok = p.waitFor() == 0
                if (!ok) android.util.Log.w("FalconHW", "sysfs rechazado: $value -> ${file.absolutePath}")
                ok
            } catch (_: Exception) { false }
        }
    }
}
