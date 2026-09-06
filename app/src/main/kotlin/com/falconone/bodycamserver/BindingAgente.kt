package com.falconone.bodycamserver

import android.content.Context
import android.util.Log
import java.io.ByteArrayInputStream
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import org.json.JSONObject

private const val TAG = "BindingAgente"

/**
 * A quien sirve esta camara ahora mismo (workflows 33 y 34).
 *
 * LO QUE NO ES, que es lo importante. Esto **no le da a la camara la identidad del
 * agente**. El documento de arquitectura lo prohibe: "a peripheral binding does not
 * copy the officer identity to the peripheral", y en la capa de evidencia "the
 * capture device signs as itself; user attribution comes through the validated
 * session and binding, not device impersonation of the officer".
 *
 * Lo que la camara guarda es una **declaracion firmada por el agente** que dice: yo,
 * cmendez.aeriaone.com, autorizo a esta camara a operar en mi nombre desde el
 * telefono DEV-xxxx hasta tal hora. La camara la conserva y la puede presentar, pero
 * no puede firmar como el agente porque nunca ha tenido su clave. Si se pierde la
 * camara, se revoca la camara; no hay que revocar al agente.
 *
 * TRES COSAS SE COMPROBAN antes de aceptarla, y las tres hacen falta:
 *
 *  1. que el certificado del agente lo emitio la CA de usuario de AeriaOne;
 *  2. que la firma cubre la declaracion entera;
 *  3. que la declaracion menciona a ESTA camara y **el nonce de ESTA sesion** de
 *     emparejamiento. Sin lo tercero, una atadura capturada ayer valdria hoy.
 */
class BindingAgente(private val context: Context) {

    /** Atadura en vigor, o null si la camara no esta al servicio de nadie. */
    @Volatile
    var enVigor: Declaracion? = null
        private set

    data class Declaracion(
        val bindingId: String,
        val userId: String,
        val deviceId: String,
        val caducaEn: Long,
    ) {
        fun caducada(ahora: Long = System.currentTimeMillis()): Boolean = ahora > caducaEn
    }

    /**
     * `BIND:<declaracion>:<firma>:<certificado del agente>` (workflow 33).
     *
     * [anclaDeUsuario] es la CA que emite certificados de agente. Es OTRA que la de
     * dispositivos con la que se valido la camara en el emparejamiento, y eso es
     * deliberado: el documento separa los dos dominios de confianza.
     */
    fun atender(linea: String, nonceDeLaSesion: ByteArray, anclaDeUsuario: X509Certificate?): String {
        val partes = linea.split(":")
        if (partes.size != 4) return "BIND_FAIL:declaracion mal formada\n"
        if (anclaDeUsuario == null) {
            return "BIND_FAIL:esta bodycam no tiene ancla de usuario instalada\n"
        }

        val bytesDeLaDeclaracion = decodificar(partes[1]) ?: return "BIND_FAIL:declaracion ilegible\n"
        val firma = decodificar(partes[2]) ?: return "BIND_FAIL:firma ilegible\n"
        val certificado = leerCertificado(partes[3]) ?: return "BIND_FAIL:certificado ilegible\n"

        if (!emitidoPor(certificado, anclaDeUsuario)) {
            return "BIND_FAIL:el certificado del agente no lo emitio AeriaOne\n"
        }
        if (!verificar(firma, bytesDeLaDeclaracion, certificado)) {
            return "BIND_FAIL:la firma no corresponde al agente\n"
        }

        val json = runCatching { JSONObject(String(bytesDeLaDeclaracion, Charsets.UTF_8)) }.getOrNull()
            ?: return "BIND_FAIL:declaracion no es JSON\n"

        // Que la atadura hable de ESTA camara y de ESTA sesion. Sin las dos
        // comprobaciones, una declaracion valida para otra camara u otro momento
        // se podria presentar aqui tal cual.
        if (json.optString("peripheral_id") != BodycamIdentity.bwcId(context)) {
            return "BIND_FAIL:la atadura no es para esta camara\n"
        }
        val nonceDeclarado = decodificar(json.optString("session_nonce"))
        if (nonceDeclarado == null || !nonceDeclarado.contentEquals(nonceDeLaSesion)) {
            return "BIND_FAIL:la atadura no corresponde a esta sesion\n"
        }

        val caduca = json.optLong("expires_at", 0L)
        if (caduca <= System.currentTimeMillis()) return "BIND_FAIL:la atadura ya venia caducada\n"

        // Que el nombre comun del certificado sea el agente que dice ser. Sin esto,
        // cualquier agente con certificado valido podria atar la camara a nombre
        // de otro.
        val declarado = json.optString("user_id")
        if (commonNameDe(certificado) != declarado) {
            return "BIND_FAIL:la atadura va a nombre de otro agente\n"
        }

        enVigor = Declaracion(
            bindingId = json.optString("binding_id"),
            userId = declarado,
            deviceId = json.optString("device_id"),
            caducaEn = caduca,
        )
        Log.i(TAG, "camara al servicio de $declarado hasta $caduca")
        return "BIND_OK:${BodycamIdentity.bwcId(context)}\n"
    }

    /**
     * `UNBIND:<declaracion>:<firma>` (workflow 34).
     *
     * Tambien va firmada: si cualquiera pudiera deshacerla, bastaria con acercarse
     * a la camara para dejar al agente sin atribucion en mitad de un incidente.
     */
    fun deshacer(linea: String, anclaDeUsuario: X509Certificate?, certificadoDelAgente: X509Certificate?): String {
        val activa = enVigor ?: return "UNBIND_OK\n"
        val partes = linea.split(":")
        if (partes.size != 3) return "UNBIND_FAIL:declaracion mal formada\n"
        if (anclaDeUsuario == null || certificadoDelAgente == null) {
            return "UNBIND_FAIL:no hay con que comprobar la firma\n"
        }

        val bytes = decodificar(partes[1]) ?: return "UNBIND_FAIL:declaracion ilegible\n"
        val firma = decodificar(partes[2]) ?: return "UNBIND_FAIL:firma ilegible\n"
        if (!verificar(firma, bytes, certificadoDelAgente)) {
            return "UNBIND_FAIL:la firma no corresponde al agente\n"
        }
        val json = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()
            ?: return "UNBIND_FAIL:declaracion no es JSON\n"
        if (json.optString("binding_id") != activa.bindingId) {
            return "UNBIND_FAIL:esa no es la atadura en vigor\n"
        }

        enVigor = null
        Log.i(TAG, "atadura deshecha: ${json.optString("reason")}")
        return "UNBIND_OK\n"
    }

    /**
     * La atadura muere sola cuando caduca, sin que nadie tenga que avisar.
     *
     * Se comprueba al leerla y no con un temporizador: un temporizador que no se
     * dispara —proceso dormido, reloj cambiado— dejaria una atadura viva de mas, y
     * aqui lo seguro es lo contrario.
     */
    fun vigente(): Declaracion? {
        val actual = enVigor ?: return null
        if (actual.caducada()) {
            Log.i(TAG, "la atadura con ${actual.userId} ha caducado")
            enVigor = null
            return null
        }
        return actual
    }

    private fun commonNameDe(certificado: X509Certificate): String? =
        certificado.subjectX500Principal.name
            .split(",")
            .map { it.trim() }
            .firstOrNull { it.startsWith("CN=") }
            ?.removePrefix("CN=")

    private fun emitidoPor(certificado: X509Certificate, ancla: X509Certificate): Boolean = try {
        certificado.verify(ancla.publicKey)
        certificado.checkValidity()
        true
    } catch (e: Exception) {
        false
    }

    private fun verificar(firma: ByteArray, datos: ByteArray, certificado: X509Certificate): Boolean = try {
        Signature.getInstance(Pkcs10.ALGORITMO_FIRMA).run {
            initVerify(certificado.publicKey)
            update(datos)
            verify(firma)
        }
    } catch (e: Exception) {
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
