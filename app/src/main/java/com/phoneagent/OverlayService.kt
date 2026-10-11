package com.phoneagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/** Foreground service: bubble, panel, tap-to-talk, optional visible Hey Agent listening. */
class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private var bubble: TextView? = null
    private var panel: LinearLayout? = null
    private var panelLog: TextView? = null
    private var sendBtn: Button? = null
    private var micBtn: Button? = null
    private var tail = ""

    private var speech: SpeechRecognizer? = null
    private var listeningHandsFree = false
    private var awaitingCommand = false // true after wake phrase heard

    private val wallEYellow = 0xE6FFC107.toInt()
    private val runningRed = 0xE6DC2626.toInt()
    private val listenGreen = 0xE622C55E.toInt()

    private val logL: (String) -> Unit = { s ->
        ui.post {
            tail = (tail + s + "\n\n").takeLast(1200)
            panelLog?.text = tail
            if (s.startsWith("🤖") && panel == null) {
                Toast.makeText(this, s.take(250), Toast.LENGTH_LONG).show()
            }
        }
    }
    private val stateL: (Boolean) -> Unit = { running ->
        ui.post {
            updateBubbleAppearance(running)
            sendBtn?.text = if (running) "Stop" else "Send"
        }
    }

    private fun prefs() = getSharedPreferences("agent", MODE_PRIVATE)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        AgentEngine.logListeners.add(logL)
        AgentEngine.stateListeners.add(stateL)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP" -> {
                stopListening()
                removeViews()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            "START_LISTEN" -> {
                prefs().edit().putBoolean("listen", true).apply()
                startHandsFreeIfNeeded()
            }
            "STOP_LISTEN" -> {
                prefs().edit().putBoolean("listen", false).apply()
                stopListening()
                updateNotification(false)
            }
        }

        updateNotification(prefs().getBoolean("listen", false))
        val wantBubble = prefs().getBoolean("bubble", false)
        if (wantBubble && Settings.canDrawOverlays(this)) showBubble()
        startHandsFreeIfNeeded()
        return START_STICKY
    }

    override fun onDestroy() {
        stopListening()
        AgentEngine.logListeners.remove(logL)
        AgentEngine.stateListeners.remove(stateL)
        removeViews()
        super.onDestroy()
    }

    private fun updateNotification(listening: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("agent_fg", "Agent running", NotificationManager.IMPORTANCE_LOW)
        )
        val title = if (listening) "Listening for Hey Agent" else "Phone Agent running"
        val text = if (listening)
            "Mic is on. Say \"Hey Agent\" then your task. Tap notification area or turn off in app to stop."
        else
            "Floating bubble active"
        val n = Notification.Builder(this, "agent_fg")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .build()
        startForeground(1, n)
    }

    private fun hasMicPermission(): Boolean {
        return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun startHandsFreeIfNeeded() {
        if (!prefs().getBoolean("listen", false)) return
        if (!hasMicPermission()) {
            ui.post { Toast.makeText(this, "Mic permission needed for Hey Agent", Toast.LENGTH_LONG).show() }
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            ui.post { Toast.makeText(this, "Speech recognition not available on this device", Toast.LENGTH_LONG).show() }
            return
        }
        listeningHandsFree = true
        awaitingCommand = false
        updateNotification(true)
        updateBubbleAppearance(AgentEngine.running)
        startSpeechCycle()
    }

    private fun stopListening() {
        listeningHandsFree = false
        awaitingCommand = false
        try { speech?.cancel() } catch (_: Exception) {}
        try { speech?.destroy() } catch (_: Exception) {}
        speech = null
    }

    private fun ensureSpeech(): SpeechRecognizer? {
        if (speech == null) {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) return null
            speech = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) {
                        // Restart hands-free cycle after a short delay
                        if (listeningHandsFree) {
                            ui.postDelayed({ startSpeechCycle() }, 800)
                        } else {
                            ui.post { micBtn?.text = "Mic" }
                        }
                    }
                    override fun onResults(results: Bundle?) {
                        val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val heard = texts?.firstOrNull()?.trim().orEmpty()
                        handleHeard(heard)
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        }
        return speech
    }

    private fun startSpeechCycle() {
        if (!listeningHandsFree && !awaitingCommand) return
        val sr = ensureSpeech() ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        try {
            sr.startListening(intent)
        } catch (e: Exception) {
            if (listeningHandsFree) ui.postDelayed({ startSpeechCycle() }, 1500)
        }
    }

    private fun handleHeard(raw: String) {
        if (raw.isBlank()) {
            if (listeningHandsFree) ui.postDelayed({ startSpeechCycle() }, 400)
            return
        }
        val lower = raw.lowercase(Locale.getDefault())
        val isWake = lower.contains("hey agent") || lower.contains("hi agent") ||
            lower.contains("okay agent") || lower.contains("ok agent") ||
            lower.startsWith("agent ")

        if (listeningHandsFree && !awaitingCommand) {
            if (isWake) {
                // Strip wake phrase if the command came in the same utterance
                var cmd = raw
                listOf("hey agent", "hi agent", "okay agent", "ok agent", "agent").forEach { w ->
                    val idx = cmd.lowercase(Locale.getDefault()).indexOf(w)
                    if (idx >= 0) cmd = cmd.substring(idx + w.length).trim().trimStart(',', '.', ' ', ':')
                }
                if (cmd.isNotBlank() && cmd.length > 2) {
                    ui.post {
                        Toast.makeText(this, "Got it: $cmd", Toast.LENGTH_SHORT).show()
                        AgentEngine.run(cmd)
                    }
                    awaitingCommand = false
                } else {
                    awaitingCommand = true
                    ui.post {
                        Toast.makeText(this, "Yes? Tell me what to do.", Toast.LENGTH_SHORT).show()
                        updateBubbleAppearance(AgentEngine.running)
                    }
                }
            }
            // Keep listening for wake or next command
            ui.postDelayed({ startSpeechCycle() }, 500)
            return
        }

        if (awaitingCommand || !listeningHandsFree) {
            // This is the actual command (tap-to-talk or post-wake)
            awaitingCommand = false
            ui.post {
                micBtn?.text = "Mic"
                Toast.makeText(this, "Heard: $raw", Toast.LENGTH_SHORT).show()
                if (!AgentEngine.running) AgentEngine.run(raw)
            }
            if (listeningHandsFree) ui.postDelayed({ startSpeechCycle() }, 600)
        }
    }

    /** One-shot listen for a command (tap-to-talk). */
    fun startTapToTalk() {
        if (!hasMicPermission()) {
            Toast.makeText(this, "Allow microphone permission first", Toast.LENGTH_LONG).show()
            return
        }
        if (AgentEngine.running) {
            Toast.makeText(this, "Agent is busy — stop it first", Toast.LENGTH_SHORT).show()
            return
        }
        awaitingCommand = true
        listeningHandsFree = prefs().getBoolean("listen", false) // keep flag
        ui.post { micBtn?.text = "…" }
        Toast.makeText(this, "Listening…", Toast.LENGTH_SHORT).show()
        // Temporarily ensure a cycle runs even if hands-free was off
        val was = listeningHandsFree
        if (!was) listeningHandsFree = true
        startSpeechCycle()
        // If user only wanted tap-to-talk, we'll leave hands-free as prefs say after result
    }

    private fun updateBubbleAppearance(running: Boolean) {
        bubble?.let {
            when {
                running -> {
                    it.text = "■"
                    (it.background as GradientDrawable).setColor(runningRed)
                    it.setTextColor(Color.WHITE)
                }
                prefs().getBoolean("listen", false) -> {
                    it.text = "W"
                    (it.background as GradientDrawable).setColor(listenGreen)
                    it.setTextColor(Color.WHITE)
                }
                else -> {
                    it.text = "W"
                    (it.background as GradientDrawable).setColor(wallEYellow)
                    it.setTextColor(0xFF1A1A1A.toInt())
                }
            }
        }
    }

    private fun showBubble() {
        if (bubble != null) return
        val b = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        }
        updateBubbleAppearance(AgentEngine.running)
        val lp = WindowManager.LayoutParams(
            dp(56), dp(56),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(20); y = dp(200)
        }
        var sx = 0; var sy = 0; var dx = 0; var dy = 0; var moved = false
        b.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = lp.x; sy = lp.y
                    dx = e.rawX.toInt(); dy = e.rawY.toInt()
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val mx = e.rawX.toInt() - dx
                    val my = e.rawY.toInt() - dy
                    if (Math.abs(mx) > 8 || Math.abs(my) > 8) moved = true
                    lp.x = sx + mx; lp.y = sy + my
                    wm.updateViewLayout(v, lp)
                }
                MotionEvent.ACTION_UP -> if (!moved) onBubbleTap()
            }
            true
        }
        wm.addView(b, lp)
        bubble = b
    }

    private fun onBubbleTap() {
        if (AgentEngine.running) {
            AgentEngine.stop()
        } else if (panel == null) {
            showPanel()
        } else {
            hidePanel()
        }
    }

    private fun showPanel() {
        if (panel != null) return

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply {
                setColor(0xF214161C.toInt())
                cornerRadius = dp(18).toFloat()
            }
        }

        box.addView(TextView(this).apply {
            text = "Phone Agent"
            setTextColor(0xFFFFC107.toInt())
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        })

        val lg = TextView(this).apply {
            setTextColor(0xFFE8EAED.toInt())
            textSize = 12.5f
            text = tail.ifEmpty { "Type or tap Mic to speak." }
        }
        val scroll = ScrollView(this).apply {
            addView(lg)
            background = GradientDrawable().apply {
                setColor(0xFF0F1115.toInt())
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        box.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(120)
        ).apply { bottomMargin = dp(10) })

        val et = EditText(this).apply {
            hint = "What should I do?"
            setTextColor(Color.WHITE)
            setHintTextColor(0x99FFFFFF.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                setColor(0xFF0F1115.toInt())
                cornerRadius = dp(10).toFloat()
            }
            maxLines = 3
        }
        box.addView(et, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val send = Button(this).apply {
            text = if (AgentEngine.running) "Stop" else "Send"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(if (AgentEngine.running) 0xFFDC2626.toInt() else 0xFF3B82F6.toInt())
                cornerRadius = dp(10).toFloat()
            }
            setOnClickListener {
                if (AgentEngine.running) AgentEngine.stop()
                else {
                    val t = et.text.toString().trim()
                    if (t.isNotEmpty()) {
                        et.setText("")
                        AgentEngine.run(t)
                    }
                }
            }
        }
        sendBtn = send

        val mic = Button(this).apply {
            text = "Mic"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(0xFF22C55E.toInt())
                cornerRadius = dp(10).toFloat()
            }
            setOnClickListener { startTapToTalk() }
        }
        micBtn = mic

        val openApp = Button(this).apply {
            text = "App"
            setTextColor(0xFFE8EAED.toInt())
            background = GradientDrawable().apply {
                setColor(0xFF2A2D35.toInt())
                cornerRadius = dp(10).toFloat()
            }
            setOnClickListener {
                hidePanel()
                startActivity(Intent(this@OverlayService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }

        val close = Button(this).apply {
            text = "✕"
            setTextColor(0xFFE8EAED.toInt())
            background = GradientDrawable().apply {
                setColor(0xFF2A2D35.toInt())
                cornerRadius = dp(10).toFloat()
            }
            setOnClickListener { hidePanel() }
        }

        row.addView(send, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f).apply { marginEnd = dp(4) })
        row.addView(mic, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        row.addView(openApp, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f).apply { marginEnd = dp(4) })
        row.addView(close, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.6f))
        box.addView(row)

        val lp = WindowManager.LayoutParams(
            dp(320), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16); y = dp(100)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        wm.addView(box, lp)
        panel = box
        panelLog = lg
    }

    private fun hidePanel() {
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null
        panelLog = null
        sendBtn = null
        micBtn = null
    }

    private fun removeViews() {
        hidePanel()
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubble = null
    }
}
