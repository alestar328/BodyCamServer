package com.falconone.bodycamserver

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import android.util.Log

/**
 * Modo kiosco: la unidad como aparato de un solo uso.
 *
 * La app pasa a ser **device owner** del aparato. No es root ni firmware propio:
 * es el mecanismo estándar de Android para flotas gestionadas, y en Android 9 da
 * casi todo lo que pide el cliente —que el agente no pueda salir de la app ni
 * llegar a los ajustes, y que las apps del fabricante no compitan con nosotros
 * por la cámara.
 *
 * ## Cómo se pone (y por qué no se puede poner solo)
 *
 * El rol solo se puede asignar sobre un aparato **sin ninguna cuenta configurada**,
 * y siempre desde fuera. Lo hace `tools/kiosco.sh` con `adb shell dpm`. Un
 * restablecimiento de fábrica se lo lleva por delante y hay que repetir el proceso
 * con el cable: no hay vía remota, porque el aprovisionamiento por QR o NFC exige
 * el asistente de bienvenida de Google y esta unidad no lo trae.
 *
 * ## Las dos reglas que no se tocan
 *
 * 1. **`DISALLOW_DEBUGGING_FEATURES` no se pone nunca.** Esa restricción apaga
 *    adb, y adb es la única vía de diagnóstico, de actualización de la app y de
 *    recuperación de esta unidad. Con el kiosco puesto y sin adb, una app que no
 *    arranque deja el aparato muerto y solo lo revive un borrado desde recovery.
 *    De hecho [aplicarPoliticas] hace lo contrario: **fuerza** `ADB_ENABLED`.
 * 2. **Siempre hay salida.** [renunciar] quita el rol sin borrar nada, y
 *    [soltarPantalla] deja la app sin anclar. Las dos se alcanzan por adb con
 *    `am start` (ver [atenderOrdenDeMantenimiento]), incluso con el kiosco puesto:
 *    el anclaje bloquea las apps de terceros, no los intents a la nuestra.
 *
 * ## Lo que NO se restringe, a propósito
 *
 *  - `DISALLOW_INSTALL_APPS`: bloquearía también `adb install`, que es como
 *    actualizamos la app en la unidad. La instalación queda cerrada de hecho
 *    porque en kiosco no hay forma de llegar a un instalador.
 *  - `DISALLOW_CONFIG_BLUETOOTH` / `_WIFI`: las dos conexiones son producto (el
 *    enlace con el teléfono y la subida de evidencia); se gobiernan desde nuestra
 *    pantalla, no capándolas a nivel de usuario.
 */
object DeviceOwner {

    const val TAG = "FalconOwner"

    private const val PREFS = "kiosco"
    private const val CLAVE_KIOSCO = "anclar_pantalla"

    /**
     * Apps del fabricante que se esconden al aplicar las políticas.
     *
     * Compiten con nosotros por el HAL de cámara y son la vía por la que un agente
     * acaba grabando fuera de la cadena de custodia. Esconder no es desinstalar
     * (`setApplicationHidden`): siguen en la partición de sistema y vuelven solas
     * si algún día se renuncia al rol.
     *
     * La lista es deliberadamente corta y explícita. Antes de ampliarla, mirar
     * `adb shell pm list packages -3` y `pm list packages | grep wiite` en la
     * unidad: esconder un paquete de sistema equivocado (teléfono, ajustes del
     * operador) deja la unidad sin llamadas y cuesta un reaprovisionamiento.
     */
    private val APPS_DEL_FABRICANTE = listOf(
        "com.wiite.camera",
    )

    // ── Estado ────────────────────────────────────────────────────────────────

    fun admin(context: Context) = ComponentName(context, FalconDeviceAdmin::class.java)

    private fun dpm(context: Context) =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    /** La app es device owner de este aparato. Todo lo de aquí depende de esto. */
    fun esPropietario(context: Context): Boolean =
        runCatching { dpm(context).isDeviceOwnerApp(context.packageName) }.getOrDefault(false)

    /**
     * El anclaje de pantalla está pedido.
     *
     * Se guarda aparte del rol porque son cosas distintas: una unidad puede ser
     * propietaria (con las apps del fabricante escondidas y los ajustes cerrados)
     * y estar sin anclar mientras se depura. Por defecto va puesto: si alguien se
     * ha molestado en aprovisionar la unidad, quiere el kiosco.
     */
    fun kioscoPedido(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(CLAVE_KIOSCO, true)

    private fun anotarKiosco(context: Context, pedido: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(CLAVE_KIOSCO, pedido).apply()
    }

    // ── Políticas ─────────────────────────────────────────────────────────────

    /**
     * Deja el aparato en el estado que queremos. Es idempotente y se llama en cada
     * arranque de [MainActivity]: así una unidad aprovisionada con una versión
     * vieja de la app recoge las políticas nuevas al actualizarse, sin volver a
     * pasar por el cable.
     *
     * Sin el rol no hace nada (y no es un error: las compilaciones de desarrollo
     * corren así a diario).
     */
    fun aplicarPoliticas(context: Context) {
        if (!esPropietario(context)) {
            Log.d(TAG, "Sin rol de propietario: la unidad va sin kiosco")
            return
        }
        val dpm = dpm(context)
        val admin = admin(context)
        val paquete = context.packageName

        // Anclaje: solo nuestra app puede entrar en lock task. Es el permiso, no
        // la acción — el anclaje lo pide la Activity con startLockTask().
        runCatching { dpm.setLockTaskPackages(admin, arrayOf(paquete)) }
            .onFailure { Log.e(TAG, "No se pudo autorizar el anclaje", it) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // GLOBAL_ACTIONS = el menú de mantener pulsado el botón de encendido.
            // Se deja a posta: es la única forma de apagar o reiniciar la unidad
            // en mano sin cable. Todo lo demás (inicio, recientes, notificaciones,
            // bloqueo de pantalla) se queda fuera.
            runCatching {
                dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS)
            }.onFailure { Log.e(TAG, "No se pudieron fijar las funciones del anclaje", it) }
        }

        // Barra de estado muerta: ni desplegable ni ajustes rápidos. En una
        // pantalla de 3 cm ya va escondida (ver goImmersive), pero esto cierra
        // también el deslizamiento que la saca un momento en modo STICKY.
        runCatching { dpm.setStatusBarDisabled(admin, true) }
            .onFailure { Log.e(TAG, "No se pudo desactivar la barra de estado", it) }

        // Somos el lanzador. Dos cosas a cambio: el botón de inicio vuelve aquí en
        // vez de sacar al agente a la pantalla del fabricante, y la app arranca
        // sola al encender — que es justo lo que BOOT_COMPLETED no consigue en
        // esta unidad (ver el comentario de MainActivity.onCreate, 2026-08-26).
        runCatching {
            dpm.addPersistentPreferredActivity(
                admin,
                IntentFilter(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addCategory(Intent.CATEGORY_DEFAULT)
                },
                ComponentName(context, MainActivity::class.java),
            )
        }.onFailure { Log.e(TAG, "No se pudo fijar la app como lanzador", it) }

        restricciones(dpm, admin)
        ajustesGlobales(dpm, admin)

        // El servicio de accesibilidad es como se capturan las teclas físicas con
        // la pantalla apagada, y hasta ahora había que activarlo a mano en
        // Ajustes → Accesibilidad. En kiosco no se llega a esa pantalla: aquí se
        // deja la lista de permitidos con la nuestra y nada más. Activarlo sigue
        // siendo cosa del aprovisionamiento (`tools/kiosco.sh`), porque ni
        // siquiera un device owner puede encender un servicio de accesibilidad.
        runCatching { dpm.setPermittedAccessibilityServices(admin, listOf(paquete)) }
            .onFailure { Log.e(TAG, "No se pudo restringir la accesibilidad", it) }

        esconderAppsDelFabricante(dpm, admin, context)

        Log.i(TAG, "Políticas aplicadas (anclaje pedido: ${kioscoPedido(context)})")
    }

    private fun restricciones(dpm: DevicePolicyManager, admin: ComponentName) {
        // OJO: DISALLOW_DEBUGGING_FEATURES no está y no debe estar. Ver la
        // cabecera de este fichero.
        val puestas = listOf(
            // El borrado desde los ajustes se lleva el rol y la evidencia sin subir.
            // No protege del borrado por recovery con los botones físicos: eso no
            // lo para ninguna política.
            UserManager.DISALLOW_FACTORY_RESET,
            // El modo seguro arranca sin apps de terceros: sería salir del kiosco
            // reiniciando.
            UserManager.DISALLOW_SAFE_BOOT,
            // La hora es parte de la evidencia: el rótulo quemado en el vídeo y el
            // manifiesto del incidente salen de ella. Solo la ajusta el técnico,
            // con ajustarHora().
            UserManager.DISALLOW_CONFIG_DATE_TIME,
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_CREATE_WINDOWS,
        )
        puestas.forEach { restriccion ->
            runCatching { dpm.addUserRestriction(admin, restriccion) }
                .onFailure { Log.e(TAG, "No se pudo aplicar $restriccion", it) }
        }
        // Por si una unidad quedó con ella puesta de una versión anterior de la app:
        // quitarla cuesta una línea y devuelve el acceso por adb.
        runCatching { dpm.clearUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES) }
    }

    private fun ajustesGlobales(dpm: DevicePolicyManager, admin: ComponentName) {
        // adb encendido por política: que un reinicio o un manotazo en ajustes no
        // nos deje fuera de la unidad.
        runCatching { dpm.setGlobalSetting(admin, Settings.Global.ADB_ENABLED, "1") }
            .onFailure { Log.e(TAG, "No se pudo forzar ADB_ENABLED", it) }
        // Con el cable puesto, la pantalla no se duerme: en banco se depura sin
        // estar despertándola, y en el cargador la unidad enseña su estado.
        // 7 = AC | USB | inalámbrico.
        runCatching { dpm.setGlobalSetting(admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, "7") }
            .onFailure { Log.e(TAG, "No se pudo fijar STAY_ON_WHILE_PLUGGED_IN", it) }
    }

    private fun esconderAppsDelFabricante(dpm: DevicePolicyManager, admin: ComponentName, context: Context) {
        APPS_DEL_FABRICANTE.forEach { paquete ->
            runCatching {
                // No se esconde lo que no está: en una unidad de otro lote el
                // paquete puede no existir, y eso no es un fallo.
                context.packageManager.getPackageInfo(paquete, 0)
                val hecho = dpm.setApplicationHidden(admin, paquete, true)
                Log.i(TAG, "Escondida $paquete: $hecho")
            }.onFailure { Log.d(TAG, "No está $paquete en esta unidad") }
        }
    }

    // ── Anclaje de pantalla ───────────────────────────────────────────────────

    /**
     * Ancla la Activity: el agente no puede salir de ella.
     *
     * Se llama junto a `goImmersive()` en las dos pantallas, y por el mismo
     * motivo: el sistema puede deshacerlo (al volver de un diálogo de permisos,
     * al recuperar el foco), así que se reaplica en vez de pedirse una sola vez.
     * Si ya está anclada, no hace nada.
     *
     * **El BACK del PTT.** El firmware inyecta BACK al mantener F2 un segundo (ver
     * `SondaEscuchaPtt`). Con la app anclada y siendo el lanzador, ese BACK ya no
     * tiene a dónde ir. Queda por comprobar en la unidad si además deja de llegar
     * a `RecordingActivity`, que es lo que de verdad queremos saber.
     */
    fun sujetarPantalla(activity: Activity) {
        if (!esPropietario(activity) || !kioscoPedido(activity)) return
        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) return
        runCatching { activity.startLockTask() }
            .onSuccess { Log.i(TAG, "Pantalla anclada: ${activity.javaClass.simpleName}") }
            .onFailure { Log.e(TAG, "No se pudo anclar la pantalla", it) }
    }

    /** Suelta el anclaje de esta Activity. No toca el rol ni las políticas. */
    fun soltarPantalla(activity: Activity) {
        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) return
        runCatching { activity.stopLockTask() }
            .onSuccess { Log.i(TAG, "Pantalla suelta") }
            .onFailure { Log.e(TAG, "No se pudo soltar la pantalla", it) }
    }

    // ── Mantenimiento ─────────────────────────────────────────────────────────

    /**
     * Reinicia la unidad. Solo con el rol puesto.
     *
     * Es la forma limpia de reiniciar en kiosco: sin cable y sin menú de apagado.
     * El sistema lo rechaza si hay una llamada en curso, y ahí no hay nada que
     * hacer salvo repetirlo después.
     */
    fun reiniciar(context: Context): Boolean {
        if (!esPropietario(context)) {
            Log.w(TAG, "Reinicio pedido sin rol de propietario: ignorado")
            return false
        }
        return runCatching { dpm(context).reboot(admin(context)); true }
            .onFailure { Log.e(TAG, "No se pudo reiniciar (¿llamada en curso?)", it) }
            .getOrDefault(false)
    }

    /**
     * Pone la hora del sistema en milisegundos de época.
     *
     * Esta unidad va **6 h por delante** del PC y hasta ahora se corregía a mano al
     * leer los logs. `setTime` existe desde API 28, que es justo la de la unidad, y
     * exige que la hora automática esté apagada — de ahí el `AUTO_TIME` a 0.
     *
     * Sin verificar todavía en la unidad: hay firmwares que reponen su propia hora
     * al reiniciar. Se comprueba con `tools/kiosco.sh hora` y un reinicio después.
     */
    fun ajustarHora(context: Context, epocaMs: Long): Boolean {
        if (!esPropietario(context) || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val dpm = dpm(context)
        val admin = admin(context)
        runCatching { dpm.setGlobalSetting(admin, Settings.Global.AUTO_TIME, "0") }
        return runCatching { dpm.setTime(admin, epocaMs) }
            .onSuccess { Log.i(TAG, "Hora ajustada a $epocaMs: $it") }
            .onFailure { Log.e(TAG, "No se pudo ajustar la hora", it) }
            .getOrDefault(false)
    }

    /**
     * Renuncia al rol de propietario. **No borra nada**: la unidad se queda con sus
     * vídeos, su identidad y la app instalada, pero deja de estar gestionada.
     *
     * Es la salida que evita tener que restaurar de fábrica. Se deshace todo en
     * orden inverso a [aplicarPoliticas] porque algunas cosas sobreviven a la
     * renuncia si no se quitan antes: las restricciones de usuario y las apps
     * escondidas se quedarían puestas sin nadie que pueda levantarlas.
     */
    fun renunciar(context: Context) {
        if (!esPropietario(context)) return
        val dpm = dpm(context)
        val admin = admin(context)
        val paquete = context.packageName

        APPS_DEL_FABRICANTE.forEach { p -> runCatching { dpm.setApplicationHidden(admin, p, false) } }
        listOf(
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_CONFIG_DATE_TIME,
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_CREATE_WINDOWS,
        ).forEach { r -> runCatching { dpm.clearUserRestriction(admin, r) } }
        runCatching { dpm.setStatusBarDisabled(admin, false) }
        runCatching { dpm.clearPackagePersistentPreferredActivities(admin, paquete) }
        runCatching { dpm.setPermittedAccessibilityServices(admin, null) }
        runCatching { dpm.setLockTaskPackages(admin, emptyArray()) }

        anotarKiosco(context, false)

        @Suppress("DEPRECATION")
        runCatching { dpm.clearDeviceOwnerApp(paquete) }
            .onSuccess { Log.w(TAG, "Rol de propietario RENUNCIADO. Nada borrado.") }
            .onFailure { Log.e(TAG, "No se pudo renunciar al rol", it) }
    }

    // ── Órdenes de mantenimiento por adb ────────────────────────────────────────────────────────

    /**
     * Atiende una orden de mantenimiento que llega como extras de un intent.
     *
     * Misma vía que el alta de identidad (ver el final de `MainActivity`): en una
     * pantalla de 3 cm no hay sitio para una pantalla de administración, así que
     * el mantenimiento se conduce con `adb` desde el PC.
     *
     *     adb shell am start -n com.falconone.bodycamserver/.MainActivity --es kiosco off
     *     adb shell am start -n com.falconone.bodycamserver/.MainActivity --es kiosco on
     *     adb shell am start -n com.falconone.bodycamserver/.MainActivity --es admin politicas
     *     adb shell am start -n com.falconone.bodycamserver/.MainActivity --es admin soltar
     *     adb shell am start -n com.falconone.bodycamserver/.MainActivity --es admin reboot
     *     adb shell am start -n com.falconone.bodycamserver/.MainActivity --el hora 1758300000000
     *
     * `tools/kiosco.sh` envuelve todo esto y es lo que conviene usar.
     *
     * **Lo que esto no comprueba.** `MainActivity` está exportada, así que estos
     * extras los podría mandar cualquier app instalada. Hoy es aceptable porque en
     * kiosco no hay forma de instalar otra app ni de ejecutarla, pero no es una
     * autorización de verdad.
     * TODO: mover las órdenes al canal Bluetooth con el teléfono emparejado, que ya
     * va autenticado con el certificado de la unidad (ver `BodycamIdentity`).
     */
    fun Activity.atenderOrdenDeMantenimiento(intent: Intent) {
        when (intent.getStringExtra("kiosco")) {
            "on" -> {
                anotarKiosco(this, true)
                aplicarPoliticas(this)
                sujetarPantalla(this)
            }
            "off" -> {
                anotarKiosco(this, false)
                soltarPantalla(this)
                Log.w(TAG, "Kiosco desactivado por adb: la unidad queda abierta")
            }
        }

        when (intent.getStringExtra("admin")) {
            "politicas" -> aplicarPoliticas(this)
            "soltar" -> soltarPantalla(this)
            "reboot" -> reiniciar(this)
            // Renunciar deja la unidad sin gestionar: volver a ponerla exige el
            // cable y `tools/kiosco.sh poner`.
            "renunciar" -> { soltarPantalla(this); renunciar(this) }
        }

        intent.getLongExtra("hora", 0L).takeIf { it > 0L }?.let { ajustarHora(this, it) }
    }
}
