package com.falconone.bodycamserver

// Comandos que recibe la bodycam desde el teléfono
object Cmd {
    const val REC_START  = "REC_START"
    const val REC_STOP   = "REC_STOP"
    const val PHOTO      = "PHOTO"
    const val STATUS     = "STATUS"
    const val IR_ON      = "IR_ON"
    const val IR_OFF     = "IR_OFF"
    const val LED        = "LED"      // LED:7  (valor 0-10 según W1-4G doc)
    const val GPS_ON     = "GPS_ON"
    const val GPS_OFF    = "GPS_OFF"
    const val TORCH_ON   = "TORCH_ON"
    const val TORCH_OFF  = "TORCH_OFF"
    const val PING       = "PING"
}

// Respuestas que envía la bodycam al teléfono
object Rsp {
    fun ok(cmd: String) = "OK:$cmd\n"
    fun error(msg: String) = "ERROR:$msg\n"
    fun pong() = "PONG\n"
    fun status(recording: Boolean, battery: Int, storage: Long, wifi: Boolean, api: Boolean) =
        "STATUS:{\"recording\":$recording,\"battery\":$battery,\"storage_mb\":$storage,\"wifi\":$wifi,\"api\":$api}\n"
}
