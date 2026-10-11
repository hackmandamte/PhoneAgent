package com.phoneagent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var keyField: EditText
    private lateinit var modelField: EditText
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var send: Button
    private lateinit var input: EditText
    private val ui = Handler(Looper.getMainLooper())

    private val logL: (String) -> Unit = { s ->
        ui.post {
            log.append(s + "\n\n")
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }
    private val stateL: (Boolean) -> Unit = { r -> ui.post { send.text = if (r) "Stop" else "Send" } }

    private fun prefs() = getSharedPreferences("agent", MODE_PRIVATE)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun save() {
        prefs().edit()
            .putString("api_key", keyField.text.toString().trim())
            .putString("model", modelField.text.toString().trim())
            .apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = prefs()
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_MEDIA_VIDEO), 1)
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        root.addView(TextView(this).apply {
            text = "Phone Agent"; textSize = 22f; setTypeface(null, Typeface.BOLD)
        })

        keyField = EditText(this).apply {
            setHint("Gemini API key (aistudio.google.com)")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(p.getString("api_key", ""))
        }
        modelField = EditText(this).apply {
            setHint("Model (e.g. gemini-2.5-flash)")
            setText(p.getString("model", "gemini-2.5-flash"))
        }
        root.addView(keyField)
        root.addView(modelField)

        val screenSw = Switch(this).apply {
            text = "Screen control (Accessibility)"
            isChecked = p.getBoolean("screen", false)
            setOnCheckedChangeListener { _, on ->
                prefs().edit().putBoolean("screen", on).apply()
                if (on) {
                    Toast.makeText(this@MainActivity, "Enable 'Phone Agent' under Settings → Accessibility", Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
        }
        val bubbleSw = Switch(this).apply {
            text = "Floating bubble"
            isChecked = p.getBoolean("bubble", false)
            setOnCheckedChangeListener { _, on ->
                prefs().edit().putBoolean("bubble", on).apply()
                if (on) {
                    if (!Settings.canDrawOverlays(this@MainActivity)) {
                        Toast.makeText(this@MainActivity, "Allow 'Display over other apps', then come back", Toast.LENGTH_LONG).show()
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")))
                    } else {
                        startForegroundService(Intent(this@MainActivity, OverlayService::class.java))
                    }
                } else {
                    startService(Intent(this@MainActivity, OverlayService::class.java).setAction("STOP"))
                }
            }
        }
        root.addView(screenSw)
        root.addView(bubbleSw)

        val clearBtn = Button(this).apply {
            text = "Clear history"
            setOnClickListener {
                AgentEngine.clearHistory()
                log.text = ""
            }
        }
        root.addView(clearBtn)

        log = TextView(this).apply { textSize = 14f; setTextIsSelectable(true) }
        scroll = ScrollView(this).apply { addView(log) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        input = EditText(this).apply { setHint("What should I do?") }
        send = Button(this).apply { text = if (AgentEngine.running) "Stop" else "Send" }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(send)
        root.addView(row)
        setContentView(root)

        send.setOnClickListener {
            if (AgentEngine.running) { AgentEngine.stop(); return@setOnClickListener }
            save()
            val t = input.text.toString().trim()
            if (t.isEmpty()) return@setOnClickListener
            input.setText("")
            AgentEngine.run(t)
        }

        synchronized(AgentEngine.transcript) { log.text = AgentEngine.transcript.toString() }
        AgentEngine.logListeners.add(logL)
        AgentEngine.stateListeners.add(stateL)
    }

    override fun onResume() {
        super.onResume()
        if (prefs().getBoolean("bubble", false) && Settings.canDrawOverlays(this)) {
            startForegroundService(Intent(this, OverlayService::class.java))
        }
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    override fun onDestroy() {
        AgentEngine.logListeners.remove(logL)
        AgentEngine.stateListeners.remove(stateL)
        super.onDestroy()
    }
}
