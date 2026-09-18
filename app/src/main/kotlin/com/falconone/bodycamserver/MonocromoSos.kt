package com.falconone.bodycamserver

import android.util.Log
import io.agora.base.VideoFrame
import io.agora.rtc2.video.IVideoFrameObserver
import java.nio.ByteBuffer

private const val TAG = "FalconNoche"

/**
 * Blanco y negro en el SOS cuando es de noche: lo mismo que hace RecordingActivity con
 * Camera2, pero para la cámara que abre Agora.
 *
 * En el SOS la cámara no es nuestra, así que no hay petición de captura donde pedir el
 * efecto monocromo; y el filtro de color por tabla (LUT) de Agora llegó después de la
 * 4.3.0 que lleva la unidad. Lo que sí hay es este observador: Agora entrega cada
 * fotograma recién capturado en I420, y rellenar sus planos de color (U y V) con el
 * valor neutro 128 lo deja en grises. Son dos copias de memoria por fotograma a
 * 640x480 y 15 fps.
 *
 * Sin él, el vídeo del SOS de noche sale magenta: el filtro IR-CUT está fuera y la
 * cámara sigue en color (ver ModoNoche).
 */
object MonocromoSos : IVideoFrameObserver {

    private const val CROMA_NEUTRO: Byte = 128.toByte()

    // Se reutiliza entre fotogramas y solo crece si cambia la resolución.
    private var gris = ByteArray(0)
    private var avisadoDeFallo = false

    override fun onCaptureVideoFrame(sourceType: Int, videoFrame: VideoFrame): Boolean {
        if (!ModoNoche.esDeNoche) return true
        val i420 = videoFrame.buffer as? VideoFrame.I420Buffer ?: return true
        val altoCroma = (i420.height + 1) / 2
        try {
            neutralizar(i420.dataU, i420.strideU * altoCroma)
            neutralizar(i420.dataV, i420.strideV * altoCroma)
        } catch (e: Exception) {
            // Un búfer de solo lectura no rompe el SOS: sale en color, que es peor
            // imagen pero sigue siendo la emergencia. Se avisa una vez, no a 15 fps.
            if (!avisadoDeFallo) Log.w(TAG, "no se pudo pasar el SOS a monocromo: ${e.message}")
            avisadoDeFallo = true
        }
        return true
    }

    private fun neutralizar(plano: ByteBuffer, bytes: Int) {
        val cuantos = minOf(bytes, plano.capacity())
        if (gris.size < cuantos) gris = ByteArray(cuantos) { CROMA_NEUTRO }
        plano.position(0)
        plano.put(gris, 0, cuantos)
        plano.position(0)
    }

    override fun getVideoFrameProcessMode(): Int = IVideoFrameObserver.PROCESS_MODE_READ_WRITE

    override fun getVideoFormatPreference(): Int = IVideoFrameObserver.VIDEO_PIXEL_I420

    override fun getObservedFramePosition(): Int = IVideoFrameObserver.POSITION_POST_CAPTURER

    override fun getRotationApplied(): Boolean = false

    override fun getMirrorApplied(): Boolean = false

    override fun onPreEncodeVideoFrame(sourceType: Int, videoFrame: VideoFrame): Boolean = true

    override fun onMediaPlayerVideoFrame(videoFrame: VideoFrame, mediaPlayerId: Int): Boolean = true

    override fun onRenderVideoFrame(channelId: String, uid: Int, videoFrame: VideoFrame): Boolean = true
}
