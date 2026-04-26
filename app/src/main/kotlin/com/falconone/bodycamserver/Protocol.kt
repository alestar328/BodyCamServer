package com.falconone.bodycamserver

// Comandos que recibe la bodycam desde el teléfono
object Cmd {
    const val REC_START    = "REC_START"
    const val REC_STOP     = "REC_STOP"
    const val PHOTO        = "PHOTO"
    const val STATUS       = "STATUS"
    const val IR_ON        = "IR_ON"
    const val IR_OFF       = "IR_OFF"
    const val LED          = "LED"          // LED:7  (valor 0-10 según W1-4G doc)
    const val GPS_ON       = "GPS_ON"
    const val GPS_OFF      = "GPS_OFF"
    const val TORCH_ON     = "TORCH_ON"
    const val TORCH_OFF    = "TORCH_OFF"
    const val PING         = "PING"
    const val STREAM_START = "STREAM_START" // inicia livestream Agora (requiere WiFi)
    const val STREAM_STOP  = "STREAM_STOP"  // detiene livestream Agora
}

const val FILE_SERVER_PORT = 8080

// Debounce compartido entre sideKeyReceiver y onKeyDown para evitar doble disparo
object ButtonDebounce {
    @Volatile private var lastMs = 0L
    fun tryAcquire(): Boolean {
        val now = System.currentTimeMillis()
        return if (now - lastMs > 300) { lastMs = now; true } else false
    }
}

// Notificaciones no solicitadas que envía la bodycam al pulsar botones físicos
object Ntf {
    const val REC_START    = "BTN_REC_START\n"
    const val REC_STOP     = "BTN_REC_STOP\n"
    const val STREAM_START = "BTN_STREAM_START\n"
    const val STREAM_STOP  = "BTN_STREAM_STOP\n"
    const val PTT          = "BTN_PTT\n"
}

// Respuestas que envía la bodycam al teléfono
object Rsp {
    fun ok(cmd: String) = "OK:$cmd\n"
    fun error(msg: String) = "ERROR:$msg\n"
    fun pong() = "PONG\n"
    fun status(recording: Boolean, battery: Int, storage: Long, wifi: Boolean, api: Boolean, ip: String = "", streaming: Boolean = false) =
        "STATUS:{\"recording\":$recording,\"battery\":$battery,\"storage_mb\":$storage,\"wifi\":$wifi,\"api\":$api,\"file_server_ip\":\"$ip\",\"file_server_port\":$FILE_SERVER_PORT,\"streaming\":$streaming,\"stream_uid\":$BODYCAM_UID,\"stream_channel\":\"$AGORA_CHANNEL\"}\n"
}
