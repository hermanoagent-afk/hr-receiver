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
 */
class HrForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "hr_receiver"
        const val NOTIFICATION_ID = 1
        @Volatile var lastStatus: String = "запуск"
        @Volatile var lastBpm: Int = 0
    }

    private lateinit var buffer: LocalBuffer
    private lateinit var sender: Sender
    private var ble: BleHrClient? = null

    override fun onCreate() {
        super.onCreate()
        buffer = LocalBuffer(this)
        val prefs = Prefs(this)

        sender = Sender(buffer, prefs) { status ->
            lastStatus = status
            updateNotification()
        }

        ble = BleHrClient(
            this,
            onMeasurement = { m, ts ->
                lastBpm = m.bpm
                buffer.put(ts, m.bpm)
                buffer.pruneOlderThan(System.currentTimeMillis() - 7L * 24 * 3600 * 1000)
                updateNotification()
            },
            onState = { status ->
                lastStatus = status
                updateNotification()
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        ble?.start()
        sender.start()
        return START_STICKY // система поднимает службу после сбоя
    }

    override fun onDestroy() {
        ble?.stop()
        sender.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Приёмник пульса", NotificationManager.IMPORTANCE_LOW)
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Приёмник пульса работает")
            .setContentText(statusText())
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Приёмник пульса работает")
            .setContentText(statusText())
            .setOngoing(true)
            .build())
    }

    private fun statusText(): String {
        val bpm = if (lastBpm > 0) " · пульс $lastBpm" else ""
        return "$lastStatus$bpm"
    }
}
