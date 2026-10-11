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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Chat-style main UI matching the design mock:
 * Agent Online status, message bubbles, quick actions, mic + input bar.
 * Settings (API key, toggles) live behind the gear.
 */
class MainActivity : Activity() {
    private lateinit var keyField: EditText
    private lateinit var modelField: EditText
    private lateinit var chatCol: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var send: TextView
    private lateinit var input: EditText
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var settingsPanel: LinearLayout
    private var settingsOpen = false
    private val ui = Handler(Looper.getMainLooper())

    // Mock palette
    private val bg = 0xFF0A0E17.toInt()
    private val card = 0xFF151B28.toInt()
    private val userBubble = 0xFF2563EB.toInt()
    private val agentBubble = 0xFF1C2433.toInt()
    private val accent = 0xFF3B82F6.toInt()
    private val cyan = 0xFF22D3EE.toInt()
    private val textPrimary = 0xFFF1F5F9.toInt()
    private val textMuted = 0xFF94A3B8.toInt()
    private val onlineGreen = 0xFF22C55E.toInt()

    private val logL: (String) -> Unit = { s ->
        ui.post { appendChatLine(s) }
    }
    private val stateL: (Boolean) -> Unit = { running ->
        ui.post {
            statusDot.background = pill(if (running) 0xFFF59E0B.toInt() else onlineGreen, 99f)
            statusText.text = if (running) "Agent Working" else "Agent Online"
            send.text = if (running) "■" else "➤"
            (send.background as? GradientDrawable)?.setColor(if (running) 0xFFDC2626.toInt() else accent)
        }
    }

    private fun prefs() = getSharedPreferences("agent", MODE_PRIVATE)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun pill(color: Int, radiusDp: Float = 16f) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
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
            requestPermissions(
                arrayOf(
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
                ), 1
            )
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
                ), 1
            )
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        // ── Top bar: Agent Online + gear ──
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(12), dp(10))
        }
        statusDot = View(this).apply {
            background = pill(if (AgentEngine.running) 0xFFF59E0B.toInt() else onlineGreen, 99f)
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) }
        }
        statusText = TextView(this).apply {
            text = if (AgentEngine.running) "Agent Working" else "Agent Online"
            setTextColor(textPrimary)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val gear = TextView(this).apply {
            text = "⚙"
            textSize = 20f
            setTextColor(textMuted)
            setPadding(dp(12), dp(6), dp(8), dp(6))
            setOnClickListener { toggleSettings() }
        }
        top.addView(statusDot)
        top.addView(statusText)
        top.addView(gear)
        root.addView(top)

        // ── Collapsible settings ──
        settingsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        val settingsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pill(card, 16f)
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
            background = pill(0xFF0A0E17.toInt(), 12f)
        }
        settingsCard.addView(keyField)
        settingsCard.addView(label("Model").apply { setPadding(0, dp(12), 0, 0) })
        modelField = EditText(this).apply {
            setHint("gemini-2.5-flash")
            setHintTextColor(textMuted)
            setTextColor(textPrimary)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setText(p.getString("model", "gemini-2.5-flash"))
            background = pill(0xFF0A0E17.toInt(), 12f)
        }
        settingsCard.addView(modelField)

        fun toggleRow(title: String, key: String, onEnable: (() -> Unit)? = null): Switch {
            return Switch(this).apply {
                text = "  $title"
                setTextColor(textPrimary)
                isChecked = p.getBoolean(key, false)
                setOnCheckedChangeListener { _, on ->
                    prefs().edit().putBoolean(key, on).apply()
                    if (on) onEnable?.invoke()
                }
            }
        }
        settingsCard.addView(toggleRow("Screen control", "screen") {
            Toast.makeText(this, "Enable Phone Agent under Settings → Accessibility", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }.apply { setPadding(0, dp(10), 0, 0) })
        settingsCard.addView(toggleRow("Floating bubble", "bubble") {
            if (!Settings.canDrawOverlays(this@MainActivity)) {
                Toast.makeText(this@MainActivity, "Allow Display over other apps", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                startForegroundService(Intent(this@MainActivity, OverlayService::class.java))
            }
        })
        settingsCard.addView(toggleRow("Listen for \"Hey Agent\"", "listen") {
            if (!Settings.canDrawOverlays(this@MainActivity)) {
                Toast.makeText(this@MainActivity, "Turn on Floating bubble first", Toast.LENGTH_LONG).show()
                prefs().edit().putBoolean("listen", false).apply()
            } else {
                startForegroundService(Intent(this@MainActivity, OverlayService::class.java).setAction("START_LISTEN"))
                Toast.makeText(this@MainActivity, "Listening on — notification stays while mic is active", Toast.LENGTH_LONG).show()
            }
        })
        val clearBtn = Button(this).apply {
            text = "Clear chat"
            setTextColor(textMuted)
            background = pill(0xFF0A0E17.toInt(), 10f)
            setOnClickListener {
                AgentEngine.clearHistory()
                chatCol.removeAllViews()
            }
        }
        settingsCard.addView(clearBtn.apply {
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(10)
            layoutParams = lp
        })
        settingsPanel.addView(settingsCard)
        root.addView(settingsPanel)

        // ── Chat messages ──
        chatCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(8))
        }
        scroll = ScrollView(this).apply {
            addView(chatCol)
            isFillViewport = true
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ── Quick actions ──
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(4), dp(12), dp(8))
        }
        fun actionChip(emoji: String, label: String, color: Int, prompt: String): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = pill(color, 16f)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(4); marginEnd = dp(4)
                }
                addView(TextView(this@MainActivity).apply {
                    text = emoji; textSize = 18f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
                })
                addView(TextView(this@MainActivity).apply {
                    text = label; textSize = 11f; gravity = Gravity.CENTER; setTextColor(0xE6FFFFFF.toInt())
                    setPadding(0, dp(2), 0, 0)
                })
                setOnClickListener {
                    if (prefs().getString("api_key", "").isNullOrBlank()) {
                        Toast.makeText(this@MainActivity, "Add API key in Settings first", Toast.LENGTH_LONG).show()
                        if (!settingsOpen) toggleSettings()
                    } else if (!AgentEngine.running) {
                        AgentEngine.run(prompt)
                    }
                }
            }
        }
        actions.addView(actionChip("📞", "Call", 0xFF1D4ED8.toInt(), "Open the phone dialer"))
        actions.addView(actionChip("🔔", "Remind me", 0xFF6D28D9.toInt(), "Help me set a reminder — ask what and when"))
        actions.addView(actionChip("➤", "Navigate", 0xFF047857.toInt(), "Help me navigate somewhere — ask where"))
        actions.addView(actionChip("📄", "Summarize", 0xFFB45309.toInt(), "Summarize what's currently on my screen"))
        root.addView(actions)

        // ── Input bar: mic + field + send ──
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(14))
        }
        val mic = TextView(this).apply {
            text = "🎤"
            textSize = 20f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF0EA5E9.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(10) }
            setOnClickListener {
                // Start overlay service and trigger tap-to-talk if bubble is on
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "Enable Floating bubble in Settings for voice", Toast.LENGTH_LONG).show()
                    if (!settingsOpen) toggleSettings()
                    return@setOnClickListener
                }
                startForegroundService(Intent(this@MainActivity, OverlayService::class.java))
                Toast.makeText(this@MainActivity, "Use the bubble Mic button, or type below", Toast.LENGTH_SHORT).show()
            }
        }
        input = EditText(this).apply {
            setHint("Talk to your agent...")
            setHintTextColor(textMuted)
            setTextColor(textPrimary)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = pill(card, 24f)
            maxLines = 4
            setOnEditorActionListener { _, _, _ ->
                submit(); true
            }
        }
        send = TextView(this).apply {
            text = if (AgentEngine.running) "■" else "➤"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = pill(if (AgentEngine.running) 0xFFDC2626.toInt() else accent, 99f)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(10) }
            setOnClickListener {
                if (AgentEngine.running) AgentEngine.stop() else submit()
            }
        }
        bar.addView(mic)
        bar.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(send)
        root.addView(bar)

        setContentView(root)

        // Seed chat from existing transcript
        synchronized(AgentEngine.transcript) {
            val t = AgentEngine.transcript.toString()
            if (t.isNotBlank()) {
                t.split("\n\n").filter { it.isNotBlank() }.forEach { appendChatLine(it) }
            } else {
                appendAgent("Hi — I'm online. Ask me anything or use the shortcuts below.")
            }
        }
        AgentEngine.logListeners.add(logL)
        AgentEngine.stateListeners.add(stateL)
    }

    private fun submit() {
        save()
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        if (prefs().getString("api_key", "").isNullOrBlank()) {
            Toast.makeText(this, "Add your API key in Settings first", Toast.LENGTH_LONG).show()
            if (!settingsOpen) toggleSettings()
            return
        }
        input.setText("")
        AgentEngine.run(t)
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 12f
        setTextColor(textMuted)
        setPadding(0, 0, 0, dp(6))
    }

    private fun toggleSettings() {
        settingsOpen = !settingsOpen
        settingsPanel.visibility = if (settingsOpen) View.VISIBLE else View.GONE
        if (!settingsOpen) save()
    }

    private fun appendChatLine(raw: String) {
        val s = raw.trim()
        if (s.isEmpty()) return
        when {
            s.startsWith("You:") -> appendUser(s.removePrefix("You:").trim())
            s.startsWith("🤖") -> appendAgent(s.removePrefix("🤖").trim())
            s.startsWith("💭") -> appendAgent(s.removePrefix("💭").trim(), muted = true)
            s.startsWith("→") || s.startsWith("…") -> { /* tool noise — skip in clean chat */ }
            s.startsWith("⚠") || s.startsWith("⏹") -> appendAgent(s)
            s == "History cleared." -> chatCol.removeAllViews()
            else -> appendAgent(s)
        }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun appendUser(text: String) {
        if (text.isBlank()) return
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(dp(40), dp(4), 0, dp(4))
        }
        val bubble = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = pill(userBubble, 18f)
        }
        row.addView(bubble)
        chatCol.addView(row)
    }

    private fun appendAgent(text: String, muted: Boolean = false) {
        if (text.isBlank()) return
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            setPadding(0, dp(4), dp(40), dp(4))
        }
        // Orb avatar
        val orb = TextView(this).apply {
            this.text = "◇"
            gravity = Gravity.CENTER
            setTextColor(cyan)
            textSize = 14f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF0C1929.toInt())
                setStroke(dp(2), cyan)
            }
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                marginEnd = dp(8)
                gravity = Gravity.TOP
            }
        }
        val bubble = TextView(this).apply {
            this.text = text
            setTextColor(if (muted) textMuted else textPrimary)
            textSize = 15f
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = pill(agentBubble, 18f)
        }
        row.addView(orb)
        row.addView(bubble, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        chatCol.addView(row)
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
