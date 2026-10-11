package com.phoneagent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
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
    private lateinit var settingsPanel: LinearLayout
    private var settingsOpen = false
    private val ui = Handler(Looper.getMainLooper())

    private val bg = 0xFF0F1115.toInt()
    private val card = 0xFF1A1D24.toInt()
    private val accent = 0xFF3B82F6.toInt()
    private val textPrimary = 0xFFE8EAED.toInt()
    private val textMuted = 0xFF9AA0A6.toInt()

    private val logL: (String) -> Unit = { s ->
        ui.post {
            log.append(s + "\n\n")
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }
    private val stateL: (Boolean) -> Unit = { r ->
        ui.post {
            send.text = if (r) "Stop" else "Send"
            send.background = pill(if (r) 0xFFDC2626.toInt() else accent)
        }
    }

    private fun prefs() = getSharedPreferences("agent", MODE_PRIVATE)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun pill(color: Int, radius: Float = 12f) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius.toInt()).toFloat()
    }

    private fun save() {
        prefs().edit()
            .putString("api_key", keyField.text.toString().trim())
            .putString("model", modelField.text.toString().trim())
            .apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        val p = prefs()
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.CAMERA), 1)
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.CAMERA), 1)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(16), dp(16), dp(16), dp(12))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "Phone Agent"
            textSize = 22f
            setTextColor(textPrimary)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val settingsBtn = TextView(this).apply {
            text = "Settings"
            textSize = 14f
            setTextColor(accent)
            setPadding(dp(12), dp(8), dp(4), dp(8))
            setOnClickListener { toggleSettings() }
        }
        header.addView(settingsBtn)
        root.addView(header)

        settingsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(12), 0, dp(8))
        }

        val settingsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pill(card, 14f)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        settingsCard.addView(label("Gemini API Key"))
        keyField = EditText(this).apply {
            setHint("Paste key from aistudio.google.com")
            setHintTextColor(textMuted)
            setTextColor(textPrimary)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(p.getString("api_key", ""))
            background = pill(0xFF0F1115.toInt(), 10f)
        }
        settingsCard.addView(keyField)

        settingsCard.addView(label("Model").apply { setPadding(0, dp(12), 0, 0) })
        modelField = EditText(this).apply {
            setHint("gemini-2.5-flash")
            setHintTextColor(textMuted)
            setTextColor(textPrimary)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setText(p.getString("model", "gemini-2.5-flash"))
            background = pill(0xFF0F1115.toInt(), 10f)
        }
        settingsCard.addView(modelField)

        val note = TextView(this).apply {
            text = "Key is stored only on this device in private app storage."
            textSize = 12f
            setTextColor(textMuted)
            setPadding(0, dp(10), 0, 0)
        }
        settingsCard.addView(note)
        settingsPanel.addView(settingsCard)
        root.addView(settingsPanel)

        val toggles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pill(card, 14f)
            setPadding(dp(14), dp(4), dp(14), dp(4))
        }
        val screenSw = Switch(this).apply {
            text = "  Screen control"
            setTextColor(textPrimary)
            isChecked = p.getBoolean("screen", false)
            setOnCheckedChangeListener { _, on ->
                prefs().edit().putBoolean("screen", on).apply()
                if (on) {
                    Toast.makeText(this@MainActivity, "Enable Phone Agent under Settings → Accessibility", Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
        }
        val bubbleSw = Switch(this).apply {
            text = "  Floating bubble"
            setTextColor(textPrimary)
            isChecked = p.getBoolean("bubble", false)
            setOnCheckedChangeListener { _, on ->
                prefs().edit().putBoolean("bubble", on).apply()
                if (on) {
                    if (!Settings.canDrawOverlays(this@MainActivity)) {
                        Toast.makeText(this@MainActivity, "Allow Display over other apps", Toast.LENGTH_LONG).show()
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    } else {
                        startForegroundService(Intent(this@MainActivity, OverlayService::class.java))
                    }
                } else {
                    startService(Intent(this@MainActivity, OverlayService::class.java).setAction("STOP"))
                }
            }
        }
        toggles.addView(screenSw)
        toggles.addView(bubbleSw)
        root.addView(toggles.apply {
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(12)
            layoutParams = lp
        })

        val clearBtn = Button(this).apply {
            text = "Clear history"
            setTextColor(textMuted)
            background = pill(card, 10f)
            setOnClickListener {
                AgentEngine.clearHistory()
                log.text = ""
            }
        }
        root.addView(clearBtn.apply {
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(10)
            layoutParams = lp
        })

        log = TextView(this).apply {
            textSize = 13.5f
            setTextColor(textPrimary)
            setTextIsSelectable(true)
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        scroll = ScrollView(this).apply {
            addView(log)
            background = pill(card, 14f)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(12)
            bottomMargin = dp(12)
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        input = EditText(this).apply {
            setHint("What should I do?")
            setHintTextColor(textMuted)
            setTextColor(textPrimary)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = pill(card, 14f)
            maxLines = 4
        }
        send = Button(this).apply {
            text = if (AgentEngine.running) "Stop" else "Send"
            setTextColor(Color.WHITE)
            background = pill(if (AgentEngine.running) 0xFFDC2626.toInt() else accent, 14f)
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(10)
        })
        row.addView(send)
        root.addView(row)

        setContentView(root)

        send.setOnClickListener {
            if (AgentEngine.running) {
                AgentEngine.stop()
                return@setOnClickListener
            }
            save()
            val t = input.text.toString().trim()
            if (t.isEmpty()) return@setOnClickListener
            if (prefs().getString("api_key", "").isNullOrBlank()) {
                Toast.makeText(this, "Add your API key in Settings first", Toast.LENGTH_LONG).show()
                if (!settingsOpen) toggleSettings()
                return@setOnClickListener
            }
            input.setText("")
            AgentEngine.run(t)
        }

        synchronized(AgentEngine.transcript) { log.text = AgentEngine.transcript.toString() }
        AgentEngine.logListeners.add(logL)
        AgentEngine.stateListeners.add(stateL)
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(textMuted)
        setPadding(0, 0, 0, dp(6))
    }

    private fun toggleSettings() {
        settingsOpen = !settingsOpen
        settingsPanel.visibility = if (settingsOpen) View.VISIBLE else View.GONE
        if (!settingsOpen) save()
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
