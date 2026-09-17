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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AddMarkerDialog {

    private val COLORS = intArrayOf(
        0xFFFF9800.toInt(), // Помаранчевий
        0xFFF44336.toInt(), // Червоний
        0xFFFFEB3B.toInt(), // Жовтий
        0xFF4CAF50.toInt(), // Зелений
        0xFF00E5FF.toInt(), // Блакитний
        0xFFE040FB.toInt()  // Фіолетовий
    )

    fun show(
        activity: Activity,
        dbHelper: DatabaseHelper,
        lat: Double,
        lon: Double,
        altitude: Double = 0.0,
        initialName: String? = null,
        initialType: String? = null,
        onMarkerAdded: (MushroomMarker) -> Unit
    ) {
        val sdf = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
        val defaultName = initialName ?: "Мітка (${sdf.format(Date())})"

        showDialog(
            activity = activity,
            title = "🍄 Нова мітка",
            initialName = defaultName,
            initialColor = 0xFF4CAF50.toInt()
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

    fun showEdit(
        activity: Activity,
        dbHelper: DatabaseHelper,
        marker: MushroomMarker,
        onMarkerUpdated: (MushroomMarker) -> Unit
    ) {
        showDialog(
            activity = activity,
            title = "✏️ Редагування мітки",
            initialName = marker.name,
            initialColor = marker.color
        ) { name, color ->
            dbHelper.updateMarker(marker.id, name, color)
            marker.name = name
            marker.color = color
            onMarkerUpdated(marker)
            Toast.makeText(activity, "Мітку \"$name\" оновлено!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDialog(
        activity: Activity,
        title: String,
        initialName: String,
        initialColor: Int,
        onSave: (name: String, color: Int) -> Unit
    ) {
        val dialog = Dialog(activity)
        val container = UiUtils.createDarkDialogContainer(activity)

        val titleTv = TextView(activity).apply {
            text = title
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

        val colorTitle = TextView(activity).apply {
            text = "Колір мітки:"
            setTextColor(Color.LTGRAY)
            textSize = 13f
            setPadding(0, 16, 0, 8)
        }
        container.addView(colorTitle)

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

        val btnSave = UiUtils.createStyledButton(activity, "Зберегти") {
            val name = nameInput.text.toString().trim().ifEmpty { "Мітка" }
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
