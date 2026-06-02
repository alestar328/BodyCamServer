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

    private val executor = Executors.newCachedThreadPool()
    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket? = null
    private var output: OutputStream? = null
    private var running = false
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
                if (cachedApiOk) HardwareController.ledBlue() // brief visual feedback
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

    // Physical button mapping (confirmed via logcat 2026-06-02; key_code F2=132/F3=133/F4=134):
    //   F2 = PTT (mic)       → toggle bodycam mic in live stream
    //   F3 = Livestream      → toggle Agora stream; notifies phone to sync
    //   F4 = Record (camera) → toggle recording; auto-upload fires on stop
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
                    val on = LivestreamService.toggleMic()
                    Log.d(TAG, "SideKey F2 → PTT mic ${if (on) "ON" else "OFF"}")
                    send(Ntf.PTT)
                }
                KeyEvent.KEYCODE_F3 -> executor.execute {
                    if (LivestreamService.isStreaming) {
                        Log.d(TAG, "SideKey F3 → STREAM STOP")
                        LivestreamService.stop()
                        send(Ntf.STREAM_STOP)
                    } else {
                        if (RecordingActivity.isRecording) {
                            RecordingActivity.stop(context)
                            Thread.sleep(500)
                        }
                        Log.d(TAG, "SideKey F3 → STREAM START")
                        val ok = LivestreamService.start(context)
                        send(if (ok) Ntf.STREAM_START else Rsp.error("Agora no pudo iniciar"))
                    }
                }
                KeyEvent.KEYCODE_F4 -> executor.execute {
                    if (RecordingActivity.isRecording) {
                        Log.d(TAG, "SideKey F4 → STOP recording")
                        RecordingActivity.stop(context)
                        send(Ntf.REC_STOP)
                    } else {
                        Log.d(TAG, "SideKey F4 → START recording")
                        TorchController.release()
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

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "BtServerService onCreate")
        HardwareController.irOff()  // reset IR state on service start
        FileServerService.start()
        acquireWakeLock()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Esperando conexión…"))
        registerReceiver(sideKeyReceiver, IntentFilter("android.intent.action.SIDE_KEY_INTENT"))
        registerReceiver(smokeKeyReceiver, IntentFilter().apply { smokeKeyActions.forEach { addAction(it) } })
        acquireWifiLock()
        connectivityHandler.post(connectivityChecker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            running = true
            executor.execute(::acceptLoop)
        }
        Log.d(TAG, "BtServerService onStartCommand")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        HardwareController.irOff()
        HardwareController.ledOff()
        LivestreamService.stop()
        FileServerService.stop()
        connectivityHandler.removeCallbacks(connectivityChecker)
        try { unregisterReceiver(sideKeyReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(smokeKeyReceiver) } catch (_: Exception) {}
        if (RecordingActivity.isRecording) RecordingActivity.stop(this)
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
        while (running) {
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
                Log.d(TAG, "Client connected: ${socket.remoteDevice.address}")
                updateNotification("Teléfono conectado: ${socket.remoteDevice.name}")
                HardwareController.ledGreen()
                handleClient(socket)
            } catch (e: IOException) {
                Log.e(TAG, "acceptLoop error: ${e.message}")
                try { ss?.close() } catch (_: IOException) {}
                serverSocket = null
                if (running) Thread.sleep(1000)
            }
        }
        Log.d(TAG, "acceptLoop stopped")
    }

    // ── Command processing ────────────────────────────────────────────────────

    private fun handleClient(socket: BluetoothSocket) {
        val reader = BufferedReader(InputStreamReader(socket.inputStream))
        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val response = processCommand(line!!.trim())
                send(response)
            }
        } catch (_: IOException) {
            // client disconnected — stop recording if active
            if (RecordingActivity.isRecording) {
                Log.d(TAG, "Client disconnected while recording — stopping")
                RecordingActivity.stop(applicationContext)
            }
        } finally {
            closeClient()
            updateNotification("Esperando conexión…")
            HardwareController.ledGreen()
        }
    }

    private fun processCommand(raw: String): String {
        Log.d(TAG, "CMD: $raw")
        val parts = raw.split(":")
        return when (parts[0].uppercase()) {

            Cmd.PING -> Rsp.pong()

            Cmd.REC_START -> {
                if (RecordingActivity.isRecording) {
                    Rsp.error("Ya grabando")
                } else {
                    TorchController.release()  // free Camera1 before Camera2 opens
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
                val ok = PhotoController.takePhoto(applicationContext)
                if (ok) Rsp.ok(Cmd.PHOTO) else Rsp.error("Photo failed: recording active or camera error")
            }

            Cmd.STATUS -> Rsp.status(
                RecordingActivity.isRecording,
                hw.batteryLevel(this),
                hw.storageMb(),
                cachedWifiOk,
                cachedApiOk,
                getWifiIp(),
                LivestreamService.isStreaming
            )

            Cmd.STREAM_START -> {
                if (!cachedWifiOk) {
                    Rsp.error("Sin WiFi — livestream requiere conexión a internet")
                } else {
                    if (RecordingActivity.isRecording) {
                        RecordingActivity.stop(applicationContext)
                        Thread.sleep(500) // espera que Camera2 libere la cámara
                    }
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

    // ── Helpers ───────────────────────────────────────────────────────────────

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

// Lightweight helper for status queries (battery, storage) without holding camera
class HardwareHelper {
    fun batteryLevel(context: android.content.Context): Int {
        val intent = context.registerReceiver(null,
            android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
        return if (level >= 0) (level * 100 / scale) else -1
    }

    fun storageMb(): Long {
        val dir = java.io.File(android.os.Environment.getExternalStorageDirectory(), "FalconOne")
        if (!dir.exists()) dir.mkdirs()
        val stat = android.os.StatFs(dir.absolutePath)
        return stat.availableBlocksLong * stat.blockSizeLong / (1024 * 1024)
    }
}
