package com.falconone.bodycamserver

import android.os.Environment
import android.util.Log
import org.json.JSONObject
import java.io.File

private const val TAG = "FalconUploadSess"

/**
 * Memoria de las subidas a medias: `fingerprint → URL de sesión`.
 *
 * Es lo único que hace falta para reanudar. La subida por bloques abre una sesión
 * en el servidor y recibe una URL; mientras la unidad recuerde esa URL puede
 * preguntar por dónde iba y seguir. Si la pierde, el fichero se sube entero otra
 * vez — que es exactamente el problema que veníamos a resolver.
 *
 * ── Por qué un fichero en la sdcard y no SharedPreferences ────────────────────
 *
 * Las preferencias viven en datos de app y desaparecen al desinstalar; el fichero
 * está junto a la evidencia, en `FalconOne/`, igual que `.incident_seq`. Una
 * grabación de 2 GB puede estar subiéndose durante horas y sobrevivir a un
 * reinicio de la unidad y a un `adb install -r` en medio.
 *
 * ── El fingerprint no es la ruta del fichero ──────────────────────────────────
 *
 * Es el **SHA-256 del ciphertext** (`sha256_cipher`, ya calculado al cifrar y
 * guardado en el manifest). Esto no es un detalle: el fingerprint por defecto de
 * las librerías de tus es *ruta + tamaño*, y si un incidente se vuelve a cifrar
 * —nonce nuevo, mismo tamaño, misma ruta— ese fingerprint no cambia aunque los
 * bytes sí. Reanudar entonces empalmaría la primera mitad de un ciphertext con la
 * segunda de otro, y el resultado sería un fichero que no descifra y que nadie
 * sabría explicar. Con el hash del contenido eso es imposible por construcción.
 */
object UploadSessions {

    private const val STORE_NAME = ".uploads.json"

    /** Sesiones más viejas que esto se descartan al leer. */
    private const val MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000

    private val storeFile: File
        get() = File(File(Environment.getExternalStorageDirectory(), "FalconOne"), STORE_NAME)

    /**
     * URL de la sesión abierta para [fingerprint], o null si no hay ninguna
     * utilizable. [length] se compara con la que se guardó: si no coincide, la
     * sesión es de otro fichero y reanudarla corrompería la subida.
     */
    @Synchronized
    fun urlFor(fingerprint: String, length: Long): String? {
        val entry = read().optJSONObject(fingerprint) ?: return null
        val stored = entry.optLong("length", -1L)
        if (stored != length) {
            Log.w(TAG, "sesión descartada: tamaño $stored, ahora $length")
            forget(fingerprint)
            return null
        }
        if (System.currentTimeMillis() - entry.optLong("updated_at") > MAX_AGE_MILLIS) {
            Log.w(TAG, "sesión caducada para ${fingerprint.take(12)}…")
            forget(fingerprint)
            return null
        }
        return entry.optString("url").takeIf { it.isNotBlank() }
    }

    @Synchronized
    fun remember(fingerprint: String, url: String, length: Long) {
        val root = read()
        root.put(fingerprint, JSONObject().apply {
            put("url", url)
            put("length", length)
            put("updated_at", System.currentTimeMillis())
        })
        write(root)
    }

    /** Marca actividad sin cambiar la URL, para que la sesión no caduque en vuelo. */
    @Synchronized
    fun touch(fingerprint: String) {
        val root = read()
        root.optJSONObject(fingerprint)?.let {
            it.put("updated_at", System.currentTimeMillis())
            write(root)
        }
    }

    /**
     * Olvida la sesión. Se llama al terminar bien —y así el fichero no crece sin
     * límite, que es el fallo por defecto de las librerías de tus— y también
     * cuando el servidor responde 404: esa sesión ya no existe y guardarla solo
     * garantiza que el siguiente intento falle igual.
     */
    @Synchronized
    fun forget(fingerprint: String) {
        val root = read()
        if (root.has(fingerprint)) {
            root.remove(fingerprint)
            write(root)
        }
    }

    private fun read(): JSONObject = try {
        storeFile.takeIf { it.isFile }?.let { JSONObject(it.readText()) } ?: JSONObject()
    } catch (e: Exception) {
        // Un store corrupto no puede bloquear la subida: se pierde la reanudación
        // de lo que hubiera en vuelo y se empieza de cero, que es recuperable.
        Log.w(TAG, "store de sesiones ilegible, se reinicia: ${e.message}")
        JSONObject()
    }

    private fun write(root: JSONObject) {
        try {
            storeFile.parentFile?.mkdirs()
            storeFile.writeText(root.toString())
        } catch (e: Exception) {
            Log.e(TAG, "no se pudo guardar el store de sesiones: ${e.message}")
        }
    }
}
