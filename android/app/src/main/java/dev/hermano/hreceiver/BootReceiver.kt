package dev.hermano.hreceiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Автозапуск после перезагрузки телефона (RECEIVE_BOOT_COMPLETED). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Prefs(context).isConfigured) {
            ContextCompat.startForegroundService(
                context, Intent(context, HrForegroundService::class.java)
            )
        }
    }
}
