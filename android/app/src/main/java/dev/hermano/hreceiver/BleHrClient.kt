package dev.hermano.hreceiver

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import dev.hermano.hreceiver.HrState.Phase
import java.text.Normalizer
import java.util.UUID

/**
 * BLE-клиент Heart Rate Service (0x180D / 0x2A37).
 *
 * Архитектура:
 * - ВСЯ логика и ВСЕ колбэки (скан, GATT, Bluetooth on/off) выполняются на одном
 *   потоке [HandlerThread] — никаких гонок между binder-потоками GATT и main-потоком;
 * - явная машина состояний [St]: результаты скана, пришедшие после stopScan()
 *   (а они приходят — очередь колбэков), игнорируются, если мы уже подключаемся;
 * - connectGatt(autoConnect=false, TRANSPORT_LE) + собственные таймауты на каждый шаг;
 * - на каждый GATT-объект — ровно один close(); колбэки от «чужих» (старых) gatt
 *   игнорируются;
 * - поиск в три слоя:
 *   1) устройства, УЖЕ подключённые к телефону (часы Garmin, связанные с Garmin
 *      Connect, могут не рекламировать 0x180D, но отдают HRS через существующий канал);
 *   2) скан с ScanSettings (LOW_LATENCY, все совпадения, расширенная реклама):
 *      без фильтра, пока экран открыт (диагностика), с фильтром 0x180D + MAC выбранного
 *      устройства — когда экран выключен (нефильтрованный скан система глушит);
 *   3) запасной путь: устройство с «часовым» именем без 0x180D в рекламе — подключаемся
 *      и проверяем сервисы через GATT;
 * - выбранное устройство (preferredAddress): если оно задано, к ДРУГИМ источникам
 *   пульса не подключаемся — иначе можно записать пульс соседа (медицинские данные!).
 */
@SuppressLint("MissingPermission")
class BleHrClient(
    context: Context,
    private val prefs: Prefs,
    private val onMeasurement: (HrsParser.Measurement, timestampMs: Long) -> Unit,
) {
    companion object {
        private const val TAG = "BleHrClient"
        val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val WATCHDOG_TICK_MS = 5_000L
        private const val NO_DATA_TIMEOUT_MS = 15_000L
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val DISCOVER_TIMEOUT_MS = 15_000L
        private const val SUBSCRIBE_TIMEOUT_MS = 10_000L
        private const val PICK_WINDOW_MS = 1_500L          // собираем кандидатов, берём сильнейший
        private const val NAME_FALLBACK_AFTER_MS = 20_000L // сколько сканировать до проверки «по имени»
        private const val SCAN_SESSION_MS = 4 * 60_000L    // перезапуск скана (Android глушит долгие)
        private const val SCAN_SILENCE_MS = 30_000L        // ноль результатов столько — скан заглушен
        private const val SYSTEM_PROBE_EVERY_MS = 3 * 60_000L
        private const val FAST_SCAN_PERIOD_MS = 2 * 60_000L
        private const val NO_HRS_BLACKLIST_MS = 5 * 60_000L
        private const val FAIL_BLACKLIST_MS = 2 * 60_000L
        private const val SEEN_TTL_MS = 90_000L
        private const val SCAN_STARTS_WINDOW_MS = 30_000L
        private const val SCAN_STARTS_MAX = 4               // лимит Android — 5 за 30 с; держим запас

        private val KNOWN_NAMES = listOf(
            "whoop", "fenix", "garmin", "forerunner", "venu", "epix", "instinct", "enduro",
            "vivoactive", "vivosmart", "hrm", "polar", "wahoo", "tickr", "heart",
        )

        /** «fēnix» -> «fenix»: убираем диакритику перед поиском имени. */
        fun normName(s: String): String =
            Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")

        fun looksLikeHrSource(name: String): Boolean {
            if (name.isBlank()) return false
            val n = normName(name)
            return KNOWN_NAMES.any { n.contains(it) }
        }

        fun gattStatusText(status: Int): String = when (status) {
            0 -> "ок"
            5, 15 -> "нужно сопряжение (код $status)"
            8 -> "таймаут связи — часы далеко или заняты (код 8)"
            19 -> "часы разорвали соединение (код 19)"
            22 -> "телефон разорвал соединение (код 22)"
            62 -> "не удалось установить соединение (код 62)"
            133 -> "ошибка стека Bluetooth (код 133)"
            147 -> "часы не ответили (код 147)"
            else -> "код $status"
        }
    }

    private data class Target(val address: String, val name: String, val reason: String)

    private enum class St { IDLE, SCANNING, CONNECTING, DISCOVERING, SUBSCRIBING, STREAMING, WAITING, STOPPED }

    private val appCtx = context.applicationContext
    private val manager = appCtx.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter

    private val thread = HandlerThread("hr-ble").apply { start() }
    private val h = Handler(thread.looper)

    // ---- состояние: трогается ТОЛЬКО на потоке h ----
    private var st = St.IDLE
    private var running = false
    private var gatt: BluetoothGatt? = null
    private var hrChar: BluetoothGattCharacteristic? = null
    private var target: Target? = null
    private var prefAddr = ""
    private var lastDataAt = 0L
    private var connectFailures = 0
    private var scanFailures = 0
    private var scanning = false
    private var scanUnfiltered = false
    private var extendedScanBroken = false
    private var scanStartedAt = 0L
    private var lastScanResultAt = 0L
    private var scanResultsCount = 0
    private var searchingSince = 0L
    private var lastSystemProbeAt = 0L
    private var lastSeenPublishAt = 0L
    private var pickPending = false
    private var receiverRegistered = false
    private val scanStarts = ArrayDeque<Long>()
    private val seen = HashMap<String, HrState.SeenDevice>()
    private val blacklistUntil = HashMap<String, Long>()

    private fun now() = System.currentTimeMillis()

    // =====================================================================
    // Публичный API — потокобезопасный, всё перекладывается на поток h
    // =====================================================================

    fun start() {
        h.post {
            if (running) return@post // повторное нажатие кнопки не должно дёргать скан
            running = true
            registerReceiver()
            h.postDelayed(watchdog, WATCHDOG_TICK_MS)
            searchingSince = now()
            beginCycle(quick = false)
        }
    }

    /** Полная остановка. После stop() объект не переиспользуется. */
    fun stop() {
        h.post { stopInternal() }
    }

    /** «Переподключиться»: сбросить всё и начать поиск заново. */
    fun reconnect() {
        h.post {
            if (!running) return@post
            resetForNewSearch()
            publish(Phase.SEARCHING, "переподключение по запросу")
            beginCycle(quick = false)
        }
    }

    /** Пользователь выбрал устройство в списке — подключаемся напрямую. */
    fun useDevice(address: String, name: String) {
        h.post {
            prefs.preferredAddress = address
            prefs.preferredName = name
            if (!running) return@post
            resetForNewSearch()
            target = Target(address, name, "выбрано вручную")
            beginCycle(quick = true)
        }
    }

    /** Забыть выбранное устройство: снова принимаем любой источник 0x180D. */
    fun forgetDevice() {
        h.post {
            prefs.preferredAddress = ""
            prefs.preferredName = ""
            if (!running) return@post
            resetForNewSearch()
            beginCycle(quick = false)
        }
    }

    // =====================================================================
    // Цикл поиска
    // =====================================================================

    private fun resetForNewSearch() {
        cancelSteps()
        stopScanInternal()
        closeGatt()
        target = null
        connectFailures = 0
        scanFailures = 0
        blacklistUntil.clear()
        lastSystemProbeAt = 0L
        searchingSince = now()
    }

    /**
     * Начало круга. quick=true — сразу прямое подключение к последнему устройству
     * (быстрое восстановление после обрыва), иначе полный поиск.
     */
    private fun beginCycle(quick: Boolean) {
        if (!running) return
        cancelSteps()
        if (!checkPreconditions()) return
        prefAddr = prefs.preferredAddress
        if (searchingSince == 0L) searchingSince = now()

        val t = target
        if (quick && t != null) {
            connect(t)
            return
        }
        val sys = systemConnectedCandidates()
        lastSystemProbeAt = now()
        if (sys.isNotEmpty()) {
            connect(sys.first())
            return
        }
        startScan()
    }

    /** Bluetooth, разрешения, геолокация. false — уже запланирован повтор и выставлен статус. */
    private fun checkPreconditions(): Boolean {
        val a = adapter
        when {
            a == null -> {
                enterWaiting(Phase.BT_OFF, "на телефоне нет Bluetooth-адаптера", 30_000)
                return false
            }
            !Perms.hasScan(appCtx) || !Perms.hasConnect(appCtx) -> {
                val msg = if (Perms.onlyCoarseLocation(appCtx))
                    "выдано только «приблизительное» местоположение — нужно «точное», иначе скан пустой"
                else "нет разрешений Bluetooth/геолокации — открой приложение и выдай их"
                enterWaiting(Phase.NO_PERMISSION, msg, 10_000)
                return false
            }
            !safe(false) { a.isEnabled } -> {
                enterWaiting(Phase.BT_OFF, "Bluetooth выключен — включи, подключусь сам", 10_000)
                return false
            }
            !Perms.locationEnabled(appCtx) -> {
                enterWaiting(Phase.LOCATION_OFF, "включи Геолокацию — без неё Android не отдаёт Bluetooth-скан", 5_000)
                return false
            }
        }
        return true
    }

    private fun enterWaiting(phase: Phase, msg: String, retryMs: Long) {
        cancelSteps()
        stopScanInternal()
        closeGatt()
        st = St.WAITING
        publish(phase, msg)
        h.postDelayed(fullCycleR, retryMs)
    }

    private val fullCycleR = Runnable { beginCycle(quick = false) }
    private val quickCycleR = Runnable { beginCycle(quick = true) }

    private fun isPreferred(addr: String) = prefAddr.isNotEmpty() && addr.equals(prefAddr, ignoreCase = true)

    private fun isBlacklisted(addr: String): Boolean {
        val until = blacklistUntil[addr] ?: return false
        if (now() > until) { blacklistUntil.remove(addr); return false }
        return true
    }

    /**
     * Устройства, уже подключённые к телефону по GATT (любым приложением).
     * Часы Garmin, связанные с Garmin Connect, держат постоянное соединение с телефоном
     * и в этом состоянии могут вообще не появляться в эфире — найти их можно только так.
     */
    private fun systemConnectedCandidates(): List<Target> {
        val list = safe(emptyList<BluetoothDevice>()) {
            manager?.getConnectedDevices(BluetoothProfile.GATT) ?: emptyList()
        }
        val t = now()
        val out = mutableListOf<Target>()
        for (d in list) {
            val addr = d.address ?: continue
            val name = safe("") { d.name ?: "" }
            val prev = seen[addr]
            seen[addr] = HrState.SeenDevice(
                address = addr,
                name = name.ifEmpty { prev?.name ?: "" },
                rssi = prev?.rssi ?: 0,
                hasHrs = prev?.hasHrs ?: false,
                connectable = true,
                systemConnected = true,
                lastSeenAt = t,
            )
            if (isBlacklisted(addr)) continue
            val ok = if (prefAddr.isNotEmpty()) isPreferred(addr) else looksLikeHrSource(name)
            if (ok) out += Target(addr, name.ifEmpty { addr }, "уже подключено к телефону, проверяю пульс")
        }
        publishSeen(force = true)
        return out
    }

    // =====================================================================
    // Скан
    // =====================================================================

    private val startScanR = Runnable { startScan() }

    private fun startScan() {
        if (!running) return
        if (!checkPreconditions()) return
        val a = adapter ?: return
        val scanner = safe(null) { a.bluetoothLeScanner }
        if (scanner == null) {
            enterWaiting(Phase.WAITING, "сканер Bluetooth недоступен, пробую снова", 5_000)
            return
        }

        // Лимит Android 7+: больше 5 запусков скана за 30 с — и система МОЛЧА отдаёт
        // ноль результатов (без onScanFailed). Сами держим не больше 4.
        val t = now()
        while (scanStarts.isNotEmpty() && t - scanStarts.first() > SCAN_STARTS_WINDOW_MS) scanStarts.removeFirst()
        if (scanStarts.size >= SCAN_STARTS_MAX) {
            val wait = scanStarts.first() + SCAN_STARTS_WINDOW_MS + 500 - t
            stopScanInternal()
            st = St.WAITING
            publish(Phase.SEARCHING, "пауза ${wait / 1000 + 1} с (лимит Android на частые запуски скана)")
            h.removeCallbacks(startScanR)
            h.postDelayed(startScanR, wait.coerceAtLeast(500))
            return
        }

        stopScanInternal() // снимаем старую регистрацию — иначе ошибка 1 (ALREADY_STARTED)

        val unfiltered = HrState.uiVisible
        val filters: List<ScanFilter> = if (unfiltered) emptyList() else buildList {
            add(ScanFilter.Builder().setServiceUuid(ParcelUuid(HR_SERVICE)).build())
            if (prefAddr.isNotEmpty() && BluetoothAdapter.checkBluetoothAddress(prefAddr.uppercase())) {
                add(ScanFilter.Builder().setDeviceAddress(prefAddr.uppercase()).build())
            }
        }
        val fast = unfiltered || t - searchingSince < FAST_SCAN_PERIOD_MS
        val sb = ScanSettings.Builder()
            // По умолчанию (startScan(callback)) — SCAN_MODE_LOW_POWER: окно 0,5 с из 5 с,
            // т.е. радио слушает эфир 10% времени. Плюс legacy-only. Это и есть главный
            // «тихий» фильтр, из-за которого редкая реклама часов не ловится.
            .setScanMode(if (fast) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_BALANCED)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(0)
        val extOk = !extendedScanBroken && safe(false) { a.isLeExtendedAdvertisingSupported }
        if (extOk) {
            sb.setLegacy(false) // ловим и расширенную рекламу BLE 5
            sb.setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
        }

        try {
            scanner.startScan(filters, sb.build(), scanCallback)
            scanning = true
            scanUnfiltered = unfiltered
            scanStartedAt = t
            lastScanResultAt = t
            scanStarts.addLast(t)
            st = St.SCANNING
            publish(Phase.SEARCHING, searchingText())
        } catch (e: Exception) {
            Log.w(TAG, "startScan", e)
            onScanFailedInternal(-1, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun stopScanInternal() {
        h.removeCallbacks(pickR)
        pickPending = false
        if (!scanning) return
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "stopScan", e)
        }
    }

    private fun restartScan() {
        stopScanInternal()
        startScan()
    }

    private val scanCallback = object : ScanCallback() {
        // Колбэки скана приходят на main-поток — перекладываем на наш.
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            h.post { handleResult(result) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            val copy = results.toList()
            h.post { copy.forEach { handleResult(it) } }
        }

        override fun onScanFailed(errorCode: Int) {
            h.post { onScanFailedInternal(errorCode, null) }
        }
    }

    private fun recordHasHrs(rec: ScanRecord?): Boolean {
        if (rec == null) return false
        if (rec.serviceUuids?.any { it.uuid == HR_SERVICE } == true) return true
        if (rec.serviceData?.keys?.any { it.uuid == HR_SERVICE } == true) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            rec.serviceSolicitationUuids?.any { it.uuid == HR_SERVICE } == true) return true
        return false
    }

    private fun handleResult(r: ScanResult) {
        if (!running || !scanning) return // хвост очереди после stopScan — игнорируем
        val t = now()
        scanResultsCount++
        lastScanResultAt = t
        scanFailures = 0

        val addr = r.device?.address ?: return
        val rec = r.scanRecord // может быть null — устройство всё равно учитываем
        val advName = rec?.deviceName ?: ""
        // имя может прийти только в scan response; в остальных пакетах берём кэш системы
        val name = advName.ifEmpty { safe("") { r.device.name ?: "" } }
        val prev = seen[addr]
        val merged = HrState.SeenDevice(
            address = addr,
            name = name.ifEmpty { prev?.name ?: "" },              // имя «липкое»
            rssi = r.rssi,
            hasHrs = recordHasHrs(rec) || prev?.hasHrs == true,     // ♥ «липкий»
            connectable = r.isConnectable,
            systemConnected = prev?.systemConnected ?: false,
            lastSeenAt = t,
        )
        seen[addr] = merged
        publishSeen(force = false)

        if (st != St.SCANNING || isBlacklisted(addr)) return

        if (isPreferred(addr)) {
            pick(Target(addr, merged.name.ifEmpty { prefs.preferredName.ifEmpty { addr } }, "выбранное устройство в эфире"))
            return
        }
        // Выбранное устройство задано — чужие источники пульса только показываем.
        if (prefAddr.isNotEmpty()) return

        if (merged.hasHrs && !pickPending) {
            pickPending = true
            h.postDelayed(pickR, PICK_WINDOW_MS)
        }
    }

    /** Из всех, кто рекламирует 0x180D последние секунды, берём самый сильный сигнал. */
    private val pickR = Runnable {
        pickPending = false
        if (st != St.SCANNING) return@Runnable
        val t = now()
        val best = seen.values
            .filter { it.hasHrs && t - it.lastSeenAt < 5_000 && !isBlacklisted(it.address) }
            .maxByOrNull { it.rssi }
            ?: return@Runnable
        pick(Target(best.address, best.name.ifEmpty { best.address }, "рекламирует пульс (0x180D)"))
    }

    private fun pick(t: Target) {
        if (st != St.SCANNING) return
        connect(t)
    }

    private fun onScanFailedInternal(code: Int, msg: String?) {
        if (!running) return
        scanning = false
        scanFailures++
        val text = when (code) {
            ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "скан уже запущен (код 1)"
            ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
                "система отказала в регистрации скана (код 2)" +
                    if (scanFailures >= 3) " — выключи и включи Bluetooth" else ""
            ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "внутренняя ошибка Bluetooth (код 3)"
            ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> {
                if (!extendedScanBroken) {
                    // расширенный скан не поддержан — откатываемся на legacy и пробуем сразу
                    extendedScanBroken = true
                    h.post(startScanR)
                    return
                }
                "скан не поддерживается (код 4)"
            }
            5 -> "нет ресурсов Bluetooth (код 5)"
            6 -> "слишком частые запуски скана (код 6)"
            else -> "ошибка скана: ${msg ?: "код $code"}"
        }
        val delay = backoff(scanFailures)
        enterWaiting(Phase.WAITING, "$text · повтор через ${delay / 1000} с", delay)
    }

    private fun backoff(n: Int): Long = (1_000L shl (n - 1).coerceIn(0, 5)).coerceAtMost(30_000L)

    // =====================================================================
    // GATT
    // =====================================================================

    private fun connect(t: Target) {
        if (!running) return
        cancelSteps()
        stopScanInternal()
        closeGatt()
        target = t
        st = St.CONNECTING
        publish(Phase.CONNECTING, "подключаюсь: ${t.reason}")

        val device = try {
            adapter?.getRemoteDevice(t.address.uppercase())
        } catch (e: IllegalArgumentException) {
            null
        }
        if (device == null) {
            failAndRetry("неверный адрес устройства ${t.address}", blacklist = true)
            return
        }
        // autoConnect=false: быстрое прямое подключение. autoConnect=true на HyperOS
        // может ждать минутами, а для устройств со сменным (RPA) адресом — вечно.
        // TRANSPORT_LE обязателен: fenix — dual-mode, без него стек может пойти по
        // классическому BR/EDR и вернуть 133.
        val g = try {
            device.connectGatt(appCtx, false, gattCallback, BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK, h)
        } catch (e: Exception) {
            Log.w(TAG, "connectGatt", e)
            null
        }
        if (g == null) {
            failAndRetry("система не дала подключиться к ${t.name}")
            return
        }
        gatt = g
        h.postDelayed(connectTimeoutR, CONNECT_TIMEOUT_MS)
    }

    private val connectTimeoutR = Runnable {
        failAndRetry("${target?.name ?: "устройство"} не ответило за ${CONNECT_TIMEOUT_MS / 1000} с")
    }
    private val discoverR = Runnable {
        val g = gatt ?: return@Runnable
        val ok = safe(false) { g.discoverServices() }
        if (!ok) failAndRetry("не удалось запросить список сервисов")
    }
    private val discoverTimeoutR = Runnable { failAndRetry("не дождался списка сервисов") }
    private val subscribeTimeoutR = Runnable { failAndRetry("часы не подтвердили подписку на пульс") }

    private fun cancelSteps() {
        h.removeCallbacks(connectTimeoutR)
        h.removeCallbacks(discoverR)
        h.removeCallbacks(discoverTimeoutR)
        h.removeCallbacks(subscribeTimeoutR)
        h.removeCallbacks(fullCycleR)
        h.removeCallbacks(quickCycleR)
        h.removeCallbacks(startScanR)
    }

    /** Ровно один close() на объект; повторные вызовы безопасны. */
    private fun closeGatt() {
        val g = gatt ?: return
        gatt = null
        hrChar = null
        try { g.disconnect() } catch (_: Exception) {}
        try { g.close() } catch (_: Exception) {}
    }

    /**
     * Любая неудача на пути к пульсу. Логика повторов:
     * - 1-2 неудачи — быстрое прямое переподключение к тому же устройству
     *   (первая 133 почти всегда лечится повтором через секунду);
     * - дальше — полный поиск (скан с фильтром по MAC/0x180D работает и при выключенном экране);
     * - чужое (не выбранное) устройство после 3 неудач или без HRS — в бан-лист на время.
     */
    private fun failAndRetry(msg: String, blacklist: Boolean = false) {
        if (!running) return
        val t = target
        cancelSteps()
        stopScanInternal()
        closeGatt()
        connectFailures++
        if (t != null && !isPreferred(t.address)) {
            if (blacklist) blacklistUntil[t.address] = now() + NO_HRS_BLACKLIST_MS
            else if (connectFailures >= 3) blacklistUntil[t.address] = now() + FAIL_BLACKLIST_MS
        }
        val quick = t != null && !isBlacklisted(t.address) && connectFailures <= 2
        if (!quick) target = if (t != null && isPreferred(t.address)) t else null
        val delay = backoff(connectFailures)
        st = St.WAITING
        publish(Phase.WAITING, "$msg · повтор через ${(delay + 999) / 1000} с")
        h.postDelayed(if (quick) quickCycleR else fullCycleR, delay)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g !== gatt) { // колбэк от старого объекта — закрываем и забываем
                try { g.close() } catch (_: Exception) {}
                return
            }
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                h.removeCallbacks(connectTimeoutR)
                st = St.DISCOVERING
                publish(Phase.SUBSCRIBING, "соединение есть, ищу сервис пульса")
                // для сопряжённых устройств стек может ещё обрабатывать Service Changed —
                // небольшая пауза перед discoverServices снижает число 129/133
                val bonded = safe(false) { g.device.bondState == BluetoothDevice.BOND_BONDED }
                h.postDelayed(discoverR, if (bonded) 1_000L else 300L)
                h.postDelayed(discoverTimeoutR, DISCOVER_TIMEOUT_MS)
                return
            }
            // отключение или ошибка подключения
            val wasStreaming = st == St.STREAMING
            val name = target?.name ?: "устройство"
            val text = if (wasStreaming) "связь с $name потеряна (${gattStatusText(status)})"
            else "не удалось подключиться к $name (${gattStatusText(status)})"
            if (wasStreaming) connectFailures = 0 // первый повтор — быстрый
            failAndRetry(text)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt) return
            h.removeCallbacks(discoverTimeoutR)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failAndRetry("ошибка поиска сервисов (${gattStatusText(status)})")
                return
            }
            val ch = g.getService(HR_SERVICE)?.getCharacteristic(HR_MEASUREMENT)
            if (ch == null) {
                val t = target
                if (t != null && isPreferred(t.address)) {
                    failAndRetry("${t.name} на связи, но не отдаёт пульс — включи трансляцию пульса на часах")
                } else {
                    failAndRetry("у ${t?.name ?: "устройства"} нет сервиса пульса — ищу дальше", blacklist = true)
                }
                return
            }
            enableNotifications(g, ch)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (g !== gatt || d.uuid != CCCD || st != St.SUBSCRIBING) return
            h.removeCallbacks(subscribeTimeoutR)
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> {
                    st = St.STREAMING
                    lastDataAt = now() // отсчёт сторожа «нет данных» — с момента подписки
                    publish(Phase.SUBSCRIBING, "подписка включена, жду первый удар")
                }
                5, 15 -> { // insufficient authentication / encryption
                    safe(false) { g.device.createBond() }
                    failAndRetry("часы требуют сопряжения — подтверди запрос на телефоне и часах")
                }
                else -> failAndRetry("часы отклонили подписку (${gattStatusText(status)})")
            }
        }

        // Android 13+: значение приходит параметром (старый ch.value не потокобезопасен)
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            handleValue(g, ch.uuid, value)
        }

        @Deprecated("Android 12 и ниже")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return // обработано выше
            val v = ch.value ?: return
            handleValue(g, ch.uuid, v.copyOf())
        }
    }

    private fun enableNotifications(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        val props = ch.properties
        val value = when {
            props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ->
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 ->
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else -> {
                failAndRetry("характеристика пульса не поддерживает уведомления")
                return
            }
        }
        if (!safe(false) { g.setCharacteristicNotification(ch, true) }) {
            failAndRetry("не удалось включить уведомления локально")
            return
        }
        hrChar = ch
        val cccd = ch.getDescriptor(CCCD)
        if (cccd == null) {
            // редкие устройства шлют уведомления без CCCD — просто ждём данные
            st = St.STREAMING
            lastDataAt = now()
            publish(Phase.SUBSCRIBING, "жду первый удар (устройство без CCCD)")
            return
        }
        st = St.SUBSCRIBING
        if (!writeDescriptor(g, cccd, value)) {
            failAndRetry("не удалось записать подписку на пульс")
            return
        }
        h.postDelayed(subscribeTimeoutR, SUBSCRIBE_TIMEOUT_MS)
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean =
        safe(false) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
            } else {
                d.value = value
                g.writeDescriptor(d)
            }
        }

    private fun handleValue(g: BluetoothGatt, uuid: UUID, value: ByteArray) {
        if (g !== gatt || !running || uuid != HR_MEASUREMENT) return
        val m = HrsParser.parse(value) ?: return
        val t = now()
        lastDataAt = t
        val tgt = target
        if (st != St.STREAMING || connectFailures != 0 || searchingSince != 0L) {
            h.removeCallbacks(subscribeTimeoutR) // данные пошли раньше подтверждения CCCD
            st = St.STREAMING
            connectFailures = 0
            searchingSince = 0L
            // первое успешное устройство запоминаем: дальше подключаемся только к нему
            if (tgt != null && !isPreferred(tgt.address)) {
                prefs.preferredAddress = tgt.address
                prefs.preferredName = tgt.name
                prefAddr = tgt.address
            }
            prefs.onboardingDone = true
        }
        val noContact = m.contactSupported && !m.contactDetected
        val valid = m.bpm in 20..250 && !noContact
        if (valid) {
            try { onMeasurement(m, t) } catch (e: Exception) { Log.w(TAG, "onMeasurement", e) }
        }
        HrState.updateBle {
            it.copy(
                phase = Phase.STREAMING,
                detail = if (noContact) "нет контакта с кожей — поправь часы/браслет" else "пульс идёт",
                deviceName = tgt?.name ?: it.deviceName,
                deviceAddress = tgt?.address ?: it.deviceAddress,
                bpm = if (valid) m.bpm else it.bpm,
                contact = if (m.contactSupported) m.contactDetected else null,
                lastDataAt = t,
                searchingSince = 0L,
            )
        }
    }

    // =====================================================================
    // Сторож
    // =====================================================================

    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                tick()
            } catch (e: Exception) {
                Log.e(TAG, "watchdog", e)
            }
            h.postDelayed(this, WATCHDOG_TICK_MS)
        }
    }

    private fun tick() {
        val t = now()
        when (st) {
            St.STREAMING -> if (t - lastDataAt > NO_DATA_TIMEOUT_MS) {
                publish(Phase.NO_DATA, "нет данных ${NO_DATA_TIMEOUT_MS / 1000} с — переподключаюсь")
                connectFailures = 0
                failAndRetry("нет данных от ${target?.name ?: "устройства"}")
            }
            St.SCANNING -> {
                if (!Perms.locationEnabled(appCtx) || safe(false) { adapter?.isEnabled } != true) {
                    beginCycle(quick = false) // checkPreconditions выставит статус
                    return
                }
                when {
                    // экран открыли/закрыли — меняем режим (без фильтра / с фильтром)
                    HrState.uiVisible != scanUnfiltered -> restartScan()
                    // долгий скан система переводит в «оппортунистический» — перезапуск
                    t - scanStartedAt > SCAN_SESSION_MS -> restartScan()
                    // при открытом экране вокруг почти всегда что-то есть; тишина = скан заглушен
                    scanUnfiltered && t - lastScanResultAt > SCAN_SILENCE_MS -> restartScan()
                    else -> {
                        if (prefAddr.isEmpty() && t - scanStartedAt > NAME_FALLBACK_AFTER_MS && tryNameFallback()) return
                        if (t - lastSystemProbeAt > SYSTEM_PROBE_EVERY_MS) {
                            lastSystemProbeAt = t
                            val sys = systemConnectedCandidates()
                            if (sys.isNotEmpty()) { connect(sys.first()); return }
                        }
                        publish(Phase.SEARCHING, searchingText())
                    }
                }
            }
            else -> {}
        }
        publishSeen(force = true)
    }

    /** Запасной путь: «часовое» имя без 0x180D в рекламе — проверяем сервисы через GATT. */
    private fun tryNameFallback(): Boolean {
        val t = now()
        if (seen.values.any { it.hasHrs && t - it.lastSeenAt < 10_000 && !isBlacklisted(it.address) }) return false
        val c = seen.values
            .filter { looksLikeHrSource(it.name) && it.connectable && t - it.lastSeenAt < 10_000 && !isBlacklisted(it.address) }
            .maxByOrNull { it.rssi } ?: return false
        connect(Target(c.address, c.name, "имя похоже на источник пульса, проверяю сервисы"))
        return true
    }

    private fun searchingText(): String {
        val t = now()
        val secs = if (searchingSince > 0) (t - searchingSince) / 1000 else 0
        val recent = seen.values.filter { t - it.lastSeenAt < SEEN_TTL_MS }
        val hrs = recent.count { it.hasHrs }
        val who = if (prefAddr.isNotEmpty()) "ищу «${prefs.preferredName.ifEmpty { prefAddr }}»" else "ищу источник пульса"
        val mode = if (scanUnfiltered) "" else " (фоновый режим: только 0x180D/выбранное)"
        val base = "$who · $secs с · в эфире ${recent.size}, с пульсом $hrs$mode"
        return when {
            secs < 30 -> base
            !scanUnfiltered -> base
            recent.isEmpty() -> "$base\nНе вижу НИЧЕГО — проверь Геолокацию, разрешения и Bluetooth"
            prefAddr.isNotEmpty() && hrs > 0 -> "$base\nВижу другой источник пульса, но жду выбранный. Нажми на устройство в списке, чтобы сменить"
            else -> "$base\nЧасы не видны: открой экран трансляции на часах (см. «Как включить трансляцию»)"
        }
    }

    // =====================================================================
    // Bluetooth on/off, геолокация
    // =====================================================================

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // зарегистрирован с handler = h, т.е. мы уже на нашем потоке
            if (!running) return
            when (i.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                        BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                            // GATT-объекты после выключения BT недействительны — закрываем сразу
                            cancelSteps()
                            scanning = false
                            closeGatt()
                            st = St.WAITING
                            publish(Phase.BT_OFF, "Bluetooth выключен — включи, подключусь сам")
                            // страховка, если STATE_ON не придёт: проверка раз в 15 с
                            h.postDelayed(fullCycleR, 15_000)
                        }
                        BluetoothAdapter.STATE_ON -> {
                            connectFailures = 0
                            scanFailures = 0
                            cancelSteps()
                            h.postDelayed(fullCycleR, 1_500)
                        }
                    }
                }
                LocationManager.PROVIDERS_CHANGED_ACTION -> {
                    if (st == St.WAITING || st == St.SCANNING) {
                        cancelSteps()
                        h.postDelayed(fullCycleR, 500)
                    }
                }
            }
        }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val f = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
        }
        try {
            ContextCompat.registerReceiver(appCtx, receiver, f, null, h, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "registerReceiver", e)
        }
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        receiverRegistered = false
        try { appCtx.unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    // =====================================================================
    // Остановка
    // =====================================================================

    private fun stopInternal() {
        running = false
        st = St.STOPPED
        h.removeCallbacksAndMessages(null)
        stopScanInternal()
        unregisterReceiver()
        val g = gatt
        val ch = hrChar
        if (g != null && ch != null) {
            // корректно выключаем уведомления на часах, потом рвём связь
            safe(false) { g.setCharacteristicNotification(ch, false) }
            ch.getDescriptor(CCCD)?.let { writeDescriptor(g, it, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) }
            h.postDelayed({
                closeGatt()
                thread.quitSafely()
            }, 300)
        } else {
            closeGatt()
            thread.quitSafely()
        }
    }

    // =====================================================================
    // Публикация состояния
    // =====================================================================

    private fun publish(phase: Phase, detail: String) {
        val t = target
        val unf = scanning && scanUnfiltered
        val since = searchingSince
        val results = scanResultsCount
        HrState.updateBle {
            it.copy(
                phase = phase,
                detail = detail,
                deviceName = t?.name ?: "",
                deviceAddress = t?.address ?: "",
                searchingSince = since,
                scanResults = results,
                unfilteredScan = unf,
            )
        }
    }

    private fun publishSeen(force: Boolean) {
        val t = now()
        if (!force && t - lastSeenPublishAt < 1_000) return
        lastSeenPublishAt = t
        seen.entries.removeAll { t - it.value.lastSeenAt > SEEN_TTL_MS && !isPreferred(it.key) }
        val list = seen.values
            .sortedWith(
                compareByDescending<HrState.SeenDevice> { isPreferred(it.address) }
                    .thenByDescending { it.hasHrs }
                    .thenByDescending { it.systemConnected }
                    .thenByDescending { looksLikeHrSource(it.name) }
                    .thenByDescending { it.hasName }
                    .thenByDescending { if (it.rssi == 0) -200 else it.rssi }
            )
            .take(40)
        val results = scanResultsCount
        HrState.updateBle { it.copy(seen = list, scanResults = results) }
    }

    private inline fun <T> safe(default: T, block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        Log.w(TAG, "BLE call failed", e)
        default
    }
}
