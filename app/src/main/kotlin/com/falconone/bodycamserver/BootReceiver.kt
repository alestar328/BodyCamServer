package com.falconone.bodycamserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Arranca el servidor automáticamente cuando la bodycam enciende
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            HardwareController.irOff()  // ensure IR is off after reboot
            context.startForegroundService(Intent(context, BtServerService::class.java))
        }
    }
}
