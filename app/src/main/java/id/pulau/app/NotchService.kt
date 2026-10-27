package id.pulau.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.RingtoneManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.text.TextUtils
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class NotchService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val ui by lazy { ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault) }
    private val prefs by lazy { getSharedPreferences("pulau", MODE_PRIVATE) }
    private lateinit var wm: WindowManager
    private var pill: LinearLayout? = null

    private val gray = 0xFF8E8E93.toInt()
    private val card = 0xFF2C2C2E.toInt()
    private val green = 0xFF30D158.toInt()
    private val blue = 0xFF0A84FF.toInt()
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val match = ViewGroup.LayoutParams.MATCH_PARENT

    // ---- tampilan ----
    private lateinit var collapsedText: TextView
    private lateinit var expanded: LinearLayout
    private lateinit var pages: List<View>
    private lateinit var tabs: List<TextView>
    private var isExpanded = false

    private lateinit var songTitle: TextView
    private lateinit var songArtist: TextView
    private lateinit var playBtn: TextView
    private lateinit var musicBar: ProgressBar

    private lateinit var timerText: TextView
    private lateinit var timerModeText: TextView
    private lateinit var startBtn: TextView

    private lateinit var waterText: TextView
    private lateinit var waterBar: ProgressBar

    // ---- status ----
    private var controller: MediaController? = null
    private var playing = false
    private var currentTitle: String? = null

    private val modeNames = arrayOf("Fokus", "Istirahat")
    private val modeMs = longArrayOf(25 * 60_000L, 5 * 60_000L)
    private var timerMode = 0
    private var timerRemaining = modeMs[0]
    private var timerRunning = false
    private var timerEndAt = 0L

    private var flashText: String? = null
    private var flashUntil = 0L
    private var lastWaterReminder = SystemClock.elapsedRealtime()

    private val autoCollapse = Runnable { setExpanded(false) }
    private val tick = object : Runnable {
        override fun run() {
            tickTimer()
            tickWaterReminder()
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    // ================= siklus layanan =================

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, buildNotification())
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (pill == null) buildOverlay()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        pill?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        pill = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("pulau", "Pulau berjalan", NotificationManager.IMPORTANCE_MIN)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, "pulau")
            .setContentTitle("Pulau aktif")
            .setContentText("Ketuk untuk membuka pengaturan")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    // ================= membangun overlay =================

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(t: String, sp: Float, color: Int = Color.WHITE, bold: Boolean = false) =
        TextView(ui).apply {
            this.text = t
            textSize = sp
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun pillButton(t: String, onClick: () -> Unit) = TextView(ui).apply {
        this.text = t
        textSize = 15f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(9), dp(8), dp(9))
        background = GradientDrawable().apply {
            setColor(card)
            cornerRadius = dp(14).toFloat()
        }
        setOnClickListener {
            bump()
            onClick()
            refresh()
        }
    }

    private fun row(vararg views: View) = LinearLayout(ui).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, wrap, 1f).apply {
                if (i > 0) leftMargin = dp(8)
            })
        }
    }

    private fun bar(color: Int) =
        ProgressBar(ui, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(color)
            progressBackgroundTintList = ColorStateList.valueOf(0xFF3A3A3C.toInt())
        }

    private fun buildOverlay() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // --- halaman musik ---
        songTitle = label("", 16f, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        songArtist = label("", 13f, gray).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        musicBar = bar(green)
        val prevBtn = pillButton("\u23EE") { controller?.transportControls?.skipToPrevious() }
        playBtn = pillButton("\u25B6") {
            val c = controller
            if (c != null) {
                if (playing) c.transportControls.pause() else c.transportControls.play()
            }
        }
        val nextBtn = pillButton("\u23ED") { controller?.transportControls?.skipToNext() }
        val musicPage = LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            addView(songTitle)
            addView(songArtist)
            addView(musicBar, LinearLayout.LayoutParams(match, dp(4)).apply {
                topMargin = dp(8)
                bottomMargin = dp(10)
            })
            addView(row(prevBtn, playBtn, nextBtn))
        }

        // --- halaman timer fokus ---
        timerText = label("25:00", 36f, bold = true).apply { gravity = Gravity.CENTER }
        timerModeText = label("Fokus", 13f, gray).apply { gravity = Gravity.CENTER }
        startBtn = pillButton("Mulai") { toggleTimer() }
        val resetBtn = pillButton("Reset") { resetTimer() }
        val modeBtn = pillButton("Ganti") { switchMode() }
        val timerPage = LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            addView(timerText, LinearLayout.LayoutParams(match, wrap))
            addView(timerModeText, LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = dp(10) })
            addView(row(startBtn, resetBtn, modeBtn))
        }

        // --- halaman air minum ---
        waterText = label("0 / 2000 ml", 22f, bold = true).apply {
            gravity = Gravity.CENTER
            setOnLongClickListener {
                val goals = intArrayOf(1500, 2000, 2500, 3000)
                val next = goals[(goals.indexOf(waterGoal()) + 1) % goals.size]
                prefs.edit().putInt("water_goal", next).apply()
                bump()
                refresh()
                true
            }
        }
        waterBar = bar(blue)
        val waterHint = label("Tahan angka untuk ganti target", 11f, gray).apply { gravity = Gravity.CENTER }
        val waterPage = LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            addView(waterText, LinearLayout.LayoutParams(match, wrap))
            addView(waterBar, LinearLayout.LayoutParams(match, dp(4)).apply {
                topMargin = dp(8)
                bottomMargin = dp(4)
            })
            addView(waterHint, LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = dp(8) })
            addView(row(
                pillButton("-100") { addWater(-100) },
                pillButton("+150") { addWater(150) },
                pillButton("+250") { addWater(250) },
                pillButton("+500") { addWater(500) }
            ))
        }

        pages = listOf(musicPage, timerPage, waterPage)

        // --- tab ---
        tabs = listOf("Musik", "Fokus", "Air").mapIndexed { i, name ->
            label(name, 13f, gray, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(2))
                setOnClickListener {
                    bump()
                    showPage(i)
                }
            }
        }

        expanded = LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(10), 0, dp(4))
            pages.forEach { addView(it) }
            addView(row(*tabs.toTypedArray()))
        }

        collapsedText = label("", 13f, bold = true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(240)
            minWidth = dp(56)
        }

        val root = LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(7), dp(16), dp(7))
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = dp(20).toFloat()
            }
            addView(collapsedText, LinearLayout.LayoutParams(wrap, wrap))
            addView(expanded, LinearLayout.LayoutParams(dp(280), wrap))
            setOnClickListener { setExpanded(!isExpanded) }
        }

        val params = WindowManager.LayoutParams(
            wrap,
            wrap,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(6)
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        showPage(0)
        wm.addView(root, params)
        pill = root
        handler.post(tick)
    }

    // ================= interaksi =================

    private fun setExpanded(e: Boolean) {
        isExpanded = e
        expanded.visibility = if (e) View.VISIBLE else View.GONE
        handler.removeCallbacks(autoCollapse)
        if (e) handler.postDelayed(autoCollapse, 10_000)
        refresh()
    }

    /** Perpanjang waktu sebelum notch menutup sendiri. */
    private fun bump() {
        if (isExpanded) {
            handler.removeCallbacks(autoCollapse)
            handler.postDelayed(autoCollapse, 10_000)
        }
    }

    private fun showPage(i: Int) {
        pages.forEachIndexed { idx, v -> v.visibility = if (idx == i) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { idx, t -> t.setTextColor(if (idx == i) Color.WHITE else gray) }
    }

    private fun flash(text: String, ms: Long) {
        flashText = text
        flashUntil = SystemClock.elapsedRealtime() + ms
    }

    // ================= pembaruan tampilan =================

    private fun refresh() {
        updateMusic()
        updateTimer()
        updateWater()
        collapsedText.text = collapsedLabel()
    }

    private fun clock(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    private fun collapsedLabel(): String {
        val f = flashText
        if (f != null && SystemClock.elapsedRealtime() < flashUntil) return f
        if (timerRunning) return "\u23F1 " + fmt(timerEndAt - SystemClock.elapsedRealtime())
        val t = currentTitle
        if (playing && !t.isNullOrBlank()) return "\u266A $t"
        return clock()
    }

    // ---------- musik ----------

    private fun findController(): MediaController? {
        return try {
            val msm = getSystemService(MediaSessionManager::class.java)
            val list = msm.getActiveSessions(ComponentName(this, MediaListenerService::class.java))
            list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: list.firstOrNull()
        } catch (e: SecurityException) {
            null
        }
    }

    private fun updateMusic() {
        val c = findController()
        controller = c
        val md = c?.metadata
        val state = c?.playbackState
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        playing = state?.state == PlaybackState.STATE_PLAYING
        currentTitle = title

        songTitle.text = title ?: "Tidak ada musik"
        songArtist.text =
            if (title == null) "Putar lagu di Spotify, YouTube Music, dll." else (artist ?: "")
        playBtn.text = if (playing) "\u23F8" else "\u25B6"

        val dur = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        if (state != null && dur > 0) {
            var pos = state.position
            if (playing) {
                val delta = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
                pos += (delta * state.playbackSpeed).toLong()
            }
            musicBar.progress = (pos.coerceIn(0L, dur) * 1000 / dur).toInt()
        } else {
            musicBar.progress = 0
        }
    }

    // ---------- timer fokus ----------

    private fun fmt(ms: Long): String {
        val s = (ms.coerceAtLeast(0L) + 999) / 1000
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
    }

    private fun updateTimer() {
        val remaining =
            if (timerRunning) timerEndAt - SystemClock.elapsedRealtime() else timerRemaining
        timerText.text = fmt(remaining)
        timerModeText.text = modeNames[timerMode] + if (timerRunning) " - berjalan" else ""
        startBtn.text = if (timerRunning) "Jeda" else "Mulai"
    }

    private fun toggleTimer() {
        val now = SystemClock.elapsedRealtime()
        if (timerRunning) {
            timerRemaining = (timerEndAt - now).coerceAtLeast(0L)
            timerRunning = false
        } else {
            timerEndAt = now + timerRemaining
            timerRunning = true
        }
    }

    private fun resetTimer() {
        timerRunning = false
        timerRemaining = modeMs[timerMode]
    }

    private fun switchMode() {
        timerMode = if (timerMode == 0) 1 else 0
        resetTimer()
    }

    private fun tickTimer() {
        if (timerRunning && SystemClock.elapsedRealtime() >= timerEndAt) {
            val wasFocus = timerMode == 0
            timerRunning = false
            timerMode = if (wasFocus) 1 else 0
            timerRemaining = modeMs[timerMode]
            flash(
                if (wasFocus) "Fokus selesai! Istirahat dulu" else "Istirahat selesai, ayo fokus",
                10_000
            )
            buzz(600)
            beep()
        }
    }

    // ---------- air minum ----------

    private fun todayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun waterGoal(): Int = prefs.getInt("water_goal", 2000)

    private fun waterMl(): Int {
        if (prefs.getString("water_day", "") != todayKey()) {
            prefs.edit().putString("water_day", todayKey()).putInt("water_ml", 0).apply()
        }
        return prefs.getInt("water_ml", 0)
    }

    private fun addWater(ml: Int) {
        val now = (waterMl() + ml).coerceAtLeast(0)
        prefs.edit().putInt("water_ml", now).apply()
    }

    private fun updateWater() {
        val ml = waterMl()
        val goal = waterGoal()
        waterText.text = "$ml / $goal ml"
        waterBar.progress = (ml * 1000 / goal).coerceAtMost(1000)
    }

    private fun tickWaterReminder() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWaterReminder < 60 * 60_000L) return
        lastWaterReminder = now
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (hour in 8..21 && waterMl() < waterGoal()) {
            flash("Waktunya minum \uD83D\uDCA7", 8_000)
            buzz(300)
        }
    }

    // ---------- bunyi & getar ----------

    @Suppress("DEPRECATION")
    private fun buzz(ms: Long) {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
        }
    }

    private fun beep() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(this, uri).play()
        } catch (e: Exception) {
        }
    }
}
