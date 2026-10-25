package dev.hermano.hreceiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Автозапуск после перезагрузки телефона и после обновления приложения.
 * Запускаем только если пользователь не останавливал приёмник вручную и
 * все разрешения на месте (иначе startForegroundService приведёт к крашу).
 * BOOT_COMPLETED входит в исключения из запрета запуска FGS из фона,
 * и тип connectedDevice разрешён для запуска из BOOT_COMPLETED на Android 15.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        try {
            val prefs = Prefs.get(context)
            if (!prefs.isConfigured || !prefs.shouldRun) return
            val err = HrForegroundService.send(context, HrForegroundService.ACTION_START)
            if (err != null) Log.w("BootReceiver", "автозапуск не удался: $err")
        } catch (e: Exception) {
            Log.e("BootReceiver", "автозапуск", e)
        }
    }
}
