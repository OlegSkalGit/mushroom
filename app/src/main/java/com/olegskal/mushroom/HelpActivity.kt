package com.olegskal.mushroom

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.olegskal.mushroom.ui.UiUtils
import com.olegskal.mushroom.util.getAppVersionName

class HelpActivity : Activity() {

    private lateinit var textViewHelp: TextView
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var currentTextSizeSp = 15f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        title = "Довідка Mushroom"

        val appVersionName = getAppVersionName()

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(24, 24, 24, 24)
        }

        val headerLayout = UiUtils.createHeaderLayout(this, "🌲 Довідка Mushroom")
        rootLayout.addView(headerLayout)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val helpContentText = """
Mushroom (v$appVersionName) — Навігатор для грибника

Надійна нативна програма для лісової навігації, фіксації грибних місць, запису треків та роботи з офлайн OSM картами.

Основні можливості:

• Робота у фоні та енергоефективність:
  - Фонова служба записує маршрут навіть із вимкненим екраном у кишені.
  - Адаптивне енергозбереження: при зупинках огляд GPS оптимізується, утримуючи заряд акумулятора на тривалий похід.

• Офлайн OSM карти:
  - 100% нативний швидкісний рушій без сторонніх бібліотек.
  - Завантаження карт: виберіть країну, потім відмітьте чекбоксами потрібні регіони/області для завантаження в офлайн.
  - Кеш зберігається на диску для повної автономності в лісі без інтернету.

• Мітки грибних місць:
  - Кнопка "+Мітка" дозволяє зберегти точку з типом гриба (Білий, Лисичка, Опеньки тощо).
  - Список міток показує точну відстань і компасний напрямок від вашого положення.
  - Чекбокси дозволяють вмикати або вимикати показ потрібних міток на карті.
  - Клік по мітці миттєво переміщує карту до неї.

• Запис та список треків:
  - Кнопка "REC" запускає запис пройденого шляху, щоб ви завжди могли повернутися до виходу з лісу чи автомобіля.
  - Список треків містить детальну статистику (кілометраж, час, точки) з можливістю центровки на карті.
  - Треки автоматично експортуються у стандартний формат GPX.

• Надійне сховище /sdcard/mushroom:
  - Налаштування, мітки, треки та карти зберігаються у загальнодоступній папці сховища.
  - Всі ваші дані залишаються цілими навіть у разі повного видалення або перевстановлення програми.

• Компас та навігація:
  - Поточне місцеположення по центру екрана з відображенням вектора ходьби.
  - Апаратний компас (стрілка на Північ) із можливістю перемикання режиму "Північ зверху" / "За курсом".

• Репозиторій проекту та оновлення:
  https://github.com/OlegSkalGit/mushroom
        """.trimIndent()

        textViewHelp = TextView(this).apply {
            text = helpContentText
            textSize = currentTextSizeSp
            setTextColor(Color.parseColor("#E0E0E0"))
            setLinkTextColor(Color.parseColor("#00E5FF"))
            setLineSpacing(8f, 1.2f)
            setPadding(16, 16, 16, 16)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            autoLinkMask = android.text.util.Linkify.WEB_URLS
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
            linksClickable = true
        }

        scaleGestureDetector = UiUtils.setupTextPinchZoom(this, textViewHelp, currentTextSizeSp, 10f, 36f) { newSp ->
            currentTextSizeSp = newSp
        }

        scrollView.addView(textViewHelp)
        rootLayout.addView(scrollView)

        setContentView(rootLayout)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }
}
