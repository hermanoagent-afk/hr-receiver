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
import androidx.core.view.WindowCompat

/**
 * Экран настройки: 3 пронумерованных поля + статус.
 * Умная вставка: если URL попал не в то поле — перекладываем сами.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true) // контент не уезжает под статусбар
        prefs = Prefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 40, 48, 48)
        }

        fun label(text: String) = TextView(this).apply {
            this.text = text
            setPadding(0, 28, 0, 8)
        }

        val serverInput = EditText(this).apply {
            hint = "https://..."
            setText(prefs.serverUrl)
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
