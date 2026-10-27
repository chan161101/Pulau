package id.pulau.app

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusOverlay: TextView
    private lateinit var statusListener: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("pulau", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(40), dp(20), dp(24))
        }

        fun tv(t: String, sp: Float, bold: Boolean = false) = TextView(this).apply {
            this.text = t
            textSize = sp
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(6))
        }

        fun btn(t: String, onClick: () -> Unit) = Button(this).apply {
            this.text = t
            isAllCaps = false
            setOnClickListener { onClick() }
        }

        root.addView(tv("Pulau", 28f, true))
        root.addView(tv("Notch kecil di tepi atas layar: musik, timer fokus, dan pengingat minum. Ketuk notch untuk membukanya.", 14f))

        root.addView(tv("Langkah 1 - Tampil di atas aplikasi lain", 16f, true))
        statusOverlay = tv("", 14f)
        root.addView(statusOverlay)
        root.addView(btn("Buka pengaturan izin") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })

        root.addView(tv("Langkah 2 - Akses notifikasi (untuk membaca musik)", 16f, true))
        statusListener = tv("", 14f)
        root.addView(statusListener)
        root.addView(btn("Buka pengaturan akses notifikasi") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        root.addView(
            tv(
                "Kalau opsi Pulau abu-abu / muncul \"Pengaturan terbatas\": buka Info Aplikasi Pulau, " +
                    "ketuk menu titik tiga di kanan atas, pilih \"Izinkan pengaturan terbatas\", lalu ulangi langkah ini.",
                12f
            )
        )

        root.addView(tv("Langkah 3 - Izin notifikasi (Android 13+)", 16f, true))
        root.addView(btn("Izinkan notifikasi") {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        })

        root.addView(tv("Penyesuaian posisi notch", 16f, true))
        root.addView(
            tv(
                "Notch mencoba menyesuaikan diri dengan kamera depan secara otomatis. Kalau celah tengahnya belum pas, " +
                    "atur manual di sini. Perubahan langsung terlihat di notch (aktifkan Pulau dulu).",
                12f
            )
        )
        val refreshers = mutableListOf<() -> Unit>()
        fun stepper(title: String, key: String, start: Int, step: Int, min: Int, max: Int): LinearLayout {
            val label = tv("", 14f)
            fun show() {
                val v = prefs.getInt(key, 0)
                label.text = title + ": " + (if (v == 0) "otomatis" else "$v")
            }
            show()
            refreshers.add { show() }
            fun change(delta: Int) {
                val v = prefs.getInt(key, 0)
                val base = if (v == 0) start else v
                prefs.edit().putInt(key, (base + delta).coerceIn(min, max)).apply()
                show()
            }
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(btn("-") { change(-step) })
                addView(btn("+") { change(step) })
            }
        }
        root.addView(stepper("Lebar area kamera (dp)", "cut_w_dp", 48, 2, 20, 140))
        root.addView(stepper("Tinggi baris notch (dp)", "cut_h_dp", 30, 1, 20, 60))
        root.addView(btn("Kembali ke otomatis") {
            prefs.edit().remove("cut_w_dp").remove("cut_h_dp").apply()
            refreshers.forEach { it() }
        })

        val autoStart = Switch(this).apply {
            this.text = "Jalankan otomatis saat HP menyala"
            isChecked = prefs.getBoolean("autostart", false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("autostart", checked).apply()
            }
            setPadding(0, dp(16), 0, dp(8))
        }
        root.addView(autoStart)

        root.addView(btn("Aktifkan Pulau") {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Selesaikan Langkah 1 dulu", Toast.LENGTH_LONG).show()
            } else {
                startForegroundService(Intent(this, NotchService::class.java))
            }
        })
        root.addView(btn("Matikan Pulau") {
            stopService(Intent(this, NotchService::class.java))
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        statusOverlay.text = if (Settings.canDrawOverlays(this)) "Aktif" else "Belum diizinkan"
        statusListener.text = if (listenerEnabled()) "Aktif" else "Belum diizinkan"
    }

    private fun listenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?: return false
        return flat.split(":").any {
            ComponentName.unflattenFromString(it)?.packageName == packageName
        }
    }
}
