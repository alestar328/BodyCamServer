package com.falconone.bodycamserver

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/**
 * Identidad del oficial portador de la unidad.
 *
 * Aparece en el overlay de grabación (junto al contador) y en el
 * `manifest.json` de cada incidente — es el "Officer ID" que pide la feature
 * EVD-002 del Security Feature List (metadatos de la evidencia).
 *
 * [userId] es la identidad del agente en AeriaOne (`cmendez.aeriaone.com`); el
 * resto es lo que se rotula. Todo sale de la atadura firmada por el agente.
 */
data class Officer(
    val name: String,
    val rank: String,
    val badge: String,
    val userId: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("officer_name", name)
        .put("officer_rank", rank)
        .put("officer_badge", badge)
        .put("officer_user_id", userId ?: JSONObject.NULL)

    companion object {
        fun fromJson(json: JSONObject): Officer = Officer(
            name = json.optString("officer_name", SinAgente.name),
            rank = json.optString("officer_rank", SinAgente.rank),
            badge = json.optString("officer_badge", SinAgente.badge),
            userId = json.optString("officer_user_id").ifBlank { null },
        )
    }
}

/**
 * Lo que se rotula cuando la cámara no está atada a nadie.
 *
 * Sustituye al "John Smith" de demostración que había antes: una evidencia a
 * nombre de un agente inventado es peor que una que dice que no sabe de quién es.
 * La placa va también en el nombre de los ficheros, por eso no lleva espacios.
 */
val SinAgente = Officer(
    name = "UNASSIGNED",
    rank = "-",
    badge = "NOAGENT",
)

/**
 * El agente al que sirve la cámara en este momento (workflow 33).
 *
 * Lo pone BtServerService al aceptar una atadura y lo quita al deshacerla, al
 * caerse el enlace o al caducar. No es lo que se rotula en un incidente: eso se
 * congela al empezar a grabar (ver EvidenceStore.guardarOficial), porque una
 * grabación sigue viva aunque el Bluetooth se corte.
 */
object AgenteDeServicio {

    // Estado de Compose y no una variable suelta: el overlay se repinta solo cuando
    // el agente se ata o se suelta. Se escribe desde el hilo del Bluetooth, que el
    // sistema de snapshots admite.
    private var atado by mutableStateOf<Officer?>(null)
    @Volatile private var caducaEn = 0L

    /** [caducaEnMillis] es el de la atadura: pasado ese momento ya no sirve a nadie. */
    fun atar(oficial: Officer, caducaEnMillis: Long) {
        caducaEn = caducaEnMillis
        atado = oficial
    }

    fun soltar() {
        atado = null
    }

    /**
     * El agente atado, o [SinAgente]. La caducidad se mira al leer y no con un
     * temporizador, por lo mismo que en BindingAgente.vigente: un temporizador que no
     * salta dejaria rotulando a un agente de mas.
     */
    fun oficial(): Officer {
        val actual = atado ?: return SinAgente
        return if (System.currentTimeMillis() > caducaEn) SinAgente else actual
    }
}
