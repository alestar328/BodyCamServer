package com.falconone.bodycamserver

import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Acuerdo de claves efímero del emparejamiento v2 (workflow 31, paso 6).
 *
 * Las claves de identidad del teléfono y de la cámara son de FIRMA (`PURPOSE_SIGN`
 * en el Keystore) y no sirven para derivar un secreto. Por eso cada conexión crea
 * un par ECDH P-256 de usar y tirar, fuera del Keystore, y lo presenta dentro del
 * saludo. Las dos claves públicas efímeras entran en la transcripción que firma
 * cada extremo con su clave de identidad: sin eso, quien estuviera en medio podría
 * cambiarlas por las suyas y leerlo todo aunque la autenticación saliera bien.
 *
 * La privada efímera vive en memoria lo que dura el saludo y se olvida: que alguien
 * robe después la clave de identidad no descifra las conversaciones pasadas.
 *
 * **Este fichero es idéntico en BodyCamServer y en Aeria Nexus** (solo cambia el
 * `package`). Si se toca uno, se toca el otro.
 */
object AcuerdoDeClaves {

    private const val CURVA = "secp256r1"

    fun parEfimero(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec(CURVA))
        generateKeyPair()
    }

    fun codificar(publica: PublicKey): String = Base64.getEncoder().encodeToString(publica.encoded)

    /**
     * La pública del otro extremo, o null si no es una clave EC P-256 bien formada.
     * Se exige la misma curva: aceptar la que venga es abrir la puerta a curvas
     * débiles o a puntos que no están en la curva.
     */
    fun leer(base64: String): PublicKey? = runCatching {
        val clave = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(base64))) as ECPublicKey
        val nuestra = parEfimero().public as ECPublicKey
        clave.takeIf { it.params.curve == nuestra.params.curve && it.params.generator == nuestra.params.generator }
    }.getOrNull()

    fun secreto(propia: PrivateKey, ajena: PublicKey): ByteArray = KeyAgreement.getInstance("ECDH").run {
        init(propia)
        doPhase(ajena, true)
        generateSecret()
    }
}

/**
 * El canal cifrado de una conexión ya emparejada: AES-256-GCM línea a línea.
 *
 * Cada línea del protocolo de texto de siempre viaja como `S:<base64>`, con el
 * texto cifrado y la etiqueta de GCM. Tres decisiones:
 *
 *  - **Una clave por sentido**, derivadas del secreto ECDH con HKDF-SHA256. Así un
 *    mensaje de la cámara no se puede devolver a la cámara como si fuera del
 *    teléfono (reflexión), y cada clave tiene su propio contador.
 *  - **El IV es un contador implícito** que no viaja: 0, 1, 2… por sentido. Una
 *    línea repetida, quitada o reordenada no descifra, porque el receptor espera
 *    exactamente la siguiente. Eso para la repetición dentro de la conexión; entre
 *    conexiones ya lo para que las claves sean nuevas cada vez.
 *  - **Un fallo rompe el canal para siempre.** Una línea que no descifra es
 *    manipulación o desincronía, y en los dos casos lo sano es cortar y volver a
 *    emparejar, no seguir con un contador en el que ya no se puede confiar.
 */
class CanalCifrado private constructor(
    private val claveSalida: SecretKeySpec,
    private val claveEntrada: SecretKeySpec,
) {
    private var contadorSalida = 0L
    private var contadorEntrada = 0L

    /** Tras el primer fallo de descifrado no se descifra nada más. */
    @Volatile var roto = false
        private set

    /** Una línea de texto a `S:<base64>`, sin el salto de línea. */
    @Synchronized
    fun cifrar(linea: String): String {
        val cifrador = Cipher.getInstance(TRANSFORMACION)
        cifrador.init(Cipher.ENCRYPT_MODE, claveSalida, GCMParameterSpec(ETIQUETA_BITS, iv(contadorSalida++)))
        cifrador.updateAAD(AAD)
        return PREFIJO + Base64.getEncoder().encodeToString(cifrador.doFinal(linea.toByteArray(Charsets.UTF_8)))
    }

    /** El texto de una trama `S:…`, o null si no descifra (y el canal queda roto). */
    @Synchronized
    fun descifrar(trama: String): String? {
        if (roto || !trama.startsWith(PREFIJO)) return null
        return try {
            val cifrador = Cipher.getInstance(TRANSFORMACION)
            cifrador.init(Cipher.DECRYPT_MODE, claveEntrada, GCMParameterSpec(ETIQUETA_BITS, iv(contadorEntrada)))
            cifrador.updateAAD(AAD)
            val claro = cifrador.doFinal(Base64.getDecoder().decode(trama.substring(PREFIJO.length)))
            contadorEntrada++
            String(claro, Charsets.UTF_8)
        } catch (e: Exception) {
            roto = true
            null
        }
    }

    companion object {
        const val PREFIJO = "S:"

        private const val TRANSFORMACION = "AES/GCM/NoPadding"
        private const val ETIQUETA_BITS = 128
        private val AAD = "AERIA-BWC-2".toByteArray(Charsets.UTF_8)
        private const val INFO_TELEFONO_A_BODYCAM = "AERIA-BWC-2 TELEFONO->BODYCAM"
        private const val INFO_BODYCAM_A_TELEFONO = "AERIA-BWC-2 BODYCAM->TELEFONO"

        /**
         * Las dos claves de la conexión. La sal son los dos nonces del saludo, que
         * ya son únicos por conexión y firmados por los dos extremos.
         *
         * @param soyTelefono qué extremo llama: decide cuál de las dos es la de salida.
         */
        fun derivar(
            secreto: ByteArray,
            nonceDelTelefono: ByteArray,
            nonceDeLaBodycam: ByteArray,
            soyTelefono: Boolean,
        ): CanalCifrado {
            val prk = hmac(nonceDelTelefono + nonceDeLaBodycam, secreto)
            val haciaBodycam = SecretKeySpec(expandir(prk, INFO_TELEFONO_A_BODYCAM), "AES")
            val haciaTelefono = SecretKeySpec(expandir(prk, INFO_BODYCAM_A_TELEFONO), "AES")
            return if (soyTelefono) CanalCifrado(haciaBodycam, haciaTelefono)
                   else CanalCifrado(haciaTelefono, haciaBodycam)
        }

        /** HKDF-Expand (RFC 5869) de un solo bloque: 32 bytes, una clave AES-256. */
        private fun expandir(prk: ByteArray, info: String): ByteArray =
            hmac(prk, info.toByteArray(Charsets.UTF_8) + byteArrayOf(1))

        private fun hmac(clave: ByteArray, datos: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(clave, "HmacSHA256"))
                doFinal(datos)
            }

        /** 12 bytes: cuatro a cero y el contador en los ocho últimos. */
        private fun iv(contador: Long): ByteArray =
            ByteBuffer.allocate(12).putInt(0).putLong(contador).array()
    }
}
