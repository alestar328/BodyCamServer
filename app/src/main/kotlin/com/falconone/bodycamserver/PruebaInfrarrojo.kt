package com.falconone.bodycamserver

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import java.io.File

private const val TAG = "FalconIrPrueba"

/** Un nodo que podría encender el infrarrojo, con el valor que lo enciende. */
data class CandidatoIr(val ruta: String, val valor: String)

/** Tres lecturas de luz: IR apagado, encendido y otra vez apagado. */
data class MedidaIr(val antes: Float, val encendido: Float, val despues: Float) {
    /**
     * El IR se ve si la luz sube claramente al encenderlo y vuelve a bajar.
     *
     * En la W1 (2026-09-18/21) el salto es enorme: de la luz ambiente (0 a oscuras,
     * 175-265 en interior) a ~470 a oscuras y 1441 con 265 de ambiente. El margen
     * fijo es para que un sensor que da 0 → 3 por ruido no cuente como IR.
     */
    val seVe: Boolean get() = encendido >= maxOf(antes, despues) * 1.5f + MARGEN_LUX

    fun describir() = "${encendido.toInt()} lux con IR, ${maxOf(antes, despues).toInt()} sin"

    companion object {
        const val MARGEN_LUX = 50f
    }
}

/**
 * La autoprueba del infrarrojo que hace el asistente de instalación.
 *
 * Android no tiene ninguna API para la luz de visión nocturna (`ConsumerIrManager`
 * es el emisor de mando a distancia, otra cosa). Así que el IR se reconoce por lo
 * que hace: **el sensor de luz ve su propio infrarrojo** (medido en la W1, ver
 * [ModoNoche]). Encender un nodo candidato y ver subir la luz prueba a la vez que
 * la pieza existe y que la app puede moverla —que el nodo exista no basta: en
 * muchos aparatos SELinux no deja a una app escribir en sysfs—.
 *
 * Sin sensor de luz, la prueba la hace una persona (ver [AsistenteActivity]).
 */
object PruebaInfrarrojo {

    /** Ruta de la W1-4G. Se prueba en cualquier aparato donde exista. */
    private val RUTAS_CONOCIDAS = listOf(
        CandidatoIr("/sys/class/i2c-dev/i2c-2/device/2-0064/ocp_regs", "1"),
    )

    /** Nombres de `/sys/class/leds` que suelen ser el IR. Sin el filtro IR-CUT. */
    private val NOMBRE_IR = Regex("(^|[_-])(ir|infrared|night|nightvision)([_-]|\$)", RegexOption.IGNORE_CASE)

    private const val ESPERA_SENSOR_MS = 3_000L  // la 1ª lectura tras encender el sensor sale a 0
    private const val ESPERA_LECTURA_MS = 1_500L // el sensor de la W1 promedia ~0,7 s

    /** Lo que ya dice el perfil primero, luego lo conocido, luego lo que parezca IR. */
    fun candidatos(): List<CandidatoIr> {
        val nodos = PerfilDispositivo.actual.nodos
        val delPerfil = listOfNotNull(nodos.ir?.let { CandidatoIr(it, nodos.irValor) })
        val conocidos = RUTAS_CONOCIDAS.filter { File(it.ruta).exists() }
        val leds = File("/sys/class/leds").listFiles().orEmpty()
            .filter { NOMBRE_IR.containsMatchIn(it.name) && !it.name.contains("cut", ignoreCase = true) }
            .map { dir ->
                val maximo = runCatching { File(dir, "max_brightness").readText().trim() }.getOrNull()
                CandidatoIr(File(dir, "brightness").path, maximo?.takeIf { it.isNotEmpty() } ?: "255")
            }
        return (delPerfil + conocidos + leds).distinctBy { it.ruta }
    }

    /** Una forma de leer la luz. */
    interface LectorLuz {
        val nombre: String
        fun encender()
        /** Lux, o negativo si la lectura falla. */
        fun leer(): Float
        fun apagar()
    }

    /**
     * Sysfs si el perfil tiene el sensor (la W1); si no, el sensor de luz estándar
     * de Android. Null si el aparato no tiene ninguno.
     */
    fun lector(context: Context): LectorLuz? {
        val nodos = PerfilDispositivo.actual.nodos
        if (nodos.luzValor != null) return object : LectorLuz {
            override val nombre = "sensor sysfs"
            override fun encender() { HardwareController.lightSensorOn() }
            override fun leer() = HardwareController.readLux().toFloat()
            override fun apagar() { HardwareController.lightSensorOff() }
        }
        val gestor = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = gestor.getDefaultSensor(Sensor.TYPE_LIGHT) ?: return null
        return object : LectorLuz, SensorEventListener {
            @Volatile private var ultimo = -1f
            override val nombre = "sensor de Android"
            override fun encender() { gestor.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST) }
            override fun leer() = ultimo
            override fun apagar() = gestor.unregisterListener(this)
            override fun onSensorChanged(event: SensorEvent) { ultimo = event.values[0] }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
    }

    /**
     * Bloquea unos segundos: llamar fuera del hilo principal. Deja el IR apagado.
     *
     * [ModoNoche] mueve el mismo nodo cada pocos segundos; quien llama tiene que
     * pararlo antes (ver [conModoNocheParado]).
     */
    fun medir(lector: LectorLuz, candidato: CandidatoIr): MedidaIr {
        fun leerEstable(): Float {
            Thread.sleep(ESPERA_LECTURA_MS)
            val a = lector.leer()
            Thread.sleep(300)
            return maxOf(a, lector.leer())
        }
        HardwareController.escribirCandidato(candidato.ruta, "0")
        val antes = leerEstable()
        val escrito = HardwareController.escribirCandidato(candidato.ruta, candidato.valor)
        val encendido = leerEstable()
        HardwareController.escribirCandidato(candidato.ruta, "0")
        val despues = leerEstable()
        return MedidaIr(antes, encendido, despues).also {
            Log.i(TAG, "${candidato.ruta}=${candidato.valor} (escrito=$escrito) con ${lector.nombre}: $it → ${it.seVe}")
        }
    }

    /** Prueba los candidatos en orden y devuelve el primero que se ve, con su medida. */
    fun probarTodos(lector: LectorLuz, candidatos: List<CandidatoIr>, progreso: (Int) -> Unit): Pair<CandidatoIr?, MedidaIr?> {
        lector.encender()
        try {
            Thread.sleep(ESPERA_SENSOR_MS)
            var ultima: MedidaIr? = null
            candidatos.forEachIndexed { i, c ->
                progreso(i + 1)
                val medida = medir(lector, c)
                if (medida.seVe) return c to medida
                ultima = medida
            }
            return null to ultima
        } finally {
            lector.apagar()
        }
    }

    /** Para el modo noche mientras dura [bloque] y lo deja como estaba. */
    fun <T> conModoNocheParado(bloque: () -> T): T {
        val estaba = ModoNoche.activo
        if (estaba) ModoNoche.parar()
        try {
            return bloque()
        } finally {
            if (estaba) ModoNoche.arrancar()
        }
    }
}
