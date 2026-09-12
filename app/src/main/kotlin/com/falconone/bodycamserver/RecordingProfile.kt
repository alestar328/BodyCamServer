package com.falconone.bodycamserver

import android.hardware.camera2.CameraCharacteristics
import android.media.MediaRecorder

/**
 * Tamaño y bitrate con los que graba la unidad.
 *
 * El original va a 1080p (petición del manager, 2026-09-11) cuando la cámara se lo
 * ofrece al grabador, y si no al 720p de siempre. La W1 no ofrece 1920x1080 exacto
 * sino 1920x1088 (medido con `dumpsys media.camera`): se acepta esa altura, que es la
 * del bloque de 16 píxeles del codificador. Las 8 líneas de más no se recortan porque
 * recortar exige recodificar, y el original no se toca.
 */
data class RecordingProfile(val width: Int, val height: Int, val bitRate: Int) {

    val label: String get() = "${width}x$height"

    companion object {
        private val HD = RecordingProfile(1280, 720, 4_000_000)

        // 8 Mbps y no los ~15 de un teléfono: la unidad tiene poca tarjeta y poca
        // batería, y a 1080p esto ya son unos 60 MB por minuto.
        private const val FULL_HD_BITRATE = 8_000_000

        fun choose(characteristics: CameraCharacteristics): RecordingProfile {
            val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(MediaRecorder::class.java)
                .orEmpty()
            val fullHd = sizes.firstOrNull { it.width == 1920 && it.height in 1080..1088 }
            return if (fullHd != null) RecordingProfile(fullHd.width, fullHd.height, FULL_HD_BITRATE) else HD
        }
    }
}
