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
            prefs.serverUrl = serverInput.text.toString().trim()
            prefs.deviceId = deviceInput.text.toString().trim()
            prefs.deviceKey = keyInput.text.toString().trim()
            ContextCompat.startForegroundService(this, Intent(this, HrForegroundService::class.java))
            status.text = "статус: служба запущена"
        }
    }

    private fun requestPermissions() {
        val need = mutableListOf<String>()
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
