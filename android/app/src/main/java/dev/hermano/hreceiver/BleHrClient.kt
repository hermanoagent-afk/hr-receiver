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
    }

    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter get() = manager.adapter
    private var gatt: BluetoothGatt? = null
    private var lastDataAt = 0L
    private var connected = false
    private var scanFailures = 0
    private var scanningSince = 0L
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
                onState("браслет не виден 60 с — похоже, он подключён к другому телефону (Pulsoid?)")
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

        val useFilter = scanFailures < 2
        onState(if (useFilter) "сканирование" else "сканирование (без фильтра, запасной режим)")
        try {
            if (useFilter) {
                val filter = ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(HR_SERVICE))
                    .build()
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()
                scanner.startScan(listOf(filter), settings, scanCallback)
            } else {
                scanner.startScan(scanCallback) // фильтруем результат сами
            }
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
            // в запасном режиме без фильтра отсекаем чужие устройства сами
            if (scanFailures >= 2) {
                val uuids = result.scanRecord?.serviceUuids ?: emptyList()
                if (uuids.none { it.uuid == HR_SERVICE }) return
            }
            scanFailures = 0
            try { adapter?.bluetoothLeScanner?.stopScan(this) } catch (_: Exception) {}
            onState("браслет найден: ${result.device.address}")
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
                onState("HRS не найден на устройстве")
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
