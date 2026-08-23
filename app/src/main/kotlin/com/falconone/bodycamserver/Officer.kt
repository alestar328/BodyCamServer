package com.falconone.bodycamserver

/**
 * Identidad del oficial portador de la unidad.
 *
 * Aparece en el overlay de grabación (junto al contador) y en el
 * `manifest.json` de cada incidente — es el "Officer ID" que pide la feature
 * EVD-002 del Security Feature List (metadatos de la evidencia).
 */
data class Officer(
    val name: String,
    val rank: String,
    val badge: String,
)

/**
 * TODO: integrar con datos reales.
 *
 * Datos de demostración hardcodeados. Los reales llegarán cuando exista la
 * sesión autenticada del oficial (login en la app del móvil + asignación de
 * unidad, features ADM-001/ADM-002 y AUTH-001 del Security Feature List):
 * en ese momento este valor debe salir del emparejamiento, no de una constante.
 *
 * Único punto del código con estos datos: overlay y manifest leen de aquí, así
 * que la integración real es cambiar esta fuente y nada más.
 */
val HardcodedOfficer = Officer(
    name = "John Smith",
    rank = "Corporal",
    badge = "36975",
)
