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

        val container = UiUtils.createDarkDialogContainer(activity)

        val titleTv = TextView(activity).apply {
            text = "🍄 Нова мітка"
            setTextColor(Color.parseColor("#4CAF50"))
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 16)
        }
        container.addView(titleTv)

        val sdf = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
        val defaultName = initialName ?: "Мітка (${sdf.format(Date())})"

        val nameInput = EditText(activity).apply {
            setText(defaultName)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#333333"))
            setPadding(20, 20, 20, 20)
            textSize = 15f
            selectAll()
        }
        container.addView(nameInput)

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
            val marker = MushroomMarker(
                id = System.currentTimeMillis(),
                name = name,
                type = initialType ?: "Гриб",
                lat = lat,
                lon = lon,
                altitude = altitude,
                timestamp = System.currentTimeMillis(),
                note = "",
                color = 0xFF4CAF50.toInt(),
                isVisible = true
            )
            dbHelper.insertMarker(marker)
            onMarkerAdded(marker)
            Toast.makeText(activity, "Мітку \"$name\" збережено!", Toast.LENGTH_SHORT).show()
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
