package com.falconone.bodycamserver

import android.content.Context
import android.util.Log

private const val TAG = "FalconTone"

/**
 * Volumen del tono que suena en la unidad al entrar en SOS (2026-09-29).
 *
 * Es configurable por decisión del usuario: el pitido confirma al agente que el
 * SOS ha salido, pero también puede delatarle delante de quien tiene al lado. Lo
 * elige el agente en su perfil del móvil, que lo manda con `SOS_TONE:<valor>` en
 * cada enlace autenticado y cada vez que lo cambia. Se guarda aquí para que valga
 * también con el teléfono lejos.
 *
 * Por defecto LOW: suena, pero bajo.
 */
object TonoSos {

    enum class Nivel(val amplitud: Double) { OFF(0.0), LOW(0.10), HIGH(0.35) }

    private const val PREFS = "tono_sos"
    private const val CLAVE = "nivel"

    fun nivel(context: Context): Nivel {
        val guardado = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CLAVE, null)
        return guardado?.let { runCatching { Nivel.valueOf(it) }.getOrNull() } ?: Nivel.LOW
    }

    /** Devuelve el nivel aplicado, o null si el valor no es OFF, LOW ni HIGH. */
    fun fijar(context: Context, valor: String): Nivel? {
        val nivel = runCatching { Nivel.valueOf(valor.trim().uppercase()) }.getOrNull() ?: return null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(CLAVE, nivel.name).apply()
        Log.d(TAG, "Tono de SOS: $nivel")
        return nivel
    }

    /** Devuelve los ms que tarda en sonar (0 con OFF). */
    fun sonar(context: Context): Long {
        val nivel = nivel(context)
        return if (nivel == Nivel.OFF) 0 else PttTones.sosActivado(nivel.amplitud)
    }
}
