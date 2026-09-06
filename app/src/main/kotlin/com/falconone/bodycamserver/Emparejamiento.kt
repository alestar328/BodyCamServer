package com.falconone.bodycamserver

import android.content.Context
import android.util.Log
import java.io.ByteArrayInputStream
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

private const val TAG = "Emparejamiento"

/**
 * Lado de la BODYCAM del emparejamiento autenticado (workflow 31).
 *
 * QUE ARREGLA. El canal RFCOMM va sin cifrado de enlace y con un UUID fijo que
 * esta publicado en nuestra propia documentacion: hasta ahora **cualquiera que
 * conociera ese UUID podia mandarle REC_STOP a la camara**, y la camara no tenia
 * forma de saber que no era el telefono del agente. Al reves igual: el telefono
 * se conectaba a la primera MAC que respondiera y se fiaba.
 *
 * El intercambio son cuatro lineas sobre el mismo canal de texto de siempre:
 *
 *     telefono -> bodycam   AUTH_HELLO:<version>:<deviceId>:<nonceA>
 *     bodycam  -> telefono  AUTH_ID:<bwcId>:<nonceB>:<certificado>
 *     telefono -> bodycam   AUTH_PROOF:<firma>:<certificado>
 *     bodycam  -> telefono  AUTH_OK:<firma>          o  AUTH_FAIL:<motivo>
 *
 * Este fichero es la contraparte de `EmparejamientoBodycam.kt` en Aeria Nexus, y
 * el protocolo esta probado alli con los dos extremos hablando entre si, incluidas
 * las pruebas de reflexion y de reutilizacion de firma. **Cualquier cambio aqui
 * hay que hacerlo tambien alli**: la transcripcion que se firma tiene que ser la
 * misma byte a byte.
 */
object ProtocoloEmparejamiento {

    /** Va firmada para que nadie pueda negociar a la baja cambiando el saludo. */
    const val VERSION = "AERIA-BWC-1"

    const val ALGORITMO_FIRMA = Pkcs10.ALGORITMO_FIRMA
    const val NONCE_BYTES = 32

    enum class Rol { TELEFONO, BODYCAM }

    /**
     * Lo que firma cada extremo. Cada trozo esta por un motivo concreto:
     *
     *  - **la version**, para que no se pueda negociar a la baja;
     *  - **el rol**, para que la firma del telefono no se pueda devolver tal cual
     *    haciendola pasar por la nuestra (ataque de reflexion);
     *  - **los dos identificadores**, para que una prueba valida entre otro
     *    telefono y otra camara no sirva aqui;
     *  - **los dos nonces**, para que ninguno de los dos pueda decidir por su
     *    cuenta lo que se va a firmar y precalcularlo.
     */
    fun transcripcion(
        rol: Rol,
        deviceId: String,
        bwcId: String,
        nonceDelTelefono: ByteArray,
        nonceDeLaBodycam: ByteArray,
    ): ByteArray {
        val codificador = Base64.getEncoder()
        return listOf(
            VERSION,
            rol.name,
            deviceId,
            bwcId,
            codificador.encodeToString(nonceDelTelefono),
            codificador.encodeToString(nonceDeLaBodycam),
        ).joinToString("|").toByteArray(Charsets.UTF_8)
    }

    fun nonce(): ByteArray = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
}

/**
 * Estado del intercambio para UNA conexion.
 *
 * Se crea con cada cliente y muere con el: un nonce vale para una conexion y solo
 * una, que es lo que impide reutilizar una respuesta capturada.
 */
class EmparejamientoDeLaBodycam(private val context: Context) {

    /** Publico porque la atadura del workflow 33 se firma sobre el. */
    val nonce = ProtocoloEmparejamiento.nonce()
    private var nonceDelTelefono: ByteArray? = null
    private var deviceId: String? = null

    /** Quien esta al otro lado, una vez acreditado. Null mientras no lo este. */
    var telefonoAutenticado: String? = null
        private set

    /** Certificado del telefono, guardado para poder comprobar el desatado. */
    var certificadoDelTelefono: X509Certificate? = null
        private set

    /**
     * Responde a `AUTH_HELLO` con nuestra identidad y nuestro nonce.
     *
     * Si la camara no esta dada de alta (workflow 13) no hay certificado que
     * presentar, y se dice: es mejor que el telefono sepa que no puede
     * comprobarnos a que se quede esperando.
     */
    fun responderASaludo(linea: String): String {
        val partes = linea.split(":")
        if (partes.size != 4 || partes[1] != ProtocoloEmparejamiento.VERSION) {
            return "AUTH_FAIL:version de protocolo no soportada\n"
        }
        val certificado = BodycamIdentity.certificado()
            ?: return "AUTH_FAIL:esta bodycam no esta dada de alta (workflow 13)\n"

        deviceId = partes[2]
        nonceDelTelefono = decodificar(partes[3]) ?: return "AUTH_FAIL:nonce ilegible\n"

        return listOf(
            "AUTH_ID",
            BodycamIdentity.bwcId(context),
            Base64.getEncoder().encodeToString(nonce),
            Base64.getEncoder().encodeToString(certificado.encoded),
        ).joinToString(":") + "\n"
    }

    /**
     * Comprueba la prueba del telefono y devuelve la nuestra.
     *
     * La camara valida al telefono con el mismo rigor con el que el telefono la
     * valida a ella: esto es autenticacion MUTUA, no un login del telefono contra
     * la camara. Si el telefono no se acredita, no firmamos nada para el.
     */
    fun responderAPrueba(linea: String, anclaDeConfianza: X509Certificate?): String {
        val partes = linea.split(":")
        if (partes.size != 3) return "AUTH_FAIL:prueba ilegible\n"

        val nonceA = nonceDelTelefono ?: return "AUTH_FAIL:no hubo saludo previo\n"
        val identificador = deviceId ?: return "AUTH_FAIL:no hubo saludo previo\n"
        val firma = decodificar(partes[1]) ?: return "AUTH_FAIL:firma ilegible\n"
        val certificadoDelTelefono = leerCertificado(partes[2])
            ?: return "AUTH_FAIL:certificado del telefono ilegible\n"

        if (anclaDeConfianza == null) {
            return "AUTH_FAIL:esta bodycam no tiene ancla de confianza instalada\n"
        }
        if (!emitidoPor(certificadoDelTelefono, anclaDeConfianza)) {
            Log.w(TAG, "certificado de telefono no emitido por la CA de AeriaOne")
            return "AUTH_FAIL:el certificado del telefono no lo emitio AeriaOne\n"
        }

        val delTelefono = ProtocoloEmparejamiento.transcripcion(
            rol = ProtocoloEmparejamiento.Rol.TELEFONO,
            deviceId = identificador,
            bwcId = BodycamIdentity.bwcId(context),
            nonceDelTelefono = nonceA,
            nonceDeLaBodycam = nonce,
        )
        if (!verificar(firma, delTelefono, certificadoDelTelefono)) {
            Log.w(TAG, "el telefono no pudo demostrar su clave")
            return "AUTH_FAIL:el telefono no demostro su identidad\n"
        }

        telefonoAutenticado = identificador
        this.certificadoDelTelefono = certificadoDelTelefono
        Log.i(TAG, "telefono $identificador autenticado")

        val nuestra = ProtocoloEmparejamiento.transcripcion(
            rol = ProtocoloEmparejamiento.Rol.BODYCAM,
            deviceId = identificador,
            bwcId = BodycamIdentity.bwcId(context),
            nonceDelTelefono = nonceA,
            nonceDeLaBodycam = nonce,
        )
        return "AUTH_OK:" + Base64.getEncoder().encodeToString(BodycamIdentity.firmar(nuestra)) + "\n"
    }

    /**
     * Que el ancla emitio ese certificado y que sigue en vigor. Se comprueba la
     * firma, no el nombre: comparar emisores por cadena de texto es lo que
     * convierte una validacion en un adorno.
     */
    private fun emitidoPor(certificado: X509Certificate, ancla: X509Certificate): Boolean = try {
        certificado.verify(ancla.publicKey)
        certificado.checkValidity()
        true
    } catch (e: Exception) {
        false
    }

    private fun verificar(
        firma: ByteArray,
        datos: ByteArray,
        certificado: X509Certificate,
    ): Boolean = try {
        Signature.getInstance(ProtocoloEmparejamiento.ALGORITMO_FIRMA).run {
            initVerify(certificado.publicKey)
            update(datos)
            verify(firma)
        }
    } catch (e: Exception) {
        // Una firma de otra clave no solo devuelve false: ECDSA puede rechazar el
        // propio DER de la firma con una excepcion.
        false
    }

    private fun leerCertificado(base64: String): X509Certificate? = try {
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(base64)))
            as X509Certificate
    } catch (e: Exception) {
        null
    }

    private fun decodificar(texto: String): ByteArray? =
        runCatching { Base64.getDecoder().decode(texto) }.getOrNull()
}
