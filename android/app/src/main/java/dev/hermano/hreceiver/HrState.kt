package dev.hermano.hreceiver

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Единый источник правды о состоянии приёмника.
 * Пишут: BleHrClient (BLE), Sender (сеть), HrForegroundService (жизненный цикл).
 * Читают: экран и уведомление. Все типы неизменяемые — StateFlow потокобезопасен.
 */
object HrState {

    /** Фаза BLE-цикла. Каждая фаза — честное описание того, что происходит прямо сейчас. */
    enum class Phase {
        STOPPED,        // служба не запущена
        NO_PERMISSION,  // нет разрешений Bluetooth/геолокации
        BT_OFF,         // Bluetooth выключен
        LOCATION_OFF,   // геолокация выключена (без неё Android не отдаёт скан)
        SEARCHING,      // ищем источник пульса
        CONNECTING,     // устанавливаем соединение
        SUBSCRIBING,    // соединились, ищем сервис и включаем уведомления
        STREAMING,      // пульс идёт
        NO_DATA,        // подключены, но данных нет — переподключаемся
        WAITING,        // пауза перед повтором (backoff)
    }

    data class SeenDevice(
        val address: String,
        val name: String,
        val rssi: Int,
        val hasHrs: Boolean,
        val connectable: Boolean,
        val systemConnected: Boolean,
        val lastSeenAt: Long,
    ) {
        val hasName: Boolean get() = name.isNotBlank()
    }

    data class Ble(
        val phase: Phase = Phase.STOPPED,
        val detail: String = "",
        val deviceName: String = "",
        val deviceAddress: String = "",
        val bpm: Int = 0,
        val contact: Boolean? = null,
        val lastDataAt: Long = 0L,
        val searchingSince: Long = 0L,
        val scanResults: Int = 0,
        val unfilteredScan: Boolean = false,
        val seen: List<SeenDevice> = emptyList(),
    )

    data class Send(
        val detail: String = "",
        val ok: Boolean = false,
        val lastOkAt: Long = 0L,
        val queue: Long = 0L,
    )

    private val _ble = MutableStateFlow(Ble())
    private val _send = MutableStateFlow(Send())
    private val _running = MutableStateFlow(false)

    val ble: StateFlow<Ble> = _ble.asStateFlow()
    val send: StateFlow<Send> = _send.asStateFlow()
    val serviceRunning: StateFlow<Boolean> = _running.asStateFlow()

    /** Экран виден пользователю: можно сканировать без фильтра ради диагностики. */
    @Volatile var uiVisible: Boolean = false

    fun updateBle(f: (Ble) -> Ble) = _ble.update(f)
    fun updateSend(f: (Send) -> Send) = _send.update(f)
    fun setRunning(v: Boolean) {
        _running.value = v
        if (!v) _ble.update { Ble(phase = Phase.STOPPED, seen = it.seen) }
    }
}
