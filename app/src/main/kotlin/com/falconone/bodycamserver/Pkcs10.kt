package com.falconone.bodycamserver

import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.util.Base64

/** Nombre distinguido del sujeto del CSR (workflow 12, paso 11). */
data class SujetoCsr(
    /** AeriaOne BYOD Device ID: lo que identifica al terminal. */
    val commonName: String,
    /** Organizacion emisora del servicio. */
    val organization: String = "AeriaOne",
    /** Tenant, p. ej. QPD. */
    val organizationalUnit: String,
)

/**
 * Peticion de certificado PKCS#10 (RFC 2986) escrita a mano.
 *
 * La estructura entera es esta:
 *
 *     CertificationRequest ::= SEQUENCE {
 *         certificationRequestInfo  CertificationRequestInfo,
 *         signatureAlgorithm        AlgorithmIdentifier,
 *         signature                 BIT STRING }
 *
 *     CertificationRequestInfo ::= SEQUENCE {
 *         version        INTEGER (0),
 *         subject        Name,
 *         subjectPKInfo  SubjectPublicKeyInfo,
 *         attributes     [0] IMPLICIT SET OF Attribute }
 *
 * Dos cosas salen gratis y conviene saber por que:
 *
 *  - `subjectPKInfo` es exactamente lo que devuelve `PublicKey.getEncoded()`, que
 *    ya viene en formato X.509 SubjectPublicKeyInfo codificado en DER.
 *  - la firma ECDSA que produce `Signature` ya es la SEQUENCE de r y s en DER,
 *    asi que entra tal cual dentro del BIT STRING.
 *
 * Lo que se firma es el `certificationRequestInfo` completo, tal y como se
 * codifico: por eso se guarda ese bloque de bytes y se reutiliza, en vez de
 * volver a construirlo. Recodificarlo y firmar el resultado es la forma clasica
 * de que la CA rechace la peticion por una firma que no cuadra.
 */
object Pkcs10 {

    /** id-at-commonName. */
    private const val OID_CN = "2.5.4.3"
    /** id-at-organizationName. */
    private const val OID_O = "2.5.4.10"
    /** id-at-organizationalUnitName. */
    private const val OID_OU = "2.5.4.11"

    /** ecdsa-with-SHA256. Para ECDSA los parametros van AUSENTES (RFC 5758 §3.2). */
    private const val OID_ECDSA_SHA256 = "1.2.840.10045.4.3.2"

    const val ALGORITMO_FIRMA = "SHA256withECDSA"

    /**
     * Construye y firma la peticion. Devuelve el DER completo.
     *
     * [privateKey] puede ser una clave de Android Keystore: no se lee su material,
     * solo se usa para firmar, que es justo el punto de que no sea exportable.
     */
    fun crear(sujeto: SujetoCsr, publicKey: PublicKey, privateKey: PrivateKey): ByteArray {
        val informacion = Der.secuencia(
            Der.entero(0),
            nombre(sujeto),
            publicKey.encoded,
            // Sin atributos. Todo lo que el backend necesita saber del terminal va
            // por el canal de alta (workflow 12, paso 12), no metido en el CSR.
            Der.contextoImplicito(0),
        )

        val firma = Signature.getInstance(ALGORITMO_FIRMA).run {
            initSign(privateKey)
            update(informacion)
            sign()
        }

        return Der.secuencia(
            informacion,
            Der.secuencia(Der.oid(OID_ECDSA_SHA256)),
            Der.bitString(firma),
        )
    }

    /** El mismo DER en PEM, que es como lo comen `openssl` y la mayoria de CA. */
    fun aPem(der: ByteArray): String = buildString {
        appendLine("-----BEGIN CERTIFICATE REQUEST-----")
        Base64.getEncoder().encodeToString(der).chunked(64).forEach { appendLine(it) }
        appendLine("-----END CERTIFICATE REQUEST-----")
    }

    /**
     * Name ::= RDNSequence, y cada RDN es un CONJUNTO con un solo par tipo-valor.
     * El orden importa: es el que vera impreso quien revise el certificado.
     */
    private fun nombre(sujeto: SujetoCsr): ByteArray = Der.secuencia(
        parNombre(OID_O, sujeto.organization),
        parNombre(OID_OU, sujeto.organizationalUnit),
        parNombre(OID_CN, sujeto.commonName),
    )

    private fun parNombre(oid: String, valor: String): ByteArray =
        Der.conjunto(Der.secuencia(Der.oid(oid), Der.utf8(valor)))
}
