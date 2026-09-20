package com.falconone.bodycamserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Arranca el servidor automáticamente cuando la bodycam enciende
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Red de seguridad del apagado seco: si la unidad se quedó sin batería o
            // la mataron sin darle tiempo a ApagadoReceiver, los nodos sysfs siguen
            // encendidos desde antes. La unidad arranca siempre a oscuras.
            HardwareController.apagarTodo()
            context.startForegroundService(Intent(context, BtServerService::class.java))
            // Un corte de red o un apagado deja subidas a medias. Aquí es donde se
            // retoman: UploadSessions recuerda el offset, esto vuelve a intentarlo.
            UploadService.resumePending(context)
        }
    }
}
