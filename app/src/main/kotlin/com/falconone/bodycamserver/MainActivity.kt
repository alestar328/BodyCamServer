package com.falconone.bodycamserver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Panel de control de la unidad.
 *
 * La pantalla física mide unos **3 × 3 cm** ([PANEL_SIDE_DP] de lado). Todo está
 * dimensionado para ese cuadrado: no cabe una lista de opciones ni un texto
 * largo, así que la pantalla es un panel de estado con un único control.
 *
 * Los `@Preview` del final renderizan los cuatro estados a ese tamaño exacto, que
 * es la razón de que esta pantalla esté en Compose: ajustar tamaños a ojo en una
 * pantalla de 3 cm exigía desplegar en la unidad en cada intento.
 *
 * Reglas que impone ese tamaño y conviene no romper:
 *
 *  - **Lo que sobra se lo queda el estado, no el control.** El SOS vive en una
 *    barra de altura fija ([SOS_BAR_HEIGHT]); con peso se comía el panel entero.
 *  - **Nada de emoji.** 📱 y 📡 dependían de que la fuente del sistema tuviera el
 *    glifo. Son vectores propios en `res/drawable`, teñidos por estado.
 *  - **Texto en dp, no en sp.** En sp el ajuste de fuente del sistema agranda el
 *    texto y descuadra el panel; aquí no lo lee nadie de cerca, así que se fija.
 *  - **Un solo objetivo táctil.** La bodycam va sujeta al uniforme: se opera con
 *    las teclas físicas o desde el teléfono. El SOS se queda porque es lo único
 *    que puede hacer falta a ciegas.
 *
 * RecordingActivity sigue en Views: lleva un TextureView atado a Camera2 y las
 * capas giradas del overlay, que no ganan nada pasando a Compose.
 */
class MainActivity : ComponentActivity() {

    private var panel by mutableStateOf(PanelState())

    private val handler = Handler(Looper.getMainLooper())

    private val uiRefresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    private val recordingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refresh()
    }

    /**
     * Relee el estado real de los servicios.
     *
     * Es un sondeo y no un push porque no todo lo que se pinta emite eventos:
     * BtServerService.connectedClient y LivestreamService.isStreaming son flags
     * que se consultan. El "Esperando teléfono…" original era una escritura de
     * una sola vez y se quedaba puesto aunque el teléfono ya estuviera conectado.
     */
    private fun refresh() {
        val client = BtServerService.connectedClient
        panel = PanelState(
            link = when {
                !BtServerService.isRunning -> Link.OFFLINE
                client != null -> Link.CONNECTED
                else -> Link.WAITING
            },
            clientName = client,
            armed = RecordingActivity.state == CaptureState.ARMED,
            recording = RecordingActivity.isRecording,
            streaming = LivestreamService.isStreaming,
            // El "conectando" no lo sabe ningún servicio: es local, desde que se
            // pulsa hasta que Agora confirma el join.
            sosConnecting = panel.sosConnecting && !LivestreamService.isStreaming,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (BuildConfig.DEBUG) atenderAltaDeIdentidadDebug(intent)

        goImmersive()

        setContent {
            ControlPanel(state = panel, onSos = ::toggleLivestream)
        }

        requestPermissions()
        if (allPermissionsGranted()) {
            startService()
            // Reanuda las subidas que quedaron a medias. Va aquí y no solo en
            // BootReceiver porque el firmware de esta unidad bloquea el arranque
            // automático en background (verificado el 2026-08-26: BOOT_COMPLETED
            // entra en la cola de background y nunca llega al receptor). Abrir la
            // app sí ocurre siempre, así que es el disparador fiable.
            UploadService.resumePending(applicationContext)
        }

        registerReceiver(
            recordingReceiver,
            IntentFilter(RecordingActivity.ACTION_STATE_CHANGED)
        )
        refresh()
        handler.post(uiRefresher)
    }

    // Al recuperar el foco (vuelta de RecordingActivity, de un diálogo de permisos)
    // el sistema restaura las barras: hay que volver a esconderlas.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    override fun onDestroy() {
        handler.removeCallbacks(uiRefresher)
        try { unregisterReceiver(recordingReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    // ── Lógica ────────────────────────────────────────────────────────────────

    // Todo arranca solo y nada se para desde la pantalla: una bodycam que se
    // queda sin enlace o sin buffer por un toque accidental no sirve de nada.
    //
    // Armar aquí es el requisito de producto (2026-08-23): abrir la app = entrar
    // en servicio. Desde este momento la unidad guarda los últimos 20 s en el
    // anillo y cualquier grabación los incluye como pre-roll. RecordingActivity
    // pasa a primer plano y dibuja este mismo panel sobre su preview.
    private fun startService() {
        startForegroundService(Intent(this, BtServerService::class.java))
        RecordingActivity.arm(this)
    }

    private fun toggleRecording() {
        if (RecordingActivity.ignoreRecordKey()) {
            Log.d("FalconKeys", "Pulsación descartada: pregunta de envío recién abierta")
            return
        }
        if (RecordingActivity.isRecording) {
            // Parada manual desde la unidad: pregunta antes de subir.
            RecordingActivity.stop(this, askUpload = true)
        } else {
            TorchController.release()
            RecordingActivity.start(this)
        }
    }

    // El SOS de la bodycam es el livestream (mismo comportamiento que la tecla F3):
    // pulsar emite, volver a pulsar corta, y el teléfono lo detecta por Agora
    // (uid 9001 → null).
    private fun toggleLivestream() {
        if (LivestreamService.isStreaming) {
            // En hilo propio: stop() destruye el RtcEngine y rearma el anillo.
            Thread { LivestreamService.stop() }.start()
        } else {
            // Feedback inmediato: entre ceder la cámara y el join de Agora pasan
            // segundos, y el botón se quedaba mudo mientras tanto.
            panel = panel.copy(sosConnecting = true)
            // En hilo propio: start() desarma y espera hasta 3 s a que el HAL
            // suelte la cámara — en el hilo principal congelaría la UI justo en
            // el momento más crítico. LivestreamService ya cierra la grabación
            // en curso por su cuenta (yieldCamera).
            Thread { LivestreamService.start(applicationContext) }.start()
        }
    }

    // ── Botones físicos ───────────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        Log.d("FalconKeys", "MainActivity onKeyDown: $keyCode")
        // F2 (PTT) NO se atiende aquí: lo lleva el broadcast SIDE_KEY_INTENT en
        // BtServerService, que además es la única vía que funciona con la pantalla
        // apagada. Con la Activity en foco onKeyDown auto-repite cada 50 ms y un
        // mantenido largo conmutaba el micro una decena de veces.
        when (keyCode) {
            KeyEvent.KEYCODE_F3 -> {
                if (!ButtonDebounce.tryAcquire()) return true
                toggleLivestream()
            }
            KeyEvent.KEYCODE_F4 -> {
                if (!ButtonDebounce.tryAcquire()) return true
                toggleRecording()
                refresh()  // respuesta visual inmediata
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    // ── Permisos ──────────────────────────────────────────────────────────────

    private val requiredPerms = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.BLUETOOTH,
    )

    private fun allPermissionsGranted() =
        requiredPerms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun requestPermissions() {
        val missing = requiredPerms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        if (results.all { it == PackageManager.PERMISSION_GRANTED }) startService()
    }
}

// ── Estado ────────────────────────────────────────────────────────────────────

enum class Link { OFFLINE, WAITING, CONNECTED }

data class PanelState(
    val link: Link = Link.OFFLINE,
    val clientName: String? = null,
    /** Servicio continuo armado: el anillo pre-evento está grabando. */
    val armed: Boolean = false,
    val recording: Boolean = false,
    val streaming: Boolean = false,
    val sosConnecting: Boolean = false,
)

// ── Medidas y colores ─────────────────────────────────────────────────────────
// Todo el ajuste fino del panel vive aquí: cambiar un número y mirar los @Preview
// del final basta, sin desplegar en la unidad.

/** Lado del panel físico, ~3 cm. Solo lo usan los @Preview. */
private const val PANEL_SIDE_DP = 190

private val PANEL_PADDING    = 8.dp
private val SOS_BAR_HEIGHT   = 60.dp   // mínimo de objetivo táctil de Android
private val PHONE_ICON_SIZE  = 40.dp
private val REC_ICON_SIZE    = 25.dp
private val LIVE_ICON_SIZE   = 25.dp
private val STATUS_TEXT_SIZE = 30.dp
private val LABEL_TEXT_SIZE  = 20.dp
private val SOS_TEXT_SIZE    = 25.dp

private val BG      = Color(0xFF12121C)
private val GREEN   = Color(0xFF2ECC71)
private val AMBER   = Color(0xFFF0A500)
private val GREY    = Color(0xFF8A8A8A)
private val RED     = Color(0xFFE74C3C)
private val BLUE    = Color(0xFF3498DB)
private val DIM     = Color(0xFF454F66)
private val SOS_IDLE       = Color(0xFF6E1F18)
private val SOS_ACTIVE     = Color(0xFFC0392B)
private val SOS_CONNECTING = Color(0xFF1565C0)
private val SOS_IDLE_TEXT  = Color(0xFFF5B7B1)

// asFixedSp() vive en RecordingOverlay.kt: lo comparten las dos pantallas.

// ── UI ────────────────────────────────────────────────────────────────────────

@Composable
fun ControlPanel(state: PanelState, onSos: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
            .padding(PANEL_PADDING)
    ) {
        // El bloque de estado se queda el hueco sobrante y va centrado; la barra
        // de SOS tiene altura fija. Al revés, el SOS ocupaba casi el panel entero.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LinkRow(state)
            Spacer(Modifier.height(10.dp))
            IndicatorRow(state)
        }
        SosBar(state, onSos)
    }
}

@Composable
private fun LinkRow(state: PanelState) {
    val color = when (state.link) {
        Link.OFFLINE   -> GREY
        Link.WAITING   -> AMBER
        Link.CONNECTED -> GREEN
    }
    val icon = if (state.link == Link.OFFLINE) R.drawable.ic_phone_off else R.drawable.ic_phone
    val label = when (state.link) {
        Link.OFFLINE   -> "Offline"
        Link.WAITING   -> "Waiting"
        Link.CONNECTED -> state.clientName ?: "Connected"
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(PHONE_ICON_SIZE),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = color,
            fontSize = STATUS_TEXT_SIZE.asFixedSp(),
            fontWeight = FontWeight.Bold,
            // El nombre del teléfono lo pone el fabricante y puede ser largo:
            // en una línea de ~170 dp hay que recortarlo o rompe la fila.
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * REC y LIVE no se ocultan al perder el teléfono: la unidad graba igual con las
 * teclas físicas, y saber si está grabando es justo lo que no puede depender de
 * que haya alguien emparejado.
 */
@Composable
private fun IndicatorRow(state: PanelState) {
    // El punto REC tiene tres estados: rojo grabando, ámbar con el anillo armado
    // (grabación continua sin incidente), apagado en reposo.
    // Azul armado = el mismo código de color que el LED físico (LedSignals):
    // buffer activo, listos para grabar con pre-roll.
    val recColor = when {
        state.recording -> RED
        state.armed     -> BLUE
        else            -> DIM
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Indicator(R.drawable.ic_rec, REC_ICON_SIZE, "REC", recColor)
        Spacer(Modifier.width(14.dp))
        Indicator(R.drawable.ic_live, LIVE_ICON_SIZE, "LIVE", if (state.streaming) BLUE else DIM)
    }
}

@Composable
private fun Indicator(icon: Int, iconSize: Dp, label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(iconSize),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            color = color,
            fontSize = LABEL_TEXT_SIZE.asFixedSp(),
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SosBar(state: PanelState, onSos: () -> Unit) {
    val (background, textColor, label) = when {
        state.streaming     -> Triple(SOS_ACTIVE, Color.White, "SOS ON")
        state.sosConnecting -> Triple(SOS_CONNECTING, Color.White, "SOS …")
        else                -> Triple(SOS_IDLE, SOS_IDLE_TEXT, "SOS")
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(SOS_BAR_HEIGHT)
            .background(background, RoundedCornerShape(7.dp))
            .clickable(onClick = onSos),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = SOS_TEXT_SIZE.asFixedSp(),
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────
// Al tamaño real del panel. Para ajustar medidas, cambia las constantes de arriba
// y mira aquí: no hace falta desplegar en la unidad.

@Preview(name = "1 · Esperando teléfono", widthDp = PANEL_SIDE_DP, heightDp = PANEL_SIDE_DP)
@Composable
private fun PreviewWaiting() =
    ControlPanel(PanelState(link = Link.WAITING), onSos = {})

@Preview(name = "2 · Conectado y grabando", widthDp = PANEL_SIDE_DP, heightDp = PANEL_SIDE_DP)
@Composable
private fun PreviewRecording() = ControlPanel(
    PanelState(link = Link.CONNECTED, clientName = "Pixel-7", recording = true),
    onSos = {},
)

@Preview(name = "3 · SOS activo", widthDp = PANEL_SIDE_DP, heightDp = PANEL_SIDE_DP)
@Composable
private fun PreviewSos() = ControlPanel(
    PanelState(link = Link.CONNECTED, clientName = "Pixel-7", recording = true, streaming = true),
    onSos = {},
)

// Nombre largo a propósito: es el caso que rompía la fila antes del ellipsis.
@Preview(name = "4 · Offline, nombre largo", widthDp = PANEL_SIDE_DP, heightDp = PANEL_SIDE_DP)
@Composable
private fun PreviewOffline() = ControlPanel(
    PanelState(link = Link.CONNECTED, clientName = "Samsung Galaxy S24 Ultra de Alejandro"),
    onSos = {},
)

/**
 * Alta de la identidad de la bodycam por intent (workflows 13 y 31).
 *
 * La W1 tiene una pantalla de 3 cm y ningun sitio donde pulsar nada: el alta se
 * conduce desde el PC con `adb`, igual que la del telefono. Lo automatiza
 * `tools/alta-bodycam.sh` en el repositorio de Aeria Nexus, que es donde vive la
 * CA de pruebas.
 *
 *     # 1. generar la clave y dejar el CSR donde se pueda recoger
 *     adb shell am start -n com.falconone.bodycamserver/.MainActivity --es bwc_enroll 1
 *     adb shell run-as com.falconone.bodycamserver cat files/identity/bwc.csr.pem
 *
 *     # 2. devolver el certificado emitido y el ancla con la que validar al telefono
 *     adb shell am start -n com.falconone.bodycamserver/.MainActivity  *         --es bwc_cert "$(base64 -w0 bwc.crt)" --es bwc_anchor "$(base64 -w0 ca.crt)"
 *
 * Solo se llama bajo BuildConfig.DEBUG. En una unidad de produccion esto lo hara
 * el canal de aprovisionamiento, que todavia no existe.
 */
private fun android.app.Activity.atenderAltaDeIdentidadDebug(intent: android.content.Intent) {
    val etiqueta = "BwcAlta"

    if (intent.getStringExtra("bwc_enroll") != null) {
        runCatching {
            // El reto deberia venir del backend. Sin el, la cadena de atestacion
            // sale bien formada y NO prueba frescura; es la misma costura que
            // tenia el telefono antes del servicio de retos.
            val reto = intent.getStringExtra("bwc_challenge")
                ?.let { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }
            BodycamIdentity.generarPar(reto)
            val csr = BodycamIdentity.crearCsr(this)
            BodycamIdentity.guardarCsr(this, csr)
            android.util.Log.i(etiqueta, "CSR de ${BodycamIdentity.bwcId(this)} listo" +
                if (reto == null) " (reto local: NO prueba frescura)" else " (con reto del backend)")
        }.onFailure { android.util.Log.e(etiqueta, "No se pudo preparar el alta", it) }
    }

    intent.getStringExtra("bwc_anchor")?.let { enBase64 ->
        runCatching {
            val pem = String(android.util.Base64.decode(enBase64, android.util.Base64.DEFAULT))
            BodycamIdentity.instalarAncla(this, pem)
        }.onFailure { android.util.Log.e(etiqueta, "No se pudo instalar el ancla", it) }
    }

    intent.getStringExtra("bwc_user_anchor")?.let { enBase64 ->
        runCatching {
            val pem = String(android.util.Base64.decode(enBase64, android.util.Base64.DEFAULT))
            BodycamIdentity.instalarAnclaDeUsuario(this, pem)
        }.onFailure { android.util.Log.e(etiqueta, "No se pudo instalar el ancla de usuario", it) }
    }

    intent.getStringExtra("bwc_cert")?.let { enBase64 ->
        runCatching {
            val pem = String(android.util.Base64.decode(enBase64, android.util.Base64.DEFAULT))
            val certificado = BodycamIdentity.instalarCertificado(pem)
            // Prueba de posesion contra el certificado recien puesto: si verifica,
            // la clave del Keystore y la que certifico la CA son el mismo par.
            val reto = "prueba-de-posesion".toByteArray()
            val firma = BodycamIdentity.firmar(reto)
            val valida = java.security.Signature.getInstance(Pkcs10.ALGORITMO_FIRMA).run {
                initVerify(certificado.publicKey); update(reto); verify(firma)
            }
            android.util.Log.i(etiqueta, "Certificado instalado para ${certificado.subjectX500Principal}")
            android.util.Log.i(etiqueta, "Emitido por ${certificado.issuerX500Principal}")
            android.util.Log.i(etiqueta, "Prueba de posesion: $valida")
        }.onFailure { android.util.Log.e(etiqueta, "No se pudo instalar el certificado", it) }
    }
}
