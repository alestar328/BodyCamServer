package com.falconone.bodycamserver

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var btnRecord: Button
    private lateinit var btnStream: Button
    private lateinit var btStatusText: TextView

    private val handler = Handler(Looper.getMainLooper())

    // Refresh UI every second to reflect recording/streaming state changes
    private val uiRefresher = object : Runnable {
        override fun run() {
            updateRecordButton()
            updateStreamButton()
            handler.postDelayed(this, 1000)
        }
    }

    // Listen for recording state changes from BtServerService commands
    private val recordingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateRecordButton()
            updateStreamButton()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 32)
            setBackgroundColor(Color.parseColor("#1a1a2e"))
        }

        // ── Título ────────────────────────────────────────────────────────────
        root.addView(TextView(this).apply {
            text = "FalconOne Server"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })

        root.addView(spacer(12))

        // ── Estado BT ─────────────────────────────────────────────────────────
        btStatusText = TextView(this).apply {
            text = "Servidor inactivo"
            textSize = 13f
            setTextColor(Color.parseColor("#aaaaaa"))
            gravity = Gravity.CENTER
        }
        root.addView(btStatusText)

        root.addView(spacer(20))

        // ── Botones servidor ──────────────────────────────────────────────────
        val rowServer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }

        val btnStart = Button(this).apply {
            text = "Iniciar"
            setBackgroundColor(Color.parseColor("#16213e"))
            setTextColor(Color.WHITE)
            setOnClickListener { startServer() }
        }
        val btnStop = Button(this).apply {
            text = "Detener"
            setBackgroundColor(Color.parseColor("#16213e"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                stopService(Intent(this@MainActivity, BtServerService::class.java))
                btStatusText.text = "Servidor detenido"
            }
        }

        rowServer.addView(btnStart, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 8 })
        rowServer.addView(btnStop,  LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 8 })
        root.addView(rowServer)

        root.addView(spacer(16))

        // ── Indicador grabación ───────────────────────────────────────────────
        btnRecord = Button(this).apply {
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setOnClickListener { toggleRecording() }
        }
        root.addView(btnRecord, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 120
        ))

        root.addView(spacer(10))

        // ── Indicador livestream ──────────────────────────────────────────────
        btnStream = Button(this).apply {
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setOnClickListener { toggleLivestream() }
        }
        root.addView(btnStream, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 120
        ))

        root.addView(spacer(24))

        // ── Botones físicos configurados ──────────────────────────────────────
        root.addView(sectionLabel("Botones físicos"))
        root.addView(buttonRow("F2  (frontal)", "PTT — micrófono"))
        root.addView(buttonRow("F3  (lateral sup.)", "Livestream"))
        root.addView(buttonRow("F4  (lateral inf.)", "Grabar / Detener"))

        setContentView(root)

        requestPermissions()
        if (allPermissionsGranted()) startServer()

        registerReceiver(recordingReceiver, IntentFilter(RecordingActivity.ACTION_STOP))
        updateRecordButton()
        updateStreamButton()
        handler.post(uiRefresher)
    }

    override fun onDestroy() {
        handler.removeCallbacks(uiRefresher)
        try { unregisterReceiver(recordingReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    // ── Lógica ────────────────────────────────────────────────────────────────

    private fun startServer() {
        startForegroundService(Intent(this, BtServerService::class.java))
        btStatusText.text = "Esperando teléfono…"
        btStatusText.setTextColor(Color.parseColor("#f0a500"))
    }

    private fun toggleRecording() {
        if (RecordingActivity.isRecording) {
            RecordingActivity.stop(this)
        } else {
            TorchController.release()
            RecordingActivity.start(this)
        }
    }

    private fun updateRecordButton() {
        if (RecordingActivity.isRecording) {
            btnRecord.text = "⏹  Grabando…"
            btnRecord.setBackgroundColor(Color.parseColor("#c0392b"))
            btnRecord.setTextColor(Color.WHITE)
        } else {
            btnRecord.text = "⏺  Iniciar grabación"
            btnRecord.setBackgroundColor(Color.parseColor("#2c3e50"))
            btnRecord.setTextColor(Color.parseColor("#aaaaaa"))
        }
    }

    private fun updateStreamButton() {
        if (LivestreamService.isStreaming) {
            btnStream.text = "⏹  Livestream activo…"
            btnStream.setBackgroundColor(Color.parseColor("#1a6fb5"))
            btnStream.setTextColor(Color.WHITE)
        } else {
            btnStream.text = "📡  Iniciar livestream"
            btnStream.setBackgroundColor(Color.parseColor("#2c3e50"))
            btnStream.setTextColor(Color.parseColor("#aaaaaa"))
        }
    }

    // ── Botones físicos ───────────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        Log.d("FalconKeys", "MainActivity onKeyDown: $keyCode")
        when (keyCode) {
            KeyEvent.KEYCODE_F2 -> {
                if (!ButtonDebounce.tryAcquire()) return true
                LivestreamService.toggleMic()
            }
            KeyEvent.KEYCODE_F3 -> {
                if (!ButtonDebounce.tryAcquire()) return true
                toggleLivestream()
            }
            KeyEvent.KEYCODE_F4 -> {
                if (!ButtonDebounce.tryAcquire()) return true
                toggleRecording()
                updateRecordButton()  // immediate visual
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private fun toggleLivestream() {
        if (LivestreamService.isStreaming) {
            LivestreamService.stop()
            updateStreamButton()  // immediate
        } else {
            if (RecordingActivity.isRecording) RecordingActivity.stop(this)
            // Immediate "connecting" feedback before Agora confirms join
            btnStream.text = "📡  Conectando livestream…"
            btnStream.setBackgroundColor(Color.parseColor("#1565c0"))
            btnStream.setTextColor(Color.WHITE)
            LivestreamService.start(this)
            // uiRefresher updates to final state once isStreaming = true
        }
    }

    // ── Permisos ──────────────────────────────────────────────────────────────

    private val requiredPerms = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.BLUETOOTH,
    )

    private fun allPermissionsGranted() =
        requiredPerms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun requestPermissions() {
        val missing = requiredPerms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        if (results.all { it == PackageManager.PERMISSION_GRANTED }) startServer()
    }

    // ── Helpers UI ────────────────────────────────────────────────────────────

    private fun spacer(dp: Int) = android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp.dpToPx()
        )
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.parseColor("#888888"))
        setPadding(0, 0, 0, 6)
    }

    private fun buttonRow(key: String, action: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 4)
            addView(TextView(this@MainActivity).apply {
                text = key
                textSize = 12f
                setTextColor(Color.parseColor("#e0e0e0"))
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@MainActivity).apply {
                text = action
                textSize = 12f
                setTextColor(Color.parseColor("#aaaaaa"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
    }

    private fun Int.dpToPx() = (this * resources.displayMetrics.density).toInt()
}
