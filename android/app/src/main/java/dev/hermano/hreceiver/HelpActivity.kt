package dev.hermano.hreceiver

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Экран подробной инструкции:
 * 1. Garmin (включение трансляции пульса, меню настроек)
 * 2. WHOOP (режим Broadcast HR)
 * 3. Особенности Xiaomi / HyperOS (фоновая работа, энергосбережение, разрешения)
 */
class HelpActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E1116"))
            setPadding(48, 48, 48, 64)
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(48, sys.top + 32, 48, sys.bottom + 48)
            insets
        }

        fun title(text: String) = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 24)
        }

        fun h2(text: String) = TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#FF5A6E"))
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 36, 0, 12)
        }

        fun body(text: String) = TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#CFD8DC"))
            textSize = 14f
            setLineSpacing(8f, 1f)
            setPadding(0, 0, 0, 16)
        }

        fun card(titleText: String, contentText: String) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#171C24"))
            setPadding(32, 28, 32, 28)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 8, 0, 16) }
            layoutParams = params

            val t = TextView(context).apply {
                text = titleText
                setTextColor(Color.WHITE)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, 8)
            }
            val c = TextView(context).apply {
                text = contentText
                setTextColor(Color.parseColor("#B0BEC5"))
                textSize = 13.5f
                setLineSpacing(6f, 1f)
            }
            addView(t)
            addView(c)
        }

        root.addView(title("Как включить трансляцию пульса"))

        root.addView(h2("1. Garmin fenix 6X (и другие Garmin)"))
        root.addView(body("Часы Garmin транслируют BLE стандарт 0x180D ТОЛЬКО в специальном режиме. Обычный экран часов или сопряжение с Garmin Connect НЕ отдают пульс сторонним приложениям."))

        root.addView(card(
            "Способ А: Быстрое меню элементов управления (рекомендуется)",
            "1. Зажми кнопку LIGHT (верхняя левая), пока не появится круговое меню.\n" +
            "2. Выбери значок сердца с радиоволнами «Трансляция пульса» (Broadcast Heart Rate).\n" +
            "3. Нажми START. На экране появится текущий пульс и кнопка «СТОП».\n" +
            "4. Внимание: если значка нет в меню — зажми MENU -> «Элементы управления» -> «Добавить» -> «Трансляция пульса»."
        ))

        root.addView(card(
            "Способ Б: Настройки датчика пульса",
            "1. Зажми среднюю левую кнопку (MENU/UP).\n" +
            "2. Перейди в: «Датчики и аксессуары» -> «Пульсометр на запястье».\n" +
            "3. Выбери «Трансляция ЧП» -> нажми START для запуска трансляции."
        ))

        root.addView(card(
            "Способ В: Трансляция во время тренировки (24/7)",
            "Если нужно мониторить пульс долго или ночью:\n" +
            "В меню «Пульсометр на запястье» включи пункт «Трансляция во время занятия» (Broadcast During Activity) = ВКЛ. " +
            "Тогда при запуске любого профиля активности (например, «Другое» / «Йога») часы будут непрерывно отдавать BLE пульс."
        ))

        root.addView(h2("2. Браслет WHOOP (4.0 / 5.0)"))
        root.addView(card(
            "Включение передачи по Bluetooth",
            "1. Открой официальное приложение WHOOP на телефоне.\n" +
            "2. Нажми значок статуса устройства (в верхнем правом углу или в More -> Device Settings).\n" +
            "3. Найди пункт «Broadcast Heart Rate» (Трансляция ЧСС).\n" +
            "4. Переведи переключатель в положение ВКЛ.\n" +
            "5. Браслет начнёт рекламировать стандартный Heart Rate Service (0x180D)."
        ))

        root.addView(h2("3. Особенности Xiaomi / HyperOS / MIUI"))
        root.addView(body("HyperOS агрессивно глушит фоновые службы и Bluetooth-сканирование для экономии батареи. Выполни эти 3 настройки:"))

        root.addView(card(
            "1. Контроль активности: «Нет ограничений»",
            "Настройки -> Приложения -> Все приложения -> HR Receiver -> «Контроль активности» (Battery saver) -> установи «Нет ограничений»."
        ))

        root.addView(card(
            "2. Автозапуск в фоне",
            "В карточке приложения HR Receiver включи переключатель «Автозапуск» (Autostart) и «Разрешить запуск в фоновом режиме»."
        ))

        root.addView(card(
            "3. Закрепление в недавних",
            "Открой список запущенных приложений (свайп снизу с удержанием), зажми карточку HR Receiver и нажми на значок «Замочек»."
        ))

        val btnSysSettings = Button(this).apply {
            text = "Открыть настройки приложения в системе"
            setBackgroundColor(Color.parseColor("#263238"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                    startActivity(intent)
                } catch (_: Exception) {}
            }
        }
        root.addView(btnSysSettings)

        val btnBack = Button(this).apply {
            text = "Вернуться назад"
            setBackgroundColor(Color.parseColor("#FF5A6E"))
            setTextColor(Color.WHITE)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 24, 0, 0) }
            layoutParams = lp
            setOnClickListener { finish() }
        }
        root.addView(btnBack)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0E1116"))
            addView(root)
        })
    }
}
