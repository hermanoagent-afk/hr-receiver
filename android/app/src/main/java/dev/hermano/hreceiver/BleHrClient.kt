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
    @Volatile private var running = false

    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            if (connected && System.currentTimeMillis() - lastDataAt > NO_DATA_TIMEOUT_MS) {
                Log.w(TAG, "нет уведомлений 15 с — переподключение")
                onState("нет данных — переподключение")
                reconnect()
            }
            android.os.Handler(context.mainLooper).postDelayed(this, WATCHDOG_TICK_MS)
        }
    }

    fun start() {
        running = true
        android.os.Handler(context.mainLooper).postDelayed(watchdog, WATCHDOG_TICK_MS)
        scan()
    }

    fun stop() {
        running = false
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    private fun scan() {
        if (!running) return
        onState("сканирование")
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(HR_SERVICE))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        adapter.bluetoothLeScanner?.startScan(listOf(filter), settings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!running) return
            adapter.bluetoothLeScanner?.stopScan(this)
            onState("браслет найден: ${result.device.address}")
            connect(result.device)
        }
        override fun onScanFailed(errorCode: Int) {
            onState("ошибка сканирования: $errorCode")
            android.os.Handler(context.mainLooper).postDelayed({ scan() }, 5_000)
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
