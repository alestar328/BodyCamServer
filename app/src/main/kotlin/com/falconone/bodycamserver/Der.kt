package com.falconone.bodycamserver

/**
 * Codificador DER minimo, solo con lo que necesita un PKCS#10.
 *
 * Se escribe a mano en vez de usar BouncyCastle (decision D3 del plan IAM). El
 * motivo es de peso del APK: BouncyCastle son varios megas y aqui hacen falta
 * seis tipos de dato. Lo que se codifica cabe en esta clase, y lo que se lee
 * (certificados, cadenas) ya lo sabe leer la plataforma.
 *
 * DER es "tipo, longitud, valor" repetido: cada elemento es un byte de etiqueta,
 * la longitud de su contenido y el contenido, que a su vez puede ser una lista de
 * elementos. No hay mas.
 *
 * Regla de oro al tocar esto: **cualquier cambio se valida contra `openssl`**, no
 * contra la propia app. Un DER mal formado suele seguir pareciendo valido desde
 * dentro y solo lo rechaza la CA, que es el peor sitio para enterarse.
 */
object Der {

    private const val ETIQUETA_ENTERO = 0x02
    private const val ETIQUETA_BIT_STRING = 0x03
    private const val ETIQUETA_OID = 0x06
    private const val ETIQUETA_UTF8 = 0x0C
    private const val ETIQUETA_SECUENCIA = 0x30
    private const val ETIQUETA_CONJUNTO = 0x31

    /** Entero no negativo. Es todo lo que aparece en un CSR (la version, un 0). */
    fun entero(valor: Int): ByteArray {
        require(valor >= 0) { "Solo se codifican enteros no negativos" }
        val bytes = ArrayDeque<Byte>()
        var resto = valor
        do {
            bytes.addFirst((resto and 0xFF).toByte())
            resto = resto ushr 8
        } while (resto != 0)
        // En DER el entero lleva signo. Si el primer bit esta a 1 se leeria como
        // negativo, asi que se antepone un cero.
        if (bytes.first().toInt() and 0x80 != 0) bytes.addFirst(0)
        return tlv(ETIQUETA_ENTERO, bytes.toByteArray())
    }

    /**
     * Cadena de bits. El primer byte del contenido dice cuantos bits sobran en el
     * ultimo byte; aqui siempre se codifican bytes enteros, asi que es 0.
     */
    fun bitString(datos: ByteArray): ByteArray =
        tlv(ETIQUETA_BIT_STRING, byteArrayOf(0) + datos)

    /**
     * Identificador de objeto a partir de su notacion con puntos, p. ej. "2.5.4.3".
     *
     * Los dos primeros numeros se empaquetan en un solo byte (a * 40 + b) y el
     * resto van en base 128 con el bit alto marcado en todos menos el ultimo.
     */
    fun oid(notacionConPuntos: String): ByteArray {
        val partes = notacionConPuntos.split(".").map { it.toInt() }
        require(partes.size >= 2) { "Un OID tiene al menos dos componentes" }
        val contenido = mutableListOf<Byte>()
        contenido.add((partes[0] * 40 + partes[1]).toByte())
        partes.drop(2).forEach { contenido.addAll(base128(it)) }
        return tlv(ETIQUETA_OID, contenido.toByteArray())
    }

    fun utf8(texto: String): ByteArray = tlv(ETIQUETA_UTF8, texto.toByteArray(Charsets.UTF_8))

    fun secuencia(vararg elementos: ByteArray): ByteArray = tlv(ETIQUETA_SECUENCIA, unir(elementos))

    fun conjunto(vararg elementos: ByteArray): ByteArray = tlv(ETIQUETA_CONJUNTO, unir(elementos))

    /**
     * Etiqueta de contexto implicita `[n]`, la que usa el campo de atributos del
     * CSR. Sin elementos queda `A0 00`, que es un conjunto vacio bien formado.
     */
    fun contextoImplicito(numero: Int, vararg elementos: ByteArray): ByteArray =
        tlv(0xA0 or numero, unir(elementos))

    /** Etiqueta + longitud + contenido: la unidad de la que se compone todo DER. */
    fun tlv(etiqueta: Int, contenido: ByteArray): ByteArray =
        byteArrayOf(etiqueta.toByte()) + longitud(contenido.size) + contenido

    /**
     * Longitud en forma corta (un byte) hasta 127, y en forma larga por encima:
     * un byte que dice cuantos bytes de longitud vienen, y luego la longitud.
     */
    private fun longitud(bytes: Int): ByteArray {
        if (bytes < 0x80) return byteArrayOf(bytes.toByte())
        val cifras = ArrayDeque<Byte>()
        var resto = bytes
        while (resto > 0) {
            cifras.addFirst((resto and 0xFF).toByte())
            resto = resto ushr 8
        }
        return byteArrayOf((0x80 or cifras.size).toByte()) + cifras.toByteArray()
    }

    private fun base128(valor: Int): List<Byte> {
        if (valor == 0) return listOf(0)
        val grupos = ArrayDeque<Byte>()
        var resto = valor
        while (resto > 0) {
            grupos.addFirst((resto and 0x7F).toByte())
            resto = resto ushr 7
        }
        // Todos menos el ultimo llevan el bit alto marcado: dice "sigo".
        return grupos.mapIndexed { indice, byte ->
            if (indice < grupos.size - 1) (byte.toInt() or 0x80).toByte() else byte
        }
    }

    private fun unir(partes: Array<out ByteArray>): ByteArray {
        val salida = ByteArray(partes.sumOf { it.size })
        var posicion = 0
        partes.forEach { parte ->
            parte.copyInto(salida, posicion)
            posicion += parte.size
        }
        return salida
    }
}
