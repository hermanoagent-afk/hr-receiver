package dev.hermano.hreceiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Служба переднего плана: держит BLE и отправку при выключенном экране.
 * Тип connectedDevice — единственный, который в Android 15 не имеет лимита 6 часов.
 * Все шаги обёрнуты в try/catch: служба не имеет права ронять приложение.
 */
class HrForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "hr_receiver"
        const val NOTIFICATION_ID = 1
        @Volatile var lastStatus: String = "запуск"
        @Volatile var lastBpm: Int = 0
        @Volatile var lastBleState: String = ""
        @Volatile var lastSendState: String = ""
    }

    private var buffer: LocalBuffer? = null
    private var sender: Sender? = null
    private var ble: BleHrClient? = null

    override fun onCreate() {
        super.onCreate()
        try {
            val buf = LocalBuffer(this)
            buffer = buf
            val prefs = Prefs(this)

            sender = Sender(buf, prefs) { status ->
                lastSendState = status
                updateNotification()
            }

            ble = BleHrClient(
                this,
                onMeasurement = { m, ts ->
                    lastBpm = m.bpm
                    buf.put(ts, m.bpm)
                    buf.pruneOlderThan(System.currentTimeMillis() - 7L * 24 * 3600 * 1000)
                    updateNotification()
                },
                onState = { status ->
                    lastBleState = status
                    updateNotification()
                },
            )
        } catch (e: Exception) {
            lastStatus = "ошибка инициализации: ${e.message}"
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startAsForeground()
        } catch (e: Exception) {
            // нет разрешений Bluetooth/уведомлений — пробуем без типа службы
            lastStatus = "ошибка foreground: ${e.message}"
            try {
                startForeground(NOTIFICATION_ID, buildNotification())
            } catch (e2: Exception) {
                lastStatus = "не удалось поднять службу: ${e2.message}"
            }
        }
        try {
            ble?.start()
        } catch (e: Exception) {
            lastStatus = "BLE ошибка: ${e.message}"
            updateNotification()
        }
        try {
            sender?.start()
        } catch (e: Exception) {
            lastStatus = "ошибка отправки: ${e.message}"
            updateNotification()
        }
        return START_STICKY // система поднимает службу после сбоя
    }

    override fun onDestroy() {
        try { ble?.stop() } catch (_: Exception) {}
        try { sender?.stop() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Приёмник пульса", NotificationManager.IMPORTANCE_LOW)
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Приёмник пульса работает")
            .setContentText(statusText())
            .setOngoing(true)
            .build()
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {}
    }

    private fun statusText(): String {
        val parts = mutableListOf<String>()
        if (lastBleState.isNotEmpty()) parts += lastBleState
        if (lastSendState.isNotEmpty()) parts += lastSendState
        if (lastBpm > 0) parts += "пульс $lastBpm"
        return if (parts.isEmpty()) "запуск…" else parts.joinToString(" · ")
    }
}
