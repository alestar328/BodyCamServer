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
 *     telefono -> bodycam   AUTH_HELLO:<version>:<deviceId>:<nonceA>[:<efimeraA>]
 *     bodycam  -> telefono  AUTH_ID:<bwcId>:<nonceB>:<certificado>[:<efimeraB>]
 *     telefono -> bodycam   AUTH_PROOF:<firma>:<certificado>
 *     bodycam  -> telefono  AUTH_OK:<firma>          o  AUTH_FAIL:<motivo>
 *
 * **Version 2 (2026-09-28): el canal queda cifrado.** Las dos lineas del saludo
 * llevan una clave publica ECDH efimera, las dos entran en lo que se firma, y tras
 * `AUTH_OK` todo lo demas va como `S:<base64>` (ver [CanalCifrado]). La version 1,
 * sin las claves efimeras, se sigue aceptando mientras haya telefonos con la app
 * anterior: autentica pero no cifra, como hasta ahora.
 *
 * Este fichero es la contraparte de `EmparejamientoBodycam.kt` en Aeria Nexus, y
 * el protocolo esta probado alli con los dos extremos hablando entre si, incluidas
 * las pruebas de reflexion y de reutilizacion de firma. **Cualquier cambio aqui
 * hay que hacerlo tambien alli**: la transcripcion que se firma tiene que ser la
 * misma byte a byte.
 */
object ProtocoloEmparejamiento {

    /**
     * Van firmadas para que nadie pueda negociar a la baja cambiando el saludo: si
     * alguien en medio cambia un v2 por un v1, el telefono firma "v2", nosotros
     * comprobariamos "v1", y la prueba no cuadra.
     */
    const val VERSION_1 = "AERIA-BWC-1"
    const val VERSION_2 = "AERIA-BWC-2"

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
     *    cuenta lo que se va a firmar y precalcularlo;
     *  - **las dos claves efimeras** (solo v2), para que nadie en medio pueda
     *    cambiarlas por las suyas: seria autenticar a los dos extremos y aun asi
     *    leer todo lo que se dicen.
     *
     * En v1 las claves efimeras son null y la transcripcion sale byte a byte igual
     * que antes de la v2.
     */
    fun transcripcion(
        version: String,
        rol: Rol,
        deviceId: String,
        bwcId: String,
        nonceDelTelefono: ByteArray,
        nonceDeLaBodycam: ByteArray,
        efimeraDelTelefono: String? = null,
        efimeraDeLaBodycam: String? = null,
    ): ByteArray {
        val codificador = Base64.getEncoder()
        return (listOf(
            version,
            rol.name,
            deviceId,
            bwcId,
            codificador.encodeToString(nonceDelTelefono),
            codificador.encodeToString(nonceDeLaBodycam),
        ) + listOfNotNull(efimeraDelTelefono, efimeraDeLaBodycam)).joinToString("|").toByteArray(Charsets.UTF_8)
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
    private var version = ProtocoloEmparejamiento.VERSION_1

    /** Solo v2: la efimera del telefono tal y como viajo, y nuestro par. */
    private var efimeraDelTelefono: String? = null
    private var efimeraPropia: java.security.KeyPair? = null

    /**
     * El canal cifrado acordado, en cuanto el telefono se acredita por v2.
     * [BtServerService] lo activa justo DESPUES de escribir `AUTH_OK` en claro: el
     * telefono no tiene las claves hasta haber comprobado esa linea.
     */
    var canalAcordado: CanalCifrado? = null
        private set

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
        val esV1 = partes.size == 4 && partes[1] == ProtocoloEmparejamiento.VERSION_1
        val esV2 = partes.size == 5 && partes[1] == ProtocoloEmparejamiento.VERSION_2
        if (!esV1 && !esV2) {
            return "AUTH_FAIL:version de protocolo no soportada\n"
        }
        val certificado = BodycamIdentity.certificado()
            ?: return "AUTH_FAIL:esta bodycam no esta dada de alta (workflow 13)\n"

        version = partes[1]
        deviceId = partes[2]
        nonceDelTelefono = decodificar(partes[3]) ?: return "AUTH_FAIL:nonce ilegible\n"
        if (esV2) {
            AcuerdoDeClaves.leer(partes[4]) ?: return "AUTH_FAIL:clave efimera ilegible\n"
            efimeraDelTelefono = partes[4]
            efimeraPropia = AcuerdoDeClaves.parEfimero()
        } else {
            Log.w(TAG, "telefono con emparejamiento v1: se autentica pero el canal NO se cifra")
        }

        return (listOf(
            "AUTH_ID",
            BodycamIdentity.bwcId(context),
            Base64.getEncoder().encodeToString(nonce),
            Base64.getEncoder().encodeToString(certificado.encoded),
        ) + listOfNotNull(efimeraPropia?.let { AcuerdoDeClaves.codificar(it.public) })).joinToString(":") + "\n"
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

        val efimeraNuestra = efimeraPropia?.let { AcuerdoDeClaves.codificar(it.public) }
        val delTelefono = ProtocoloEmparejamiento.transcripcion(
            version = version,
            rol = ProtocoloEmparejamiento.Rol.TELEFONO,
            deviceId = identificador,
            bwcId = BodycamIdentity.bwcId(context),
            nonceDelTelefono = nonceA,
            nonceDeLaBodycam = nonce,
            efimeraDelTelefono = efimeraDelTelefono,
            efimeraDeLaBodycam = efimeraNuestra,
        )
        if (!verificar(firma, delTelefono, certificadoDelTelefono)) {
            Log.w(TAG, "el telefono no pudo demostrar su clave")
            return "AUTH_FAIL:el telefono no demostro su identidad\n"
        }

        telefonoAutenticado = identificador
        this.certificadoDelTelefono = certificadoDelTelefono
        Log.i(TAG, "telefono $identificador autenticado")

        val nuestra = ProtocoloEmparejamiento.transcripcion(
            version = version,
            rol = ProtocoloEmparejamiento.Rol.BODYCAM,
            deviceId = identificador,
            bwcId = BodycamIdentity.bwcId(context),
            nonceDelTelefono = nonceA,
            nonceDeLaBodycam = nonce,
            efimeraDelTelefono = efimeraDelTelefono,
            efimeraDeLaBodycam = efimeraNuestra,
        )
        val par = efimeraPropia
        val suya = efimeraDelTelefono?.let(AcuerdoDeClaves::leer)
        if (par != null && suya != null) {
            canalAcordado = CanalCifrado.derivar(
                secreto = AcuerdoDeClaves.secreto(par.private, suya),
                nonceDelTelefono = nonceA,
                nonceDeLaBodycam = nonce,
                soyTelefono = false,
            )
            // La privada efimera ya no hace falta: sin ella no se pueden rehacer
            // las claves de esta conexion.
            efimeraPropia = null
        }
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
