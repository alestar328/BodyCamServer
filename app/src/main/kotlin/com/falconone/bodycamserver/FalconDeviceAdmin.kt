package com.falconone.bodycamserver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receptor de administración del dispositivo.
 *
 * Android exige que el rol de *device owner* apunte a un [DeviceAdminReceiver]
 * declarado en el manifest: es el componente que se le pasa a `dpm set-device-owner`
 * y el que recibe después cada llamada de [android.app.admin.DevicePolicyManager].
 * Sin esta clase no hay kiosco.
 *
 * No lleva lógica. Todas las políticas viven en [DeviceOwner], que es a donde hay
 * que ir a mirar; aquí solo se registran en el log las transiciones, porque son
 * justo las que hay que poder reconstruir cuando una unidad aparece sin el rol.
 */
class FalconDeviceAdmin : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(DeviceOwner.TAG, "Administración activada — propietario: ${DeviceOwner.esPropietario(context)}")
    }

    /**
     * Solo llega si alguien retira el rol. Con `DISALLOW_FACTORY_RESET` puesto, las
     * vías que quedan son un borrado desde recovery (que se lleva la app entera y
     * no deja rastro en este log) o nuestra propia renuncia — ver
     * [DeviceOwner.renunciar].
     */
    override fun onDisabled(context: Context, intent: Intent) {
        Log.w(DeviceOwner.TAG, "Administración DESACTIVADA: la unidad ya no está en kiosco")
    }
}
