# ПРОМТ ДЛЯ АГЕНТА (скопируй эту секцию целиком)

Ты — senior Android-разработчик (Kotlin, BLE). Проверь и улучши приложение «HR Receiver» из этого файла (весь код ниже).

## Что делает приложение
Приёмник пульса: находит по Bluetooth LE устройство-транслятор пульса (Garmin fenix 6X в режиме «Трансляция пульса», позже браслет WHOOP), подключается к стандартному Heart Rate Service (0x180D / 0x2A37), читает пульс ~1 раз в секунду, складывает в локальный буфер (SQLite) и шлёт пачками по HTTPS на наш сервер. Должно часами работать с выключенным экраном (foreground service). Это медицинский мониторинг (ночные приступы) — надёжность важнее всего.

## Текущие проблемы (главное)
1. На Xiaomi/HyperOS приложение сканирует эфир (видит 25+ устройств), но НЕ находит часы в режиме трансляции пульса, хотя трансляция на часах включена. Поиск имени с диакритикой («fēnix») уже починен. Что ещё может блокировать обнаружение? Проверь: scan settings (сейчас startScan(callback) без ScanSettings — какая частота результатов?), throttle сканирования, логику watchdog, не пропускаются ли scanRecord с null, поведение при дублях onScanResult.
2. Нужна полная ревизия BLE-цикла: утечки GATT, ошибки 133/8, авто-reconnect, корректное выключение уведомлений, поведение при потере/возврате Bluetooth.
3. UX сейчас примитивный (3 поля + статус). Нужно: экран-инструкция «как включить трансляцию на часах/браслете», понятный первый запуск, честные состояния (что ищем, что нашли, что не так), кнопка «переподключиться».

## Требования и ограничения
- minSdk 26, targetSdk 35, Kotlin. Без сторонней аналитики (Firebase и т.п. запрещены — медицинские данные).
- Foreground service ТОЛЬКО типа connectedDevice (у dataSync в Android 15 лимит 6 часов).
- Пачки формата: {"device","sent_at","status":{...},"hr":[[ts,bpm],...]} на POST /ingest с заголовком X-Device-Key; ответ {"ok":true,"acked_to":ms}; буфер стирается до acked_to; идемпотентность на сервере по (device, ts).
- Ключи — EncryptedSharedPreferences. HTTPS обязателен (cleartext запрещён по умолчанию).
- Приложение НЕ должно падать никогда: любая ошибка BLE/сети — это статус на экран, не краш (уже частично сделано, проверь всё).

## Что сделать
1. Найти и объяснить баг обнаружения часо́в (п.1) — это приоритет №1.
2. Ревизия всего BLE-цикла и жизненного цикла службы (п.2).
3. Улучшить UX (п.3): минимум — экран помощи с инструкцией для Garmin и WHOOP, единый статус-экран.
4. Выдать: список найденных проблем по severity (critical/major/minor), затем ИСПРАВЛЕННЫЕ ПОЛНЫЕ файлы (не диффы — целые файлы целиком).
5. Отдельно: чеклист ручного тестирования на живом телефоне (Xiaomi/HyperOS + Garmin fenix 6X).

Код приложения, манифесты и сборочные файлы — ниже. Серверная часть не трогать.

# ОПИСАНИЕ ПРОЕКТА

## Задача проекта
Заменить платный Pulsoid собственным приёмником пульса: источник (браслет WHOOP 5 на человеке или Garmin fenix 6X для тестов) транслирует пульс по Bluetooth LE стандартом Heart Rate Service (0x180D/0x2A37) -> наше Android-приложение читает -> HTTPS пачками на наш сервер -> журнал пульса, сторожа, графики. Мониторинг 24/7, критичны ночные пропуски.

## Архитектура
- Клиент: Android-приложение (это меню ниже).
- Сервер: Python/Flask/SQLite (НЕ входит в задачу ревью): POST /ingest (авторизация X-Device-Key, идемпотентность PK device+ts, калибровка часов), /status /series /journal /gaps /raw/<device>. Итоговый ряд: ячейки 1с, ведущий приёмник, эстафета при молчании >=10с.
- Сборка: GitHub Actions (workflow в репо) -> app-debug.apk.

## Текущее состояние
- Сервер: работает, 14/14 сценариев тестов.
- Android: собирается в CI, ставится на телефон, НЕ падает, уведомление живёт, heartbeat доходит до сервера.
- НЕ работает: обнаружение источника пульса. Телефон (Xiaomi, HyperOS) видит 25+ BLE-устройств вокруг, но часы в режиме «Трансляция пульса» (Broadcast Heart Rate, экран активен) не появляются в эфире для приложения. Диагностика в приложении: список видимых устройств с пометкой ♥ у тех, кто рекламирует 0x180D.

## История отладки (что уже сделано/починено)
1. Краш при пустом адресе сервера (исключение в потоке убивало процесс) -> все ошибки обёрнуты.
2. Шапка приложения перекрывала поле ввода -> NoActionBar/инсеты.
3. «ошибка сканирования: 1» (HyperOS) -> снятие старой регистрации перед startScan, backoff.
4. Ноль результатов скана -> требование ACCESS_FINE_LOCATION на всех API (Android молча отдаёт ноль устройств без него) + проверка включённой геолокации.
5. Поиск имени «fenix» пропускал «fēnix» -> нормализация диакритики.
6. Сейчас: скан видит устройства, но часы-источник не обнаруживаются (п.1 промта).

## Проверенные факты
- Pulsoid для Garmin использует ТОТ ЖЕ механизм: Broadcast Heart Rate на часах + BLE HRS (их статья: https://plsoid.medium.com/streaming-heart-rate-from-garmin-with-pulsoid-d04d06ae2d16). Часы не отдают пульс по BLE, пока не включена трансляция.
- На часах трансляция включена (экран с пульсом и «СТОП» подтверждён фото).

# ИСХОДНЫЙ КОД ПРИЛОЖЕНИЯ

## Файл: android/app/src/main/AndroidManifest.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- BLE -->
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />

    <!-- Фон и сеть -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:allowBackup="false"
        android:label="HR Receiver"
        android:theme="@style/Theme.MaterialComponents.DayNight">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- Тип connectedDevice: НЕ dataSync (у него в Android 15 лимит 6 часов) -->
        <service
            android:name=".HrForegroundService"
            android:exported="false"
            android:foregroundServiceType="connectedDevice" />

        <receiver
            android:name=".BootReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
            </intent-filter>
        </receiver>
    </application>
</manifest>

```

## Файл: android/build.gradle.kts

```kotlin
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
}

```

## Файл: android/settings.gradle.kts

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "hr-receiver"
include(":app")

```

## Файл: android/app/build.gradle.kts

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.hermano.hreceiver"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.hermano.hreceiver"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}

```

## Файл: android/gradle.properties

```properties
org.gradle.jvmargs=-Xmx2048m
android.useAndroidX=true
kotlin.code.style=official

```

## Файл: android/app/src/main/java/dev/hermano/hreceiver/MainActivity.kt

```kotlin
package dev.hermano.hreceiver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Экран настройки: 3 пронумерованных поля + статус.
 * Умная вставка: если URL попал не в то поле — перекладываем сами.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true) // контент не уезжает под статусбар
        supportActionBar?.hide() // шапка перекрывала поле 1 — убираем
        prefs = Prefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 40, 48, 48)
        }
        // отступы от статусбара и нижней панели
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(48, 40 + sys.top, 48, 48 + sys.bottom)
            insets
        }

        fun label(text: String) = TextView(this).apply {
            this.text = text
            setPadding(0, 28, 0, 8)
        }

        val serverInput = EditText(this).apply {
            hint = "https://..."
            setText(prefs.serverUrl.ifEmpty { "https://formation-april-leads-diet.trycloudflare.com" })
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val deviceInput = EditText(this).apply {
            hint = "phone-1"
            setText(prefs.deviceId.ifEmpty { "phone-1" })
        }
        val keyInput = EditText(this).apply {
            hint = "sbx-key-1"
            setText(prefs.deviceKey)
        }
        val saveBtn = Button(this).apply { text = "Сохранить и запустить" }
        val status = TextView(this).apply { text = "статус: не запущено" }

        root.addView(label("1. Адрес сервера"))
        root.addView(serverInput)
        root.addView(label("2. Имя устройства"))
        root.addView(deviceInput)
        root.addView(label("3. Ключ устройства"))
        root.addView(keyInput)
        root.addView(saveBtn)
        root.addView(status)
        setContentView(ScrollView(this).apply { addView(root) })

        requestPermissions()

        // живой статус из службы — обновляем каждые 2 с
        val handler = android.os.Handler(mainLooper)
        handler.post(object : Runnable {
            override fun run() {
                val s = HrForegroundService
                if (s.lastBleState.isNotEmpty() || s.lastSendState.isNotEmpty()) {
                    status.text = buildString {
                        append("BLE: ${s.lastBleState.ifEmpty { "—" }}\n")
                        append("отправка: ${s.lastSendState.ifEmpty { "—" }}")
                        if (s.lastBpm > 0) append("\nпульс: ${s.lastBpm}")
                        val seen = BleHrClient.seenSummary
                        if (seen.isNotEmpty()) append("\n\nустройства:\n").append(seen)
                    }
                }
                handler.postDelayed(this, 2000)
            }
        })

        saveBtn.setOnClickListener {
            var url = norm(serverInput.text.toString())
            var dev = norm(deviceInput.text.toString())
            var key = norm(keyInput.text.toString())

            // умная вставка: URL мог попасть не в то поле — перекладываем
            if (!url.startsWith("http") && dev.startsWith("http")) {
                val t = url; url = dev; dev = t
                serverInput.setText(url); deviceInput.setText(dev)
            }
            if (!url.startsWith("http") && key.startsWith("http")) {
                val t = url; url = key; key = t
                serverInput.setText(url); keyInput.setText(key)
            }
            // домен без схемы — подставляем https://
            if (url.isNotEmpty() && !url.startsWith("http")) url = "https://$url"

            when {
                url.isEmpty() -> {
                    status.text = "ошибка: поле 1 «Адрес сервера» пустое"
                    return@setOnClickListener
                }
                !url.startsWith("http://") && !url.startsWith("https://") -> {
                    status.text = "ошибка: в поле 1 не адрес («${url.take(25)}…»)"
                    return@setOnClickListener
                }
                dev.isEmpty() -> {
                    status.text = "ошибка: поле 2 «Имя устройства» пустое"
                    return@setOnClickListener
                }
                key.isEmpty() -> {
                    status.text = "ошибка: поле 3 «Ключ» пустое"
                    return@setOnClickListener
                }
                !hasBtPermissions() -> {
                    status.text = "нужны разрешения Bluetooth — нажми «Разрешить»"
                    requestPermissions()
                    return@setOnClickListener
                }
            }

            prefs.serverUrl = url
            prefs.deviceId = dev
            prefs.deviceKey = key
            try {
                ContextCompat.startForegroundService(this, Intent(this, HrForegroundService::class.java))
                status.text = "статус: служба запущена, смотри уведомление"
            } catch (e: Exception) {
                status.text = "ошибка запуска: ${e.message}"
            }
        }
    }

    /** Убирает невидимые символы (Telegram вставляет ZWSP) и края. */
    private fun norm(s: String): String =
        s.replace(Regex("[\\u200B-\\u200F\\uFEFF\\u00A0]"), "").trim()

    private fun hasBtPermissions(): Boolean {
        val need = mutableListOf<String>()
        // Android требует FINE_LOCATION для отдачи результатов BLE-скана
        // (даже на 12+, если BLUETOOTH_SCAN без флага neverForLocation)
        need += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            need += Manifest.permission.BLUETOOTH_SCAN
            need += Manifest.permission.BLUETOOTH_CONNECT
        }
        return need.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val need = mutableListOf<String>()
        need += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            need += Manifest.permission.BLUETOOTH_SCAN
            need += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            need += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = need.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
        }
    }
}

```

## Файл: android/app/src/main/java/dev/hermano/hreceiver/HrForegroundService.kt

```kotlin
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

```

## Файл: android/app/src/main/java/dev/hermano/hreceiver/BleHrClient.kt

```kotlin
package dev.hermano.hreceiver

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

/**
 * BLE-клиент Heart Rate Service.
 *
 * Ключевые моменты (из плана, п. 3.1):
 * - скан ТОЛЬКО с фильтром 0x180D — без фильтра Android глушит сканирование
 *   при выключенном экране;
 * - autoConnect=true — сам подхватит браслет, когда тот появится;
 * - сторож: подключено, но уведомлений нет 15 с -> разорвать и подключиться заново;
 * - старое соединение обязательно close(), иначе ошибки 133 и утечка соединений;
 * - после КАЖДОГО подключения заново включать уведомления (дескриптор 0x2902).
 */
@SuppressLint("MissingPermission")
class BleHrClient(
    private val context: Context,
    private val onMeasurement: (HrsParser.Measurement, timestampMs: Long) -> Unit,
    private val onState: (String) -> Unit,
) {
    companion object {
        private const val TAG = "BleHrClient"
        val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val NO_DATA_TIMEOUT_MS = 15_000L
        private const val WATCHDOG_TICK_MS = 5_000L
        private val KNOWN_NAMES = listOf("whoop", "fenix", "garmin", "forerunner", "venu", "hrm", "polar", "hr broadcast")

        /** «fēnix» -> «fenix»: убираем диакритику перед поиском имени. */
        private fun normName(s: String): String =
            java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{M}"), "")

        @Volatile var seenSummary: String = ""
    }

    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter get() = manager.adapter
    private var gatt: BluetoothGatt? = null
    private var lastDataAt = 0L
    private var connected = false
    private var scanFailures = 0
    private var scanningSince = 0L
    private val seen = mutableMapOf<String, Pair<String, Boolean>>() // addr -> (name, hasHrs)
    private var lastSeenCount = 0
    @Volatile private var running = false

    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            if (connected && System.currentTimeMillis() - lastDataAt > NO_DATA_TIMEOUT_MS) {
                Log.w(TAG, "нет уведомлений 15 с — переподключение")
                onState("нет данных — переподключение")
                reconnect()
            } else if (!connected && scanningSince > 0 &&
                System.currentTimeMillis() - scanningSince > 60_000) {
                onState(if (seen.isEmpty())
                    "не вижу НИКАКИХ BT-устройств 60 с — проверь Геолокацию и Bluetooth"
                else
                    "вижу ${seen.size} устр., но часы не транслируют пульс — проверь ЭКРАН ТРАНСЛЯЦИИ на часах")
            }
            android.os.Handler(context.mainLooper).postDelayed(this, WATCHDOG_TICK_MS)
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return // повторное нажатие кнопки не должно дёргать скан
        running = true
        android.os.Handler(context.mainLooper).postDelayed(watchdog, WATCHDOG_TICK_MS)
        scan()
    }

    fun stop() {
        running = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    @SuppressLint("MissingPermission")
    private fun scan() {
        if (!running) return
        val a = adapter
        if (a == null || !a.isEnabled) {
            onState("Bluetooth выключен — включи его, жду и пробую снова")
            android.os.Handler(context.mainLooper).postDelayed({ scan() }, 5_000)
            return
        }
        val scanner = a.bluetoothLeScanner ?: run {
            onState("сканер недоступен, пробую через 5 с")
            android.os.Handler(context.mainLooper).postDelayed({ scan() }, 5_000)
            return
        }

        // сначала снимаем старую регистрацию — иначе система отвечает ошибкой 1
        try { scanner.stopScan(scanCallback) } catch (_: Exception) {}

        // Android требует включённую геолокацию для отдачи результатов BLE-скана
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val locOn = try {
            lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) { true }
        if (!locOn) {
            onState("ВКЛЮЧИ ГЕОЛОКАЦИЮ — без неё Android не отдаёт Bluetooth-скан")
            android.os.Handler(context.mainLooper).postDelayed({ scan() }, 5_000)
            return
        }

        // всегда сканируем без фильтра: фильтрованный скан на части OEM глушится,
        // а так ещё видно всех вокруг для диагностики
        onState("сканирование")
        try {
            scanner.startScan(scanCallback)
            scanningSince = System.currentTimeMillis()
        } catch (e: Exception) {
            onState("ошибка сканирования: ${e.message}")
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        val delayMs = (5_000L shl minOf(scanFailures, 2)).coerceAtMost(30_000L)
        android.os.Handler(context.mainLooper).postDelayed({ scan() }, delayMs)
    }

    @SuppressLint("MissingPermission")
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!running) return
            val addr = result.device.address
            val name = result.scanRecord?.deviceName ?: ""
            val uuids = result.scanRecord?.serviceUuids ?: emptyList()
            val hasHrs = uuids.any { it.uuid == HR_SERVICE }
            val norm = normName(name)
            val knownName = KNOWN_NAMES.any { norm.contains(it) }

            if (seen[addr]?.first != name || seen[addr]?.second != hasHrs) {
                seen[addr] = name.ifEmpty { addr } to hasHrs
                // сначала: датчики пульса (♥), потом именованные, потом анонимные MAC
                seenSummary = seen.values
                    .sortedWith(compareByDescending<Pair<String, Boolean>> { it.second }
                        .thenByDescending { it.first.any { c -> c.isLetter() } })
                    .take(20)
                    .joinToString("\n") { (n, h) ->
                        (if (h) "♥ " else if (n.any { it.isLetter() }) "• " else "· ") + n
                    }
            }
            if (seen.size != lastSeenCount) {
                lastSeenCount = seen.size
                onState("сканирование: вижу ${seen.size} устр." +
                    if (hasHrs) "" else if (knownName) ", есть $name" else "")
            }

            if (!hasHrs && !knownName) return

            scanFailures = 0
            try { adapter?.bluetoothLeScanner?.stopScan(this) } catch (_: Exception) {}
            onState("нашёл кандидата: ${name.ifEmpty { addr }}" + if (hasHrs) "" else " (проверяю HRS)")
            connect(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            scanFailures++
            val name = when (errorCode) {
                1 -> "система отказала в регистрации (код 1) — лечу переподключением"
                2 -> "сканирование слишком часто (код 2)"
                else -> "код $errorCode"
            }
            onState("ошибка сканирования: $name")
            scheduleRetry()
        }
    }

    private fun connect(device: BluetoothDevice) {
        gatt?.close() // старое соединение закрываем обязательно
        gatt = device.connectGatt(context, true, gattCallback)
    }

    private fun reconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        connected = false
        scan()
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connected = true
                    lastDataAt = System.currentTimeMillis()
                    onState("подключено, ищу сервис")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connected = false
                    onState("связь пропала, жду браслет")
                    g.close()
                    android.os.Handler(context.mainLooper).postDelayed({ scan() }, 2_000)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ch = g.getService(HR_SERVICE)?.getCharacteristic(HR_MEASUREMENT) ?: run {
                onState("на устройстве нет HRS — ищу дальше")
                connected = false
                g.close()
                android.os.Handler(context.mainLooper).postDelayed({ scan() }, 2_000)
                return
            }
            g.setCharacteristicNotification(ch, true)
            ch.getDescriptor(CCCD)?.let {
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(it)
            }
            onState("подписка на пульс включена")
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (ch.uuid != HR_MEASUREMENT) return
            val m = HrsParser.parse(ch.value ?: return) ?: return
            lastDataAt = System.currentTimeMillis()
            onMeasurement(m, System.currentTimeMillis()) // время приёма — часы телефона
        }
    }
}

```

## Файл: android/app/src/main/java/dev/hermano/hreceiver/HrsParser.kt

```kotlin
package dev.hermano.hreceiver

/**
 * Разбор Heart Rate Measurement (0x2A37), Bluetooth SIG стандарт.
 *
 * Формат:
 *  байт 0 — флаги:
 *    бит 0: 0 = пульс 8 бит, 1 = 16 бит
 *    бит 2: есть ли энергия
 *    бит 3: contact status (есть/нет контакт с кожей)
 *    бит 4: есть ли RR-интервалы (единица 1/1024 с)
 *  далее — пульс (1 или 2 байта, little-endian),
 *  далее — опционально энергия (2 байта),
 *  далее — RR-интервалы (по 2 байта, little-endian, 1/1024 с).
 */
object HrsParser {

    data class Measurement(
        val bpm: Int,
        val contactSupported: Boolean,
        val contactDetected: Boolean,
        val rrMs: List<Double>,
    )

    fun parse(value: ByteArray): Measurement? {
        if (value.size < 2) return null
        val flags = value[0].toInt() and 0xFF
        val is16 = flags and 0x01 != 0
        val energyPresent = flags and 0x04 != 0
        val contactSupported = flags and 0x08 != 0
        val contactDetected = flags and 0x06 == 0x06
        val rrPresent = flags and 0x10 != 0

        var i = 1
        val bpm: Int
        if (is16) {
            if (value.size < 3) return null
            bpm = (value[i].toInt() and 0xFF) or ((value[i + 1].toInt() and 0xFF) shl 8)
            i += 2
        } else {
            bpm = value[i].toInt() and 0xFF
            i += 1
        }
        if (energyPresent) i += 2

        val rr = mutableListOf<Double>()
        if (rrPresent) {
            while (i + 1 < value.size) {
                val raw = (value[i].toInt() and 0xFF) or ((value[i + 1].toInt() and 0xFF) shl 8)
                rr.add(raw * 1000.0 / 1024.0) // 1/1024 с -> мс
                i += 2
            }
        }
        return Measurement(bpm, contactSupported, contactDetected, rr)
    }
}

```

## Файл: android/app/src/main/java/dev/hermano/hreceiver/LocalBuffer.kt

```kotlin
package dev.hermano.hreceiver

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Буфер на телефоне (предложение, п. 3.1): SQLite, 7 дней,
 * удаление только после подтверждения сервера (acked_to).
 */
class LocalBuffer(context: Context) :
    SQLiteOpenHelper(context, "hr_buffer.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE readings (
                ts INTEGER PRIMARY KEY,
                bpm INTEGER NOT NULL
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {}

    fun put(ts: Long, bpm: Int) {
        val cv = ContentValues().apply {
            put("ts", ts)
            put("bpm", bpm)
        }
        writableDatabase.insertWithOnConflict("readings", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun unsent(limit: Int = 500): List<Pair<Long, Int>> {
        val out = mutableListOf<Pair<Long, Int>>()
        readableDatabase.rawQuery("SELECT ts, bpm FROM readings ORDER BY ts LIMIT ?", arrayOf(limit.toString()))
            .use { c ->
                while (c.moveToNext()) out.add(c.getLong(0) to c.getInt(1))
            }
        return out
    }

    fun markSent(ackedTo: Long) {
        writableDatabase.execSQL("DELETE FROM readings WHERE ts <= ?", arrayOf(ackedTo))
    }

    fun count(): Long =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM readings", null).use { it.moveToFirst(); it.getLong(0) }

    fun pruneOlderThan(cutoff: Long) {
        writableDatabase.execSQL("DELETE FROM readings WHERE ts < ?", arrayOf(cutoff))
    }
}

```

## Файл: android/app/src/main/java/dev/hermano/hreceiver/Sender.kt

```kotlin
package dev.hermano.hreceiver

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Отправка пачек на сервер (предложение, п. 3.1 и 3.3):
 * - пачка каждые 5 с (500 записей максимум);
 * - heartbeat «жив» раз в 60 с, если пульса нет;
 * - повтор с растущей паузой: от 5 с до 5 мин;
 * - acked_to: сервер подтверждает, телефон стирает отправленное.
 *
 * ВАЖНО: ни одна ошибка здесь НЕ должна ронять процесс —
 * необработанное исключение в потоке убивает всё приложение.
 */
class Sender(
    private val buffer: LocalBuffer,
    private val prefs: Prefs,
    private val onStatus: (String) -> Unit,
) {
    companion object {
        private const val BATCH_MS = 5_000L
        private const val HEARTBEAT_MS = 60_000L
        private const val MAX_RETRY_MS = 300_000L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    @Volatile private var running = false
    private var retryDelayMs = 5_000L
    private var lastSentOkAt = 0L

    fun start() {
        if (running) return
        running = true
        Thread {
            while (running) {
                val ok = try {
                    flushOnce()
                } catch (e: Exception) {
                    onStatus("ошибка: ${e.message}")
                    false
                }
                if (ok) {
                    retryDelayMs = 5_000L
                    lastSentOkAt = System.currentTimeMillis()
                    try { Thread.sleep(BATCH_MS) } catch (_: InterruptedException) { break }
                } else {
                    onStatus("нет связи, повтор через ${retryDelayMs / 1000} с")
                    try { Thread.sleep(retryDelayMs) } catch (_: InterruptedException) { break }
                    retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_MS)
                }
            }
        }.start()
    }

    fun stop() {
        running = false
    }

    /** true — пачка принята. Никогда не бросает исключений. */
    fun flushOnce(): Boolean {
        return try {
            doFlush()
        } catch (e: Exception) {
            onStatus("ошибка отправки: ${e.message}")
            false
        }
    }

    private fun doFlush(): Boolean {
        if (prefs.serverUrl.isBlank() || prefs.deviceKey.isBlank()) {
            onStatus("не настроено: заполни адрес сервера и ключ")
            return false
        }

        val rows = buffer.unsent()
        val needHeartbeat = rows.isEmpty() &&
            System.currentTimeMillis() - lastSentOkAt > HEARTBEAT_MS
        if (rows.isEmpty() && !needHeartbeat) return true

        val payload = JSONObject().apply {
            put("device", prefs.deviceId)
            put("sent_at", System.currentTimeMillis())
            put("status", JSONObject().apply {
                put("ble", if (rows.isNotEmpty()) "connected" else "unknown")
                put("queue", rows.size)
                put("app", BuildConfig.VERSION_NAME)
            })
            put("hr", JSONArray().apply {
                rows.forEach { (ts, bpm) -> put(JSONArray().put(ts).put(bpm)) }
            })
        }

        val request = Request.Builder()
            .url(prefs.serverUrl.trimEnd('/') + "/ingest")
            .addHeader("X-Device-Key", prefs.deviceKey)
            .post(payload.toString().toRequestBody(jsonType))
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                onStatus("сервер ответил ${resp.code}" +
                    if (resp.code == 403) " — имя устройства или ключ не те" else "")
                return false
            }
            val ackedTo = JSONObject(resp.body?.string() ?: "{}").optLong("acked_to", -1)
            if (ackedTo > 0) buffer.markSent(ackedTo)
            onStatus("отправлено ${rows.size}, в буфере ${buffer.count()}")
            return true
        }
    }
}

```

## Файл: android/app/src/main/java/dev/hermano/hreceiver/Prefs.kt

```kotlin
package dev.hermano.hreceiver

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Настройки приёмника. Ключ устройства храним в EncryptedSharedPreferences
 * (на базе Android Keystore). В production — только так.
 */
class Prefs(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sp = EncryptedSharedPreferences.create(
        context,
        "hr_receiver_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var serverUrl: String
        get() = sp.getString("server_url", "") ?: ""
        set(v) = sp.edit().putString("server_url", v).apply()

    var deviceKey: String
        get() = sp.getString("device_key", "") ?: ""
        set(v) = sp.edit().putString("device_key", v).apply()

    var deviceId: String
        get() = sp.getString("device_id", "") ?: ""
        set(v) = sp.edit().putString("device_id", v).apply()

    val isConfigured: Boolean
        get() = serverUrl.isNotBlank() && deviceKey.isNotBlank() && deviceId.isNotBlank()
}

```

## Файл: android/app/src/main/java/dev/hermano/hreceiver/BootReceiver.kt

```kotlin
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

```
