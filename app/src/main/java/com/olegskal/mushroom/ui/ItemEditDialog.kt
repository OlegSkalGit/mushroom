package com.olegskal.mushroom.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.olegskal.mushroom.db.DatabaseHelper
import com.olegskal.mushroom.model.MushroomMarker
import com.olegskal.mushroom.model.MushroomTrack
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ItemEditDialog {

    private val COLORS = intArrayOf(
        0xFFFF9800.toInt(), // Помаранчевий
        0xFFF44336.toInt(), // Червоний
        0xFFFFEB3B.toInt(), // Жовтий
        0xFF4CAF50.toInt(), // Зелений
        0xFF00E5FF.toInt(), // Блакитний
        0xFFE040FB.toInt()  // Фіолетовий
    )

    private fun formatDate(): String {
        return SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date())
    }

    // 1. Створення нової мітки
    fun showAddMarker(
        activity: Activity,
        dbHelper: DatabaseHelper,
        lat: Double,
        lon: Double,
        altitude: Double = 0.0,
        initialName: String? = null,
        initialType: String? = null,
        onMarkerAdded: (MushroomMarker) -> Unit
    ) {
        val defaultName = initialName ?: "Мітка (${formatDate()})"
        showDialog(
            activity = activity,
            dialogTitle = "🍄 Нова мітка",
            initialName = defaultName,
            colorTitle = "Колір мітки:",
            initialColor = 0xFF4CAF50.toInt(),
            saveButtonText = "Зберегти"
        ) { name, color ->
            val marker = MushroomMarker(
                id = System.currentTimeMillis(),
                name = name,
                type = initialType ?: "Гриб",
                lat = lat,
                lon = lon,
                altitude = altitude,
                timestamp = System.currentTimeMillis(),
                note = "",
                color = color,
                isVisible = true
            )
            dbHelper.insertMarker(marker)
            onMarkerAdded(marker)
            Toast.makeText(activity, "Мітку \"$name\" збережено!", Toast.LENGTH_SHORT).show()
        }
    }

    // 2. Редагування мітки
    fun showEditMarker(
        activity: Activity,
        dbHelper: DatabaseHelper,
        marker: MushroomMarker,
        onMarkerUpdated: (MushroomMarker) -> Unit
    ) {
        showDialog(
            activity = activity,
            dialogTitle = "✏️ Редагування мітки",
            initialName = marker.name,
            colorTitle = "Колір мітки:",
            initialColor = marker.color,
            saveButtonText = "Зберегти"
        ) { name, color ->
            dbHelper.updateMarker(marker.id, name, color)
            marker.name = name
            marker.color = color
            onMarkerUpdated(marker)
            Toast.makeText(activity, "Мітку \"$name\" оновлено!", Toast.LENGTH_SHORT).show()
        }
    }

    // 3. Запуск запису нового треку
    fun showCreateTrack(
        activity: Activity,
        onStartRecording: (title: String, color: Int) -> Unit
    ) {
        val defaultTitle = "Трек (${formatDate()})"
        showDialog(
            activity = activity,
            dialogTitle = "🧭 Новий трек",
            initialName = defaultTitle,
            colorTitle = "Колір треку:",
            initialColor = 0xFF4CAF50.toInt(),
            saveButtonText = "Записати"
        ) { title, color ->
            onStartRecording(title, color)
        }
    }

    // 4. Редагування треку
    fun showEditTrack(
        activity: Activity,
        dbHelper: DatabaseHelper,
        track: MushroomTrack,
        onTrackUpdated: (MushroomTrack) -> Unit
    ) {
        showDialog(
            activity = activity,
            dialogTitle = "✏️ Редагування треку",
            initialName = track.title,
            colorTitle = "Колір треку:",
            initialColor = track.color,
            saveButtonText = "Зберегти"
        ) { title, color ->
            dbHelper.updateTrackInfo(track.id, title, color)
            track.title = title
            track.color = color
            onTrackUpdated(track)
            Toast.makeText(activity, "Трек \"$title\" оновлено!", Toast.LENGTH_SHORT).show()
        }
    }

    // Єдине уніфіковане діалогове вікно
    private fun showDialog(
        activity: Activity,
        dialogTitle: String,
        initialName: String,
        colorTitle: String,
        initialColor: Int,
        saveButtonText: String,
        onSave: (name: String, color: Int) -> Unit
    ) {
        val dialog = Dialog(activity)
        val container = UiUtils.createDarkDialogContainer(activity)

        val titleTv = TextView(activity).apply {
            text = dialogTitle
            setTextColor(Color.parseColor("#4CAF50"))
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 16)
        }
        container.addView(titleTv)

        val nameInput = EditText(activity).apply {
            setText(initialName)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#333333"))
            setPadding(20, 20, 20, 20)
            textSize = 15f
            selectAll()
        }
        container.addView(nameInput)

        val colorTitleTv = TextView(activity).apply {
            text = colorTitle
            setTextColor(Color.LTGRAY)
            textSize = 13f
            setPadding(0, 16, 0, 8)
        }
        container.addView(colorTitleTv)

        var selectedColor = initialColor
        val colorRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 8)
        }

        val colorViews = ArrayList<TextView>()
        fun updateColorSelection() {
            for ((idx, tv) in colorViews.withIndex()) {
                val c = COLORS[idx]
                tv.text = if (c == selectedColor) "✓" else ""
            }
        }

        val density = activity.resources.displayMetrics.density
        val sz = (36 * density).toInt()
        val m = (4 * density).toInt()

        for (c in COLORS) {
            val tv = TextView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                    setMargins(m, 0, m, 0)
                }
                setBackgroundColor(c)
                setTextColor(if (c == 0xFFFFEB3B.toInt()) Color.BLACK else Color.WHITE)
                textSize = 18f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setOnClickListener {
                    selectedColor = c
                    updateColorSelection()
                }
            }
            colorViews.add(tv)
            colorRow.addView(tv)
        }
        updateColorSelection()
        container.addView(colorRow)

        container.addView(UiUtils.createDialogDivider(activity))

        val buttonRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 0)
        }

        val rowParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(8, 0, 8, 0)
        }

        val btnSave = UiUtils.createStyledButton(activity, saveButtonText) {
            val defaultFallback = if (colorTitle.contains("трек", ignoreCase = true)) "Трек" else "Мітка"
            val name = nameInput.text.toString().trim().ifEmpty { defaultFallback }
            onSave(name, selectedColor)
            dialog.dismiss()
        }
        btnSave.layoutParams = rowParams

        val btnCancel = UiUtils.createStyledButton(activity, "Скасувати") {
            dialog.dismiss()
        }
        btnCancel.layoutParams = rowParams

        buttonRow.addView(btnSave)
        buttonRow.addView(btnCancel)
        container.addView(buttonRow)

        dialog.setContentView(container)
        dialog.show()
    }
}
