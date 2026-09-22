package com.falconone.bodycamserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.net.HttpURLConnection
import java.net.URL
import android.util.Log
import android.view.KeyEvent
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors

private val FALCON_UUID: UUID = UUID.fromString("FA1C0000-1337-4242-CAFE-DEADBEEF0001")
private const val SERVICE_NAME = "FalconOneServer"
private const val TAG = "FalconServer"
private const val NOTIF_CHANNEL = "falcon_bt_server"
private const val NOTIF_ID = 1

class BtServerService : Service() {

    companion object {
        // Estado observable por MainActivity (mismo patrón que
        // LivestreamService.isStreaming / RecordingActivity.isRecording).
        @Volatile var isRunning = false
            private set
        // Nombre y MAC del teléfono conectado, o null sin cliente.
        @Volatile var connectedClient: String? = null
            private set
    }

    private val executor = Executors.newCachedThreadPool()
    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket? = null
    private var output: OutputStream? = null

    /** Intercambio del workflow 31 de la conexion en curso. Null si no hay cliente. */
    private var emparejamiento: EmparejamientoDeLaBodycam? = null

    /** A quien sirve la camara en esta conexion (workflows 33 y 34). */
    private var binding: BindingAgente? = null

    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var wifiLock: WifiManager.WifiLock

    // Battery/storage helper (no camera — camera is in RecordingActivity)
    private val hw = HardwareHelper()

    // Connectivity cache — updated every 30s by background checker
    @Volatile private var cachedWifiOk = false
    @Volatile private var cachedApiOk  = false
    private val connectivityHandler = Handler(Looper.getMainLooper())
    private val connectivityChecker = object : Runnable {
        override fun run() {
            executor.execute {
                cachedWifiOk = isWifiConnected()
                cachedApiOk  = if (cachedWifiOk) isApiReachable() else false
                Log.d(TAG, "Connectivity: wifi=$cachedWifiOk api=$cachedApiOk")
            }
            connectivityHandler.postDelayed(this, 30_000)
        }
    }

    private fun getWifiIp(): String {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress ?: ""
        } catch (_: Exception) { "" }
    }

    private fun isWifiConnected(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun isApiReachable(): Boolean {
        return try {
            val conn = URL("https://nexus.aeriaone.com/").openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            conn.connect()
            val code = conn.responseCode
            conn.disconnect()
            code in 200..499  // any response means server is up
        } catch (e: Exception) {
            Log.w(TAG, "API check failed: ${e.message}")
            false
        }
    }

    // Physical button mapping — VERIFIED via logcat (FalconSmoke / SIDE_KEY_INTENT)
    // on 2026-06-03 (confirmed THREE times). Each physical button emits:
    //   • 132 / KEYCODE_F2 = "PTT / audio" button → conmuta el micro en Agora     → BTN_PTT_ON/OFF
    //   • 133 / KEYCODE_F3 = "SOS" button         → toggle Agora livestream       → BTN_STREAM_*
    //   • 134 / KEYCODE_F4 = "record" button      → toggle local recording        → BTN_REC_*
    //
    // El PTT (132) vive SOLO aquí, en el broadcast del firmware. Se quitó de
    // MainActivity.onKeyDown y del servicio de accesibilidad a propósito: medido el
    // 2026-09-08 en la unidad, el broadcast llega siempre —pantalla apagada, sin
    // Activity delante— y con Activity en foco onKeyDown además AUTO-REPITE cada
    // 50 ms, así que un mantenido largo conmutaba el micro varias veces. Una sola
    // puerta y el conmutador es fiable.
    //
    // The physical SOS button (133) is wired to LIVESTREAM on purpose: the bodycam
    // joining Agora with video IS the SOS signal the phone reacts to. In Falcon One,
    // BTN_STREAM_* / bodycam video live == SOS popup. Normal recording (134) stays local
    // and must NEVER raise SOS on the phone. Do NOT swap F3/F4 — this matches the
    // hardware (we flip-flopped twice before the logcat settled it).
    private val sideKeyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != "android.intent.action.SIDE_KEY_INTENT") return
            val keyCode = intent.getIntExtra("key_code", 0)
            val status  = intent.getIntExtra("key_status", -1)
            Log.d(TAG, "SideKey key_code=$keyCode key_status=$status")
            if (status == 1) return  // ignore release; accept 0 (press) and -1 (W1 firmware)
            if (!ButtonDebounce.tryAcquire()) return  // onKeyDown already handled this press

            // Run on executor so RtcEngine.create/destroy don't block the main thread.
            // This keeps sideKeyReceiver fast (<5ms) so onKeyDown arrives while debounce
            // is still active (within 300ms) and gets correctly discarded.
            when (keyCode) {
                KeyEvent.KEYCODE_F2 -> executor.execute {
                    // Abrir el micro sin red daría un PTT_ON que no transmite nada:
                    // Agora crearía el engine y fallaría al entrar en el canal por
                    // dentro. Cerrarlo sí se permite siempre, para poder callar.
                    if (!cachedWifiOk && !LivestreamService.isMicEnabled) {
                        Log.d(TAG, "SideKey F2 → PTT descartado: sin conexión")
                        // Sin sonido el agente cree que ha abierto el micro y habla
                        // solo: el zumbido es lo único que se lo dice ahí fuera.
                        PttTones.denegado()
                        send(Rsp.error("Sin WiFi — el PTT viaja por el canal de Agora"))
                    } else {
                        val on = LivestreamService.togglePtt(applicationContext)
                        Log.d(TAG, "SideKey F2 → PTT mic ${if (on) "ON" else "OFF"}")
                        val fallo = LivestreamService.lastPttError
                        when {
                            on -> send(Ntf.PTT_ON)
                            // Cerrar es un OFF normal; no abrir es un error que el
                            // agente tiene que ver, no un PTT que se apaga solo.
                            fallo != null -> { send(Rsp.error(fallo)); send(Ntf.PTT_OFF) }
                            else -> send(Ntf.PTT_OFF)
                        }
                    }
                }
                KeyEvent.KEYCODE_F3 -> executor.execute {
                    if (LivestreamService.sosActivo) {
                        Log.d(TAG, "SideKey F3 → STREAM STOP")
                        LivestreamService.stop()
                        send(Ntf.STREAM_STOP)
                    } else {
                        // LivestreamService cede la cámara y rearma al terminar.
                        PreviewController.stop()
                        Log.d(TAG, "SideKey F3 → STREAM START")
                        val ok = LivestreamService.start(context)
                        send(if (ok) Ntf.STREAM_START else Rsp.error("Agora no pudo iniciar"))
                    }
                }
                KeyEvent.KEYCODE_F4 -> executor.execute {
                    // Rebote del mismo botón que acaba de parar: sin esto se leía
                    // isRecording=false y arrancaba otra grabación encima de la
                    // pregunta de envío, con la pantalla apagándose acto seguido.
                    if (RecordingActivity.ignoreRecordKey()) {
                        Log.d(TAG, "SideKey F4 descartado: pregunta de envío recién abierta")
                        return@execute
                    }
                    if (RecordingActivity.isRecording) {
                        Log.d(TAG, "SideKey F4 → STOP recording")
                        RecordingActivity.stop(context, askUpload = true)
                        send(Ntf.REC_STOP)
                    } else {
                        Log.d(TAG, "SideKey F4 → START recording")
                        TorchController.release()
                        PreviewController.stop()
                        RecordingActivity.start(context)
                        send(Ntf.REC_START)
                    }
                }
            }
        }
    }

    // ── SMOKE TEST (vendor com.smarteye.mcu side-key broadcasts) ───────────────
    // Confirms whether the firmware's physical-button broadcasts reach a 3rd-party
    // runtime-registered receiver. Filter logcat with tag "FalconSmoke".
    // Remove this block once the coexistence strategy is validated.
    private val smokeKeyActions = arrayOf(
        "android.intent.action.PRESS_VIDEO_KEY",  "android.intent.action.LONG_PRESS_VIDEO_KEY",
        "android.intent.action.PRESS_RECORD_KEY", "android.intent.action.LONG_PRESS_RECORD_KEY",
        "android.intent.action.PRESS_PIC_KEY",    "android.intent.action.LONG_PRESS_PIC_KEY",
        "android.intent.action.DOWN_PTT_KEY",     "android.intent.action.UP_PTT_KEY",
        "android.intent.action.PRESS_SOS_KEY",    "android.intent.action.LONG_PRESS_SOS_KEY",
        "android.intent.action.PRESS_MARK_KEY",   "android.intent.action.LONG_PRESS_MARK_KEY",
        // also the keycode-style broadcast used by the DSI variant, just in case:
        "android.intent.action.SIDE_KEY_INTENT",
    )
    private val smokeKeyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val sb = StringBuilder()
            intent.extras?.let { ex -> for (k in ex.keySet()) sb.append("$k=${ex.get(k)} ") }
            Log.i("FalconSmoke", "BTN action=${intent.action}  extras[$sb]")
        }
    }

    /**
     * Apagar la unidad tiene que dejarla a oscuras. Se registra aquí, y no en el
     * manifest, porque ACTION_SHUTDOWN es un broadcast implícito no exceptuado en
     * Android 8: con targetSdk 28 un receiver del manifest no lo recibiría. Este
     * servicio es el que está vivo siempre, así que es el sitio.
     */
    private val apagadoReceiver = ApagadoReceiver()

    /**
     * Alimenta el nivel de batería de [LedSignals], que desde el 2026-09-22 también
     * pinta el LED por carga (verde ≥80, azul 40-79, rojo por debajo).
     *
     * Va aquí, y no en un sondeo periódico, porque el sistema ya emite el cambio y
     * este servicio es el único proceso vivo siempre. `LedSignals` no puede
     * preguntarlo por su cuenta: es un `object` sin `Context`, y lo llaman sitios
     * (receivers, `onDestroy`, el hilo de captura) que tampoco tienen uno a mano.
     *
     * `ACTION_BATTERY_CHANGED` llega mucho más a menudo de lo que cambia el
     * porcentaje —también por temperatura o voltaje—; de que eso no se convierta en
     * una escritura de sysfs por broadcast se encarga [LedSignals.actualizarBateria].
     */
    private val bateriaReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            LedSignals.actualizarBateria(porcentajeBateria(intent))
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "BtServerService onCreate")
        // Arranca en modo día (IR apagado, filtro puesto) y a partir de ahí decide la luz.
        ModoNoche.arrancar()
        LedSignals.apagando = false   // se levanta al apagar la unidad; aquí ya no toca
        // `ACTION_BATTERY_CHANGED` es sticky: registrarse devuelve ya el último, así
        // que el LED arranca con el color de carga en vez de con el de "desconocido".
        LedSignals.actualizarBateria(porcentajeBateria(
            registerReceiver(bateriaReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))))
        LedSignals.refresh()  // pinta por estado real — evita pisar el azul de buffer o dejar colores pegados del firmware
        FileServerService.start()
        acquireWakeLock()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Esperando conexión…"))
        // Si Agora pierde la captura, el PTT se cierra solo: hay que decírselo al
        // teléfono para que no siga mostrando el micro abierto.
        LivestreamService.onPttDropped = { motivo ->
            Log.w(TAG, "PTT caído: $motivo")
            send(Rsp.error(motivo))
            send(Ntf.PTT_OFF)
        }
        registerReceiver(apagadoReceiver, ApagadoReceiver.filtro())
        registerReceiver(sideKeyReceiver, IntentFilter("android.intent.action.SIDE_KEY_INTENT"))
        registerReceiver(smokeKeyReceiver, IntentFilter().apply { smokeKeyActions.forEach { addAction(it) } })
        acquireWifiLock()
        connectivityHandler.post(connectivityChecker)
        // La unidad escucha el canal todo el tiempo, para que el PTT de los teléfonos
        // suene por su altavoz. En el executor: crear el RtcEngine tarda.
        executor.execute { LivestreamService.escuchar(applicationContext) }
        // Medicion de bateria de la escucha (2026-09-14). Se registra en el contexto de
        // la aplicacion para que sobreviva a un reinicio del servicio.
        if (BuildConfig.DEBUG) SondaEscuchaPtt.registrar(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            isRunning = true
            executor.execute(::acceptLoop)
            // Solo al arrancar el servidor, que es cuando se enciende la unidad o se
            // abre la app: es el momento en que alguien la esta buscando.
            VisibilidadBluetooth.hacerVisible()
        }
        Log.d(TAG, "BtServerService onStartCommand")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        // Mismo apagado que el del botón de encendido: para el modo noche con su
        // veto, baja el IR, el LED y la linterna, y devuelve el filtro a modo día.
        ApagadoReceiver.apagarTodasLasLuces("servicio parado")
        LivestreamService.salirDelCanal()
        PreviewController.stop()
        FileServerService.stop()
        connectivityHandler.removeCallbacks(connectivityChecker)
        try { unregisterReceiver(apagadoReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(bateriaReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(sideKeyReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(smokeKeyReceiver) } catch (_: Exception) {}
        // Desarmar del todo: disarm sella el incidente en curso si lo hay.
        if (RecordingActivity.isHoldingCamera) RecordingActivity.disarm(this)
        connectedClient = null
        closeConnections()
        if (wifiLock.isHeld) wifiLock.release()
        wakeLock.release()
        super.onDestroy()
    }

    // ── BT accept loop ────────────────────────────────────────────────────────

    private fun acceptLoop() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: run {
            Log.e(TAG, "BT not available"); return
        }
        Log.d(TAG, "acceptLoop started")
        while (isRunning) {
            var ss: BluetoothServerSocket? = null
            try {
                try { serverSocket?.close() } catch (_: IOException) {}
                serverSocket = null

                Log.d(TAG, "Opening RFCOMM server socket…")
                ss = adapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, FALCON_UUID)
                serverSocket = ss
                Log.d(TAG, "Waiting for client connection…")
                updateNotification("Esperando conexión…")

                val socket = ss.accept()

                // Close server socket right after accept — frees SDP slot for next session
                try { ss.close() } catch (_: IOException) {}
                serverSocket = null

                clientSocket = socket
                output = socket.outputStream
                val device = socket.remoteDevice
                connectedClient = device.name?.let { "$it (${device.address})" } ?: device.address
                Log.d(TAG, "Client connected: ${device.address}")
                updateNotification("Teléfono conectado: $connectedClient")
                LedSignals.refresh()
                handleClient(socket)
            } catch (e: IOException) {
                Log.e(TAG, "acceptLoop error: ${e.message}")
                try { ss?.close() } catch (_: IOException) {}
                serverSocket = null
                if (isRunning) Thread.sleep(1000)
            }
        }
        Log.d(TAG, "acceptLoop stopped")
    }

    // ── Command processing ────────────────────────────────────────────────────

    private fun handleClient(socket: BluetoothSocket) {
        val reader = BufferedReader(InputStreamReader(socket.inputStream))
        // Un intercambio por conexion: el nonce vale para esta y solo esta, que es
        // lo que impide reutilizar una respuesta capturada de otra sesion.
        emparejamiento = EmparejamientoDeLaBodycam(this)
        binding = BindingAgente(this)
        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val response = processCommand(line!!.trim())
                send(response)
            }
        } catch (_: IOException) {
            // Client disconnected. Do NOT stop an active recording: BT drops are
            // routine (2.4 GHz coexistence with the livestream, body attenuation)
            // and the phone auto-reconnects. Recording is local evidence — it only
            // stops by physical button or explicit REC_STOP command.
            if (RecordingActivity.isRecording) {
                Log.d(TAG, "Client disconnected while recording — recording continues")
            }
        } finally {
            connectedClient = null
            emparejamiento = null
            // La atadura no sobrevive al enlace: mientras no hay telefono, la
            // camara no esta al servicio de nadie.
            binding = null
            closeClient()
            // Sin teléfono nadie mira el visor: se libera la cámara para no
            // drenar batería. Si el enlace vuelve, el teléfono lo reabre.
            PreviewController.stop()
            updateNotification("Esperando conexión…")
            LedSignals.refresh()
        }
    }

    private fun processCommand(raw: String): String {
        // El emparejamiento se atiende antes de registrar nada: la linea lleva
        // certificados en base64 y llenaria el log de ruido en cada conexion.
        if (raw.startsWith("AUTH_")) return procesarEmparejamiento(raw)
        if (raw.startsWith("BIND") || raw.startsWith("UNBIND")) return procesarAtadura(raw)

        Log.d(TAG, "CMD: $raw")
        val parts = raw.split(":")
        return when (parts[0].uppercase()) {

            Cmd.PING -> Rsp.pong()

            Cmd.REC_START -> {
                if (RecordingActivity.isRecording) {
                    Rsp.error("Ya grabando")
                } else {
                    TorchController.release()  // free Camera1 before Camera2 opens
                    PreviewController.stop()   // el visor también usa Camera1
                    RecordingActivity.start(applicationContext)
                    Log.d(TAG, "REC_START — RecordingActivity launched")
                    Rsp.ok(Cmd.REC_START)
                }
            }

            Cmd.REC_STOP -> {
                if (!RecordingActivity.isRecording) {
                    Rsp.error("No estaba grabando")
                } else {
                    RecordingActivity.stop(applicationContext)
                    Log.d(TAG, "REC_STOP — broadcast sent")
                    Rsp.ok(Cmd.REC_STOP)
                }
            }

            Cmd.PHOTO -> {
                // Con el visor abierto la foto usa esa misma sesión de cámara:
                // lo que el teléfono ve es exactamente lo que se captura.
                val ok = if (PreviewController.isActive) {
                    PreviewController.takePhoto(applicationContext)
                } else {
                    PhotoController.takePhoto(applicationContext)
                }
                if (ok) Rsp.ok(Cmd.PHOTO) else Rsp.error("Photo failed: recording active or camera error")
            }

            Cmd.SERVICE_START -> {
                if (RecordingActivity.isHoldingCamera) {
                    Rsp.error("El servicio ya está activo")
                } else {
                    TorchController.release()
                    PreviewController.stop()
                    RecordingActivity.arm(applicationContext)
                    Rsp.ok(Cmd.SERVICE_START)
                }
            }

            Cmd.SERVICE_STOP -> {
                RecordingActivity.disarm(applicationContext)
                Rsp.ok(Cmd.SERVICE_STOP)
            }

            Cmd.PREVIEW_START -> {
                if (RecordingActivity.isHoldingCamera) {
                    // El monitor de la sesión de captura ya sirve /preview/stream.
                    Rsp.error("Captura activa — usa el monitor de la grabación")
                } else if (LivestreamService.isStreaming) {
                    Rsp.error("Livestream activo — el visor no está disponible")
                } else if (!cachedWifiOk) {
                    Rsp.error("Sin WiFi — el visor viaja por WiFi")
                } else {
                    val ok = PreviewController.start()
                    if (ok) Rsp.ok(Cmd.PREVIEW_START) else Rsp.error("No se pudo abrir la cámara")
                }
            }

            Cmd.PREVIEW_STOP -> {
                PreviewController.stop()
                Rsp.ok(Cmd.PREVIEW_STOP)
            }

            Cmd.STATUS -> Rsp.status(
                RecordingActivity.isRecording,
                hw.batteryLevel(this),
                hw.storageMb(),
                cachedWifiOk,
                cachedApiOk,
                getWifiIp(),
                LivestreamService.isStreaming,
                PreviewController.isActive,
                RecordingActivity.serviceRequested,
                RecordingActivity.state.name,
                LivestreamService.isMicEnabled,
                BodycamIdentity.uidAgora(this),
            )

            Cmd.STREAM_START -> {
                if (!cachedWifiOk) {
                    Rsp.error("Sin WiFi — livestream requiere conexión a internet")
                } else {
                    // LivestreamService cede la cámara y rearma al terminar.
                    PreviewController.stop() // Agora necesita la cámara para sí
                    val ok = LivestreamService.start(applicationContext)
                    if (ok) Rsp.ok(Cmd.STREAM_START) else Rsp.error("Agora no pudo iniciar")
                }
            }

            Cmd.STREAM_STOP -> {
                LivestreamService.stop()
                Rsp.ok(Cmd.STREAM_STOP)
            }

            Cmd.IR_ON  -> { HardwareController.irOn();  Rsp.ok(Cmd.IR_ON)  }
            Cmd.IR_OFF -> { HardwareController.irOff(); Rsp.ok(Cmd.IR_OFF) }

            Cmd.LED -> {
                val v = parts.getOrNull(1)?.toIntOrNull() ?: 0
                HardwareController.setLed(v)
                Rsp.ok("${Cmd.LED}:$v")
            }

            // ── Subida de evidencia ───────────────────────────────────────────
            // El teléfono es el único sitio con pantalla para enseñar la lista y
            // decidir: en la unidad no cabe. Cancelar NO borra el vídeo, solo lo
            // saca de la cola de reintentos (ver UploadCancel).

            Cmd.UPLOAD_LIST -> Rsp.uploads(UploadCancel.resumen())

            Cmd.UPLOAD_CANCEL -> {
                val id = parts.getOrNull(1)
                when {
                    id.isNullOrBlank() -> Rsp.error("Falta el incidente: UPLOAD_CANCEL:INC_000032")
                    UploadCancel.cancelar(id) -> Rsp.ok("${Cmd.UPLOAD_CANCEL}:$id")
                    else -> Rsp.error("No se pudo cancelar $id")
                }
            }

            Cmd.UPLOAD_RESUME -> {
                val id = parts.getOrNull(1)
                when {
                    id.isNullOrBlank() -> Rsp.error("Falta el incidente: UPLOAD_RESUME:INC_000032")
                    !UploadCancel.reanudar(id) -> Rsp.error("No se pudo reanudar $id")
                    else -> {
                        // Vuelve a la cola en el momento, sin esperar al siguiente
                        // arranque: quien lo pide está mirando el teléfono ahora.
                        UploadService.startIncident(applicationContext, id)
                        Rsp.ok("${Cmd.UPLOAD_RESUME}:$id")
                    }
                }
            }

            Cmd.GPS_ON  -> { HardwareController.gpsOn();  Rsp.ok(Cmd.GPS_ON)  }
            Cmd.GPS_OFF -> { HardwareController.gpsOff(); Rsp.ok(Cmd.GPS_OFF) }

            Cmd.TORCH_ON  -> {
                val ok = TorchController.turnOn()
                if (ok) Rsp.ok(Cmd.TORCH_ON) else Rsp.error("Torch no disponible")
            }
            Cmd.TORCH_OFF -> { TorchController.turnOff(); Rsp.ok(Cmd.TORCH_OFF) }

            else -> Rsp.error("Comando desconocido: $raw")
        }
    }

    /**
     * Emparejamiento autenticado con el telefono (workflow 31).
     *
     * QUE PASA SI NO SE AUTENTICA, dicho aqui para que se vea al leerlo: hoy
     * **no se cierra la conexion**. Hay telefonos en campo con la version anterior
     * de Aeria Nexus que no conocen el intercambio, y cortarles el enlace los
     * dejaria sin camara sin haber ganado nada. Se registra y se sigue.
     *
     * En cuanto todos los terminales lleven la version nueva, esto se invierte:
     * un cliente que no se acredite no debe poder mandar REC_STOP ni STATUS.
     * El sitio para ese cambio es processCommand, comprobando
     * `emparejamiento?.telefonoAutenticado` antes del `when`.
     */
    private fun procesarEmparejamiento(raw: String): String {
        val enCurso = emparejamiento ?: return Rsp.error("Emparejamiento fuera de conexion")
        return when {
            raw.startsWith("AUTH_HELLO") -> enCurso.responderASaludo(raw)
            raw.startsWith("AUTH_PROOF") ->
                enCurso.responderAPrueba(raw, BodycamIdentity.anclaDeConfianza(this))
            else -> Rsp.error("Paso de emparejamiento desconocido")
        }
    }

    /**
     * Atadura agente-camara (workflows 33 y 34).
     *
     * Solo se atiende con el telefono ya acreditado: aceptar una atadura de alguien
     * que no ha demostrado quien es seria dejar que cualquiera ponga la camara al
     * servicio de un agente inventado.
     */
    private fun procesarAtadura(raw: String): String {
        val enCurso = emparejamiento
        val actual = binding
        if (enCurso == null || actual == null) return Rsp.error("Atadura fuera de conexion")
        if (enCurso.telefonoAutenticado == null) {
            return "BIND_FAIL:el telefono no se ha acreditado\n"
        }

        val ancla = BodycamIdentity.anclaDeUsuario(this)
        return if (raw.startsWith("UNBIND")) {
            actual.deshacer(raw, ancla, enCurso.certificadoDelTelefono)
        } else {
            actual.atender(raw, enCurso.nonce, ancla)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    // Synchronized: command responses (handleClient thread) and BTN_* notifications
    // (side-key executor threads) write to the same stream; without this their
    // bytes can interleave inside a single line and corrupt the protocol.
    @Synchronized
    private fun send(data: String) {
        try { output?.write(data.toByteArray(Charsets.UTF_8)) } catch (_: IOException) {}
    }

    private fun closeClient() {
        try { output?.close() }       catch (_: IOException) {}
        try { clientSocket?.close() } catch (_: IOException) {}
        output = null
        clientSocket = null
    }

    private fun closeConnections() {
        closeClient()
        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null
    }

    private fun acquireWifiLock() {
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "FalconOne::WiFi")
        wifiLock.acquire()
        Log.d(TAG, "WifiLock acquired (HIGH_PERF)")
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FalconOne::BtServer")
        wakeLock.acquire()
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(NOTIF_CHANNEL, "FalconOne BT Server", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("FalconOne Server")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()

    private fun updateNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
    }
}

/**
 * Porcentaje 0-100 de un `ACTION_BATTERY_CHANGED`, o -1 si no se puede sacar.
 *
 * El nivel viene en la escala que diga el propio intent, que no tiene por qué ser
 * 100. Lo comparten el LED y el STATUS del teléfono para que no puedan discrepar.
 */
private fun porcentajeBateria(intent: Intent?): Int {
    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    return if (level >= 0 && scale > 0) level * 100 / scale else -1
}

// Lightweight helper for status queries (battery, storage) without holding camera
class HardwareHelper {
    fun batteryLevel(context: Context): Int = porcentajeBateria(
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))

    fun storageMb(): Long {
        val dir = java.io.File(android.os.Environment.getExternalStorageDirectory(), "FalconOne")
        if (!dir.exists()) dir.mkdirs()
        val stat = android.os.StatFs(dir.absolutePath)
        return stat.availableBlocksLong * stat.blockSizeLong / (1024 * 1024)
    }
}
