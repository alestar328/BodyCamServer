package com.falconone.bodycamserver

import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG = "FalconPerfil"

/** Lo que puede hacer un botón físico. Ver [BotonesFisicos]. */
enum class Accion(val etiqueta: String) {
    SOS("SOS"),
    GRABAR("GRABAR"),
    PTT("PTT"),
}

/**
 * Por dónde llega una pulsación.
 *
 * El orden es la preferencia del asistente cuando la misma pulsación llega por
 * varias vías a la vez: un broadcast del fabricante es su interfaz para apps y en
 * la W1 es lo único que llega con la pantalla apagada; la accesibilidad es la vía
 * estándar de Android para teclas globales; la Activity solo ve la tecla con la
 * app delante y la pantalla encendida.
 */
enum class Fuente { BROADCAST, ACCESIBILIDAD, ACTIVIDAD }

/**
 * Un botón aprendido.
 *
 * [codigo] es el `keyCode` de Android, o el código que traiga el broadcast.
 * [scan] solo se usa para distinguir botones cuando el fabricante los deja todos en
 * `KEYCODE_UNKNOWN`. [broadcast] es la acción del intent, solo con [Fuente.BROADCAST].
 * [conSoltar]: si se vieron pulsar y soltar por separado. Hoy el PTT es un
 * conmutador igualmente; se guarda porque es lo que haría posible un PTT de
 * mantener-para-hablar en un modelo que lo permita.
 */
data class Boton(
    val fuente: Fuente,
    val codigo: Int,
    val scan: Int = 0,
    val broadcast: String? = null,
    val conSoltar: Boolean = false,
) {
    fun coincide(p: Pulsacion): Boolean {
        if (p.fuente != fuente) return false
        if (fuente == Fuente.BROADCAST) return p.broadcast == broadcast && p.codigo == codigo
        if (p.codigo != codigo) return false
        return codigo != KeyEvent.KEYCODE_UNKNOWN || p.scan == scan
    }

    /** Para enseñarlo en la pantalla de 3 cm: corto. */
    fun describir(): String {
        val tecla = when {
            fuente == Fuente.BROADCAST && broadcast != null && codigo == 0 ->
                broadcast.substringAfterLast('.')
            codigo == KeyEvent.KEYCODE_UNKNOWN -> "scan $scan"
            else -> KeyEvent.keyCodeToString(codigo).removePrefix("KEYCODE_") + " ($codigo)"
        }
        val via = when (fuente) {
            Fuente.BROADCAST -> "broadcast"
            Fuente.ACCESIBILIDAD -> "accesibilidad"
            Fuente.ACTIVIDAD -> "solo con la app delante"
        }
        return "$tecla · $via"
    }

    fun aJson(): JSONObject = JSONObject()
        .put("fuente", fuente.name)
        .put("codigo", codigo)
        .put("scan", scan)
        .put("broadcast", broadcast ?: JSONObject.NULL)
        .put("soltar", conSoltar)

    companion object {
        fun deJson(o: JSONObject) = Boton(
            fuente = Fuente.valueOf(o.getString("fuente")),
            codigo = o.getInt("codigo"),
            scan = o.optInt("scan", 0),
            broadcast = if (o.isNull("broadcast")) null else o.getString("broadcast"),
            conSoltar = o.optBoolean("soltar", false),
        )
    }
}

/** Resultado de probar una pieza de hardware. */
enum class Verificacion { SIN_PROBAR, VERIFICADO, NO_DISPONIBLE }

/**
 * Rutas sysfs de las piezas que la app mueve por su cuenta. `null` = el modelo no la
 * tiene, o no sabemos dónde está; quien la use no hace nada y lo deja en el log.
 */
data class Nodos(
    val ir: String? = null,
    /** Lo que enciende el IR: "1" en la W1; en `/sys/class/leds` es el brillo máximo. */
    val irValor: String = "1",
    val led: String? = null,
    val luzActivar: String? = null,
    val luzValor: String? = null,
    val filtroIr: String? = null,
    val gps: String? = null,
) {
    fun aJson(): JSONObject = JSONObject().apply {
        CLAVES.forEach { clave -> put(clave, leer(clave) ?: JSONObject.NULL) }
        put("ir_valor", irValor)
    }

    fun leer(clave: String): String? = when (clave) {
        "ir" -> ir
        "led" -> led
        "luz_activar" -> luzActivar
        "luz_valor" -> luzValor
        "filtro_ir" -> filtroIr
        "gps" -> gps
        else -> null
    }

    /** Una ruta vacía la quita: así se declara por adb que el modelo no tiene esa pieza. */
    fun con(clave: String, ruta: String?): Nodos? {
        val r = ruta?.takeIf { it.isNotBlank() }
        return when (clave) {
            "ir" -> copy(ir = r)
            "led" -> copy(led = r)
            "luz_activar" -> copy(luzActivar = r)
            "luz_valor" -> copy(luzValor = r)
            "filtro_ir" -> copy(filtroIr = r)
            "gps" -> copy(gps = r)
            else -> null
        }
    }

    companion object {
        val CLAVES = listOf("ir", "led", "luz_activar", "luz_valor", "filtro_ir", "gps")

        fun deJson(o: JSONObject?): Nodos {
            if (o == null) return Nodos()
            var n = Nodos()
            CLAVES.forEach { clave ->
                if (o.has(clave) && !o.isNull(clave)) n = n.con(clave, o.getString(clave)) ?: n
            }
            return n.copy(irValor = o.optString("ir_valor", "1"))
        }
    }
}

/**
 * Todo lo que la app necesita saber del modelo de bodycam en el que corre.
 *
 * Existe porque el aparato de producción será de otro fabricante (aviso del cliente,
 * 2026-09-24) y no lo tendremos para desarrollar: los botones y los nodos de
 * hardware de la W1 no se pueden dar por buenos en otro modelo.
 *
 * - [botones] lo rellena el asistente de instalación ([AsistenteActivity]).
 * - [broadcastsExtra] son acciones de broadcast del fabricante que no están en la
 *   lista de candidatas de [BotonesFisicos]. Se añaden por adb (ver
 *   [PerfilDispositivo.atenderOrdenDePerfil]): Android no deja escuchar "cualquier
 *   broadcast", así que el nombre hay que saberlo de antemano.
 * - [nodos] y [infrarrojo]: el hardware que se mueve por sysfs.
 * - [pantallaApagada]: qué botones se comprobó que llegan con la pantalla apagada.
 */
data class Perfil(
    val origen: String,
    val botones: Map<Accion, Boton> = emptyMap(),
    val broadcastsExtra: List<String> = emptyList(),
    val nodos: Nodos = Nodos(),
    val infrarrojo: Verificacion = Verificacion.SIN_PROBAR,
    val pantallaApagada: Map<Accion, Boolean> = emptyMap(),
) {
    fun accionDe(p: Pulsacion): Accion? = botones.entries.firstOrNull { it.value.coincide(p) }?.key

    fun aJson(): JSONObject = JSONObject()
        .put("version", 1)
        .put("origen", origen)
        .put("botones", JSONObject().apply { botones.forEach { (a, b) -> put(a.name, b.aJson()) } })
        .put("broadcasts_extra", JSONArray(broadcastsExtra))
        .put("nodos", nodos.aJson())
        .put("infrarrojo", infrarrojo.name)
        .put("pantalla_apagada", JSONObject().apply { pantallaApagada.forEach { (a, ok) -> put(a.name, ok) } })

    companion object {
        fun deJson(o: JSONObject): Perfil {
            val botones = o.optJSONObject("botones")?.let { bs ->
                Accion.values().mapNotNull { a -> bs.optJSONObject(a.name)?.let { a to Boton.deJson(it) } }.toMap()
            } ?: emptyMap()
            val extra = o.optJSONArray("broadcasts_extra")?.let { arr -> List(arr.length()) { arr.getString(it) } }
                ?: emptyList()
            val apagada = o.optJSONObject("pantalla_apagada")?.let { m ->
                Accion.values().filter { m.has(it.name) }.associateWith { m.getBoolean(it.name) }
            } ?: emptyMap()
            return Perfil(
                origen = o.optString("origen", "guardado"),
                botones = botones,
                broadcastsExtra = extra,
                nodos = Nodos.deJson(o.optJSONObject("nodos")),
                infrarrojo = runCatching { Verificacion.valueOf(o.getString("infrarrojo")) }
                    .getOrDefault(Verificacion.SIN_PROBAR),
                pantallaApagada = apagada,
            )
        }
    }
}

/**
 * El perfil en uso y dónde se guarda.
 *
 * [actual] nunca está vacío de salida: antes de [cargar] vale lo que diga
 * [porDefecto], que no necesita `Context`. Así los `object` que tocan hardware
 * ([HardwareController], [BotonesFisicos]) funcionan aunque los llame alguien que
 * arrancó antes que el servicio.
 */
object PerfilDispositivo {

    private const val PREFS = "perfil_dispositivo"
    private const val CLAVE = "perfil"

    /**
     * La W1-4G (DSJ-ZXAN9A1) con la que se ha desarrollado todo.
     *
     * Los botones son los medidos por logcat el 2026-06-03 y el 2026-09-08 (ver el
     * comentario del receptor en [BtServerService]): llegan por el broadcast
     * `SIDE_KEY_INTENT`, que es la única vía que funciona con la pantalla apagada.
     * Los nodos son los de la documentación W1-4G, comprobados por adb.
     */
    private val W1 = Perfil(
        origen = "W1-4G (por defecto)",
        botones = mapOf(
            Accion.PTT to Boton(Fuente.BROADCAST, KeyEvent.KEYCODE_F2, broadcast = BotonesFisicos.SIDE_KEY),
            Accion.SOS to Boton(Fuente.BROADCAST, KeyEvent.KEYCODE_F3, broadcast = BotonesFisicos.SIDE_KEY),
            Accion.GRABAR to Boton(Fuente.BROADCAST, KeyEvent.KEYCODE_F4, broadcast = BotonesFisicos.SIDE_KEY),
        ),
        nodos = Nodos(
            ir = "/sys/class/i2c-dev/i2c-2/device/2-0064/ocp_regs",
            led = "/sys/class/i2c-dev/i2c-2/device/2-0045/aw2013_regs",
            luzActivar = "/sys/class/input/input0/driver/enable",
            luzValor = "/sys/class/input/input0/driver/lux",
            filtroIr = "/sys/class/misc/wiite_con_ctrl/motor_enable",
            gps = "/sys/class/misc/wiite_con_ctrl/beidou_enable",
        ),
        // Medido el 2026-09-18: el sensor pasa de 0 a ~470 lux con los IR encendidos.
        infrarrojo = Verificacion.VERIFICADO,
        // Solo el PTT se midió con la pantalla apagada (2026-09-08). SOS y GRABAR
        // van por el mismo broadcast, pero eso no es una medida: los confirma el
        // asistente.
        pantallaApagada = mapOf(Accion.PTT to true),
    )

    /**
     * El perfil que toca sin nada guardado.
     *
     * La W1 se reconoce por el modelo o por sus nodos: el nombre lo pone el
     * integrador y puede cambiar entre lotes, las piezas no. Cualquier otro aparato
     * arranca sin botones ni nodos, y [necesitaAsistente] lo manda al asistente.
     */
    fun porDefecto(): Perfil {
        val porModelo = android.os.Build.MODEL == "DSJ-ZXAN9A1"
        val porNodos = listOf(W1.nodos.ir, W1.nodos.filtroIr).all { ruta -> ruta != null && File(ruta).exists() }
        return if (porModelo || porNodos) W1 else Perfil(origen = "sin configurar")
    }

    @Volatile var actual: Perfil = porDefecto()
        private set

    /** Lo avisa [guardar]: [BtServerService] vuelve a registrar sus broadcasts. */
    @Volatile var alCambiar: (() -> Unit)? = null

    private var cargado = false

    fun cargar(context: Context) {
        if (cargado) return
        cargado = true
        val texto = prefs(context).getString(CLAVE, null) ?: run {
            Log.i(TAG, "Sin perfil guardado: ${actual.origen}")
            return
        }
        runCatching { Perfil.deJson(JSONObject(texto)) }
            .onSuccess { actual = it; Log.i(TAG, "Perfil cargado: ${it.aJson()}") }
            .onFailure { Log.e(TAG, "Perfil guardado ilegible, se usa ${actual.origen}", it) }
    }

    fun guardar(context: Context, perfil: Perfil) {
        cargado = true
        actual = perfil
        prefs(context).edit().putString(CLAVE, perfil.aJson().toString()).apply()
        Log.i(TAG, "Perfil guardado: ${perfil.aJson()}")
        alCambiar?.invoke()
    }

    /** Sin un solo botón asignado la unidad no sirve: hay que pasar por el asistente. */
    fun necesitaAsistente(): Boolean = actual.botones.isEmpty()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Órdenes de perfil por adb, con la misma vía que las de mantenimiento de
     * [DeviceOwner]: un `am start` con extras a [MainActivity].
     *
     * ```
     * # abrir el asistente
     * adb shell am start -n com.falconone.bodycamserver/.MainActivity --es asistente 1
     * # un broadcast del fabricante que escuchar (varios, separados por comas)
     * adb shell am start -n com.falconone.bodycamserver/.MainActivity --es perfil_broadcast com.fab.SOS_KEY
     * # la ruta de una pieza (vacía = el modelo no la tiene)
     * adb shell am start -n com.falconone.bodycamserver/.MainActivity --es perfil_nodo "ir=/sys/class/leds/ir/brightness"
     * # ver el perfil en uso:  adb logcat -s FalconPerfil
     * adb shell am start -n com.falconone.bodycamserver/.MainActivity --es perfil ver
     * # volver al de fábrica (W1 si se reconoce, vacío si no)
     * adb shell am start -n com.falconone.bodycamserver/.MainActivity --es perfil borrar
     * ```
     *
     * Misma salvedad que las de [DeviceOwner]: `MainActivity` está exportada y
     * cualquier app instalada podría mandar esto. En kiosco no se puede instalar nada.
     *
     * @return true si hay que abrir el asistente.
     */
    fun atenderOrdenDePerfil(context: Context, intent: Intent): Boolean {
        cargar(context)
        intent.getStringExtra("perfil_broadcast")?.let { lista ->
            val nuevas = lista.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            guardar(context, actual.copy(broadcastsExtra = (actual.broadcastsExtra + nuevas).distinct()))
        }
        intent.getStringExtra("perfil_nodo")?.let { orden ->
            val clave = orden.substringBefore('=').trim()
            val nodos = actual.nodos.con(clave, orden.substringAfter('=', "").trim())
            if (nodos == null) {
                Log.w(TAG, "Nodo desconocido '$clave'. Valen: ${Nodos.CLAVES}")
            } else {
                // Una ruta nueva invalida lo que se hubiera probado con la anterior.
                val ir = if (clave == "ir") Verificacion.SIN_PROBAR else actual.infrarrojo
                guardar(context, actual.copy(nodos = nodos, infrarrojo = ir))
            }
        }
        when (intent.getStringExtra("perfil")) {
            "ver" -> Log.i(TAG, "Perfil en uso: ${actual.aJson().toString(2)}")
            "borrar" -> {
                prefs(context).edit().remove(CLAVE).apply()
                actual = porDefecto()
                Log.w(TAG, "Perfil borrado: vuelve a ${actual.origen}")
                alCambiar?.invoke()
            }
        }
        return intent.getStringExtra("asistente") != null
    }
}
