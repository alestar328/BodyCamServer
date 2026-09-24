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
    const val PREVIEW_START = "PREVIEW_START" // visor remoto: frames JPEG en GET /preview (WiFi)
    const val PREVIEW_STOP  = "PREVIEW_STOP"
    const val SERVICE_START = "SERVICE_START" // arma la grabación continua (anillo pre-evento)
    const val SERVICE_STOP  = "SERVICE_STOP"  // desarma y descarta el anillo

    // ── Subida de evidencia ───────────────────────────────────────────────────
    // La reanudación no se rinde nunca (ver UploadCancel): estos tres son la
    // puerta de salida de ese bucle desde el teléfono.
    const val UPLOAD_LIST   = "UPLOAD_LIST"   // qué hay pendiente, entregado o cancelado
    const val UPLOAD_CANCEL = "UPLOAD_CANCEL" // UPLOAD_CANCEL:INC_000032 — corta y no reintenta
    const val UPLOAD_RESUME = "UPLOAD_RESUME" // UPLOAD_RESUME:INC_000032 — lo devuelve a la cola
}

const val FILE_SERVER_PORT = 8080

// Antirrebote de los botones físicos. Lo usa BotonesFisicos, que es su única puerta.
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
    // El PTT es un conmutador, no un mantener-para-hablar: el firmware solo avisa
    // al soltar el botón. Por eso lleva estado, como REC y STREAM, en vez del
    // antiguo BTN_PTT suelto que no decía si el micro quedaba abierto o cerrado.
    const val PTT_ON       = "BTN_PTT_ON\n"
    const val PTT_OFF      = "BTN_PTT_OFF\n"
}

// Respuestas que envía la bodycam al teléfono
object Rsp {
    fun ok(cmd: String) = "OK:$cmd\n"
    fun error(msg: String) = "ERROR:$msg\n"
    fun pong() = "PONG\n"
    // stream_uid es el numero de ESTA unidad en Agora: el telefono lo usa para
    // distinguir su propia bodycam de la de otros agentes.
    /**
     * Estado de subida de cada incidente de la unidad.
     *
     * Un array y no un objeto por id: el teléfono lo pinta como lista y así
     * conserva el orden en el que la unidad los tiene. `pending` es lo que
     * queda por entregar y no está cancelado — es decir, lo único sobre lo que
     * tiene sentido ofrecer un botón de cancelar.
     */
    fun uploads(items: List<Triple<String, Boolean, Boolean>>): String {
        val cuerpo = items.joinToString(",") { (id, entregado, cancelado) ->
            """{"id":"$id","delivered":$entregado,"cancelled":$cancelado,""" +
                """"pending":${!entregado && !cancelado}}"""
        }
        return "UPLOADS:[$cuerpo]\n"
    }

    fun status(recording: Boolean, battery: Int, storage: Long, wifi: Boolean, api: Boolean, ip: String, streaming: Boolean, preview: Boolean, armed: Boolean, captureState: String, ptt: Boolean, streamUid: Int) =
        "STATUS:{\"recording\":$recording,\"battery\":$battery,\"storage_mb\":$storage,\"wifi\":$wifi,\"api\":$api,\"file_server_ip\":\"$ip\",\"file_server_port\":$FILE_SERVER_PORT,\"streaming\":$streaming,\"stream_uid\":$streamUid,\"stream_channel\":\"$AGORA_CHANNEL\",\"preview\":$preview,\"armed\":$armed,\"capture_state\":\"$captureState\",\"ptt\":$ptt}\n"
}
