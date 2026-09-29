package com.falconone.bodycamserver

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.security.auth.x500.X500Principal

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
    private const val PRIMER_UID_AGORA = 10_000
    private const val MAYOR_SUFIJO = 0xFFFF
    private const val CARPETA = "identity"
    private const val FICHERO_CSR = "bwc.csr.pem"
    private const val FICHERO_ANCLA = "ca.pem"
    private const val FICHERO_ANCLA_USUARIO = "user-ca.pem"

    // Seriales que traen aparatos sin serial propio: derivar de ellos daria el mismo
    // BWC a todas las unidades.
    private val SERIALES_GENERICOS = setOf("UNKNOWN", "0123456789ABCDEF", "0123456789")

    private val keystore: KeyStore
        get() = KeyStore.getInstance(PROVEEDOR).apply { load(null) }

    fun existe(): Boolean = runCatching { keystore.containsAlias(ALIAS) }.getOrDefault(false)

    // Ya derivado del serial en este proceso: no hace falta volver a leerlo.
    @Volatile private var bwcDelSerial: String? = null

    /**
     * Identificador de la camara: `BWC-` + las 4 primeras cifras hex del SHA-256
     * del serial del aparato (`ro.serialno`). Decidido el 2026-09-29.
     *
     * Hasta entonces era aleatorio y vivia en las preferencias: desinstalar la app
     * o borrar sus datos cambiaba el id y dejaba la credencial del IAM huerfana.
     * Derivado del serial, una reinstalacion vuelve al mismo BWC. Lo que NO arregla
     * es el choque entre dos unidades (sigue siendo de 16 bits, ~0,7 % con 30):
     * eso lo detecta el alta (409) y `tools/alta-bodycam-iam.sh` antes de mandar
     * nada. Alargarlo cambiaria el uid de Agora en las dos apps.
     *
     * Leer el serial en Android 9 pide READ_PHONE_STATE: el device owner se lo da
     * solo ([DeviceOwner.aplicarPoliticas]) y el guion de alta lo concede por adb.
     * Sin el permiso se sigue con el id guardado, o uno aleatorio si no hay.
     *
     * Un id ya guardado solo se sustituye por el del serial si la unidad no tiene
     * aun un certificado emitido a su nombre: cambiarlo despues del alta dejaria
     * la credencial del backend apuntando a un id que ya no existe.
     */
    fun bwcId(context: Context): String {
        bwcDelSerial?.let { return it }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val guardado = prefs.getString(CLAVE_BWC_ID, null)
        val delSerial = idDesdeSerial()

        if (delSerial == null) {
            guardado?.let { return it }
            Log.w(TAG, "Sin serial legible: identidad aleatoria hasta que lo haya")
            val sufijo = ByteArray(2).also { SecureRandom().nextBytes(it) }
                .joinToString("") { "%02X".format(it) }
            return "BWC-$sufijo".also { prefs.edit().putString(CLAVE_BWC_ID, it).apply() }
        }

        if (guardado != null && guardado != delSerial) {
            if (tieneCertificadoEmitido()) {
                Log.w(TAG, "El serial da $delSerial, pero ya hay certificado para $guardado: se mantiene")
                return guardado
            }
            Log.i(TAG, "Identidad $guardado sustituida por la del serial: $delSerial")
        }
        if (guardado != delSerial) prefs.edit().putString(CLAVE_BWC_ID, delSerial).apply()
        bwcDelSerial = delSerial
        return delSerial
    }

    /** `BWC-XXXX` sacado del serial, o null si no se puede leer o es el generico. */
    @SuppressLint("MissingPermission", "HardwareIds")
    private fun idDesdeSerial(): String? {
        val serial = runCatching { Build.getSerial() }.getOrNull()?.trim()?.uppercase()
        if (serial.isNullOrEmpty() || serial in SERIALES_GENERICOS || serial.all { it == serial[0] }) {
            return null
        }
        val resumen = MessageDigest.getInstance("SHA-256").digest(serial.toByteArray(Charsets.US_ASCII))
        return "BWC-" + "%02X%02X".format(resumen[0], resumen[1])
    }

    /**
     * El certificado del Keystore lo emitio una CA de verdad: ni es el autofirmado
     * ni viene de la CA de pruebas (`OU=Test`, la de tools/alta-bodycam.sh del
     * movil). Un certificado de pruebas no deja credencial en el backend, asi que
     * no hay nada que proteger: la W1 del 7-sep lo tenia y se quedaba sin migrar.
     */
    private fun tieneCertificadoEmitido(): Boolean {
        val cert = certificado() ?: return false
        if (cert.issuerX500Principal == cert.subjectX500Principal) return false
        val emisor = cert.issuerX500Principal.getName(X500Principal.RFC2253)
        return emisor.split(',').none { it.trim().equals("OU=Test", ignoreCase = true) }
    }

    /**
     * Numero con el que la unidad entra en el canal de Agora, sacado de su
     * identidad: BWC-896E entra como 10000 + 0x896E = 45182.
     *
     * Antes todas las unidades entraban como 9001. Con dos a la vez en el canal
     * Agora echa a una, y el telefono no podia distinguir su bodycam de la de otro
     * agente: silenciaba el SOS ajeno creyendo que era el propio.
     *
     * No es un segundo identificador: si las identidades son distintas, los numeros
     * tambien. El rango 10000-75535 queda por debajo del grabador en la nube
     * (90000-99999), y los telefonos empiezan en 100000. Si el registro de AeriaOne
     * cambia el formato del identificador, solo hay que tocar esta funcion.
     */
    fun uidAgora(context: Context): Int {
        val identificador = bwcId(context)
        val sufijo = identificador.removePrefix("BWC-")
        val valorHex = sufijo.toIntOrNull(16)?.takeIf { it <= MAYOR_SUFIJO }
        if (valorHex != null) return PRIMER_UID_AGORA + valorHex

        // Un formato que no sea de cuatro cifras hexadecimales ya no garantiza que
        // no choque con otra unidad: se reparte por el rango y se deja constancia.
        Log.e(TAG, "Identificador con formato inesperado: $identificador")
        return PRIMER_UID_AGORA + Math.floorMod(sufijo.hashCode(), MAYOR_SUFIJO + 1)
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
