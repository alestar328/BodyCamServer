package com.falconone.bodycamserver

import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "FalconUpload"

/**
 * Incidentes cuya subida se ha cancelado.
 *
 * ## Por qué hace falta
 *
 * La reanudación no se rinde nunca: [UploadService.resumePending] reencola en cada
 * arranque de la unidad y en cada apertura de la app **todo** incidente sin recibo
 * de entrega, y [ChunkedUploader] reintenta con espera creciente. Es lo que
 * queremos para una evidencia real en una furgoneta sin cobertura, y es un
 * problema para un incidente que nunca va a llegar —una prueba vieja, un destino
 * mal configurado, un vídeo que el agente no quiere mandar ahora—: se reintenta
 * para siempre, gastando batería y red.
 *
 * Cancelar es la puerta de salida de ese bucle.
 *
 * ## Qué es cancelar y qué no
 *
 * Cancelar **corta la transferencia en curso y marca el incidente para que no se
 * vuelva a intentar**. No borra nada: el vídeo, el manifiesto y la sesión de
 * subida se quedan como están, y [reanudar] lo devuelve a la cola sin haber
 * perdido el progreso (el servidor sigue teniendo los bloques que ya recibió, ver
 * [UploadSessions]).
 *
 * Es deliberado que **no borre la evidencia**. Una bodycam en la que un botón
 * pueda hacer desaparecer un vídeo no es defendible en cadena de custodia. Si
 * algún día hace falta descartar de verdad, eso es otra operación, con su propio
 * rastro.
 *
 * ## Dónde vive la marca
 *
 * Un fichero vacío dentro del directorio del incidente, junto al vídeo y al
 * manifiesto. Va ahí y no en una lista central por dos razones: sobrevive a
 * reinstalar la app, y si alguien copia o mueve el incidente, la decisión viaja
 * con él en vez de quedarse huérfana.
 */
object UploadCancel {

    private const val MARCA = "upload.cancelled"

    private fun marca(incidentId: String) = File(EvidenceStore.incidentDir(incidentId), MARCA)

    fun cancelado(incidentId: String): Boolean = marca(incidentId).isFile

    /**
     * Marca el incidente. La transferencia en vuelo se entera en el siguiente
     * bloque o al salir de la espera entre reintentos, así que puede tardar unos
     * segundos en pararse de verdad.
     */
    fun cancelar(incidentId: String): Boolean = try {
        val dir = EvidenceStore.incidentDir(incidentId)
        if (!dir.isDirectory) {
            Log.w(TAG, "cancelar: no existe el incidente $incidentId")
            false
        } else {
            val cuando = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
            marca(incidentId).writeText("cancelled_at=$cuando\n")
            Log.w(TAG, "$incidentId: subida CANCELADA — no se reintentará")
            true
        }
    } catch (e: Exception) {
        Log.e(TAG, "no se pudo cancelar $incidentId", e)
        false
    }

    /** Quita la marca. Vuelve a la cola en la próxima reanudación. */
    fun reanudar(incidentId: String): Boolean = try {
        val m = marca(incidentId)
        val habia = m.isFile
        if (habia && !m.delete()) {
            Log.e(TAG, "no se pudo quitar la marca de $incidentId")
            false
        } else {
            if (habia) Log.i(TAG, "$incidentId: cancelación retirada, vuelve a la cola")
            true
        }
    } catch (e: Exception) {
        Log.e(TAG, "no se pudo reanudar $incidentId", e)
        false
    }

    /** Los que están esperando subida, con la marca de cada uno. Para el teléfono. */
    fun resumen(): List<Triple<String, Boolean, Boolean>> =
        EvidenceStore.incidentIds().map { id ->
            Triple(id, UploadService.isDelivered(id), cancelado(id))
        }

    /**
     * La misma cancelación, por adb. El camino de verdad es el teléfono
     * (`UPLOAD_CANCEL` en [Cmd]); esto es para desatascar una unidad en banco sin
     * tener que emparejar nada:
     *
     *     adb shell am start -n com.falconone.bodycamserver/.MainActivity --es upload_cancel INC_000032
     *     adb shell am start -n com.falconone.bodycamserver/.MainActivity --es upload_resume INC_000032
     */
    fun Context.atenderOrdenDeSubida(intent: Intent) {
        intent.getStringExtra("upload_cancel")?.takeIf { it.isNotBlank() }?.let { cancelar(it) }
        intent.getStringExtra("upload_resume")?.takeIf { it.isNotBlank() }?.let { id ->
            if (reanudar(id)) UploadService.startIncident(this, id)
        }
    }
}
