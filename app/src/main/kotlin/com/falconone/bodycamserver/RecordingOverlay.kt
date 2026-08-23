package com.falconone.bodycamserver

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/**
 * Todo lo que se pinta **encima** del preview de cámara durante la grabación: el
 * contador, la capa de reposo y la pregunta de envío.
 *
 * Vive aparte de [RecordingActivity] a propósito. La activity se queda con la
 * cámara (Camera2, MediaRecorder, el `TextureView` del que se alimenta el monitor
 * remoto), que no gana nada en Compose; el overlay es UI pura y sí gana: los
 * `@Preview` del final permiten ajustar la pregunta sin desplegar en la unidad,
 * que es lo que antes obligaba a compilar e instalar por cada cambio de tamaño.
 *
 * ## El giro
 *
 * Estas vistas se dibujan con la orientación que reporta el sistema, que en esta
 * unidad no coincide con cómo se lee la pantalla. El preview de cámara ya lo
 * compensa por su lado con una `Matrix` (`applyPreviewTransform`), pero eso solo
 * afecta al `TextureView`, no a lo que va encima.
 *
 * `Modifier.rotate` gira sin cambiar las medidas del layout, igual que
 * `View.setRotation`, así que la capa se mide con **ancho y alto intercambiados**
 * y se centra: ya rotada, cubre la pantalla exacta. Compose invierte la matriz
 * para el táctil, de modo que Yes/No se pulsan donde se ven.
 */
const val OVERLAY_ROTATION_DEGREES = -90f

/**
 * Pregunta de envío en curso. [label] es lo que se enseña bajo la pregunta —
 * hoy, el id del incidente recién cerrado. [secondsLeft] es la cuenta atrás
 * hasta el "No" por defecto.
 */
data class PromptState(
    val label: String,
    val secondsLeft: Int,
)

/**
 * @param elapsed texto del contador, o null si no se muestra (parado o en reposo).
 * @param covered capa de reposo puesta. No apaga el panel — Android no lo permite
 *   sin permisos de administrador de dispositivo, y por esa vía el despertar
 *   pasaría por la pantalla de bloqueo, que es justo lo que no queremos aquí.
 * @param prompt pregunta de envío, o null.
 */
data class OverlayState(
    val elapsed: String? = null,
    val covered: Boolean = false,
    val prompt: PromptState? = null,
)

// ── Medidas ───────────────────────────────────────────────────────────────────
// El panel de la unidad mide ~3 cm de lado. Como es cuadrado, el giro no cambia
// las dimensiones disponibles: se diseña para el mismo cuadrado que MainActivity.

private val ELAPSED_TEXT_SIZE  = 30.dp
private val QUESTION_TEXT_SIZE = 35.dp
private val FILENAME_TEXT_SIZE = 25.dp
private val ANSWER_TEXT_SIZE   = 20.dp
private val HINT_TEXT_SIZE     = 20.dp
private val ANSWER_HEIGHT      = 40.dp
private val PROMPT_PADDING     = 10.dp

private val REC_RED      = Color(0xFFFF5555)
private val SCRIM        = Color(0xE6000000)
private val ANSWER_YES   = Color(0xFF2E7D32)
private val ANSWER_NO    = Color(0xFF5A5A5A)
private val FILENAME_GREY = Color(0xFF888888)
private val HINT_GREY     = Color(0xFFAAAAAA)

/** Texto medido en dp: inmune al ajuste de tamaño de fuente del sistema. */
@Composable
internal fun Dp.asFixedSp(): TextUnit = with(LocalDensity.current) { this@asFixedSp.toSp() }

// ── UI ────────────────────────────────────────────────────────────────────────

/**
 * @param rotationDegrees expuesto solo para los `@Preview`: pasando 0 se ve el
 *   contenido derecho, como lo lee el agente, en vez de girado como queda en el
 *   framebuffer. En la app siempre va con el valor por defecto.
 */
@Composable
fun RecordingOverlay(
    state: OverlayState,
    onAnswer: (Boolean) -> Unit,
    rotationDegrees: Float = OVERLAY_ROTATION_DEGREES,
) {
    Box(Modifier.fillMaxSize()) {
        // Orden = profundidad: el contador queda debajo de la capa de reposo, y la
        // pregunta por encima de todo. Es el mismo orden que tenían las vistas.
        state.elapsed?.let { text ->
            Rotated(rotationDegrees) {
                ElapsedBadge(text, Modifier.align(Alignment.TopStart))
            }
        }

        if (state.covered) {
            Box(Modifier.fillMaxSize().background(Color.Black))
        }

        state.prompt?.let { prompt ->
            Rotated(rotationDegrees) {
                UploadPrompt(prompt, onAnswer)
            }
        }
    }
}

/**
 * Capa a pantalla completa girada, medida con las dimensiones **intercambiadas**:
 * ya rotada 90°, una caja de `alto × ancho` cubre exactamente un contenedor de
 * `ancho × alto`.
 *
 * `requiredSize` y no `size`: `size` respeta las constraints del padre, así que
 * la dimensión que excede al contenedor se recorta y la capa acaba cuadrada en
 * vez de traspuesta — se veían franjas sin cubrir a los lados del eje largo.
 * `requiredSize` ignora las constraints entrantes y deja que el hijo desborde,
 * que es justo lo que hace falta aquí.
 */
@Composable
private fun Rotated(rotationDegrees: Float, content: @Composable BoxScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .requiredSize(width = maxHeight, height = maxWidth)
                .rotate(rotationDegrees),
            content = content,
        )
    }
}

@Composable
private fun ElapsedBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = REC_RED,
        fontSize = ELAPSED_TEXT_SIZE.asFixedSp(),
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        // Sombra para que se lea sobre cualquier escena que esté grabando.
        style = TextStyle(shadow = Shadow(color = Color.Black, blurRadius = 6f)),
        modifier = modifier.padding(10.dp),
    )
}

/**
 * "Send to the server?".
 *
 * El fichero se queda en la unidad en los dos casos: la respuesta decide la
 * subida, nunca si se guarda. Sin respuesta **no** se sube, y no al revés: subir
 * es la irreversible de las dos, y el vídeo sigue en la unidad para mandarlo
 * luego desde el teléfono.
 */
@Composable
private fun UploadPrompt(state: PromptState, onAnswer: (Boolean) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SCRIM)
            .padding(PROMPT_PADDING),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Send to the server?",
            color = Color.White,
            fontSize = QUESTION_TEXT_SIZE.asFixedSp(),
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = state.label,
            color = FILENAME_GREY,
            fontSize = FILENAME_TEXT_SIZE.asFixedSp(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            AnswerButton("Yes", ANSWER_YES, Modifier.weight(1f)) { onAnswer(true) }
            Spacer(Modifier.width(8.dp))
            AnswerButton("No", ANSWER_NO, Modifier.weight(1f)) { onAnswer(false) }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "No answer in ${state.secondsLeft}s = No",
            color = HINT_GREY,
            fontSize = HINT_TEXT_SIZE.asFixedSp(),
        )
    }
}

@Composable
private fun AnswerButton(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(ANSWER_HEIGHT)
            .background(color, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = ANSWER_TEXT_SIZE.asFixedSp(),
            fontWeight = FontWeight.Bold,
        )
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────
// Al tamaño real del panel (~3 cm). Los tres primeros van sin girar, que es como
// lo lee el agente; el último lleva el giro real para comprobar que, ya rotado,
// sigue cabiendo.

private const val PANEL_DP = 190

@Preview(name = "1 · Pregunta de envío", widthDp = PANEL_DP, heightDp = PANEL_DP)
@Composable
private fun PreviewPrompt() = RecordingOverlay(
    state = OverlayState(prompt = PromptState("INC_20260823_181500", 30)),
    onAnswer = {},
    rotationDegrees = 0f,
)

@Preview(name = "2 · Pregunta a punto de vencer", widthDp = PANEL_DP, heightDp = PANEL_DP)
@Composable
private fun PreviewPromptExpiring() = RecordingOverlay(
    state = OverlayState(prompt = PromptState("INC_20260823_181500", 3)),
    onAnswer = {},
    rotationDegrees = 0f,
)

@Preview(name = "3 · Grabando", widthDp = PANEL_DP, heightDp = PANEL_DP)
@Composable
private fun PreviewRecording() = RecordingOverlay(
    state = OverlayState(elapsed = "● REC  00:12:34"),
    onAnswer = {},
    rotationDegrees = 0f,
)

@Preview(name = "4 · Pregunta girada (framebuffer real)", widthDp = PANEL_DP, heightDp = PANEL_DP)
@Composable
private fun PreviewPromptRotated() = RecordingOverlay(
    state = OverlayState(prompt = PromptState("INC_20260823_181500", 30)),
    onAnswer = {},
)
