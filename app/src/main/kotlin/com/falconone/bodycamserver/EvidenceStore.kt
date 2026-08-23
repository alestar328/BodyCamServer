package com.falconone.bodycamserver

import android.os.Environment
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "FalconStore"

/**
 * Dueño del layout en disco de la evidencia capturada.
 *
 *     FalconOne/buffer/            anillo pre-evento, se borra solo
 *     FalconOne/incidents/<id>/    evidencia promovida, el anillo NUNCA la toca
 *     FalconOne/VID_*.mp4          grabaciones del modelo anterior, intactas
 *     FalconOne/IMG_*.jpg          fotos, intactas
 *
 * La regla que hace segura la eliminación automática: el anillo solo borra
 * dentro de buffer/. Una vez promovido, un segmento está fuera de su alcance
 * por construcción, no por una comprobación que se pueda olvidar.
 *
 * Promover es un renameTo() dentro del mismo sistema de ficheros, así que
 * mover dos minutos de vídeo al incidente no copia bytes.
 */
object EvidenceStore {

    /**
     * Tamaño de segmento. La rotación de MediaRecorder es por tamaño, no por
     * duración: la API solo emite MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING,
     * no existe el equivalente de duración. A 4 Mbps de vídeo más AAC (~516 KB/s)
     * esto son unos 15 s, pero la duración real varía con la escena y el cálculo
     * del pre-roll no depende de ella: se resuelve con los instantes de inicio
     * reales embebidos en el nombre.
     */
    const val SEGMENT_BYTES = 8L * 1024 * 1024

    /** Ventana que el anillo garantiza tener disponible al pulsar grabar. */
    const val PRE_ROLL_MILLIS = 120_000L

    /**
     * Tope duro de segmentos en el anillo. El recorte normal es temporal; este
     * límite solo actúa si los timestamps dejan de ser fiables (cambio de hora,
     * NTP saltando hacia atrás) y el recorte temporal deja de converger.
     */
    const val RING_MAX_SEGMENTS = 32

    private const val SEGMENT_PREFIX = "SEG_"
    private const val SEGMENT_SUFFIX = ".mp4"
    private const val MANIFEST_NAME = "manifest.json"

    private val root: File
        get() = File(Environment.getExternalStorageDirectory(), "FalconOne")

    val bufferDir: File get() = File(root, "buffer").also { it.mkdirs() }
    val incidentsDir: File get() = File(root, "incidents").also { it.mkdirs() }

    // ── Anillo pre-evento ─────────────────────────────────────────────────────

    /**
     * El instante de inicio va embebido en el nombre con ancho fijo, así que
     * ordenar lexicográficamente equivale a ordenar cronológicamente. No
     * dependemos de lastModified(), que en almacenamiento externo es poco fiable.
     */
    fun newBufferSegment(startMillis: Long = System.currentTimeMillis()): File =
        File(bufferDir, "%s%013d%s".format(SEGMENT_PREFIX, startMillis, SEGMENT_SUFFIX))

    fun bufferSegments(): List<File> = segmentsIn(bufferDir)

    fun startMillisOf(segment: File): Long =
        segment.name.removePrefix(SEGMENT_PREFIX).removeSuffix(SEGMENT_SUFFIX).toLongOrNull() ?: 0L

    /**
     * Descarta los segmentos que ya quedan por detrás de la ventana de pre-roll.
     *
     * Usa el mismo predicado que [promotePreRoll] —el segmento que contiene el
     * corte se conserva entero— así que lo que sobrevive al recorte es
     * exactamente lo que se promovería si se pulsara grabar en este instante.
     * Cualquier divergencia entre ambos sería un pre-roll incompleto.
     *
     * [active] son los segmentos que MediaRecorder puede tener abiertos: nunca
     * se tocan. Ver la nota de [promotePreRoll] sobre por qué son dos y no uno.
     */
    fun trimRing(nowMillis: Long, active: Set<File> = emptySet()) {
        val segments = bufferSegments()
        val keepFrom = preRollStartIndex(segments, nowMillis)
        val doomed = segments.take(keepFrom).toMutableList()

        // Backstop: si los timestamps se han vuelto locos el recorte temporal no
        // converge, y el anillo crecería sin límite sobre el disco.
        val excess = segments.size - doomed.size - RING_MAX_SEGMENTS
        if (excess > 0) {
            Log.w(TAG, "anillo por encima del tope ($excess de más) — recorte por número")
            doomed += segments.drop(doomed.size).take(excess)
        }

        doomed.forEach { seg ->
            if (seg in active) return@forEach
            if (seg.delete()) Log.d(TAG, "anillo: descartado ${seg.name}")
            else Log.w(TAG, "anillo: no se pudo borrar ${seg.name}")
        }
    }

    /**
     * Vacía el anillo. Se llama al armar: los restos de una sesión anterior no
     * tienen continuidad temporal con la nueva, y presentarlos como pre-roll
     * sería juntar dos momentos distintos en un mismo incidente.
     */
    fun clearRing() {
        val gone = bufferSegments().count { it.delete() }
        if (gone > 0) Log.d(TAG, "anillo vaciado ($gone segmentos)")
    }

    // ── Incidentes ────────────────────────────────────────────────────────────

    fun newIncidentId(at: Date = Date()): String =
        "INC_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(at)

    fun incidentDir(incidentId: String): File =
        File(incidentsDir, incidentId).also { it.mkdirs() }

    /** Incidentes más recientes primero. */
    fun incidentIds(): List<String> =
        incidentsDir.listFiles { f -> f.isDirectory }
            ?.map { it.name }?.sortedDescending() ?: emptyList()

    fun incidentSegments(incidentId: String): List<File> =
        segmentsIn(File(incidentsDir, incidentId))

    /**
     * Mueve al incidente los segmentos sellados que cubren la ventana de pre-roll
     * anterior a [triggerMillis].
     *
     * El segmento que contiene el instante de corte entra entero: recortar por
     * dentro exigiría recodificar. El pre-roll real va de PRE_ROLL_MILLIS a
     * PRE_ROLL_MILLIS + la duración de un segmento — siempre de más, nunca de
     * menos. El instante exacto del disparo queda en el manifest para que, si
     * alguna vez hace falta un corte al segundo, se haga en servidor.
     *
     * [active] son los segmentos que MediaRecorder puede tener abiertos, y se
     * excluyen: mover un fichero abierto lo saca de buffer/ sin que el grabador
     * se entere —en Linux la escritura sigue al inodo— y el segmento acaba en el
     * incidente por dos caminos a la vez.
     *
     * Son DOS y no uno porque el aviso de rotación (803) llega después de que
     * MediaRecorder haya abierto realmente el fichero siguiente: en esa ventana
     * el fichero abierto es el "pendiente", no el que consideramos en vuelo.
     * Verificado en dispositivo: promover con un solo exclusor movía el fichero
     * recién abierto.
     */
    fun promotePreRoll(incidentId: String, triggerMillis: Long, active: Set<File>): List<File> {
        val sealed = bufferSegments().filterNot { it in active }
        if (sealed.isEmpty()) {
            Log.w(TAG, "sin pre-roll disponible para $incidentId")
            return emptyList()
        }

        val from = preRollStartIndex(sealed, triggerMillis)
        val promoted = sealed.drop(from).mapNotNull { adoptIntoIncident(incidentId, it) }

        val covered = promoted.firstOrNull()?.let { triggerMillis - startMillisOf(it) } ?: 0L
        Log.d(TAG, "pre-roll de $incidentId: ${promoted.size} segmentos, ${covered / 1000}s")
        return promoted
    }

    /**
     * Traslada al incidente todo lo que quede en el anillo. Solo debe llamarse
     * con el grabador ya cerrado: en RECORDING cada segmento sellado se adopta
     * al vuelo, así que lo que queda en buffer/ es la cola del incidente.
     *
     * Evita tener que adivinar cuál de los ficheros activos era el último, que
     * es justo donde estaba la carrera entre la rotación real y su aviso.
     */
    fun drainBufferInto(incidentId: String): List<File> =
        bufferSegments().mapNotNull { adoptIntoIncident(incidentId, it) }

    /**
     * Mueve un segmento ya sellado del anillo al incidente. Es la única vía por
     * la que un fichero sale de buffer/, y solo se invoca sobre ficheros cerrados.
     */
    fun adoptIntoIncident(incidentId: String, segment: File): File? {
        val dest = File(incidentDir(incidentId), segment.name)
        return if (segment.renameTo(dest)) {
            dest
        } else {
            Log.e(TAG, "no se pudo mover ${segment.name} a $incidentId")
            null
        }
    }

    /**
     * Manifest de la sesión: lo que el teléfono y el repositorio necesitan para
     * reconstruir un incidente segmentado como una grabación continua, y para
     * situar un timestamp del transcript en el segmento correcto.
     */
    fun writeManifest(
        incidentId: String,
        armedAtMillis: Long,
        triggerMillis: Long,
        stoppedMillis: Long,
    ) {
        val segments = incidentSegments(incidentId)
        val origin = segments.firstOrNull()?.let { startMillisOf(it) } ?: triggerMillis

        val arr = JSONArray()
        segments.forEachIndexed { i, seg ->
            val start = startMillisOf(seg)
            arr.put(JSONObject().apply {
                put("index", i)
                put("filename", seg.name)
                put("size_bytes", seg.length())
                put("start_epoch_ms", start)
                // Desplazamiento desde el inicio del incidente: es lo que convierte
                // un timestamp del transcript en (segmento, offset).
                put("offset_ms", start - origin)
                // Anterior a la pulsación, no "vino del anillo": el segmento que
                // estaba en vuelo al disparar también empieza antes del disparo,
                // y para quien revise la evidencia eso es material pre-evento.
                put("pre_roll", start < triggerMillis)
            })
        }

        val manifest = JSONObject().apply {
            put("incident_id", incidentId)
            put("device_model", android.os.Build.MODEL)
            put("armed_at_epoch_ms", armedAtMillis)
            put("trigger_epoch_ms", triggerMillis)
            put("stopped_epoch_ms", stoppedMillis)
            put("pre_roll_target_ms", PRE_ROLL_MILLIS)
            // Pre-roll realmente entregado: puede ser mayor que el objetivo por el
            // redondeo a segmento, o menor si se grabó antes de llenar el anillo.
            put("pre_roll_actual_ms", triggerMillis - origin)
            // Dónde cae la pulsación dentro de la línea de tiempo del incidente.
            // Sin esto no se puede distinguir lo pre-evento de lo grabado a petición.
            put("trigger_offset_ms", triggerMillis - origin)
            put("segment_count", segments.size)
            put("segments", arr)
        }

        try {
            File(incidentDir(incidentId), MANIFEST_NAME)
                .writeText(manifest.toString(2))
            Log.d(TAG, "manifest escrito para $incidentId (${segments.size} segmentos)")
        } catch (e: Exception) {
            Log.e(TAG, "no se pudo escribir el manifest de $incidentId: ${e.message}")
        }
    }

    fun manifestOf(incidentId: String): File? =
        File(File(incidentsDir, incidentId), MANIFEST_NAME).takeIf { it.isFile }

    fun incidentSizeBytes(incidentId: String): Long =
        incidentSegments(incidentId).sumOf { it.length() }

    // ── Modelo anterior ───────────────────────────────────────────────────────

    /**
     * Grabaciones y fotos del modelo de fichero único, en la raíz de FalconOne.
     * No se migran: mover evidencia ya capturada durante un refactor no aporta
     * nada y sí puede perderla. Se siguen listando y sirviendo tal cual.
     */
    fun legacyFiles(): List<File> =
        root.listFiles { f ->
            f.isFile && (f.name.endsWith(".mp4") || f.name.endsWith(".jpg"))
        }?.sortedByDescending { it.lastModified() } ?: emptyList()

    // ── Internos ──────────────────────────────────────────────────────────────

    private fun segmentsIn(dir: File): List<File> =
        dir.listFiles { f ->
            f.isFile && f.name.startsWith(SEGMENT_PREFIX) && f.name.endsWith(SEGMENT_SUFFIX)
        }?.sortedBy { it.name } ?: emptyList()

    /**
     * Índice del primer segmento que hay que conservar para cubrir la ventana de
     * pre-roll que termina en [atMillis]: el último que empieza en o antes del
     * corte —porque contiene el corte— y todo lo posterior.
     *
     * Si ningún segmento es anterior al corte, el anillo aún no cubre la ventana
     * completa (acaba de armarse) y se conserva todo lo que hay.
     */
    private fun preRollStartIndex(segments: List<File>, atMillis: Long): Int {
        if (segments.isEmpty()) return 0
        val cutoff = atMillis - PRE_ROLL_MILLIS
        return segments.indexOfLast { startMillisOf(it) <= cutoff }.coerceAtLeast(0)
    }
}
