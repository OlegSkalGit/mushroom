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
        initialName: String? = null,
        initialType: String? = null,
        onMarkerAdded: (MushroomMarker) -> Unit
    ) {
        val dialog = Dialog(activity)
        dialog.setTitle("Нова мітка")

        var currentLat = lat
        var currentLon = lon

        val container = UiUtils.createDarkDialogContainer(activity)

        val titleTv = TextView(activity).apply {
            text = "🍄 Поставити мітку"
            setTextColor(Color.parseColor("#4CAF50"))
            textSize = 18f
            setPadding(0, 0, 0, 16)
        }
        container.addView(titleTv)

        val coordRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
        }

        val coordTv = TextView(activity).apply {
            text = String.format(Locale.US, "Координати: %.5f, %.5f", currentLat, currentLon)
            setTextColor(Color.LTGRAY)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnPasteCoord = Button(activity).apply {
            text = "📋 З буфера"
            setTextColor(Color.parseColor("#00E5FF"))
            setBackgroundColor(Color.TRANSPARENT)
            textSize = 12f
            setOnClickListener {
                val clip = com.olegskal.mushroom.util.GeoDataExchange.getClipboardText(activity)
                val parsed = com.olegskal.mushroom.util.GeoDataExchange.parseCoordinates(clip)
                if (parsed != null) {
                    currentLat = parsed.first
                    currentLon = parsed.second
                    coordTv.text = String.format(Locale.US, "Координати: %.5f, %.5f", currentLat, currentLon)
                    Toast.makeText(activity, "Вставлено: ${String.format(Locale.US, "%.5f, %.5f", currentLat, currentLon)}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(activity, "У буфері не знайдено координат", Toast.LENGTH_SHORT).show()
                }
            }
        }

        coordRow.addView(coordTv)
        coordRow.addView(btnPasteCoord)
        container.addView(coordRow)

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
            if (initialType != null) {
                val idx = MUSHROOM_TYPES.indexOfFirst { it.contains(initialType) }
                if (idx >= 0) setSelection(idx)
            }
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
        val defaultName = initialName ?: "Білий гриб (${sdf.format(Date())})"

        val nameInput = EditText(activity).apply {
            setText(defaultName)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#333333"))
            setPadding(16, 16, 16, 16)
        }
        container.addView(nameInput)

        var userEditedName = initialName != null
        nameInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) userEditedName = true
        }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (!userEditedName) {
                    val chosen = MUSHROOM_TYPES[position].replace("🍄 ", "").replace("🚗 ", "").replace("📍 ", "")
                    nameInput.setText("$chosen (${sdf.format(Date())})")
                }
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
                lat = currentLat,
                lon = currentLon,
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
