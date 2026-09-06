package com.falconone.bodycamserver

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Base64

private const val TAG = "BodycamIdentity"

/**
 * Identidad criptografica propia de la bodycam (workflow 13 del catalogo IAM de
 * AeriaOne).
 *
 * POR QUE LA CAMARA NECESITA IDENTIDAD PROPIA. El documento de arquitectura lo
 * pone en sus reglas de no equivalencia: "a peripheral binding does not copy the
 * officer identity to the peripheral". La camara no es el agente y no es el
 * telefono; es un tercer sujeto de confianza que se revoca por su lado. Si la
 * evidencia solo pudiera decir "grabado por el telefono DEV-xxxx", perder una
 * camara obligaria a retirar el terminal, y la cadena de custodia no podria
 * distinguir que aparato capturo cada pieza.
 *
 * COMO. Par EC P-256 generado DENTRO del Keystore de Android, no exportable, con
 * atestacion cuando el hardware la soporta. Exactamente el mismo mecanismo que el
 * alta del telefono (workflow 12), del que este fichero es el equivalente para la
 * W1: `Der` y `Pkcs10` son los mismos, copiados de Aeria Nexus.
 *
 * LO QUE ESTA W1 NO PUEDE DAR, y hay que decirlo por escrito (decision D4 del plan
 * IAM): el alta de fabrica del catalogo (workflow 11) presupone arranque seguro y
 * retirada de las credenciales de fabrica, y esta unidad lleva el launcher del
 * proveedor como aplicacion privilegiada. Lo que se acredita aqui es que **esta
 * instalacion de BodyCamServer** posee una clave que no sale del Keystore. Es un
 * periferico de garantia reducida, y llamarlo de otra forma seria mentir.
 */
object BodycamIdentity {

    private const val PROVEEDOR = "AndroidKeyStore"
    private const val ALIAS = "aeria.bwc.key"
    private const val CURVA = "secp256r1"
    private const val PREFS = "aeria_bwc_identity"
    private const val CLAVE_BWC_ID = "bwc_id"
    private const val CARPETA = "identity"
    private const val FICHERO_CSR = "bwc.csr.pem"
    private const val FICHERO_ANCLA = "ca.pem"
    private const val FICHERO_ANCLA_USUARIO = "user-ca.pem"

    private val keystore: KeyStore
        get() = KeyStore.getInstance(PROVEEDOR).apply { load(null) }

    fun existe(): Boolean = runCatching { keystore.containsAlias(ALIAS) }.getOrDefault(false)

    /**
     * Identificador de la camara. Lo emite el registro de dispositivos de
     * AeriaOne; que se lo invente esta funcion es exactamente lo que el backend
     * vendra a corregir, igual que en el alta del telefono.
     */
    fun bwcId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(CLAVE_BWC_ID, null)?.let { return it }
        val sufijo = ByteArray(2).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02X".format(it) }
        return "BWC-$sufijo".also { prefs.edit().putString(CLAVE_BWC_ID, it).apply() }
    }

    /**
     * Genera el par si no existe. Se intenta con atestacion y se cae a sin ella:
     * en una W1 la clave de atestacion del fabricante puede no estar, y quedarse
     * sin clave por eso seria peor que quedarse sin atestacion.
     */
    fun generarPar(retoDeAtestacion: ByteArray?) {
        if (existe()) return
        val conAtestacion = retoDeAtestacion != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
        runCatching { generar(retoDeAtestacion.takeIf { conAtestacion }) }
            .onFailure {
                Log.w(TAG, "atestacion no disponible, se genera sin ella: ${it.message}")
                generar(null)
            }
    }

    private fun generar(reto: ByteArray?) {
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec(CURVA))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .apply { if (reto != null) setAttestationChallenge(reto) }
            .build()
        java.security.KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVEEDOR)
            .apply { initialize(spec) }
            .generateKeyPair()
        Log.i(TAG, "par de claves de la bodycam creado en el Keystore")
    }

    /** Peticion de certificado a nombre de esta camara. */
    fun crearCsr(context: Context, tenant: String = "QPD"): String {
        val publica = keystore.getCertificate(ALIAS)?.publicKey ?: error("No hay clave de la bodycam")
        val privada = manejadorDeClavePrivada() ?: error("No hay clave de la bodycam")
        val der = Pkcs10.crear(
            sujeto = SujetoCsr(commonName = bwcId(context), organizationalUnit = tenant),
            publicKey = publica,
            privateKey = privada,
        )
        return Pkcs10.aPem(der)
    }

    /** Deja el CSR donde `adb run-as` pueda recogerlo, igual que hace el telefono. */
    fun guardarCsr(context: Context, pem: String): File =
        File(context.filesDir, CARPETA).apply { mkdirs() }
            .resolve(FICHERO_CSR)
            .apply { writeText(pem) }

    /**
     * Instala el certificado que emitio la CA de dispositivos, comprobando antes
     * que corresponde a nuestra clave: instalar el de otro par dejaria el Keystore
     * en un estado incoherente que solo se descubriria al fallar una firma.
     */
    fun instalarCertificado(pemDeLaCadena: String): X509Certificate {
        val cadena = leerPem(pemDeLaCadena)
        require(cadena.isNotEmpty()) { "No se encontro ningun certificado en el PEM" }
        val hoja = cadena.first()
        val publica = keystore.getCertificate(ALIAS)?.publicKey ?: error("No hay clave de la bodycam")
        require(hoja.publicKey.encoded.contentEquals(publica.encoded)) {
            "El certificado no corresponde a la clave de esta bodycam"
        }
        val privada = manejadorDeClavePrivada() ?: error("No hay clave de la bodycam")
        keystore.setKeyEntry(ALIAS, privada, null, cadena.toTypedArray())
        Log.i(TAG, "certificado de la bodycam instalado: ${hoja.subjectX500Principal}")
        return hoja
    }

    /** Certificado emitido, o null si la camara aun no esta dada de alta. */
    fun certificado(): X509Certificate? = keystore.getCertificate(ALIAS) as? X509Certificate

    /**
     * Ancla con la que la camara valida al telefono que se conecta.
     *
     * En el modelo real la reparte el backend con el resto de la politica
     * (workflow 65). Aqui es un fichero que se instala en el alta, y por eso vive
     * junto al CSR: sin ancla, la camara no puede comprobar a nadie y lo dice en
     * vez de aceptar a cualquiera.
     */
    fun anclaDeConfianza(context: Context): X509Certificate? {
        val fichero = File(context.filesDir, CARPETA).resolve(FICHERO_ANCLA)
        if (!fichero.isFile) return null
        return runCatching { leerPem(fichero.readText()).firstOrNull() }.getOrNull()
    }

    /**
     * Ancla con la que la camara valida al AGENTE que la ata a su nombre
     * (workflow 33). Es otra distinta de la de dispositivos, y no por descuido: el
     * documento separa "User Identity CA" de "Device Identity CA" y pide perfiles
     * y politicas propios para cada una. Con una sola, revocar un agente y revocar
     * un terminal serian la misma operacion.
     */
    fun anclaDeUsuario(context: Context): X509Certificate? {
        val fichero = File(context.filesDir, CARPETA).resolve(FICHERO_ANCLA_USUARIO)
        if (!fichero.isFile) return null
        return runCatching { leerPem(fichero.readText()).firstOrNull() }.getOrNull()
    }

    fun instalarAnclaDeUsuario(context: Context, pem: String): X509Certificate {
        val ancla = leerPem(pem).firstOrNull() ?: error("El PEM del ancla de usuario no trae certificado")
        File(context.filesDir, CARPETA).apply { mkdirs() }
            .resolve(FICHERO_ANCLA_USUARIO)
            .writeText(pem)
        Log.i(TAG, "ancla de usuario instalada: ${ancla.subjectX500Principal}")
        return ancla
    }

    fun instalarAncla(context: Context, pem: String): X509Certificate {
        val ancla = leerPem(pem).firstOrNull() ?: error("El PEM del ancla no trae certificado")
        File(context.filesDir, CARPETA).apply { mkdirs() }
            .resolve(FICHERO_ANCLA)
            .writeText(pem)
        Log.i(TAG, "ancla de confianza instalada: ${ancla.subjectX500Principal}")
        return ancla
    }

    /** Firma con la clave privada, que no sale del Keystore. */
    fun firmar(datos: ByteArray): ByteArray {
        val privada = manejadorDeClavePrivada() ?: error("No hay clave de la bodycam")
        return Signature.getInstance(Pkcs10.ALGORITMO_FIRMA).run {
            initSign(privada)
            update(datos)
            sign()
        }
    }

    private fun manejadorDeClavePrivada(): PrivateKey? = keystore.getKey(ALIAS, null) as? PrivateKey

    private fun leerPem(texto: String): List<X509Certificate> {
        val fabrica = CertificateFactory.getInstance("X.509")
        return BLOQUE.findAll(texto).map { coincidencia ->
            val base64 = coincidencia.groupValues[1].filterNot { it.isWhitespace() }
            fabrica.generateCertificate(
                ByteArrayInputStream(Base64.getDecoder().decode(base64)),
            ) as X509Certificate
        }.toList()
    }

    private val BLOQUE = Regex(
        "-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----",
        RegexOption.DOT_MATCHES_ALL,
    )
}
