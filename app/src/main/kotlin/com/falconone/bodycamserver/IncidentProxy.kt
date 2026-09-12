package com.falconone.bodycamserver

import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File

private const val TAG = "FalconProxy"

/**
 * Proxy de un incidente: la copia ligera que el backend pasa por un LLM para
 * transcribir lo que ocurre. 720 de lado corto, 15 fps y el rótulo del oficial.
 * Contrato: docs/BACKEND-PROXY-AND-SOS.md del repo de Nexus, §1.
 *
 * El proxy no es evidencia. Se cifra igual que el original porque viaja por red,
 * pero su claro no se conserva nunca (el del original sí, por EVD-007), y el .fev se
 * borra en cuanto se entrega (ver UploadService).
 */
object IncidentProxy {

    /** Proxy ya cifrado y lo que hay que declarar de él en el manifest y al subirlo. */
    data class Result(
        val sealed: EvidenceCrypto.Sealed,
        /** `sha256_plain` del ORIGINAL: lo que une las dos piezas en el backend. */
        val proxyOf: String,
        val width: Int,
        val height: Int,
        val fps: Int,
    )

    /**
     * Hace y cifra el proxy de [original]. Null si falla: el incidente sigue su curso
     * sin él, y el backend puede sacar su copia del original.
     */
    fun make(incidentId: String, original: File, proxyOf: String): Result? {
        val dir = EvidenceStore.proxyDir(incidentId).also { it.mkdirs() }
        val plain = File(dir, original.name.removeSuffix(".mp4") + "_proxy.mp4")
        plain.delete()

        val started = System.currentTimeMillis()
        val made = try {
            VideoStamper.makeProxy(original, plain, HardcodedOfficer, IncidentAssembler.rotationOf(original))
        } catch (e: Exception) {
            Log.e(TAG, "$incidentId: el proxy falló: ${e.message}")
            false
        }
        val size = if (made) videoSizeOf(plain) else null
        val sealed = if (made) EvidenceCrypto.seal(plain) else null
        plain.delete()

        if (size == null || sealed == null) {
            Log.w(TAG, "$incidentId: sin proxy — el backend tendrá que sacarlo del original")
            return null
        }
        Log.d(
            TAG,
            "$incidentId: proxy ${size.first}x${size.second} a ${VideoStamper.PROXY_FPS} fps, " +
                "${sealed.plainBytes / 1024} KB (original ${original.length() / 1024} KB) " +
                "en ${System.currentTimeMillis() - started} ms",
        )
        return Result(sealed, proxyOf, size.first, size.second, VideoStamper.PROXY_FPS)
    }

    /** El proxy cifrado que falta por entregar, o null si no hay o ya se entregó. */
    fun pending(incidentId: String): File? =
        EvidenceStore.proxyDir(incidentId)
            .listFiles { f -> f.isFile && f.name.endsWith(EvidenceCrypto.EXTENSION) }
            ?.firstOrNull()

    private fun videoSizeOf(video: File): Pair<Int, Int>? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(video.absolutePath)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            if (width == null || height == null) null else width to height
        } catch (e: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }
}
