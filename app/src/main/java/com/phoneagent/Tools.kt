package com.phoneagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.speech.tts.TextToSpeech
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

object Tools {
    private const val S = "STRING"
    private const val N = "NUMBER"
    private const val B = "BOOLEAN"
    private const val NOSVC = "ERROR: screen control not connected. Ask the user to enable Phone Agent in Settings > Accessibility."

    private class T(
        val name: String, val desc: String,
        val params: Map<String, String> = emptyMap(),
        val optional: Set<String> = emptySet(),
        val screen: Boolean = false
    )

    private val all = listOf(
        T("open_app", "Launch an installed app by name or package.", mapOf("name" to S)),
        T("list_apps", "List installed launchable apps (name and package). Useful before open_app."),
        T("open_url", "Open a URL in the browser.", mapOf("url" to S)),
        T("web_fetch", "Fetch a web page as plain text (strips HTML).", mapOf("url" to S)),
        T("notify", "Show a notification.", mapOf("title" to S, "text" to S)),
        T("speak", "Speak text aloud.", mapOf("text" to S)),
        T("battery", "Battery level and charging state."),
        T("datetime", "Current date, time and timezone on the device."),
        T("clipboard_get", "Read the clipboard (Android may block this in background)."),
        T("clipboard_set", "Copy text to the clipboard.", mapOf("text" to S)),
        T("share_text", "Open the Android share sheet with the given text.", mapOf("text" to S, "title" to S), setOf("title")),
        T("torch", "Flashlight on or off.", mapOf("on" to B)),
        T("set_alarm", "Set an alarm (24h time).", mapOf("hour" to N, "minute" to N, "label" to S), setOf("label")),
        T("set_timer", "Start a countdown timer.", mapOf("seconds" to N, "label" to S), setOf("label")),
        T("compose_sms", "Open the SMS composer with a draft. It is not sent until send is tapped.", mapOf("number" to S, "text" to S)),
        T("dial", "Open the dialer with a number (does not call).", mapOf("number" to S)),
        T("open_settings", "Open a system Settings page. page: main, accessibility, apps, display, sound, wifi, bluetooth, battery, notifications, overlay, app_info.", mapOf("page" to S)),
        T("youtube_search", "Open YouTube (app if installed, else browser) with a search query.", mapOf("query" to S)),
        T("youtube_open", "Open a specific YouTube video by URL or video ID.", mapOf("url_or_id" to S)),
        T("youtube_studio", "Open YouTube Studio (channel dashboard / upload area)."),
        T("youtube_channel", "Open a YouTube channel by handle, URL, or channel ID.", mapOf("handle_or_url" to S)),
        T("draft_upload", "Build a YouTube upload draft (title + description + tags) and copy it to the clipboard so you can paste into YouTube Studio.", mapOf("title" to S, "description" to S, "tags" to S), setOf("tags")),
        T("list_videos", "List recent local video files on the device (name, path, duration, size). Optional limit (default 30)."),
        T("video_info", "Get detailed info (duration, resolution, codec, size) for a local video by path.", mapOf("path" to S)),
        T("trim_video", "Trim a local video you own. start_sec and end_sec are seconds from the beginning. Output is written next to the source or to agent storage.", mapOf("path" to S, "start_sec" to N, "end_sec" to N, "output_name" to S), setOf("output_name")),
        T("split_video", "Split a local video you own into sequential segments of segment_sec seconds each. Returns list of output paths.", mapOf("path" to S, "segment_sec" to N, "output_prefix" to S), setOf("output_prefix")),
        T("open_video", "Open a local video file in the default player or gallery.", mapOf("path" to S)),
        T("share_video", "Share a local video file via the Android share sheet (can send to YouTube app).", mapOf("path" to S, "title" to S), setOf("title")),
        T("read_file", "Read a text file from the agent's private storage.", mapOf("path" to S)),
        T("write_file", "Write a text file in the agent's private storage.", mapOf("path" to S, "content" to S)),
        T("list_files", "List files in the agent's private storage.", mapOf("path" to S), setOf("path")),
        T("remember", "Store a durable fact for later recall (e.g. channel templates, preferred titles).", mapOf("key" to S, "value" to S)),
        T("recall", "Recall stored facts (empty key = all).", mapOf("key" to S), setOf("key")),
        T("wait", "Pause up to 60 seconds.", mapOf("seconds" to N)),
        T("screen_read", "List visible UI elements with text, flags and tap coordinates.", screen = true),
        T("tap_text", "Tap the element containing this text.", mapOf("text" to S), screen = true),
        T("tap_xy", "Tap screen coordinates.", mapOf("x" to N, "y" to N), screen = true),
        T("type_text", "Type into the focused text field (tap it first).", mapOf("text" to S, "append" to B), setOf("append"), screen = true),
        T("scroll", "Scroll: 'down' reveals content below, 'up' above, 'left'/'right' sideways.", mapOf("direction" to S), screen = true),
        T("press", "Press a system key: back, home, recents, notifications, quick_settings.", mapOf("key" to S), screen = true)
    )

    fun declarations(screenOn: Boolean): JSONArray {
        val arr = JSONArray()
        for (t in all) {
            if (t.screen && !screenOn) continue
            val params = JSONObject()
            val required = JSONArray()
            for ((k, typ) in t.params) {
                params.put(k, JSONObject().put("type", typ.lowercase()))
                if (k !in t.optional) required.put(k)
            }
            arr.put(JSONObject()
                .put("name", t.name)
                .put("description", t.desc)
                .put("parameters", JSONObject()
                    .put("type", "object")
                    .put("properties", params)
                    .put("required", required)))
        }
        return arr
    }

    fun run(ctx: Context, name: String, a: JSONObject, screenOn: Boolean): String {
        fun screen(block: (AgentAccessibilityService) -> String): String {
            val svc = AgentAccessibilityService.instance
            return if (svc == null || !screenOn) NOSVC else block(svc)
        }
        return when (name) {
            "open_app" -> openApp(ctx, a.getString("name"))
            "list_apps" -> listApps(ctx)
            "open_url" -> go(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(a.getString("url"))))
            "web_fetch" -> fetch(a.getString("url"))
            "notify" -> notify(ctx, a.getString("title"), a.getString("text"))
            "speak" -> speak(ctx, a.getString("text"))
            "battery" -> battery(ctx)
            "datetime" -> {
                val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)
                df.timeZone = TimeZone.getDefault()
                "now=${df.format(Date())} tz=${TimeZone.getDefault().id}"
            }
            "clipboard_get" -> {
                val cm = ctx.getSystemService(ClipboardManager::class.java)
                cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: "(empty)"
            }
            "clipboard_set" -> {
                val cm = ctx.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("agent", a.getString("text")))
                "copied"
            }
            "share_text" -> {
                val i = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, a.getString("text"))
                    putExtra(Intent.EXTRA_SUBJECT, a.optString("title", "Shared by Phone Agent"))
                }
                go(ctx, Intent.createChooser(i, a.optString("title", "Share")))
            }
            "torch" -> torch(ctx, a.getBoolean("on"))
            "set_alarm" -> {
                val i = Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, a.getInt("hour"))
                    .putExtra(AlarmClock.EXTRA_MINUTES, a.getInt("minute"))
                    .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Phone Agent"))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                go(ctx, i)
            }
            "set_timer" -> {
                val i = Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, a.getInt("seconds"))
                    .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Phone Agent"))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                go(ctx, i)
            }
            "compose_sms" -> {
                val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + a.getString("number")))
                    .putExtra("sms_body", a.getString("text"))
                go(ctx, i)
            }
            "dial" -> go(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + a.getString("number"))))
            "open_settings" -> openSettings(ctx, a.getString("page"))
            "youtube_search" -> youtubeSearch(ctx, a.getString("query"))
            "youtube_open" -> youtubeOpen(ctx, a.getString("url_or_id"))
            "youtube_studio" -> youtubeStudio(ctx)
            "youtube_channel" -> youtubeChannel(ctx, a.getString("handle_or_url"))
            "draft_upload" -> draftUpload(ctx, a.getString("title"), a.getString("description"), a.optString("tags", ""))
            "list_videos" -> listVideos(ctx, a.optInt("limit", 30))
            "video_info" -> videoInfo(a.getString("path"))
            "trim_video" -> trimVideo(ctx, a.getString("path"), a.getDouble("start_sec"), a.getDouble("end_sec"), a.optString("output_name", ""))
            "split_video" -> splitVideo(ctx, a.getString("path"), a.getDouble("segment_sec"), a.optString("output_prefix", ""))
            "open_video" -> openVideo(ctx, a.getString("path"))
            "share_video" -> shareVideo(ctx, a.getString("path"), a.optString("title", "Video"))
            "read_file" -> {
                val f = safe(ctx, a.getString("path"))
                if (!f.exists()) "ERROR: not found" else f.readText().take(50_000)
            }
            "write_file" -> {
                val f = safe(ctx, a.getString("path"))
                f.parentFile?.mkdirs()
                f.writeText(a.getString("content"))
                "wrote ${f.length()} bytes"
            }
            "list_files" -> {
                val dir = safe(ctx, a.optString("path", "."))
                if (!dir.exists()) "ERROR: not found"
                else if (dir.isFile) dir.name
                else (dir.listFiles()?.joinToString("\n") { (if (it.isDirectory) "[dir] " else "") + it.name }
                    ?: "(empty)").ifEmpty { "(empty)" }
            }
            "remember" -> {
                val p = ctx.getSharedPreferences("agent_mem", Context.MODE_PRIVATE)
                p.edit().putString(a.getString("key"), a.getString("value")).apply()
                "stored"
            }
            "recall" -> {
                val p = ctx.getSharedPreferences("agent_mem", Context.MODE_PRIVATE)
                val k = a.optString("key", "")
                if (k.isEmpty()) p.all.entries.joinToString("\n") { "${it.key}: ${it.value}" }.ifEmpty { "(nothing stored)" }
                else (p.getString(k, null) ?: "(not found)")
            }
            "wait" -> {
                var ms = minOf(a.getDouble("seconds"), 60.0).toLong() * 1000
                while (ms > 0 && !AgentEngine.cancelled) { Thread.sleep(250); ms -= 250 }
                "done"
            }
            "screen_read" -> screen { it.readScreen() }
            "tap_text" -> screen { it.tapText(a.getString("text")) }
            "tap_xy" -> screen { it.tapXY(a.getDouble("x").toFloat(), a.getDouble("y").toFloat()) }
            "type_text" -> screen { it.typeText(a.getString("text"), a.optBoolean("append", false)) }
            "scroll" -> screen { it.scroll(a.optString("direction", "down")) }
            "press" -> screen { it.press(a.getString("key")) }
            else -> "ERROR: unknown tool $name"
        }
    }

    private fun go(ctx: Context, i: Intent): String {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        return "ok"
    }

    private fun openApp(ctx: Context, name: String): String {
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val n = name.lowercase()
        val hit = apps.firstOrNull { it.activityInfo.packageName.equals(name, true) }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == n }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(n) }
            ?: return "ERROR: no app matches '$name'. Use list_apps to see installed apps."
        val i = pm.getLaunchIntentForPackage(hit.activityInfo.packageName)
            ?: return "ERROR: cannot launch ${hit.activityInfo.packageName}"
        return go(ctx, i)
    }

    private fun listApps(ctx: Context): String {
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        return apps
            .map { "${it.loadLabel(pm)} (${it.activityInfo.packageName})" }
            .sorted()
            .joinToString("\n")
            .take(8000)
            .ifEmpty { "(none)" }
    }

    private fun openSettings(ctx: Context, page: String): String {
        val action = when (page.lowercase()) {
            "main" -> Settings.ACTION_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "notifications" -> Settings.ACTION_APP_NOTIFICATION_SETTINGS
            "overlay" -> Settings.ACTION_MANAGE_OVERLAY_PERMISSION
            "app_info" -> Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            else -> return "ERROR: unknown page. Use: main, accessibility, apps, display, sound, wifi, bluetooth, battery, notifications, overlay, app_info"
        }
        val i = Intent(action)
        if (page.lowercase() in setOf("notifications", "overlay", "app_info")) {
            i.data = Uri.parse("package:" + ctx.packageName)
        }
        return go(ctx, i)
    }

    private fun youtubeSearch(ctx: Context, query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val appIntent = Intent(Intent.ACTION_SEARCH).apply {
            setPackage("com.google.android.youtube")
            putExtra("query", query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            ctx.startActivity(appIntent)
            "opened YouTube app search for: $query"
        } catch (_: Exception) {
            go(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded")))
            "opened YouTube web search for: $query"
        }
    }

    private fun youtubeOpen(ctx: Context, urlOrId: String): String {
        val raw = urlOrId.trim()
        val id = when {
            raw.matches(Regex("^[a-zA-Z0-9_-]{11}$")) -> raw
            raw.contains("youtu.be/") -> raw.substringAfter("youtu.be/").takeWhile { it != '?' && it != '&' }
            raw.contains("v=") -> raw.substringAfter("v=").takeWhile { it != '&' }
            raw.contains("/shorts/") -> raw.substringAfter("/shorts/").takeWhile { it != '?' && it != '&' }
            else -> null
        }
        val uri = if (id != null) Uri.parse("https://www.youtube.com/watch?v=$id")
                  else Uri.parse(if (raw.startsWith("http")) raw else "https://www.youtube.com/watch?v=$raw")
        val appIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.youtube")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            ctx.startActivity(appIntent)
            "opened in YouTube app: $uri"
        } catch (_: Exception) {
            go(ctx, Intent(Intent.ACTION_VIEW, uri))
            "opened in browser: $uri"
        }
    }

    private fun youtubeStudio(ctx: Context): String {
        val studioUri = Uri.parse("https://studio.youtube.com")
        val appIntent = Intent(Intent.ACTION_VIEW, studioUri).apply {
            setPackage("com.google.android.apps.youtube.creator")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            ctx.startActivity(appIntent)
            "opened YouTube Studio app"
        } catch (_: Exception) {
            try {
                val yt = Intent(Intent.ACTION_VIEW, studioUri).apply {
                    setPackage("com.google.android.youtube")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(yt)
                "opened YouTube Studio via YouTube app"
            } catch (_: Exception) {
                go(ctx, Intent(Intent.ACTION_VIEW, studioUri))
                "opened YouTube Studio in browser"
            }
        }
    }

    private fun youtubeChannel(ctx: Context, handleOrUrl: String): String {
        val raw = handleOrUrl.trim().removePrefix("@")
        val uri = when {
            raw.startsWith("http") -> Uri.parse(raw)
            raw.startsWith("UC") && raw.length >= 24 -> Uri.parse("https://www.youtube.com/channel/$raw")
            else -> Uri.parse("https://www.youtube.com/@$raw")
        }
        val appIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.youtube")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            ctx.startActivity(appIntent)
            "opened channel in YouTube app: $uri"
        } catch (_: Exception) {
            go(ctx, Intent(Intent.ACTION_VIEW, uri))
            "opened channel in browser: $uri"
        }
    }

    private fun draftUpload(ctx: Context, title: String, description: String, tags: String): String {
        val draft = buildString {
            appendLine("TITLE:")
            appendLine(title)
            appendLine()
            appendLine("DESCRIPTION:")
            appendLine(description)
            if (tags.isNotBlank()) {
                appendLine()
                appendLine("TAGS:")
                appendLine(tags)
            }
        }
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("youtube_draft", draft))
        return "Draft copied to clipboard. Open YouTube Studio → Upload and paste title/description/tags.\n\n$draft"
    }

    private fun listVideos(ctx: Context, limit: Int): String {
        val results = mutableListOf<String>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATA
        )
        val sort = "${MediaStore.Video.Media.DATE_ADDED} DESC"
        try {
            ctx.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection, null, null, sort
            )?.use { c ->
                val nameIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dataIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                var count = 0
                while (c.moveToNext() && count < limit.coerceIn(1, 100)) {
                    val name = c.getString(nameIdx) ?: "?"
                    val durMs = c.getLong(durIdx)
                    val size = c.getLong(sizeIdx)
                    val path = c.getString(dataIdx) ?: ""
                    val dur = String.format("%d:%02d", TimeUnit.MILLISECONDS.toMinutes(durMs),
                        TimeUnit.MILLISECONDS.toSeconds(durMs) % 60)
                    val mb = String.format("%.1f", size / 1_000_000.0)
                    results.add("$name | $dur | ${mb}MB | $path")
                    count++
                }
            }
        } catch (e: Exception) {
            return "ERROR listing videos (permission?): ${e.message}"
        }
        return if (results.isEmpty()) "(no videos found or permission denied – grant Photos/Videos access)" 
               else results.joinToString("\n")
    }

    private fun videoInfo(path: String): String {
        val f = File(path)
        if (!f.exists()) return "ERROR: file not found: $path"
        return try {
            val info = FFprobeKit.getMediaInformation(path)
            val mi = info?.mediaInformation
            if (mi == null) "ERROR: could not probe video"
            else buildString {
                appendLine("path=$path")
                appendLine("duration=${mi.duration}s")
                appendLine("size=${mi.size} bytes")
                appendLine("bitrate=${mi.bitrate}")
                mi.streams?.forEach { s ->
                    appendLine("stream: type=${s.type} codec=${s.codec} ${s.width}x${s.height}")
                }
            }.trim()
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    private fun outputDir(ctx: Context): File {
        val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "PhoneAgent")
        dir.mkdirs()
        return dir
    }

    private fun trimVideo(ctx: Context, path: String, startSec: Double, endSec: Double, outputName: String): String {
        val src = File(path)
        if (!src.exists()) return "ERROR: source not found: $path"
        if (endSec <= startSec) return "ERROR: end_sec must be > start_sec"
        val name = if (outputName.isNotBlank()) {
            if (outputName.endsWith(".mp4")) outputName else "$outputName.mp4"
        } else {
            "trim_${src.nameWithoutExtension}_${startSec.toInt()}-${endSec.toInt()}.mp4"
        }
        val out = File(outputDir(ctx), name)
        val cmd = "-y -ss $startSec -to $endSec -i \"${src.absolutePath}\" -c copy \"${out.absolutePath}\""
        val session = FFmpegKit.execute(cmd)
        return if (ReturnCode.isSuccess(session.returnCode)) {
            "trimmed → ${out.absolutePath} (${out.length() / 1024} KB)"
        } else {
            val cmd2 = "-y -ss $startSec -to $endSec -i \"${src.absolutePath}\" -c:v libx264 -c:a aac -preset veryfast \"${out.absolutePath}\""
            val s2 = FFmpegKit.execute(cmd2)
            if (ReturnCode.isSuccess(s2.returnCode)) {
                "trimmed (re-encoded) → ${out.absolutePath} (${out.length() / 1024} KB)"
            } else {
                "ERROR: FFmpeg failed: ${s2.failStackTrace?.take(400) ?: s2.output?.take(400)}"
            }
        }
    }

    private fun splitVideo(ctx: Context, path: String, segmentSec: Double, outputPrefix: String): String {
        val src = File(path)
        if (!src.exists()) return "ERROR: source not found: $path"
        if (segmentSec < 3.0) return "ERROR: segment_sec should be at least 3 seconds"
        val prefix = outputPrefix.ifBlank { "part_${src.nameWithoutExtension}" }
        val outPattern = File(outputDir(ctx), "${prefix}_%03d.mp4").absolutePath
        val cmd = "-y -i \"${src.absolutePath}\" -c copy -map 0 -segment_time $segmentSec -f segment -reset_timestamps 1 \"$outPattern\""
        val session = FFmpegKit.execute(cmd)
        if (!ReturnCode.isSuccess(session.returnCode)) {
            return "ERROR: FFmpeg split failed: ${session.failStackTrace?.take(400) ?: session.output?.take(400)}"
        }
        val produced = outputDir(ctx).listFiles()
            ?.filter { it.name.startsWith(prefix) && it.extension == "mp4" }
            ?.sortedBy { it.name }
            ?: emptyList()
        return if (produced.isEmpty()) "ERROR: no output files created"
               else "created ${produced.size} segments:\n" + produced.joinToString("\n") { it.absolutePath }
    }

    private fun openVideo(ctx: Context, path: String): String {
        val f = File(path)
        if (!f.exists()) return "ERROR: not found: $path"
        val uri = Uri.fromFile(f)
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            ctx.startActivity(i)
            "opened $path"
        } catch (e: Exception) {
            "ERROR: no app can open video: ${e.message}"
        }
    }

    private fun shareVideo(ctx: Context, path: String, title: String): String {
        val f = File(path)
        if (!f.exists()) return "ERROR: not found: $path"
        val uri = Uri.fromFile(f)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "video/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            ctx.startActivity(Intent.createChooser(i, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "share sheet opened for $path"
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    private fun fetch(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
        val raw = c.inputStream.bufferedReader().use { it.readText() }.take(400_000)
        return raw.replace(Regex("(?is)<(script|style).*?</\\1>"), " ")
            .replace(Regex("(?s)<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ").trim()
    }

    private fun notify(ctx: Context, title: String, text: String): String {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("agent_msgs", "Agent messages", NotificationManager.IMPORTANCE_DEFAULT))
        val n = Notification.Builder(ctx, "agent_msgs")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text)).build()
        nm.notify((System.currentTimeMillis() % 100000).toInt(), n)
        return "shown"
    }

    private var tts: TextToSpeech? = null
    @Volatile private var ttsOk = false

    private fun speak(ctx: Context, text: String): String {
        if (tts == null) {
            tts = TextToSpeech(ctx.applicationContext) { st ->
                ttsOk = (st == TextToSpeech.SUCCESS)
                if (ttsOk) tts?.setLanguage(Locale.getDefault())
            }
        }
        var w = 0
        while (!ttsOk && w < 20) { Thread.sleep(100); w++ }
        if (!ttsOk) return "ERROR: text-to-speech not ready"
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "agent")
        return "speaking"
    }

    private fun battery(ctx: Context): String {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return "ERROR: battery info unavailable"
        val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        return "level=${lvl * 100 / scale}% charging=$charging"
    }

    private fun torch(ctx: Context, on: Boolean): String {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "ERROR: no flashlight"
        cm.setTorchMode(id, on)
        return if (on) "torch on" else "torch off"
    }

    private fun safe(ctx: Context, path: String): File {
        val base = ctx.filesDir.canonicalFile
        val f = File(base, path).canonicalFile
        require(f.path.startsWith(base.path)) { "path outside agent storage" }
        return f
    }
}
