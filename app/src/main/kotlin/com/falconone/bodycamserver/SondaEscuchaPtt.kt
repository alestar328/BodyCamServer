package com.falconone.bodycamserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Process
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

private const val TAG = "SondaPtt"

/**
 * Sonda de medicion, SOLO en debug: cuanto cuesta que la unidad escuche el canal.
 *
 * Nacio el 2026-09-14 con su propio motor de Agora para medir la escucha antes de
 * construirla. Desde el 2026-09-15 la escucha vive en [LivestreamService] y la sonda
 * solo la saca y la vuelve a meter, porque Agora admite un motor por proceso.
 *
 * Se maneja por broadcast desde adb (ver [registrar]). No toca nada con SOS o PTT
 * abiertos: sacar la unidad del canal los cortaria.
 */
object SondaEscuchaPtt {

    private const val ACCION = "com.falconone.bodycamserver.SONDA_PTT"
    private const val MUESTRA_SEGUNDOS = 10L
    private const val FASE_BATERIA_SEGUNDOS = 600

    private val hilo = Executors.newSingleThreadExecutor()
    @Volatile private var midiendo = false

    /**
     * adb shell am broadcast -a com.falconone.bodycamserver.SONDA_PTT --es orden <orden>
     *   escuchar  vuelve a meter la unidad en el canal
     *   parar     saca la unidad del canal
     *   bateria   10 min sin escuchar y 10 min escuchando, con muestras cada 10 s
     */
    fun registrar(context: Context) {
        val receptor = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.getStringExtra("orden")) {
                    "escuchar" -> hilo.execute { LivestreamService.escuchar(ctx.applicationContext) }
                    "parar" -> hilo.execute { parar() }
                    "bateria" -> Thread { medirBateria(ctx.applicationContext) }.start()
                    else -> Log.w(TAG, "Orden desconocida")
                }
            }
        }
        context.registerReceiver(receptor, IntentFilter(ACCION))
        Log.i(TAG, "Sonda lista")
    }

    private fun parar() {
        if (LivestreamService.isStreaming || LivestreamService.isMicEnabled) {
            Log.w(TAG, "SOS o PTT abiertos: la sonda no saca a la unidad del canal")
            return
        }
        LivestreamService.salirDelCanal()
    }

    private fun medirBateria(context: Context) {
        if (midiendo) return
        midiendo = true
        val fichero = File(context.getExternalFilesDir(null), "sonda_ptt_bateria.csv")
        fichero.writeText("epoch_ms,fase,nivel,corriente_ua,voltaje_uv,rx_bytes,tx_bytes,modo_audio\n")
        Log.i(TAG, "Midiendo bateria en ${fichero.absolutePath}")
        hilo.submit { parar() }.get()
        muestrear(context, fichero, "sin_escucha")
        hilo.submit { LivestreamService.escuchar(context) }.get()
        muestrear(context, fichero, "escuchando")
        Log.i(TAG, "Medicion de bateria terminada")
        midiendo = false
    }

    private fun muestrear(context: Context, fichero: File, fase: String) {
        val bateria = context.getSystemService(BatteryManager::class.java)
        val audio = context.getSystemService(AudioManager::class.java)
        val muestras = FASE_BATERIA_SEGUNDOS / MUESTRA_SEGUNDOS
        repeat(muestras.toInt()) {
            val linea = listOf(
                System.currentTimeMillis(),
                fase,
                bateria.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
                leerSys("current_now"),
                leerSys("voltage_now"),
                TrafficStats.getUidRxBytes(Process.myUid()),
                TrafficStats.getUidTxBytes(Process.myUid()),
                audio.mode,
            ).joinToString(",")
            fichero.appendText(linea + "\n")
            Thread.sleep(MUESTRA_SEGUNDOS * 1000)
        }
    }

    private fun leerSys(nombre: String): String =
        runCatching { File("/sys/class/power_supply/battery/$nombre").readText().trim() }
            .getOrDefault("")
}
