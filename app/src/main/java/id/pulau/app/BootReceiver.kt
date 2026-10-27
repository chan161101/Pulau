package id.pulau.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences("pulau", Context.MODE_PRIVATE)
        if (prefs.getBoolean("autostart", false) && Settings.canDrawOverlays(context)) {
            context.startForegroundService(Intent(context, NotchService::class.java))
        }
    }
}
