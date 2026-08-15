package com.falconone.bodycamserver

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val TAG = "FalconBench"

/**
 * Coste real de hashear y cifrar evidencia en el dispositivo.
 *
 * Existe porque el número no se puede obtener desde fuera: la unidad no trae
 * openssl ni Python, y medirlo en el PC da el rendimiento del PC, que está un
 * orden de magnitud por encima de este ARM.
 *
 * ── Por qué se cifra por bloques ──────────────────────────────────────────
 *
 * En Android, AES/GCM **no se puede cifrar en streaming**. Conscrypt acumula
 * todo lo que se le pasa por Cipher.update() en un buffer interno y solo cifra
 * al llamar a doFinal(): verificado en esta unidad, pedirle 256 MB de una vez
 * revienta con OutOfMemoryError en OpenSSLCipher$EVP_AEAD.expand. Es inherente
 * a los modos AEAD — el tag cubre todo el mensaje, así que no hay salida
 * parcial que emitir hasta el final.
 *
 * La forma viable, y la que mide esto, es trocear: cada bloque se cifra entero
 * con doFinal y lleva su propio nonce y su propio tag. El coste en espacio son
 * 28 bytes por bloque (0,003 % con bloques de 1 MB) y a cambio la memoria queda
 * acotada al tamaño de bloque en vez de al del fichero.
 *
 * El nonce es un prefijo aleatorio de 8 bytes por fichero más un contador de
 * bloque de 4: así no puede repetirse dentro de un mismo fichero, que es la
 * forma habitual de romper GCM.
 *
 * ── Qué mide cada fase ────────────────────────────────────────────────────
 *
 *   read          solo leer de almacenamiento externo
 *   sha256        hash leyendo de disco — el caso de la cadena de custodia
 *   encrypt_mem   cifrado sin tocar disco — techo de CPU
 *   encrypt_file  leer, cifrar y escribir el .enc — el caso real completo
 *
 * Si encrypt_file se parece a read, el cuello es el almacenamiento y optimizar
 * el cifrado no serviría de nada.
 */
object CryptoBenchmark {

    private const val CHUNK = 1024 * 1024
    private const val GCM_TAG_BITS = 128
    private const val NONCE_BYTES = 12

    /** Tope de lo que se carga en RAM para la prueba de CPU. */
    private const val MEM_SAMPLE_BYTES = 8 * 1024 * 1024

    /** Masa a procesar en la prueba de CPU para que el reloj sea fiable. */
    private const val MEM_TARGET_BYTES = 64L * 1024 * 1024

    fun run(file: File, repeats: Int = 3): JSONObject {
        val size = file.length()
        Log.d(TAG, "benchmark sobre ${file.name} (${size / 1024} KB), $repeats repeticiones")

        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val sample = readSample(file, minOf(size, MEM_SAMPLE_BYTES.toLong()).toInt())

        // Descartada: la primera pasada paga el JIT y la carga del proveedor.
        sha256(file)

        val result = JSONObject().apply {
            put("file", file.name)
            put("size_bytes", size)
            put("size_mb", round(size / 1_048_576.0))
            put("repeats", repeats)
            put("provider", providerName())
            put("chunk_kb", CHUNK / 1024)
        }

        var digest = ""
        result.put("read", measure(repeats, size) { readOnly(file) })
        result.put("sha256", measure(repeats, size) { digest = sha256(file) })
        result.put("sha256_hex", digest)

        // El trozo en RAM se repite: con un segmento de 2 MB el reloj no tiene
        // resolución para dar un MB/s creíble de una sola pasada.
        val loops = maxOf(1, (MEM_TARGET_BYTES / sample.size).toInt())
        val memBytes = sample.size.toLong() * loops
        result.put("encrypt_mem", measure(repeats, memBytes) { encryptInMemory(sample, key, loops) })

        val out = File(file.parentFile, file.name + ".bench.enc")
        result.put("encrypt_file", measure(repeats, size) { encryptToFile(file, out, key) })
        result.put("encrypted_bytes", out.length())
        out.delete()

        Log.d(TAG, "benchmark terminado: $result")
        return result
    }

    // ── Fases ─────────────────────────────────────────────────────────────────

    private fun readOnly(file: File): Long {
        var total = 0L
        val buf = ByteArray(CHUNK)
        file.inputStream().buffered().use { input ->
            var n = input.read(buf)
            while (n != -1) {
                total += n
                n = input.read(buf)
            }
        }
        return total
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(CHUNK)
        file.inputStream().buffered().use { input ->
            var n = input.read(buf)
            while (n != -1) {
                md.update(buf, 0, n)
                n = input.read(buf)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Cifra un buffer ya en memoria, bloque a bloque. Sin disco de por medio. */
    private fun encryptInMemory(sample: ByteArray, key: ByteArray, loops: Int) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secret = SecretKeySpec(key, "AES")
        val prefix = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val out = ByteArray(CHUNK + GCM_TAG_BITS / 8)
        var block = 0

        repeat(loops) {
            var offset = 0
            while (offset < sample.size) {
                val n = minOf(CHUNK, sample.size - offset)
                cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(GCM_TAG_BITS, nonce(prefix, block++)))
                cipher.doFinal(sample, offset, n, out, 0)
                offset += n
            }
        }
    }

    /**
     * Caso real: leer de disco, cifrar por bloques y escribir.
     * Formato: prefijo(8) || por cada bloque [ ciphertext || tag(16) ].
     */
    private fun encryptToFile(src: File, dst: File, key: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secret = SecretKeySpec(key, "AES")
        val prefix = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val buf = ByteArray(CHUNK)
        val out = ByteArray(CHUNK + GCM_TAG_BITS / 8)
        var block = 0

        FileOutputStream(dst).buffered().use { sink ->
            sink.write(prefix)
            src.inputStream().buffered().use { input ->
                var n = input.read(buf)
                while (n != -1) {
                    cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(GCM_TAG_BITS, nonce(prefix, block++)))
                    val written = cipher.doFinal(buf, 0, n, out, 0)
                    sink.write(out, 0, written)
                    n = input.read(buf)
                }
            }
        }
    }

    // ── Utilidades ────────────────────────────────────────────────────────────

    /** prefijo aleatorio por fichero (8) + contador de bloque (4) = 12 bytes. */
    private fun nonce(prefix: ByteArray, block: Int): ByteArray =
        ByteArray(NONCE_BYTES).also {
            System.arraycopy(prefix, 0, it, 0, 8)
            it[8]  = (block ushr 24).toByte()
            it[9]  = (block ushr 16).toByte()
            it[10] = (block ushr 8).toByte()
            it[11] = block.toByte()
        }

    /** minSdk es 26, así que nada de InputStream.readNBytes (API 33). */
    private fun readSample(file: File, max: Int): ByteArray {
        val buf = ByteArray(max)
        var filled = 0
        file.inputStream().buffered().use { input ->
            while (filled < max) {
                val n = input.read(buf, filled, max - filled)
                if (n == -1) break
                filled += n
            }
        }
        return if (filled == max) buf else buf.copyOf(filled)
    }

    private fun providerName(): String = try {
        Cipher.getInstance("AES/GCM/NoPadding").provider.name
    } catch (e: Exception) {
        "desconocido"
    }

    /**
     * Ejecuta [block] varias veces y devuelve el mejor tiempo además de la media.
     * El mejor es el más representativo del coste real: los peores recogen ruido
     * de otros procesos, y en esta unidad la captura puede estar activa.
     */
    private fun measure(repeats: Int, bytes: Long, block: () -> Unit): JSONObject {
        val times = LongArray(repeats)
        repeat(repeats) { i ->
            val t0 = System.nanoTime()
            block()
            times[i] = System.nanoTime() - t0
        }
        val bestMs = (times.minOrNull() ?: 0L) / 1_000_000.0
        val avgMs = times.average() / 1_000_000.0
        return JSONObject().apply {
            put("bytes", bytes)
            put("best_ms", round(bestMs))
            put("avg_ms", round(avgMs))
            put("best_mb_s", round(bytes / 1_048_576.0 / (bestMs / 1000.0)))
            put("avg_mb_s", round(bytes / 1_048_576.0 / (avgMs / 1000.0)))
        }
    }

    private fun round(v: Double): Double = Math.round(v * 100) / 100.0
}
