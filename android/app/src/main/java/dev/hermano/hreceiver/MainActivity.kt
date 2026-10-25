package dev.hermano.hreceiver

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import dev.hermano.hreceiver.HrState.Phase
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Главный экран HR Receiver:
 * - Чёткий и понятный первый запуск (онбординг / подсказки)
 * - Единый статус-экран (честное состояние: ищем, подключаемся, стримим, нет пульса)
 * - Кнопки управления: «Переподключить», «Остановить», «Запустить», «Как включить трансляцию»
 * - Список обнаруженных BLE устройств с интерактивным выбором (по клику подключается напрямую)
 * - Проверка и запрос разрешений без вылетов
 */
class MainActivity : AppCompatActivity() {

    companion object {
        // Конфиг сандбокса встроен в приложение — вводить ничего не нужно
        const val DEFAULT_SERVER = "https://formation-april-leads-diet.trycloudflare.com"
        const val DEFAULT_DEVICE = "phone-1"
        const val DEFAULT_KEY = "sbx-key-1"
        const val DEFAULT_OPERATOR = "Сева"
    }

    private lateinit var prefs: Prefs

    private lateinit var serverInput: EditText
    private lateinit var deviceInput: EditText
    private lateinit var keyInput: EditText
    private lateinit var operatorInput: EditText
    private lateinit var statusCard: LinearLayout
    private lateinit var tvStatusPhase: TextView
    private lateinit var tvStatusDetail: TextView
    private lateinit var tvBpmLarge: TextView
    private lateinit var tvSendDetail: TextView
    private lateinit var tvPreferredDevice: TextView
    private lateinit var devicesContainer: LinearLayout
    private lateinit var tvDevicesHeader: TextView
    private lateinit var btnToggleService: Button
    private lateinit var btnReconnect: Button
    private lateinit var btnHelp: Button

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val fineGranted = Perms.hasScan(this)
        if (!fineGranted) {
            tvStatusDetail.text = if (Perms.onlyCoarseLocation(this)) {
                "Внимание: выдано только «приблизительное» местоположение. Нужно «точное» для сканирования BLE!"
            } else {
                "Для сканирования Bluetooth требуются разрешения. Открой настройки приложения."
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        supportActionBar?.hide()

        prefs = Prefs.get(this)

        // Предзаполнение конфига: пользователь ничего не вводит
        if (prefs.serverUrl.isBlank()) prefs.serverUrl = DEFAULT_SERVER
        if (prefs.deviceId.isBlank()) prefs.deviceId = DEFAULT_DEVICE
        if (prefs.deviceKey.isBlank()) prefs.deviceKey = DEFAULT_KEY
        if (prefs.operatorName.isBlank()) prefs.operatorName = DEFAULT_OPERATOR

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E1116"))
            setPadding(40, 40, 40, 60)
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(40, sys.top + 24, 40, sys.bottom + 48)
            insets
        }

        buildUi(root)
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0E1116"))
            addView(root)
        })

        observeState()
        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
        HrState.uiVisible = true
    }

    override fun onPause() {
        super.onPause()
        HrState.uiVisible = false
    }

    private fun buildUi(root: LinearLayout) {
        val titleView = TextView(this).apply {
            text = "HR Receiver"
            setTextColor(Color.WHITE)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 4)
        }
        val subTitleView = TextView(this).apply {
            text = "Медицинский приёмник пульса BLE 24/7"
            setTextColor(Color.parseColor("#78909C"))
            textSize = 13f
            setPadding(0, 0, 0, 24)
        }
        root.addView(titleView)
        root.addView(subTitleView)

        // --- КАРТОЧКА СТАТУСА ---
        statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#171C24"))
            setPadding(36, 32, 36, 32)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 24) }
            layoutParams = lp
        }

        tvStatusPhase = TextView(this).apply {
            text = "Остановлено"
            setTextColor(Color.parseColor("#B0BEC5"))
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        }
        tvBpmLarge = TextView(this).apply {
            text = "—"
            setTextColor(Color.parseColor("#FF5A6E"))
            textSize = 48f
            typeface = Typeface.DEFAULT_BOLD
            visibility = View.GONE
        }
        tvStatusDetail = TextView(this).apply {
            text = "Служба не активна. Нажмите «Запустить»."
            setTextColor(Color.parseColor("#CFD8DC"))
            textSize = 13.5f
            setLineSpacing(6f, 1f)
            setPadding(0, 8, 0, 8)
        }
        tvSendDetail = TextView(this).apply {
            text = "Сервер: —"
            setTextColor(Color.parseColor("#90A4AE"))
            textSize = 12.5f
            setPadding(0, 4, 0, 0)
        }
        tvPreferredDevice = TextView(this).apply {
            val pref = prefs.preferredName.ifEmpty { prefs.preferredAddress }
            text = if (pref.isNotEmpty()) "Выбранное устройство: $pref" else "Источник: любой 0x180D"
            setTextColor(Color.parseColor("#80CBC4"))
            textSize = 12f
            setPadding(0, 8, 0, 0)
        }

        statusCard.addView(tvStatusPhase)
        statusCard.addView(tvBpmLarge)
        statusCard.addView(tvStatusDetail)
        statusCard.addView(tvSendDetail)
        statusCard.addView(tvPreferredDevice)
        root.addView(statusCard)

        // --- КНОПКИ ДЕЙСТВИЙ ---
        val buttonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 24) }
            layoutParams = lp
        }

        btnToggleService = Button(this).apply {
            text = "Запустить"
            setBackgroundColor(Color.parseColor("#FF5A6E"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 8, 0)
            }
            setOnClickListener { toggleService() }
        }

        btnReconnect = Button(this).apply {
            text = "Переподключить"
            setBackgroundColor(Color.parseColor("#263238"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(8, 0, 0, 0)
            }
            setOnClickListener {
                val err = HrForegroundService.send(this@MainActivity, HrForegroundService.ACTION_RECONNECT)
                if (err != null) tvStatusDetail.text = err
            }
        }

        buttonsRow.addView(btnToggleService)
        buttonsRow.addView(btnReconnect)
        root.addView(buttonsRow)

        // --- КНОПКА ИНСТРУКЦИИ ---
        btnHelp = Button(this).apply {
            text = "📖 Как включить трансляцию на часах / HyperOS"
            setBackgroundColor(Color.parseColor("#1C2530"))
            setTextColor(Color.parseColor("#80D8FF"))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 28) }
            layoutParams = lp
            setOnClickListener {
                startActivity(Intent(this@MainActivity, HelpActivity::class.java))
            }
        }
        root.addView(btnHelp)

        // --- НАСТРОЙКИ СЕРВЕРА (СВОРАЧИВАЕМЫЕ / КОМПАКТНЫЕ) ---
        fun fieldLabel(t: String) = TextView(this).apply {
            text = t
            setTextColor(Color.parseColor("#90A4AE"))
            textSize = 13f
            setPadding(0, 12, 0, 4)
        }

        serverInput = EditText(this).apply {
            hint = "https://example.com"
            setHintTextColor(Color.parseColor("#455A64"))
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#171C24"))
            setPadding(24, 20, 24, 20)
            setText(prefs.serverUrl.ifEmpty { "https://formation-april-leads-diet.trycloudflare.com" })
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }

        deviceInput = EditText(this).apply {
            hint = "phone-1"
            setHintTextColor(Color.parseColor("#455A64"))
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#171C24"))
            setPadding(24, 20, 24, 20)
            setText(prefs.deviceId.ifEmpty { "phone-1" })
        }

        keyInput = EditText(this).apply {
            hint = "sbx-key-1"
            setHintTextColor(Color.parseColor("#455A64"))
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#171C24"))
            setPadding(24, 20, 24, 20)
            setText(prefs.deviceKey)
        }

        operatorInput = EditText(this).apply {
            hint = "например: Сева / папа / мама"
            setHintTextColor(Color.parseColor("#455A64"))
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#171C24"))
            setPadding(24, 20, 24, 20)
            setText(prefs.operatorName)
        }

        val btnSaveConfig = Button(this).apply {
            text = "Сохранить настройки"
            setBackgroundColor(Color.parseColor("#263238"))
            setTextColor(Color.WHITE)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 16, 0, 32) }
            layoutParams = lp
            setOnClickListener { saveConfig() }
        }

        // --- НАСТРОЙКИ СЕРВЕРА: скрыты, обычному пользователю не нужны ---
        val settingsSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        settingsSection.addView(fieldLabel("Адрес сервера (HTTPS)"))
        settingsSection.addView(serverInput)
        settingsSection.addView(fieldLabel("ID клиента"))
        settingsSection.addView(deviceInput)
        settingsSection.addView(fieldLabel("Ключ авторизации"))
        settingsSection.addView(keyInput)
        settingsSection.addView(fieldLabel("Кто с этим телефоном (имя)"))
        settingsSection.addView(operatorInput)
        settingsSection.addView(btnSaveConfig)

        val btnToggleSettings = Button(this).apply {
            text = "⚙ Сервер и ключ (обычно не нужно)"
            setBackgroundColor(Color.parseColor("#1C2530"))
            setTextColor(Color.parseColor("#78909C"))
            textSize = 12f
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 16) }
            layoutParams = lp
            setOnClickListener {
                settingsSection.visibility =
                    if (settingsSection.visibility == View.GONE) View.VISIBLE else View.GONE
            }
        }
        root.addView(btnToggleSettings)
        root.addView(settingsSection)

        // --- СПИСОК УСТРОЙСТВ BLE ВОКРУГ ---
        tvDevicesHeader = TextView(this).apply {
            text = "Источник пульса. Чтобы поменять — нажмите «Выбрать» у нужного " +
                "(например, WHOOP). «Забыть» = снова автоматический выбор:"
            setTextColor(Color.WHITE)
            textSize = 13f
            setLineSpacing(4f, 1f)
            setPadding(0, 8, 0, 12)
        }
        devicesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        root.addView(tvDevicesHeader)
        root.addView(devicesContainer)
    }

    private fun observeState() {
        lifecycleScope.launch {
            combine(HrState.ble, HrState.send, HrState.serviceRunning) { b, s, running ->
                Triple(b, s, running)
            }.collect { (ble, send, running) ->
                updateUiState(ble, send, running)
            }
        }
    }

    private fun updateUiState(b: HrState.Ble, s: HrState.Send, running: Boolean) {
        btnToggleService.text = if (running) "Остановить" else "Запустить"
        btnToggleService.setBackgroundColor(
            if (running) Color.parseColor("#C62828") else Color.parseColor("#FF5A6E")
        )

        val pref = prefs.preferredName.ifEmpty { prefs.preferredAddress }
        val src = if (pref.isNotEmpty()) {
            "Источник: $pref (только он)"
        } else {
            "Источник: первый обнаруженный с 0x180D"
        }
        tvPreferredDevice.text = "Телефон: ${prefs.operatorName}\n$src"

        when (b.phase) {
            Phase.STOPPED -> {
                tvStatusPhase.text = "Остановлено"
                tvStatusPhase.setTextColor(Color.parseColor("#B0BEC5"))
                tvBpmLarge.visibility = View.GONE
                tvStatusDetail.text = b.detail.ifEmpty { "Служба не активна. Нажмите «Запустить»." }
            }
            Phase.NO_PERMISSION -> {
                tvStatusPhase.text = "Требуются разрешения"
                tvStatusPhase.setTextColor(Color.parseColor("#FFA726"))
                tvBpmLarge.visibility = View.GONE
                tvStatusDetail.text = b.detail
            }
            Phase.BT_OFF -> {
                tvStatusPhase.text = "Bluetooth выключен"
                tvStatusPhase.setTextColor(Color.parseColor("#EF5350"))
                tvBpmLarge.visibility = View.GONE
                tvStatusDetail.text = b.detail
            }
            Phase.LOCATION_OFF -> {
                tvStatusPhase.text = "Геолокация выключена"
                tvStatusPhase.setTextColor(Color.parseColor("#FFA726"))
                tvBpmLarge.visibility = View.GONE
                tvStatusDetail.text = b.detail
            }
            Phase.SEARCHING -> {
                tvStatusPhase.text = "Поиск устройств..."
                tvStatusPhase.setTextColor(Color.parseColor("#29B6F6"))
                tvBpmLarge.visibility = View.GONE
                tvStatusDetail.text = b.detail
            }
            Phase.CONNECTING, Phase.SUBSCRIBING -> {
                tvStatusPhase.text = "Подключение..."
                tvStatusPhase.setTextColor(Color.parseColor("#26A69A"))
                tvBpmLarge.visibility = View.GONE
                tvStatusDetail.text = "${b.deviceName.ifEmpty { "Устройство" }}: ${b.detail}"
            }
            Phase.STREAMING -> {
                tvStatusPhase.text = "Пульс принимается ♥"
                tvStatusPhase.setTextColor(Color.parseColor("#2BD9A0"))
                if (b.bpm > 0) {
                    tvBpmLarge.visibility = View.VISIBLE
                    tvBpmLarge.text = "${b.bpm}"
                } else {
                    tvBpmLarge.visibility = View.GONE
                }
                val contactStr = when (b.contact) {
                    true -> " · контакт с кожей есть"
                    false -> " · НЕТ контакта с кожей!"
                    null -> ""
                }
                tvStatusDetail.text = "${b.deviceName} ($contactStr)\n${b.detail}"
            }
            Phase.NO_DATA, Phase.WAITING -> {
                tvStatusPhase.text = "Нет данных / пауза"
                tvStatusPhase.setTextColor(Color.parseColor("#FFCA28"))
                tvBpmLarge.visibility = View.GONE
                tvStatusDetail.text = b.detail
            }
        }

        tvSendDetail.text = "Сервер: ${s.detail.ifEmpty { "ожидание отправки" }}"

        renderDevicesList(b.seen)
    }

    private fun renderDevicesList(list: List<HrState.SeenDevice>) {
        devicesContainer.removeAllViews()
        if (list.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "Пока нет найденных устройств в эфире."
                setTextColor(Color.parseColor("#546E7A"))
                textSize = 13f
                setPadding(0, 8, 0, 8)
            }
            devicesContainer.addView(emptyTv)
            return
        }

        for (d in list) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Color.parseColor("#171C24"))
                setPadding(24, 20, 24, 20)
                gravity = Gravity.CENTER_VERTICAL
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 10) }
                layoutParams = lp

                val infoLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val title = TextView(context).apply {
                    val icon = if (d.hasHrs) "♥ " else if (d.systemConnected) "🔗 " else "• "
                    text = "$icon${d.name.ifEmpty { "Безымянное устройство" }}"
                    setTextColor(if (d.hasHrs) Color.parseColor("#FF5A6E") else Color.WHITE)
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                }
                val subtitle = TextView(context).apply {
                    val hrsNote = if (d.hasHrs) " [HRS 0x180D]" else ""
                    val connNote = if (d.systemConnected) " [подключено к системе]" else ""
                    text = "${d.address} | RSSI: ${d.rssi} dBm$hrsNote$connNote"
                    setTextColor(Color.parseColor("#78909C"))
                    textSize = 11.5f
                }
                infoLayout.addView(title)
                infoLayout.addView(subtitle)
                addView(infoLayout)

                val isCurrent = prefs.preferredAddress.equals(d.address, ignoreCase = true)
                val actionBtn = Button(context).apply {
                    text = if (isCurrent) "Забыть" else "Выбрать"
                    textSize = 11.5f
                    setBackgroundColor(
                        if (isCurrent) Color.parseColor("#37474F") else Color.parseColor("#263238")
                    )
                    setTextColor(if (isCurrent) Color.parseColor("#FF8A80") else Color.parseColor("#80D8FF"))
                    setOnClickListener {
                        if (isCurrent) {
                            HrForegroundService.send(this@MainActivity, HrForegroundService.ACTION_FORGET_DEVICE)
                        } else {
                            HrForegroundService.send(this@MainActivity, HrForegroundService.ACTION_USE_DEVICE) { i ->
                                i.putExtra(HrForegroundService.EXTRA_ADDRESS, d.address)
                                i.putExtra(HrForegroundService.EXTRA_NAME, d.name)
                            }
                        }
                    }
                }
                addView(actionBtn)
            }
            devicesContainer.addView(item)
        }
    }

    private fun norm(s: String): String =
        s.replace(Regex("[\\u200B-\\u200F\\uFEFF\\u00A0]"), "").trim()

    private fun saveConfig(): Boolean {
        var url = norm(serverInput.text.toString())
        var dev = norm(deviceInput.text.toString())
        var key = norm(keyInput.text.toString())

        if (!url.startsWith("http") && dev.startsWith("http")) {
            val t = url; url = dev; dev = t
            serverInput.setText(url); deviceInput.setText(dev)
        }
        if (!url.startsWith("http") && key.startsWith("http")) {
            val t = url; url = key; key = t
            serverInput.setText(url); keyInput.setText(key)
        }
        if (url.isNotEmpty() && !url.startsWith("http")) url = "https://$url"

        if (url.isEmpty() || !url.startsWith("https://")) {
            tvStatusDetail.text = "Ошибка: Адрес сервера должен начинаться с https://"
            return false
        }
        if (dev.isEmpty()) {
            tvStatusDetail.text = "Ошибка: Имя устройства пустое"
            return false
        }
        if (key.isEmpty()) {
            tvStatusDetail.text = "Ошибка: Ключ пустой"
            return false
        }

        prefs.serverUrl = url
        prefs.deviceId = dev
        prefs.deviceKey = key
        prefs.operatorName = norm(operatorInput.text.toString()).ifEmpty { DEFAULT_OPERATOR }
        tvStatusDetail.text = "Настройки сохранены."
        return true
    }

    private fun toggleService() {
        if (HrState.serviceRunning.value) {
            HrForegroundService.send(this, HrForegroundService.ACTION_STOP)
        } else {
            if (!saveConfig()) return
            if (!Perms.hasBle(this)) {
                checkAndRequestPermissions()
                return
            }
            val err = HrForegroundService.send(this, HrForegroundService.ACTION_START)
            if (err != null) {
                tvStatusDetail.text = err
            } else {
                tvStatusDetail.text = "Служба запущена."
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val missing = Perms.allRuntimePermissions().filter { !Perms.granted(this, it) }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}
