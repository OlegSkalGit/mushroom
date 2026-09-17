package com.olegskal.mushroom.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.view.ViewGroup
import android.widget.*
import com.olegskal.mushroom.db.DatabaseHelper
import com.olegskal.mushroom.model.MushroomMarker
import java.text.SimpleDateFormat
import java.util.*

object AddMarkerDialog {

    private val MUSHROOM_TYPES = arrayOf(
        "🍄 Білий гриб",
        "🍄 Підосиковик",
        "🍄 Підберезовик",
        "🍄 Лисичка",
        "🍄 Опеньки",
        "🍄 Маслюк",
        "🍄 Рижик",
        "🚗 Автомобіль / База",
        "📍 Знайдене місце"
    )

    fun show(
        activity: Activity,
        dbHelper: DatabaseHelper,
        lat: Double,
        lon: Double,
        altitude: Double = 0.0,
        onMarkerAdded: (MushroomMarker) -> Unit
    ) {
        val dialog = Dialog(activity)
        dialog.setTitle("Нова мітка")

        val container = UiUtils.createDarkDialogContainer(activity)

        val titleTv = TextView(activity).apply {
            text = "🍄 Поставити мітку"
            setTextColor(Color.parseColor("#4CAF50"))
            textSize = 18f
            setPadding(0, 0, 0, 16)
        }
        container.addView(titleTv)

        val coordTv = TextView(activity).apply {
            text = String.format(Locale.US, "Координати: %.5f, %.5f", lat, lon)
            setTextColor(Color.LTGRAY)
            textSize = 12f
            setPadding(0, 0, 0, 16)
        }
        container.addView(coordTv)

        val spinnerLabel = TextView(activity).apply {
            text = "Тип мітки:"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, 0, 0, 8)
        }
        container.addView(spinnerLabel)

        val spinner = Spinner(activity).apply {
            val adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, MUSHROOM_TYPES)
            setAdapter(adapter)
            setBackgroundColor(Color.parseColor("#2A2A2A"))
        }
        container.addView(spinner)

        val nameLabel = TextView(activity).apply {
            text = "Назва мітки:"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, 16, 0, 8)
        }
        container.addView(nameLabel)

        val sdf = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
        val defaultName = "Білий гриб (${sdf.format(Date())})"

        val nameInput = EditText(activity).apply {
            setText(defaultName)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#333333"))
            setPadding(16, 16, 16, 16)
        }
        container.addView(nameInput)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val chosen = MUSHROOM_TYPES[position].replace("🍄 ", "").replace("🚗 ", "").replace("📍 ", "")
                nameInput.setText("$chosen (${sdf.format(Date())})")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val noteLabel = TextView(activity).apply {
            text = "Примітка (необов'язково):"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, 16, 0, 8)
        }
        container.addView(noteLabel)

        val noteInput = EditText(activity).apply {
            hint = "Опис місця, орієнтири..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#333333"))
            setPadding(16, 16, 16, 16)
        }
        container.addView(noteInput)

        val btnSave = UiUtils.createStyledButton(activity, "Зберегти мітку") {
            val name = nameInput.text.toString().trim().ifEmpty { "Мітка" }
            val type = spinner.selectedItem?.toString() ?: "Гриб"
            val note = noteInput.text.toString().trim()

            val color = when {
                type.contains("Автомобіль") -> 0xFF2196F3.toInt() // Blue
                type.contains("Лисичка") -> 0xFFFF9800.toInt() // Orange
                type.contains("Опеньки") -> 0xFFFFC107.toInt() // Amber
                type.contains("Підосиковик") -> 0xFFE91E63.toInt() // Reddish
                type.contains("Білий") -> 0xFF4CAF50.toInt() // Green
                else -> 0xFF9C27B0.toInt()
            }

            val marker = MushroomMarker(
                id = System.currentTimeMillis(),
                name = name,
                type = type,
                lat = lat,
                lon = lon,
                altitude = altitude,
                timestamp = System.currentTimeMillis(),
                note = note,
                color = color,
                isVisible = true
            )

            dbHelper.insertMarker(marker)
            onMarkerAdded(marker)
            Toast.makeText(activity, "Мітку \"$name\" збережено!", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        val btnCancel = UiUtils.createStyledButton(activity, "Скасувати") {
            dialog.dismiss()
        }

        container.addView(UiUtils.createDialogDivider(activity))
        container.addView(btnSave)
        container.addView(btnCancel)

        dialog.setContentView(container)
        dialog.show()
    }
}
