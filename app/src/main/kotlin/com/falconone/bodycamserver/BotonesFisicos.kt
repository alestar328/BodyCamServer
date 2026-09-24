package com.falconone.bodycamserver

import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent

private const val TAG = "FalconKeys"

/**
 * Una pulsación tal y como llega, antes de saber qué botón es.
 *
 * [soltando]: la tecla se suelta (`ACTION_UP`, o el broadcast dice que es la
 * suelta). [repeticion]: el auto-repetir de Android con la tecla mantenida.
 * [pantallaEncendida]: si la pantalla estaba encendida al llegar; el asistente lo
 * usa para comprobar qué botones funcionan con ella apagada.
 */
data class Pulsacion(
    val fuente: Fuente,
    val codigo: Int,
    val scan: Int = 0,
    val broadcast: String? = null,
    val soltando: Boolean = false,
    val repeticion: Int = 0,
    val pantallaEncendida: Boolean = true,
    val instante: Long = SystemClock.elapsedRealtime(),
) {
    companion object {
        fun de(event: KeyEvent, fuente: Fuente, pantallaEncendida: Boolean = true) = Pulsacion(
            fuente = fuente,
            codigo = event.keyCode,
            scan = event.scanCode,
            soltando = event.action == KeyEvent.ACTION_UP,
            repeticion = event.repeatCount,
            pantallaEncendida = pantallaEncendida,
        )
    }
}

/**
 * La única puerta de los botones físicos.
 *
 * Antes cada vía tenía su propio `when (keyCode)` con F2/F3/F4 escritos a mano:
 * el broadcast del fabricante en [BtServerService], `onKeyDown` en las dos
 * Activities y el servicio de accesibilidad, que además les daba a F3 y F4 otras
 * funciones (IR y linterna). Ahora las tres vías entregan aquí la pulsación en
 * bruto, y qué botón es cada cosa lo dice el [Perfil] del modelo.
 *
 * **Una sola vía por botón.** Cada [Boton] recuerda por qué [Fuente] se aprendió y
 * solo esa lo dispara. Es la lección del PTT (2026-09-08): con el broadcast (que
 * llega al soltar) y `onKeyDown` (al pulsar) atendidos a la vez, una pulsación
 * larga se separaba más que el antirrebote y el micro se abría y cerraba de golpe.
 *
 * **Aprendiendo** ([aprendiendo] puesto), nada se ejecuta: todo va al asistente.
 * Pulsar SOS mientras se configura no puede lanzar un SOS.
 */
object BotonesFisicos {

    /** El broadcast de teclas de la W1. Ver el comentario del receptor en [BtServerService]. */
    const val SIDE_KEY = "android.intent.action.SIDE_KEY_INTENT"

    /**
     * Broadcasts de botones que se escuchan siempre, conocidos de firmwares de
     * bodycams chinas (la lista del smoke test de junio). Solo se atienden si un
     * botón del perfil los usa; el resto se registra en el log (`FalconSmoke`) y,
     * con el asistente abierto, se le enseñan por si son los del modelo nuevo.
     */
    private val CANDIDATOS = listOf(
        SIDE_KEY,
        "android.intent.action.PRESS_VIDEO_KEY",  "android.intent.action.LONG_PRESS_VIDEO_KEY",
        "android.intent.action.PRESS_RECORD_KEY", "android.intent.action.LONG_PRESS_RECORD_KEY",
        "android.intent.action.PRESS_PIC_KEY",    "android.intent.action.LONG_PRESS_PIC_KEY",
        "android.intent.action.DOWN_PTT_KEY",     "android.intent.action.UP_PTT_KEY",
        "android.intent.action.PRESS_SOS_KEY",    "android.intent.action.LONG_PRESS_SOS_KEY",
        "android.intent.action.PRESS_MARK_KEY",   "android.intent.action.LONG_PRESS_MARK_KEY",
    )

    /** Nombres con los que los fabricantes meten el código de tecla en el broadcast. */
    private val EXTRAS_CODIGO = listOf("key_code", "keycode", "keyCode", "code")

    /** Las teclas que el asistente no deja asignar: son del sistema. */
    val RESERVADAS = setOf(
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER,
        KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_MENU,
    )

    /** Lo pone [BtServerService]: es quien sabe ejecutar cada acción y avisar al teléfono. */
    @Volatile var ejecutor: ((Accion) -> Unit)? = null

    /** Lo pone el asistente mientras está abierto. Ver la nota de la clase. */
    @Volatile var aprendiendo: ((Pulsacion) -> Unit)? = null

    fun filtroBroadcasts(): IntentFilter = IntentFilter().apply {
        (CANDIDATOS + PerfilDispositivo.actual.broadcastsExtra).distinct().forEach { addAction(it) }
    }

    /** Traduce un broadcast del fabricante. Null si no parece una tecla. */
    fun deBroadcast(intent: Intent, pantallaEncendida: Boolean): Pulsacion? {
        val accion = intent.action ?: return null
        val extras = intent.extras
        Log.i("FalconSmoke", "BTN action=$accion extras[${extras?.keySet()?.joinToString(" ") { "$it=${extras.get(it)}" }}]")
        val codigo = EXTRAS_CODIGO.firstNotNullOfOrNull { clave ->
            extras?.get(clave)?.toString()?.toIntOrNull()
        } ?: 0
        // key_status: 0 al pulsar, 1 al soltar. La W1 manda siempre -1 y solo al
        // soltar; se trata como pulsación, que es lo que ha funcionado siempre.
        val soltando = intent.getIntExtra("key_status", -1) == 1 || accion.contains("UP_")
        return Pulsacion(Fuente.BROADCAST, codigo, broadcast = accion, soltando = soltando,
            pantallaEncendida = pantallaEncendida)
    }

    /**
     * Entrada de todas las vías.
     *
     * @return true si la pulsación era nuestra y no debe seguir su camino. Solo se
     *   consume lo que coincide con un botón del perfil **por esta misma vía**: una
     *   tecla asignada por broadcast pasa de largo por la accesibilidad y por la
     *   Activity, para no pisar al firmware que emite ese broadcast.
     */
    fun recibir(p: Pulsacion): Boolean {
        aprendiendo?.let { oyente ->
            oyente(p)
            return p.fuente != Fuente.BROADCAST
        }

        val accion = PerfilDispositivo.actual.accionDe(p) ?: return false
        // Se dispara al pulsar. La suelta y el auto-repetir de una tecla mantenida
        // se tragan para que no lleguen a nadie más, pero no hacen nada.
        if (p.soltando || p.repeticion > 0) return true
        if (!ButtonDebounce.tryAcquire()) {
            Log.d(TAG, "$accion descartado: rebote")
            return true
        }
        val destino = ejecutor
        if (destino == null) {
            Log.w(TAG, "$accion sin ejecutor: el servicio no está corriendo")
            return true
        }
        Log.d(TAG, "${p.fuente} ${p.codigo} → $accion")
        destino(accion)
        return true
    }
}
