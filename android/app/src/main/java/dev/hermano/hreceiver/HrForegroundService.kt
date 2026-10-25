package dev.hermano.hreceiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/**
 * Служба переднего плана: держит BLE и отправку при выключенном экране.
 * Тип connectedDevice — единственный подходящий, который в Android 15 не имеет лимита 6 часов.
 * Все шаги обёрнуты в try/catch: служба не имеет права ронять приложение.
 *
 * Управление — через action в Intent: START (по умолчанию), STOP, RECONNECT,
 * USE_DEVICE (+адрес/имя), FORGET_DEVICE. Экран и кнопки уведомления шлют их сюда.
 */
class HrForegroundService : Service() {

    companion object {
        private const val TAG = "HrService"
        const val CHANNEL_ID = "hr_receiver"
        const val NOTIFICATION_ID = 1

        const val ACTION_START = "dev.hermano.hreceiver.START"
        const val ACTION_STOP = "dev.hermano.hreceiver.STOP"
        const val ACTION_RECONNECT = "dev.hermano.hreceiver.RECONNECT"
        const val ACTION_USE_DEVICE = "dev.hermano.hreceiver.USE_DEVICE"
        const val ACTION_FORGET_DEVICE = "dev.hermano.hreceiver.FORGET_DEVICE"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_NAME = "name"

        private const val PRUNE_EVERY_MS = 3600_000L
        private const val KEEP_MS = 7L * 24 * 3600 * 1000
        private const val WAKELOCK_MS = 15 * 60_000L

        /**
         * Безопасный запуск из Activity/BootReceiver. Возвращает текст ошибки или null.
         * startForegroundService() без последующего startForeground() = краш процесса,
         * поэтому сначала проверяем всё, что может помешать startForeground().
         */
        fun send(ctx: Context, action: String, extras: (Intent) -> Unit = {}): String? {
            if (action == ACTION_START && !Perms.hasBle(ctx)) {
                return "нет разрешений Bluetooth/геолокации"
            }
            val i = Intent(ctx, HrForegroundService::class.java).setAction(action)
            extras(i)
            return try {
                if (action == ACTION_START) ContextCompat.startForegroundService(ctx, i)
                else if (HrState.serviceRunning.value) ctx.startService(i)
                else return null // служба не запущена — команде некому выполняться
                null
            } catch (e: Exception) {
                Log.w(TAG, "start service", e)
                "не удалось запустить службу: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private var buffer: LocalBuffer? = null
    private var sender: Sender? = null
    private var ble: BleHrClient? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var scope: CoroutineScope? = null
    private var lastPruneAt = 0L
    private var inForeground = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ensureComponents()
    }

    /** Создаёт буфер/отправку/BLE, если их нет (после STOP объекты уничтожаются). */
    private fun ensureComponents() {
        try {
            val buf = buffer ?: LocalBuffer(this).also { buffer = it }
            val prefs = Prefs.get(this)
            if (sender == null) sender = Sender(buf, prefs)
            if (ble == null) ble = BleHrClient(this, prefs) { m, ts ->
                buf.put(ts, m.bpm)
                if (ts - lastPruneAt > PRUNE_EVERY_MS) { // раньше: DELETE на КАЖДЫЙ удар
                    lastPruneAt = ts
                    buf.pruneOlderThan(ts - KEEP_MS)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "init", e)
            HrState.updateBle { it.copy(detail = "ошибка инициализации: ${e.message}") }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground — ПЕРВЫМ делом при любом входе: если нас подняли через
        // startForegroundService, у нас есть ~5-10 с, иначе система убьёт процесс.
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val action = intent?.action ?: ACTION_START // null intent = перезапуск START_STICKY
        val prefs = Prefs.get(this)
        try {
            when (action) {
                ACTION_STOP -> {
                    prefs.shouldRun = false
                    shutdown()
                    return START_NOT_STICKY
                }
                ACTION_RECONNECT -> {
                    ble?.reconnect()
                    sender?.kick()
                }
                ACTION_USE_DEVICE -> {
                    val addr = intent?.getStringExtra(EXTRA_ADDRESS).orEmpty()
                    val name = intent?.getStringExtra(EXTRA_NAME).orEmpty()
                    if (addr.isNotEmpty()) ble?.useDevice(addr, name)
                }
                ACTION_FORGET_DEVICE -> ble?.forgetDevice()
                else -> {
                    prefs.shouldRun = true
                    startWork()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "command $action", e)
            HrState.updateBle { it.copy(detail = "ошибка команды: ${e.message}") }
        }
        return START_STICKY // система поднимает службу после сбоя
    }

    private fun startWork() {
        if (HrState.serviceRunning.value && scope != null) return
        ensureComponents()
        HrState.setRunning(true)
        acquireWakeLock()
        try { ble?.start() } catch (e: Exception) { Log.e(TAG, "ble start", e) }
        try { sender?.start() } catch (e: Exception) { Log.e(TAG, "sender start", e) }

        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = s
        // уведомление: не чаще раза в секунду, всегда с последним состоянием
        s.launch {
            combine(HrState.ble, HrState.send) { b, snd -> b to snd }
                .conflate()
                .collect { (b, snd) ->
                    updateNotification(b, snd)
                    delay(1_000)
                }
        }
        // продление wake lock: держим CPU, чтобы поток отправки не «засыпал» в Doze
        s.launch {
            while (true) {
                delay(WAKELOCK_MS / 2)
                acquireWakeLock()
            }
        }
    }

    private fun shutdown() {
        try { ble?.stop() } catch (_: Exception) {}
        try { sender?.stop() } catch (_: Exception) {}
        ble = null
        sender = null
        scope?.cancel()
        scope = null
        releaseWakeLock()
        HrState.setRunning(false)
        try { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        inForeground = false
        stopSelf()
    }

    override fun onDestroy() {
        try { ble?.stop() } catch (_: Exception) {}
        try { sender?.stop() } catch (_: Exception) {}
        scope?.cancel()
        releaseWakeLock()
        try { buffer?.close() } catch (_: Exception) {}
        HrState.setRunning(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------------

    private fun goForeground(): Boolean {
        if (inForeground) return true
        val n = buildNotification(HrState.ble.value, HrState.send.value)
        return try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
            inForeground = true
            true
        } catch (e: Exception) {
            // Android 12+: ForegroundServiceStartNotAllowedException (запуск из фона),
            // Android 14+: SecurityException (нет предусловий типа connectedDevice).
            Log.e(TAG, "startForeground", e)
            HrState.updateBle { it.copy(detail = "система не дала запустить службу: ${e.message}") }
            false
        }
    }

    private fun acquireWakeLock() {
        try {
            val wl = wakeLock ?: (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hreceiver:ble")
                .apply { setReferenceCounted(false) }
                .also { wakeLock = it }
            wl.acquire(WAKELOCK_MS)
        } catch (e: Exception) {
            Log.w(TAG, "wakelock", e)
        }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    private fun createChannel() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Приёмник пульса", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Постоянное уведомление: состояние связи с часами/браслетом"
                    setShowBadge(false)
                }
            )
        } catch (_: Exception) {}
    }

    private fun pending(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code,
            Intent(this, HrForegroundService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun buildNotification(b: HrState.Ble, s: HrState.Send): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = when (b.phase) {
            HrState.Phase.STREAMING -> if (b.bpm > 0) "♥ ${b.bpm} уд/мин" else "Пульс: подключено"
            HrState.Phase.SEARCHING -> "Ищу часы/браслет…"
            HrState.Phase.CONNECTING, HrState.Phase.SUBSCRIBING -> "Подключаюсь…"
            HrState.Phase.NO_DATA, HrState.Phase.WAITING -> "Нет пульса — восстанавливаю связь"
            HrState.Phase.BT_OFF -> "Bluetooth выключен"
            HrState.Phase.LOCATION_OFF -> "Геолокация выключена"
            HrState.Phase.NO_PERMISSION -> "Нужны разрешения"
            HrState.Phase.STOPPED -> "Приёмник пульса"
        }
        val text = buildString {
            if (b.deviceName.isNotEmpty()) append(b.deviceName).append(" · ")
            append(b.detail.lineSequence().firstOrNull().orEmpty().ifEmpty { "запуск…" })
        }
        val big = buildString {
            append(b.detail.ifEmpty { "запуск…" })
            if (s.detail.isNotEmpty()) append("\nсервер: ").append(s.detail)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(big))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Переподключить", pending(ACTION_RECONNECT, 1))
            .addAction(0, "Стоп", pending(ACTION_STOP, 2))
            .build()
    }

    private fun updateNotification(b: HrState.Ble, s: HrState.Send) {
        if (!inForeground) return
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(b, s))
        } catch (_: Exception) {}
    }
}
