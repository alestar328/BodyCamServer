package com.falconone.bodycamserver

import android.util.Log
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val TAG = "FalconCrypto"

/**
 * Cifrado y hash de la evidencia. Implementa el formato FEVD v1, especificado en
 * `docs/CRYPTO-FORMAT.md` — ese documento es el contrato, y lo comparten esta app y
 * la del teléfono (AeriaNexusPrototype).
 *
 * ── Por qué se cifra por bloques ──────────────────────────────────────────────
 *
 * En Android AES/GCM **no se puede cifrar en streaming**: Conscrypt acumula todo lo
 * que se le pasa por Cipher.update() y solo cifra en doFinal(). Verificado en esta
 * unidad, pedirle 256 MB de una vez revienta con OutOfMemoryError en
 * OpenSSLCipher$EVP_AEAD.expand. Es inherente a los modos AEAD: el tag cubre todo el
 * mensaje, así que no hay salida parcial que emitir hasta el final.
 *
 * Por eso —y esta es la única diferencia real con el script `aes-256Sha256.py` que
 * envió ciberseguridad, que usa un solo nonce y un solo tag para todo el fichero—
 * cada bloque de 1 MB es un mensaje GCM independiente. Cuesta 16 bytes por bloque
 * (0,0015 %) y a cambio la memoria queda acotada al tamaño de bloque, no al del
 * fichero.
 *
 * ── Qué ata cada bloque a su sitio ────────────────────────────────────────────
 *
 *   nonce_i = prefijo aleatorio de 8 bytes ‖ uint32be(i)
 *   aad_i   = cabecera completa ‖ uint32be(i)
 *
 * El prefijo es por fichero, así que un nonce no puede repetirse dentro del mismo
 * —que es la forma habitual de romper GCM—. El índice en el AAD impide reordenar
 * bloques, y como plain_size va en la cabecera y la cabecera es AAD, tampoco se
 * puede truncar el fichero sin que salte la verificación.
 *
 * ── Cuándo se ejecuta ─────────────────────────────────────────────────────────
 *
 * Siempre **al cerrar** el incidente, nunca en paralelo a la grabación: la unidad ya
 * se calienta grabando y el cifrado compite por CPU e I/O con el grabador. Coste
 * medido sobre grabación real de 20 min (473 MB): ~5,1 s.
 */
object EvidenceCrypto {

    /** Texto claro por bloque. Cambiarlo cambia el formato: va en la cabecera. */
    const val CHUNK = 1024 * 1024

    const val EXTENSION = ".fev"

    /**
     * Si el MP4 en claro se borra tras cifrarlo.
     *
     * En `false` a propósito: EVD-007 (política de retención local) sigue pendiente
     * de decisión del manager, y hoy la galería y [UploadService] leen el claro.
     * Cuando se decida, es esta línea y nada más.
     */
    const val DELETE_PLAINTEXT = false

    private const val VERSION = 1
    private const val TAG_BYTES = 16
    private const val TAG_BITS = 128
    private const val PREFIX_BYTES = 8
    private const val DEK_BYTES = 32
    private const val FIXED_HEADER = 29

    private val MAGIC = byteArrayOf(0x46, 0x45, 0x56, 0x44) // "FEVD"

    /** Lo que hay que persistir en el manifest tras cifrar. */
    data class Sealed(
        val file: File,
        /** SHA-256 del MP4 original: el de la cadena de custodia. */
        val plainSha256: String,
        /** SHA-256 del .fev: permite verificar la subida sin tener ninguna clave. */
        val cipherSha256: String,
        val plainBytes: Long,
        val cipherBytes: Long,
        /** Ids de quienes pueden recuperar la DEK. Ver [EvidenceKeys]. */
        val recipients: List<String>,
        val elapsedMillis: Long,
    )

    /**
     * Cifra [plain] y devuelve lo que hay que anotar en el manifest, o null si no se
     * pudo. El fichero se lee **una sola vez**: el hash del claro se calcula en la
     * misma pasada que el cifrado.
     *
     * Ante cualquier fallo borra el destino a medias: un .fev truncado es peor que
     * no tenerlo, porque parece evidencia.
     */
    fun seal(
        plain: File,
        dest: File = File(plain.parentFile, plain.name + EXTENSION),
    ): Sealed? {
        if (!plain.isFile) {
            Log.e(TAG, "no existe el fichero a cifrar: ${plain.name}")
            return null
        }

        val recipients = EvidenceKeys.recipients()
        if (recipients.isEmpty()) {
            // Sin destinatario nadie podría volver a abrirlo: cifrar sería destruir.
            Log.e(TAG, "sin destinatarios de clave — no se cifra ${plain.name}")
            return null
        }

        val started = System.currentTimeMillis()
        val size = plain.length()
        val dek = ByteArray(DEK_BYTES).also { SecureRandom().nextBytes(it) }
        val prefix = ByteArray(PREFIX_BYTES).also { SecureRandom().nextBytes(it) }

        return try {
            val wraps = recipients.map { it.id to it.wrap(dek) }
            val header = buildHeader(size, prefix, wraps)
            val plainDigest = MessageDigest.getInstance("SHA-256")
            val cipherDigest = MessageDigest.getInstance("SHA-256")

            plain.inputStream().buffered().use { input ->
                dest.outputStream().buffered().use { output ->
                    output.write(header)
                    cipherDigest.update(header)
                    encryptBlocks(input, output, dek, prefix, header, plainDigest, cipherDigest)
                }
            }

            val sealed = Sealed(
                file = dest,
                plainSha256 = hex(plainDigest.digest()),
                cipherSha256 = hex(cipherDigest.digest()),
                plainBytes = size,
                cipherBytes = dest.length(),
                recipients = wraps.map { it.first },
                elapsedMillis = System.currentTimeMillis() - started,
            )
            Log.d(
                TAG,
                "cifrado ${plain.name} → ${dest.name} (${size / 1024} KB, " +
                    "${sealed.elapsedMillis} ms, para ${sealed.recipients})"
            )

            if (DELETE_PLAINTEXT && !plain.delete()) {
                Log.w(TAG, "no se pudo borrar el claro ${plain.name}")
            }
            sealed
        } catch (e: Exception) {
            Log.e(TAG, "fallo cifrando ${plain.name}: ${e.message}", e)
            dest.delete()
            null
        } finally {
            Arrays.fill(dek, 0)
        }
    }

    /**
     * Descifra [enc] sobre [dest]. Devuelve false si ningún destinatario local puede
     * abrirlo —el caso normal cuando la DEK solo va envuelta para el servidor— o si
     * la autenticación falla en algún bloque, que es lo que delata manipulación.
     */
    fun open(enc: File, dest: File): Boolean {
        val header = readHeader(enc) ?: return false
        val dek = EvidenceKeys.unwrap(header.wraps) ?: return false

        return try {
            val expected = header.length + header.plainSize + TAG_BYTES * header.blocks
            if (enc.length() != expected) {
                // Con la cabecera autenticada esto solo pasa si el fichero llegó
                // incompleto; conviene decirlo antes que fallar bloque a bloque.
                Log.e(TAG, "${enc.name}: tamaño ${enc.length()}, esperado $expected")
                return false
            }

            enc.inputStream().buffered().use { input ->
                var skipped = 0L
                while (skipped < header.length) skipped += input.skip(header.length - skipped)
                dest.outputStream().buffered().use { output ->
                    decryptBlocks(input, output, dek, header)
                }
            }
            Log.d(TAG, "descifrado ${enc.name} → ${dest.name}")
            true
        } catch (e: Exception) {
            // AEADBadTagException entra por aquí: el contenido no es el que se cifró.
            Log.e(TAG, "fallo descifrando ${enc.name}: ${e.message}", e)
            dest.delete()
            false
        } finally {
            Arrays.fill(dek, 0)
        }
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(CHUNK)
        file.inputStream().buffered().use { input ->
            var n = input.read(buf)
            while (n != -1) {
                md.update(buf, 0, n)
                n = input.read(buf)
            }
        }
        return hex(md.digest())
    }

    // ── Bloques ───────────────────────────────────────────────────────────────

    private fun encryptBlocks(
        input: InputStream,
        output: OutputStream,
        dek: ByteArray,
        prefix: ByteArray,
        header: ByteArray,
        plainDigest: MessageDigest,
        cipherDigest: MessageDigest,
    ) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val key = SecretKeySpec(dek, "AES")
        val buf = ByteArray(CHUNK)
        val nonce = prefix.copyOf(prefix.size + 4)
        val aad = header.copyOf(header.size + 4)
        var index = 0

        var n = input.read(buf)
        while (n != -1) {
            putIndex(nonce, prefix.size, index)
            putIndex(aad, header.size, index)
            // init por bloque, y no un solo init: GCM prohíbe reutilizar el par
            // (clave, nonce), así que cada bloque es un mensaje nuevo.
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad)
            val block = cipher.doFinal(buf, 0, n)

            output.write(block)
            plainDigest.update(buf, 0, n)
            cipherDigest.update(block)
            index++
            n = input.read(buf)
        }
    }

    private fun decryptBlocks(
        input: InputStream,
        output: OutputStream,
        dek: ByteArray,
        header: Header,
    ) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val key = SecretKeySpec(dek, "AES")
        val buf = ByteArray(header.chunk + TAG_BYTES)
        val nonce = header.noncePrefix.copyOf(header.noncePrefix.size + 4)
        val aad = header.bytes.copyOf(header.bytes.size + 4)
        var remaining = header.plainSize
        var index = 0

        while (remaining > 0) {
            val plainLen = minOf(header.chunk.toLong(), remaining).toInt()
            val blockLen = plainLen + TAG_BYTES
            readFully(input, buf, blockLen)

            putIndex(nonce, header.noncePrefix.size, index)
            putIndex(aad, header.bytes.size, index)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad)
            output.write(cipher.doFinal(buf, 0, blockLen))

            remaining -= plainLen
            index++
        }
    }

    // ── Cabecera ──────────────────────────────────────────────────────────────

    /** Cabecera ya parseada. [bytes] son los bytes crudos, que son el AAD. */
    data class Header(
        val bytes: ByteArray,
        val chunk: Int,
        val plainSize: Long,
        val noncePrefix: ByteArray,
        val wraps: List<Pair<String, ByteArray>>,
    ) {
        val length: Long get() = bytes.size.toLong()
        val blocks: Long get() = (plainSize + chunk - 1) / chunk

        // data class con ByteArray: equals/hashCode por contenido, o dos cabeceras
        // idénticas se compararían distintas.
        override fun equals(other: Any?): Boolean =
            this === other || (other is Header && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    private fun buildHeader(
        plainSize: Long,
        prefix: ByteArray,
        wraps: List<Pair<String, ByteArray>>,
    ): ByteArray {
        var len = FIXED_HEADER
        wraps.forEach { (id, blob) -> len += 1 + id.toByteArray(Charsets.US_ASCII).size + 2 + blob.size }
        require(len <= 0xFFFF) { "cabecera de $len bytes, no cabe en header_len" }

        val out = ByteArray(len)
        MAGIC.copyInto(out, 0)
        out[4] = VERSION.toByte()
        out[5] = 0 // flags
        putShort(out, 6, len)
        putInt(out, 8, CHUNK)
        putLong(out, 12, plainSize)
        prefix.copyInto(out, 20)
        out[28] = wraps.size.toByte()

        var at = FIXED_HEADER
        wraps.forEach { (id, blob) ->
            val idBytes = id.toByteArray(Charsets.US_ASCII)
            out[at++] = idBytes.size.toByte()
            idBytes.copyInto(out, at); at += idBytes.size
            putShort(out, at, blob.size); at += 2
            blob.copyInto(out, at); at += blob.size
        }
        return out
    }

    /** Lee y valida la cabecera. Útil también para inspeccionar sin descifrar. */
    fun readHeader(enc: File): Header? = try {
        DataInputStream(enc.inputStream().buffered()).use { input ->
            val fixed = ByteArray(FIXED_HEADER)
            input.readFully(fixed)
            require(fixed.copyOf(4).contentEquals(MAGIC)) { "no es un fichero FEVD" }
            require(fixed[4].toInt() == VERSION) { "versión ${fixed[4].toInt()} no soportada" }

            val len = readShort(fixed, 6)
            require(len >= FIXED_HEADER) { "header_len $len inválido" }
            val rest = ByteArray(len - FIXED_HEADER)
            input.readFully(rest)
            val bytes = fixed + rest

            var at = 0
            val wraps = ArrayList<Pair<String, ByteArray>>(fixed[28].toInt())
            repeat(fixed[28].toInt()) {
                val idLen = rest[at++].toInt() and 0xFF
                val id = String(rest, at, idLen, Charsets.US_ASCII); at += idLen
                val blobLen = readShort(rest, at); at += 2
                wraps.add(id to rest.copyOfRange(at, at + blobLen)); at += blobLen
            }

            Header(
                bytes = bytes,
                chunk = readInt(bytes, 8),
                plainSize = readLong(bytes, 12),
                noncePrefix = bytes.copyOfRange(20, 20 + PREFIX_BYTES),
                wraps = wraps,
            )
        }
    } catch (e: Exception) {
        Log.e(TAG, "cabecera ilegible en ${enc.name}: ${e.message}")
        null
    }

    // ── Utilidades ────────────────────────────────────────────────────────────

    private fun putIndex(dst: ByteArray, at: Int, value: Int) = putInt(dst, at, value)

    private fun putShort(dst: ByteArray, at: Int, v: Int) {
        dst[at] = (v ushr 8).toByte(); dst[at + 1] = v.toByte()
    }

    private fun putInt(dst: ByteArray, at: Int, v: Int) {
        dst[at] = (v ushr 24).toByte(); dst[at + 1] = (v ushr 16).toByte()
        dst[at + 2] = (v ushr 8).toByte(); dst[at + 3] = v.toByte()
    }

    private fun putLong(dst: ByteArray, at: Int, v: Long) {
        for (i in 0 until 8) dst[at + i] = (v ushr (56 - 8 * i)).toByte()
    }

    private fun readShort(src: ByteArray, at: Int): Int =
        ((src[at].toInt() and 0xFF) shl 8) or (src[at + 1].toInt() and 0xFF)

    private fun readInt(src: ByteArray, at: Int): Int {
        var v = 0
        for (i in 0 until 4) v = (v shl 8) or (src[at + i].toInt() and 0xFF)
        return v
    }

    private fun readLong(src: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (src[at + i].toLong() and 0xFF)
        return v
    }

    /** read() puede devolver menos de lo pedido; un bloque parcial no descifra. */
    private fun readFully(input: InputStream, buf: ByteArray, len: Int) {
        var read = 0
        while (read < len) {
            val n = input.read(buf, read, len - read)
            if (n == -1) throw IllegalStateException("fichero truncado")
            read += n
        }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
