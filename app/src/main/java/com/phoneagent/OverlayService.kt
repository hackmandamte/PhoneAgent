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
import android.widget.TextView
import android.widget.Toast

/** Foreground service: keeps the agent alive in the background and draws the floating bubble. */
class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private var bubble: TextView? = null
    private var panel: LinearLayout? = null
    private var panelLog: TextView? = null
    private var tail = ""

    private val logL: (String) -> Unit = { s ->
        ui.post {
            tail = (tail + s + "\n\n").takeLast(700)
            panelLog?.text = tail
            if (s.startsWith("🤖") && panel == null) {
                Toast.makeText(this, s.take(250), Toast.LENGTH_LONG).show()
            }
        }
    }
    private val stateL: (Boolean) -> Unit = { running ->
        ui.post {
            bubble?.let {
                it.text = if (running) "■" else "AI"
                (it.background as GradientDrawable).setColor(
                    if (running) 0xE6FF3B30.toInt() else 0xE60A84FF.toInt())
            }
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
        val b = TextView(this).apply {
            text = if (AgentEngine.running) "■" else "AI"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (AgentEngine.running) 0xE6FF3B30.toInt() else 0xE60A84FF.toInt())
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
        if (AgentEngine.running) AgentEngine.stop()
        else if (panel == null) showPanel()
        else hidePanel()
    }

    private fun showPanel() {
        if (panel != null) return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply { setColor(0xF2202124.toInt()); cornerRadius = dp(16).toFloat() }
        }
        val lg = TextView(this).apply { setTextColor(Color.WHITE); textSize = 12f; maxLines = 8; text = tail }
        val et = EditText(this).apply {
            hint = "Tell the agent what to do"
            setTextColor(Color.WHITE)
            setHintTextColor(0x99FFFFFF.toInt())
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val send = Button(this).apply {
            text = "Send"
            setOnClickListener {
                val t = et.text.toString().trim()
                if (t.isNotEmpty()) { hidePanel(); AgentEngine.run(t) }
            }
        }
        val close = Button(this).apply { text = "Close"; setOnClickListener { hidePanel() } }
        row.addView(send); row.addView(close)
        box.addView(lg); box.addView(et); box.addView(row)

        val lp = WindowManager.LayoutParams(
            dp(300), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16); y = dp(120)
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
    }

    private fun removeViews() {
        hidePanel()
        bubble?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        bubble = null
    }
}
