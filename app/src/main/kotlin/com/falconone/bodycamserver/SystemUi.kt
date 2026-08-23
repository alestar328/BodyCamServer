package com.falconone.bodycamserver

import android.app.Activity
import android.view.View
import androidx.core.view.WindowCompat

/**
 * Esconde las barras de estado y de navegación.
 *
 * En una pantalla de ~3 cm las barras del sistema no son un detalle estético: se
 * llevan una fracción enorme del panel. Y no solo tapan — **el sistema le reserva
 * el hueco a la ventana**, así que la activity recibe menos superficie de la que
 * tiene la pantalla. En `RecordingActivity` eso se veía como un margen lateral
 * junto a la pregunta de envío: la capa girada cubría bien la ventana, pero la
 * ventana no cubría la pantalla.
 *
 * Un tema `Fullscreen` solo quita la barra de estado. La de navegación se va por
 * aquí, y no basta con llamarlo una vez: el sistema la restaura al recuperar el
 * foco, así que hay que reaplicarlo desde `onWindowFocusChanged`.
 *
 * **STICKY y no el inmersivo normal:** un deslizamiento desde el borde devuelve
 * las barras un momento y se vuelven a esconder solas. Hace falta esa vía de
 * escape para poder salir de la app en la propia unidad, pero sin que un roce con
 * el uniforme las deje puestas para siempre.
 *
 * Si más adelante hace falta que no se puedan sacar en absoluto, eso ya es modo
 * kiosco (`startLockTask` con device owner) — es otra cosa, y encaja con las
 * features de bloqueo del dispositivo del listado de ciberseguridad.
 *
 * ## Esconder no es lo mismo que llegar al borde
 *
 * Los flags de abajo esconden las barras, pero por sí solos **no** hacen que el
 * content view llegue al borde: el decor le sigue aplicando los insets, así que
 * el `FrameLayout` de la activity arranca por debajo de donde estaría la barra de
 * estado. Se veía como una franja arriba sin el sombreado de la pregunta de
 * envío: el scrim cubría toda la ventana, pero la ventana no empezaba en y = 0.
 *
 * `setDecorFitsSystemWindows(false)` es lo que quita esos insets y deja el
 * contenido de borde a borde.
 *
 * Se usan los flags antiguos y no `WindowInsetsController` porque `targetSdk` es
 * 28: es la API que gobierna en este proceso.
 */
@Suppress("DEPRECATION")
internal fun Activity.goImmersive() {
    // El content view llega al borde: sin esto el decor lo baja por los insets.
    WindowCompat.setDecorFitsSystemWindows(window, false)

    window.decorView.systemUiVisibility = (
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
}
