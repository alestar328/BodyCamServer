package com.falconone.bodycamserver

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyFactory
import java.security.KeyStore
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

private const val TAG = "FalconKeys"

/**
 * Custodia de la clave de evidencia.
 *
 * Cada fichero se cifra con una DEK propia y aleatoria (ver [EvidenceCrypto]); lo
 * que se decide aquí es **quién puede recuperar esa DEK**. Un [KeyWrapper] es un
 * destinatario: envuelve la DEK con su propia clave y guarda el resultado en la
 * cabecera del .fev.
 *
 * La lista de destinatarios es la costura que deja abierta la decisión de custodia
 * pendiente con el manager. Añadir o quitar destinatarios no cambia ni un byte del
 * formato de los bloques cifrados:
 *
 *   solo servidor  → opción B de Seguridad-Claves-Bodycam.md §3.5 (nadie descifra
 *                    en la unidad, ni siquiera quien la tenga en la mano)
 *   ambos          → opción C, híbrido: el servidor descifra y la unidad además
 *                    puede reproducir lo que grabó
 *
 * Ver docs/CRYPTO-FORMAT.md §3.
 */
interface KeyWrapper {

    /** Identificador ASCII que se graba en la cabecera. Dice quién puede abrir. */
    val id: String

    fun wrap(dek: ByteArray): ByteArray

    /** DEK en claro, o null si este destinatario no es capaz de abrir el blob. */
    fun unwrap(blob: ByteArray): ByteArray?
}

object EvidenceKeys {

    /**
     * Destinatarios de la DEK, en orden de preferencia al descifrar.
     *
     * Hoy solo está el Keystore del dispositivo: la pública de Nexus todavía no
     * existe (decisión 1 del plan semanal). En cuanto llegue, [NexusKeyWrapper]
     * entra solo y el formato no se entera.
     */
    fun recipients(): List<KeyWrapper> =
        listOfNotNull(DeviceKeyWrapper.takeIf { it.available() }, NexusKeyWrapper.fromApk())

    /** El primero de [recipients] capaz de abrir uno de los envoltorios del fichero. */
    fun unwrap(wraps: List<Pair<String, ByteArray>>): ByteArray? {
        for (r in recipients()) {
            val blob = wraps.firstOrNull { it.first == r.id }?.second ?: continue
            r.unwrap(blob)?.let { return it }
        }
        Log.e(
            TAG,
            "ningún destinatario local puede abrir este fichero: " +
                wraps.joinToString { it.first }
        )
        return null
    }
}

/**
 * Envuelve la DEK con una clave AES-256 de AndroidKeyStore.
 *
 * La clave se genera en el dispositivo y **no sale de él**: no hay ningún secreto
 * en el APK que filtrar. La contrapartida está en la doc: si la unidad se rompe o
 * se reinstala el sistema, lo cifrado solo con este destinatario es irrecuperable.
 * Por eso el destino natural es acompañarlo del envoltorio del servidor.
 *
 * blob = IV(12) ‖ ciphertext ‖ tag(16).
 *
 * No se pide autenticación de usuario (setUserAuthenticationRequired): la unidad
 * cifra sin nadie delante, justo al terminar de grabar.
 */
object DeviceKeyWrapper : KeyWrapper {

    private const val ALIAS = "falcon_evidence_v1"
    private const val STORE = "AndroidKeyStore"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    override val id: String = "ks:$ALIAS"

    fun available(): Boolean = runCatching { key() != null }.getOrDefault(false)

    override fun wrap(dek: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, requireNotNull(key()) { "sin clave de Keystore" })
        val sealed = cipher.doFinal(dek)
        return cipher.iv + sealed
    }

    override fun unwrap(blob: ByteArray): ByteArray? = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            requireNotNull(key()) { "sin clave de Keystore" },
            GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES),
        )
        cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    } catch (e: Exception) {
        // Esperable si el fichero viene de otra unidad, o si la clave se perdió
        // al reinstalar el sistema. No es un fallo del formato.
        Log.w(TAG, "no se pudo abrir el envoltorio de Keystore: ${e.message}")
        null
    }

    /** Crea la clave la primera vez y la reutiliza siempre. */
    @Synchronized
    private fun key(): SecretKey? = try {
        val store = KeyStore.getInstance(STORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey) ?: generate()
    } catch (e: Exception) {
        Log.e(TAG, "Keystore no disponible: ${e.message}")
        null
    }

    private fun generate(): SecretKey {
        Log.d(TAG, "generando la clave de evidencia en Keystore ($ALIAS)")
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return gen.generateKey()
    }
}

/**
 * Envuelve la DEK con la **clave pública** de Nexus (RSA-OAEP/SHA-256), de forma que
 * solo el servidor pueda abrirla. Es la opción recomendada en
 * Seguridad-Claves-Bodycam.md §3.5: una clave pública embebida en el APK no es una
 * filtración, es su sitio natural, y elimina de raíz el problema de los secretos en
 * el APK en vez de mitigarlo.
 *
 * ── Hoy va con una clave de DESARROLLO ────────────────────────────────────────
 *
 * La pública real de Nexus sigue pendiente (decisión 1 del plan de la semana 24-30
 * ago): la genera ciberseguridad en su infraestructura y nos entrega solo la
 * pública, porque quien tenga la privada puede descifrar toda la evidencia y eso
 * no puede acabar en manos del proveedor de software.
 *
 * Mientras tanto, y siguiendo lo que pidió backend —stubs en vez de bloqueos—, aquí
 * hay un par RSA-2048 de usar y tirar cuyo `kid` empieza por `dev-`. Con él la DEK
 * viaja también envuelta para "el servidor", que es lo que permite subir el `.fev`
 * y que el destinatario lo abra con `tools/falcon_evidence_decrypt.py`. La privada
 * está en `tools/dev-keys/`, fuera del control de versiones.
 *
 * **El `kid` es la salvaguarda:** cualquier cosa cifrada para `dev-*` es material
 * de pruebas por definición. Sustituir la clave el día que llegue la real es pegar
 * dos constantes aquí; no toca ni el formato ni [EvidenceCrypto].
 */
class NexusKeyWrapper private constructor(
    private val kid: String,
    private val spki: ByteArray,
) : KeyWrapper {

    override val id: String = "srv:$kid"

    override fun wrap(dek: ByteArray): ByteArray {
        val pub = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(spki))
        val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
        // Explícito y no "OAEPWithSHA-256AndMGF1Padding": ese nombre deja MGF1 en
        // SHA-1 en varias versiones de Android, y entonces el servidor no descifra.
        cipher.init(
            Cipher.ENCRYPT_MODE,
            pub,
            OAEPParameterSpec(
                "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT
            ),
        )
        return cipher.doFinal(dek)
    }

    /** La privada vive en el servidor: la unidad no puede deshacer este envoltorio. */
    override fun unwrap(blob: ByteArray): ByteArray? = null

    companion object {
        /** El prefijo `dev-` marca que esto NO es la clave de producción. */
        private const val KEY_ID = "dev-2026-08"

        private const val PUBLIC_KEY_B64 =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEApfQAeE9GxRa0424gB/zK7OB32ssLrslezEx3" +
            "ewMxOHPgKo7nPYvXGxcq9Wh2e3vfNyo1QkNpyVGF1J1cKieBEVEwZIpJT8INFQotwmTGzVOjqogRb9r4" +
            "giRW1AugkrA2rDNkRR/s5/770PuvrxDE/2F7PcMMQlBL/Dl+Tl6/l1zYb51QMvJ89w/eku7TAe7aD4Cx" +
            "9/lOaG0IQKqRd/zeuG0hubLDJC8ND9ARy8a47K9/Vgnl3uhrlE01HU37muzhbOCyMB9dxlZ6fLjTtfKG" +
            "VctsxIyD62iOYlQJ6XJJfbLv4HsxNZTz1iwnB11FXgKnbps/NXAdOPfYz5pyo7wSuQIDAQAB"

        fun fromApk(): NexusKeyWrapper? {
            if (PUBLIC_KEY_B64.isBlank() || KEY_ID.isBlank()) return null
            return try {
                NexusKeyWrapper(KEY_ID, Base64.getDecoder().decode(PUBLIC_KEY_B64))
            } catch (e: Exception) {
                Log.e(TAG, "la pública de Nexus no es un SPKI válido: ${e.message}")
                null
            }
        }
    }
}
