package dev.hermano.hreceiver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Минимальный экран v0.1: настройка (сервер, имя устройства, ключ) + статус.
 * Без графиков — они уже на сервере.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = Prefs(this)

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        val serverInput = EditText(this).apply {
            hint = "Адрес сервера (https://...)"
            setText(prefs.serverUrl)
        }
        val deviceInput = EditText(this).apply {
            hint = "Имя устройства (например: телефон-кухня)"
            setText(prefs.deviceId)
        }
        val keyInput = EditText(this).apply {
            hint = "Ключ устройства"
            setText(prefs.deviceKey)
        }
        val saveBtn = Button(this).apply { text = "Сохранить и запустить" }
        val status = TextView(this).apply { text = "статус: не запущено" }

        root.addView(serverInput)
        root.addView(deviceInput)
        root.addView(keyInput)
        root.addView(saveBtn)
        root.addView(status)
        setContentView(root)

        requestPermissions()

        saveBtn.setOnClickListener {
            val url = serverInput.text.toString().trim()
            val dev = deviceInput.text.toString().trim()
            val key = keyInput.text.toString().trim()

            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                status.text = "ошибка: адрес сервера должен начинаться с https://"
                return@setOnClickListener
            }
            if (dev.isEmpty() || key.isEmpty()) {
                status.text = "ошибка: заполни имя устройства и ключ"
                return@setOnClickListener
            }
            if (!hasBtPermissions()) {
                status.text = "нужны разрешения Bluetooth — нажми «Разрешить»"
                requestPermissions()
                return@setOnClickListener
            }

            prefs.serverUrl = url
            prefs.deviceId = dev
            prefs.deviceKey = key
            try {
                ContextCompat.startForegroundService(this, Intent(this, HrForegroundService::class.java))
                status.text = "статус: служба запущена"
            } catch (e: Exception) {
                status.text = "ошибка запуска: ${e.message}"
            }
        }
    }

    private fun hasBtPermissions(): Boolean {
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            need += Manifest.permission.BLUETOOTH_SCAN
            need += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            need += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return need.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            need += Manifest.permission.BLUETOOTH_SCAN
            need += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            need += Manifest.permission.ACCESS_FINE_LOCATION
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
