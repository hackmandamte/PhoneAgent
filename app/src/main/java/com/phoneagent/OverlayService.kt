package com.phoneagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Foreground service: keeps the agent alive and draws the WALL-E floating bubble. */
class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private var bubble: TextView? = null
    private var panel: LinearLayout? = null
    private var panelLog: TextView? = null
    private var sendBtn: Button? = null
    private var tail = ""

    // WALL-E colors
    private val wallEYellow = 0xE6FFC107.toInt()
    private val wallEDark = 0xE61A1A1A.toInt()
    private val runningRed = 0xE6DC2626.toInt()

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
            bubble?.let {
                it.text = if (running) "■" else "W"
                (it.background as GradientDrawable).setColor(if (running) runningRed else wallEYellow)
                it.setTextColor(if (running) Color.WHITE else 0xFF1A1A1A.toInt())
            }
            sendBtn?.text = if (running) "Stop" else "Send"
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        AgentEngine.logListeners.add(logL)
        AgentEngine.stateListeners.add(stateL)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            removeViews()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("agent_fg", "Agent running", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, Notification.Builder(this, "agent_fg")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Phone Agent running").build())
        val wantBubble = getSharedPreferences("agent", MODE_PRIVATE).getBoolean("bubble", false)
        if (wantBubble && Settings.canDrawOverlays(this)) showBubble()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        AgentEngine.logListeners.remove(logL)
        AgentEngine.stateListeners.remove(stateL)
        removeViews()
        super.onDestroy()
    }

    private fun showBubble() {
        if (bubble != null) return
        val running = AgentEngine.running
        val b = TextView(this).apply {
            text = if (running) "■" else "W"
            gravity = Gravity.CENTER
            setTextColor(if (running) Color.WHITE else 0xFF1A1A1A.toInt())
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (running) runningRed else wallEYellow)
            }
        }
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

        // Header
        val header = TextView(this).apply {
            text = "Phone Agent"
            setTextColor(0xFFFFC107.toInt())
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        }
        box.addView(header)

        // Log (scrollable)
        val lg = TextView(this).apply {
            setTextColor(0xFFE8EAED.toInt())
            textSize = 12.5f
            text = tail.ifEmpty { "Tap Send to give me a task." }
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
            LinearLayout.LayoutParams.MATCH_PARENT, dp(140)
        ).apply { bottomMargin = dp(10) })

        // Input
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

        // Buttons row
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val send = Button(this).apply {
            text = if (AgentEngine.running) "Stop" else "Send"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(if (AgentEngine.running) 0xFFDC2626.toInt() else 0xFF3B82F6.toInt())
                cornerRadius = dp(10).toFloat()
            }
            setOnClickListener {
                if (AgentEngine.running) {
                    AgentEngine.stop()
                } else {
                    val t = et.text.toString().trim()
                    if (t.isNotEmpty()) {
                        et.setText("")
                        AgentEngine.run(t)
                    }
                }
            }
        }
        sendBtn = send

        val openApp = Button(this).apply {
            text = "App"
            setTextColor(0xFFE8EAED.toInt())
            background = GradientDrawable().apply {
                setColor(0xFF2A2D35.toInt())
                cornerRadius = dp(10).toFloat()
            }
            setOnClickListener {
                hidePanel()
                val i = Intent(this@OverlayService, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
            }
        }

        val close = Button(this).apply {
            text = "Close"
            setTextColor(0xFFE8EAED.toInt())
            background = GradientDrawable().apply {
                setColor(0xFF2A2D35.toInt())
                cornerRadius = dp(10).toFloat()
            }
            setOnClickListener { hidePanel() }
        }

        row.addView(send, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        row.addView(openApp, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        row.addView(close, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
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
        panel?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        panel = null
        panelLog = null
        sendBtn = null
    }

    private fun removeViews() {
        hidePanel()
        bubble?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        bubble = null
    }
}
