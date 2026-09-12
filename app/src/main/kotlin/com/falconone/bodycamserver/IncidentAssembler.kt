package com.falconone.bodycamserver

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

private const val TAG = "FalconAssembler"

/**
 * Ensambla los segmentos de un incidente en **un único MP4**.
 *
 * Así funcionan las bodycam policiales: la unidad graba continuamente en
 * segmentos internos (el anillo), pero la evidencia que ve el mundo es un solo
 * vídeo que arranca ~20 s antes de la pulsación y termina al parar. Los
 * segmentos son un detalle del mecanismo de buffer y desaparecen al cerrar.
 *
 * **No recodifica.** Todos los segmentos de un incidente salen de la misma
 * sesión de MediaRecorder (rotación por setNextOutputFile), así que comparten
 * configuración de códec: basta copiar las muestras H.264/AAC al fichero final
 * desplazando sus marcas de tiempo (remux). El coste es de I/O — del orden de
 * copiar el fichero — no de CPU, que en este SoC es lo que no sobra.
 *
 * Hasta el 2026-09-11 aquí se quemaba también el rótulo del oficial, y eso SÍ
 * recodificaba: la evidencia sellada era una segunda generación del vídeo. El
 * rótulo pasó al proxy (IncidentProxy); el original sale como lo grabó la cámara.
 *
 * Corre en un hilo propio tras cerrar el grabador: el anillo ya está rearmado
 * y escribe en buffer/, esto solo lee y escribe en incidents/<id>/.
 */
object IncidentAssembler {

    /**
     * Une los segmentos del incidente en un único MP4 con nombre de evidencia
     * (placa_fecha_hora del primer segmento) y borra las piezas.
     *
     * Devuelve el fichero final, o null si algo falló — en ese caso los
     * segmentos originales se conservan intactos: ante la duda, la evidencia
     * nunca se toca.
     */
    fun assemble(incidentId: String): File? {
        val segments = EvidenceStore.incidentSegments(incidentId)
        if (segments.isEmpty()) {
            Log.w(TAG, "incidente sin segmentos: $incidentId")
            return null
        }

        val startMillis = EvidenceStore.startMillisOf(segments.first())
        // Un solo segmento ya es el vídeo final, con su nombre de evidencia: no hay
        // nada que coser.
        if (segments.size == 1) return segments[0]

        // Extensión .tmp mientras se construye: incidentSegments() y el servidor
        // HTTP solo miran .mp4, así que nadie puede listar ni subir un vídeo a
        // medio coser.
        val work = File(EvidenceStore.incidentDir(incidentId), "assembling.tmp")
        work.delete()
        try {
            remux(segments, work)
        } catch (e: Exception) {
            Log.e(TAG, "ensamblado de $incidentId falló: ${e.message} — se conservan los segmentos")
            work.delete()
            return null
        }

        // Solo cuando el fichero final está completo se retiran las piezas. El
        // primer segmento se llama igual que el resultado final, por eso se
        // borra antes de renombrar.
        segments.forEach { it.delete() }
        val final = File(EvidenceStore.incidentDir(incidentId), EvidenceStore.evidenceName(startMillis))
        return if (work.renameTo(final)) {
            Log.d(TAG, "$incidentId ensamblado: ${final.name} (${final.length() / 1_048_576} MB)")
            final
        } else {
            Log.e(TAG, "no se pudo renombrar el ensamblado de $incidentId")
            work
        }
    }

    /** Copia las muestras de todos los segmentos, en orden, a un único MP4. */
    private fun remux(segments: List<File>, out: File) {
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        // La rotación vive en los metadatos del MP4, no en los frames: hay que
        // copiarla o el vídeo final saldría tumbado.
        muxer.setOrientationHint(rotationOf(segments.first()))

        var videoTrack = -1
        var audioTrack = -1
        var offsetUs = 0L
        val buffer = ByteBuffer.allocateDirect(1 shl 20)  // 1 MB: sobra para un keyframe 720p
        val info = MediaCodec.BufferInfo()
        var started = false

        try {
            for (segment in segments) {
                val extractor = MediaExtractor()
                extractor.setDataSource(segment.absolutePath)
                try {
                    // Las pistas del muxer se crean con los formatos del PRIMER
                    // segmento; los demás comparten códec por venir de la misma
                    // sesión de grabación, así que solo se mapean.
                    val trackMap = HashMap<Int, Int>()
                    var segmentEndUs = 0L
                    for (i in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(i)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                        when {
                            mime.startsWith("video/") -> {
                                if (videoTrack < 0) videoTrack = muxer.addTrack(format)
                                trackMap[i] = videoTrack
                            }
                            mime.startsWith("audio/") -> {
                                if (audioTrack < 0) audioTrack = muxer.addTrack(format)
                                trackMap[i] = audioTrack
                            }
                        }
                        if (trackMap.containsKey(i)) {
                            extractor.selectTrack(i)
                            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                                segmentEndUs = maxOf(segmentEndUs, format.getLong(MediaFormat.KEY_DURATION))
                            }
                        }
                    }
                    if (!started) { muxer.start(); started = true }

                    while (true) {
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        val track = trackMap[extractor.sampleTrackIndex]
                        if (track != null) {
                            info.offset = 0
                            info.size = size
                            info.presentationTimeUs = extractor.sampleTime + offsetUs
                            info.flags =
                                if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                                    MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                            muxer.writeSampleData(track, buffer, info)
                            segmentEndUs = maxOf(segmentEndUs, extractor.sampleTime)
                        }
                        extractor.advance()
                    }
                    // El siguiente segmento continúa donde este terminó de verdad
                    // (su duración real, no la nominal): así no hay saltos ni
                    // solapes en la línea de tiempo del vídeo final.
                    offsetUs += segmentEndUs
                } finally {
                    extractor.release()
                }
            }
            muxer.stop()
        } finally {
            try { muxer.release() } catch (_: Exception) {}
        }
    }

    /** Rotación que el MP4 declara en sus metadatos. La usa también el proxy. */
    fun rotationOf(segment: File): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(segment.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
        } catch (_: Exception) {
            0
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }
}
