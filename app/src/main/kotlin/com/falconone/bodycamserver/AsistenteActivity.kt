package com.falconone.bodycamserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

private const val TAG = "FalconAsistente"

/**
 * Asistente de instalación: enseña a la app los botones y el hardware de un modelo
 * de bodycam que no conoce.
 *
 * Existe porque el aparato de producción será de otro fabricante y no lo tendremos
 * (2026-09-24). Lo que aprende se guarda en el [Perfil] del modelo, y a partir de ahí
 * [BotonesFisicos] y [HardwareController] trabajan con eso.
 *
 * Pasos:
 *  1. **Botones.** Para SOS, GRABAR y PTT: "pulsa el botón", y otra vez para
 *     confirmar. Se escucha por las tres vías a la vez (broadcast del fabricante,
 *     accesibilidad y esta Activity) y se queda la mejor que haya llegado, en el
 *     orden de [Fuente]. Confirmar pulsando otra vez, y no tocando la pantalla, deja
 *     el asistente usable sin mirar un panel de 3 cm y descarta rebotes.
 *  2. **Pantalla apagada.** Por cada botón: apagar la pantalla, pulsarlo y volver a
 *     encenderla. Es la prueba que importa: con la pantalla apagada Android tira las
 *     teclas que no despiertan el aparato, y en la W1 solo el broadcast sobrevivía.
 *  3. **Infrarrojo.** Ver [PruebaInfrarrojo].
 *  4. **Resumen** y guardar. Salir sin guardar deja el perfil como estaba.
 *
 * Mientras está abierto **ningún botón ejecuta su acción** ([BotonesFisicos.aprendiendo]).
 *
 * Se abre solo si el perfil no tiene botones (modelo no reconocido), o por adb:
 * `adb shell am start -n com.falconone.bodycamserver/.MainActivity --es asistente 1`.
 * Lo que va pasando queda en `adb logcat -s FalconAsistente FalconKeys FalconSmoke FalconIrPrueba`.
 */
class AsistenteActivity : ComponentActivity() {

    private enum class FaseApagada { APAGAR, APAGADA, RESULTADO }
    private enum class FaseIr { INICIO, PROBANDO, PREGUNTA, RESULTADO }

    private sealed class Paso {
        data class Pedir(val accion: Accion, val aviso: String? = null) : Paso()
        data class Confirmar(val accion: Accion, val boton: Boton, val nota: String?) : Paso()
        data class Apagada(val accion: Accion, val fase: FaseApagada, val ok: Boolean = false) : Paso()
        data class Infrarrojo(val fase: FaseIr, val texto: String) : Paso()
        object Resumen : Paso()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var paso by mutableStateOf<Paso>(Paso.Pedir(Accion.SOS))

    /** El perfil al abrir: lo que se salta o no se prueba se queda como estaba. */
    private lateinit var base: Perfil
    private val botones = linkedMapOf<Accion, Boton>()
    private val apagada = mutableMapOf<Accion, Boolean>()
    private var infrarrojo: Verificacion? = null   // null = paso saltado
    private var nodoIr: CandidatoIr? = null
    private var candidatosIr: List<CandidatoIr> = emptyList()
    private var preguntaIr = 0                     // candidato en pregunta, sin sensor
    /** El modo noche que se paró para preguntar a ojo; se devuelve al acabar. */
    private var nochePausada = false

    // ── Escucha ───────────────────────────────────────────────────────────────

    /** Lo que llega junto: la misma pulsación por varias vías, y su suelta. */
    private val ventana = mutableListOf<Pulsacion>()
    private val cerrarVentana = Runnable { alCerrarVentana() }
    private val pulsacionesApagada = mutableListOf<Pulsacion>()

    private fun alPulsar(p: Pulsacion) {
        handler.post { oir(p) }
    }

    private fun oir(p: Pulsacion) {
        Log.d(TAG, "Oído: $p")
        when (val s = paso) {
            is Paso.Pedir, is Paso.Confirmar -> {
                ventana += p
                // Se cierra un rato después de lo ÚLTIMO que llegue: en la W1 el
                // broadcast llega al soltar, que en una pulsación larga es tarde.
                handler.removeCallbacks(cerrarVentana)
                handler.postDelayed(cerrarVentana, VENTANA_MS)
            }
            is Paso.Apagada -> if (s.fase == FaseApagada.APAGADA) pulsacionesApagada += p
            else -> Unit
        }
    }

    private val pantallaReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val s = paso as? Paso.Apagada ?: return
            when {
                intent.action == Intent.ACTION_SCREEN_OFF && s.fase == FaseApagada.APAGAR -> {
                    pulsacionesApagada.clear()
                    paso = s.copy(fase = FaseApagada.APAGADA)
                }
                // El broadcast de la W1 llega al soltar, y soltar puede ser después
                // de que el propio botón haya encendido la pantalla: se espera un poco.
                intent.action == Intent.ACTION_SCREEN_ON && s.fase == FaseApagada.APAGADA ->
                    handler.postDelayed({ evaluarApagada(s.accion) }, ESPERA_TRAS_ENCENDER_MS)
            }
        }
    }

    // ── Ciclo de vida ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PerfilDispositivo.cargar(this)
        base = PerfilDispositivo.actual
        BotonesFisicos.aprendiendo = ::alPulsar
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goImmersive()
        registerReceiver(pantallaReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
        Log.i(TAG, "Asistente abierto. Perfil de partida: ${base.aJson()}")
        setContent {
            Rotated(OVERLAY_ROTATION_DEGREES) { PantallaAsistente(vista()) }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    override fun onDestroy() {
        if (BotonesFisicos.aprendiendo != null) BotonesFisicos.aprendiendo = null
        // Cerrado a mitad de la pregunta del IR: que no se quede encendido ni el
        // modo noche parado.
        if (nochePausada) {
            candidatosIr.getOrNull(preguntaIr)?.let { c -> Thread { HardwareController.escribirCandidato(c.ruta, "0") }.start() }
            reanudarNoche()
        }
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(pantallaReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    /**
     * Todas las teclas son del asistente, ATRÁS incluida: en la W1 mantener F2 hace
     * que el firmware inyecte ATRÁS al segundo, y cerraría el asistente a mitad.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        BotonesFisicos.recibir(Pulsacion.de(event, Fuente.ACTIVIDAD))
        return true
    }

    // ── Botones ───────────────────────────────────────────────────────────────

    private fun alCerrarVentana() {
        val oidas = ventana.toList()
        ventana.clear()
        val (boton, nota) = elegir(oidas)
        when (val s = paso) {
            is Paso.Pedir -> {
                if (boton == null) {
                    paso = s.copy(aviso = nota ?: "No se reconoció. Otra vez")
                    return
                }
                val otra = botones.entries.firstOrNull { it.key != s.accion && mismoBoton(it.value, boton) }?.key
                paso = if (otra != null) s.copy(aviso = "Ese botón ya es ${otra.etiqueta}")
                       else Paso.Confirmar(s.accion, boton, nota)
            }
            is Paso.Confirmar -> {
                if (boton != null && mismoBoton(boton, s.boton)) {
                    botones[s.accion] = s.boton.copy(conSoltar = s.boton.conSoltar || boton.conSoltar)
                    Log.i(TAG, "${s.accion} = ${botones[s.accion]}")
                    trasBoton(s.accion)
                } else {
                    paso = Paso.Pedir(s.accion, "No era el mismo botón. Otra vez")
                }
            }
            else -> Unit
        }
    }

    /**
     * La mejor vía de lo que ha llegado junto, y una nota si hay algo que avisar.
     * Una tecla del sistema (ATRÁS, INICIO…) no se asigna nunca.
     */
    private fun elegir(oidas: List<Pulsacion>): Pair<Boton?, String?> {
        fun reservada(p: Pulsacion) = p.fuente != Fuente.BROADCAST && p.codigo in BotonesFisicos.RESERVADAS
        val utiles = oidas.filterNot(::reservada)
        val fuente = Fuente.values().firstOrNull { f -> utiles.any { it.fuente == f } }
            ?: return null to (if (oidas.any(::reservada)) "Esa tecla es del sistema" else null)
        val deEsa = utiles.filter { it.fuente == fuente }
        val primera = deEsa.firstOrNull { !it.soltando } ?: deEsa.first()
        val boton = Boton(
            fuente = fuente,
            codigo = primera.codigo,
            scan = primera.scan,
            broadcast = primera.broadcast,
            conSoltar = fuente != Fuente.BROADCAST && deEsa.any { it.soltando && it.codigo == primera.codigo },
        )
        val notas = buildList {
            val otras = utiles.map { it.fuente }.distinct() - fuente
            if (otras.isNotEmpty()) add("También por ${otras.joinToString { it.name.lowercase() }}")
            // En la W1, mantener F2 un segundo inyecta ATRÁS (2026-09-08).
            if (oidas.any { it.codigo == KeyEvent.KEYCODE_BACK && it.fuente != Fuente.BROADCAST }) {
                add("Al mantenerlo, el sistema pulsa ATRÁS")
            }
            if (fuente == Fuente.ACTIVIDAD) add("Solo funcionará con la app delante")
        }
        return boton to notas.joinToString(". ").ifEmpty { null }
    }

    private fun mismoBoton(a: Boton, b: Boton) =
        a.fuente == b.fuente && a.codigo == b.codigo && a.broadcast == b.broadcast &&
            (a.codigo != KeyEvent.KEYCODE_UNKNOWN || a.scan == b.scan)

    private fun saltarBoton(accion: Accion) {
        // Saltar no borra: lo que tuviera el perfil se queda.
        base.botones[accion]?.let { botones[accion] = it }
        trasBoton(accion)
    }

    private fun trasBoton(accion: Accion) {
        val siguiente = Accion.values().getOrNull(accion.ordinal + 1)
        paso = when {
            siguiente != null -> Paso.Pedir(siguiente)
            botones.isNotEmpty() -> Paso.Apagada(botones.keys.first(), FaseApagada.APAGAR)
            else -> inicioInfrarrojo()
        }
    }

    // ── Pantalla apagada ──────────────────────────────────────────────────────

    private fun evaluarApagada(accion: Accion) {
        val s = paso as? Paso.Apagada ?: return
        if (s.accion != accion || s.fase != FaseApagada.APAGADA) return
        val boton = botones[accion] ?: return
        val ok = pulsacionesApagada.any { boton.coincide(it) }
        Log.i(TAG, "$accion con la pantalla apagada: ${if (ok) "llega" else "NO llega"} (oído: $pulsacionesApagada)")
        apagada[accion] = ok
        paso = s.copy(fase = FaseApagada.RESULTADO, ok = ok)
    }

    private fun trasApagada(accion: Accion) {
        val claves = botones.keys.toList()
        val siguiente = claves.getOrNull(claves.indexOf(accion) + 1)
        paso = if (siguiente != null) Paso.Apagada(siguiente, FaseApagada.APAGAR) else inicioInfrarrojo()
    }

    // ── Infrarrojo ────────────────────────────────────────────────────────────

    private fun inicioInfrarrojo(): Paso {
        candidatosIr = PruebaInfrarrojo.candidatos()
        Log.i(TAG, "Candidatos IR: $candidatosIr")
        return if (candidatosIr.isEmpty()) {
            infrarrojo = Verificacion.NO_DISPONIBLE
            nodoIr = null
            Paso.Infrarrojo(FaseIr.RESULTADO, "No se encontró ningún infrarrojo que la app pueda mover")
        } else {
            Paso.Infrarrojo(FaseIr.INICIO, "Tapa la cámara con la mano y toca PROBAR")
        }
    }

    private fun probarInfrarrojo() {
        val lector = PruebaInfrarrojo.lector(this)
        if (lector == null) {
            // Sin sensor de luz lo juzga una persona, candidato a candidato. El modo
            // noche se para durante toda la pregunta: mueve el mismo nodo y apagaría
            // el IR mientras alguien lo está mirando.
            nochePausada = ModoNoche.activo
            if (nochePausada) ModoNoche.parar()
            preguntaIr = 0
            preguntarIr()
            return
        }
        paso = Paso.Infrarrojo(FaseIr.PROBANDO, "Probando…")
        Thread {
            val (candidato, medida) = PruebaInfrarrojo.conModoNocheParado {
                PruebaInfrarrojo.probarTodos(lector, candidatosIr) { n ->
                    handler.post { paso = Paso.Infrarrojo(FaseIr.PROBANDO, "Probando $n de ${candidatosIr.size}…") }
                }
            }
            handler.post {
                infrarrojo = if (candidato != null) Verificacion.VERIFICADO else Verificacion.NO_DISPONIBLE
                nodoIr = candidato
                val texto = when {
                    candidato != null -> "Infrarrojo OK\n${medida?.describir()}"
                    medida != null -> "No se vio el infrarrojo\n${medida.describir()}"
                    else -> "No se vio el infrarrojo"
                }
                paso = Paso.Infrarrojo(FaseIr.RESULTADO, texto)
            }
        }.start()
    }

    private fun preguntarIr() {
        val c = candidatosIr.getOrNull(preguntaIr)
        if (c == null) {
            infrarrojo = Verificacion.NO_DISPONIBLE
            nodoIr = null
            reanudarNoche()
            paso = Paso.Infrarrojo(FaseIr.RESULTADO, "Sin sensor de luz y nadie vio el IR")
            return
        }
        Thread { HardwareController.escribirCandidato(c.ruta, c.valor) }.start()
        paso = Paso.Infrarrojo(FaseIr.PREGUNTA,
            "¿Brillan los LEDs de infrarrojos? Se ven rojizos, o con la cámara de un móvil")
    }

    private fun responderIr(seVe: Boolean) {
        val c = candidatosIr.getOrNull(preguntaIr) ?: return
        Thread { HardwareController.escribirCandidato(c.ruta, "0") }.start()
        Log.i(TAG, "IR ${c.ruta}: la persona dice ${if (seVe) "que se ve" else "que no"}")
        if (seVe) {
            infrarrojo = Verificacion.VERIFICADO
            nodoIr = c
            reanudarNoche()
            paso = Paso.Infrarrojo(FaseIr.RESULTADO, "Infrarrojo OK (visto a ojo)")
        } else {
            preguntaIr++
            preguntarIr()
        }
    }

    private fun reanudarNoche() {
        if (!nochePausada) return
        nochePausada = false
        ModoNoche.arrancar()
    }

    // ── Guardar ───────────────────────────────────────────────────────────────

    private fun perfilNuevo(): Perfil {
        var nodos = base.nodos
        var ir = base.infrarrojo
        infrarrojo?.let { resultado ->
            ir = resultado
            // Un IR que no se vio no se vuelve a encender: nodo fuera del perfil.
            nodos = nodos.copy(ir = nodoIr?.ruta, irValor = nodoIr?.valor ?: nodos.irValor)
        }
        return base.copy(
            origen = "asistente",
            botones = botones.toMap(),
            pantallaApagada = base.pantallaApagada.filterKeys { it in botones } + apagada,
            nodos = nodos,
            infrarrojo = ir,
        )
    }

    private fun guardar() {
        PerfilDispositivo.guardar(this, perfilNuevo())
        BotonesFisicos.aprendiendo = null
        finish()
    }

    private fun salirSinGuardar() {
        Log.i(TAG, "Salida sin guardar: el perfil sigue siendo ${base.origen}")
        BotonesFisicos.aprendiendo = null
        finish()
    }

    private fun accesibilidadActiva(): Boolean =
        Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains(packageName) == true

    // ── Lo que se enseña ──────────────────────────────────────────────────────

    private fun vista(): Vista = when (val s = paso) {
        is Paso.Pedir -> Vista(
            titulo = "${s.accion.etiqueta} · ${s.accion.ordinal + 1}/${Accion.values().size}",
            texto = "Pulsa el botón de ${s.accion.etiqueta}",
            aviso = s.aviso ?: if (!accesibilidadActiva()) "Accesibilidad apagada: solo se ven teclas con la app delante" else null,
            derecha = Toque("SALTAR") { saltarBoton(s.accion) },
        )
        is Paso.Confirmar -> Vista(
            titulo = s.accion.etiqueta,
            texto = "${s.boton.describir()}\nPúlsalo otra vez para confirmar",
            aviso = s.nota,
            derecha = Toque("REPETIR") { paso = Paso.Pedir(s.accion) },
        )
        is Paso.Apagada -> when (s.fase) {
            FaseApagada.APAGAR -> Vista(
                titulo = "${s.accion.etiqueta} · pantalla apagada",
                texto = "Apaga la pantalla, pulsa ${s.accion.etiqueta} y vuelve a encenderla",
                derecha = Toque("SALTAR") { trasApagada(s.accion) },
            )
            FaseApagada.APAGADA -> Vista(titulo = s.accion.etiqueta, texto = "Comprobando…")
            FaseApagada.RESULTADO -> Vista(
                titulo = s.accion.etiqueta,
                texto = if (s.ok) "Funciona con la pantalla apagada" else "Con la pantalla apagada NO llega",
                aviso = if (s.ok) null else "Solo servirá con la pantalla encendida",
                izquierda = Toque("REPETIR") { paso = s.copy(fase = FaseApagada.APAGAR) },
                derecha = Toque("SEGUIR") { trasApagada(s.accion) },
            )
        }
        is Paso.Infrarrojo -> when (s.fase) {
            FaseIr.INICIO -> Vista(
                titulo = "Infrarrojo", texto = s.texto,
                izquierda = Toque("SALTAR") { infrarrojo = null; paso = Paso.Resumen },
                derecha = Toque("PROBAR") { probarInfrarrojo() },
            )
            FaseIr.PROBANDO -> Vista(titulo = "Infrarrojo", texto = s.texto)
            FaseIr.PREGUNTA -> Vista(
                titulo = "Infrarrojo", texto = s.texto,
                izquierda = Toque("NO") { responderIr(false) },
                derecha = Toque("SÍ") { responderIr(true) },
            )
            FaseIr.RESULTADO -> Vista(
                titulo = "Infrarrojo", texto = s.texto,
                izquierda = if (candidatosIr.isEmpty()) null else Toque("REPETIR") { paso = inicioInfrarrojo() },
                derecha = Toque("SEGUIR") { paso = Paso.Resumen },
            )
        }
        Paso.Resumen -> Vista(
            titulo = "Resumen",
            texto = resumen(),
            izquierda = Toque("SALIR") { salirSinGuardar() },
            derecha = Toque("GUARDAR") { guardar() },
        )
    }

    private fun resumen(): String {
        val p = perfilNuevo()
        val lineas = Accion.values().map { a ->
            val b = p.botones[a] ?: return@map "${a.etiqueta}: sin botón"
            val apag = when (p.pantallaApagada[a]) { true -> " ✔"; false -> " ✘"; null -> "" }
            "${a.etiqueta}: ${b.describir()}$apag"
        }
        val ir = when (p.infrarrojo) {
            Verificacion.VERIFICADO -> "IR: OK"
            Verificacion.NO_DISPONIBLE -> "IR: no"
            Verificacion.SIN_PROBAR -> "IR: sin probar"
        }
        return (lineas + ir).joinToString("\n")
    }

    companion object {
        private const val VENTANA_MS = 900L
        private const val ESPERA_TRAS_ENCENDER_MS = 2_000L
    }
}

// ── UI ────────────────────────────────────────────────────────────────────────

/** Un botón táctil del asistente. */
private class Toque(val texto: String, val alTocar: () -> Unit)

private class Vista(
    val titulo: String,
    val texto: String,
    val aviso: String? = null,
    val izquierda: Toque? = null,
    val derecha: Toque? = null,
)

private val FONDO    = Color(0xFF12121C)
private val TITULO   = Color(0xFF3498DB)
private val AVISO    = Color(0xFFF0A500)
private val BOTON    = Color(0xFF2A3550)
private val BOTON_OK = Color(0xFF2E7D32)

private val TITULO_TEXT = 18.dp
private val CUERPO_TEXT = 15.dp
private val AVISO_TEXT  = 12.dp
private val BOTON_TEXT  = 16.dp
private val BARRA_ALTO  = 44.dp

/**
 * Mismas reglas que el panel de [MainActivity]: pantalla de ~3 cm, texto en dp
 * fijos, y como mucho dos objetivos táctiles, abajo.
 */
@Composable
private fun PantallaAsistente(v: Vista) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FONDO)
            .padding(8.dp),
    ) {
        Text(v.titulo, color = TITULO, fontSize = TITULO_TEXT.asFixedSp(), fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(v.texto, color = Color.White, fontSize = CUERPO_TEXT.asFixedSp(), fontWeight = FontWeight.Bold)
            v.aviso?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = AVISO, fontSize = AVISO_TEXT.asFixedSp())
            }
        }
        if (v.izquierda != null || v.derecha != null) {
            Row(Modifier.fillMaxWidth().height(BARRA_ALTO)) {
                v.izquierda?.let { BotonBarra(it, BOTON, Modifier.weight(1f)) }
                if (v.izquierda != null && v.derecha != null) Spacer(Modifier.width(6.dp))
                v.derecha?.let { BotonBarra(it, if (v.izquierda != null) BOTON_OK else BOTON, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun BotonBarra(accion: Toque, color: Color, modifier: Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(color, RoundedCornerShape(7.dp))
            .clickable(onClick = accion.alTocar),
        contentAlignment = Alignment.Center,
    ) {
        Text(accion.texto, color = Color.White, fontSize = BOTON_TEXT.asFixedSp(),
            fontWeight = FontWeight.Bold, maxLines = 1, textAlign = TextAlign.Center)
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────
// Al tamaño real del panel (190 dp), sin girar, como se lee en la unidad.

@Preview(name = "Pedir", widthDp = 190, heightDp = 190)
@Composable
private fun PreviewPedir() = PantallaAsistente(Vista(
    titulo = "SOS · 1/3", texto = "Pulsa el botón de SOS",
    aviso = "Accesibilidad apagada: solo se ven teclas con la app delante",
    derecha = Toque("SALTAR") {},
))

@Preview(name = "Confirmar", widthDp = 190, heightDp = 190)
@Composable
private fun PreviewConfirmar() = PantallaAsistente(Vista(
    titulo = "SOS", texto = "F3 (133) · broadcast\nPúlsalo otra vez para confirmar",
    aviso = "También por accesibilidad", derecha = Toque("REPETIR") {},
))

@Preview(name = "Resumen", widthDp = 190, heightDp = 190)
@Composable
private fun PreviewResumen() = PantallaAsistente(Vista(
    titulo = "Resumen",
    texto = "SOS: F3 (133) · broadcast ✔\nGRABAR: F4 (134) · broadcast ✔\nPTT: F2 (132) · broadcast ✔\nIR: OK",
    izquierda = Toque("SALIR") {}, derecha = Toque("GUARDAR") {},
))
