package com.olegskal.mushroom.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.olegskal.mushroom.db.DatabaseHelper
import com.olegskal.mushroom.math.GeoMath
import com.olegskal.mushroom.model.MushroomMarker
import com.olegskal.mushroom.util.L10n
import java.util.Locale

object MarkersListDialog {

    fun show(
        activity: Activity,
        dbHelper: DatabaseHelper,
        currentLat: Double?,
        currentLon: Double?,
        onSelectMarker: (MushroomMarker) -> Unit,
        onVisibilityChanged: () -> Unit
    ) {
        val dialog = Dialog(activity)
        dialog.setTitle("Markers List")

        val container = UiUtils.createDarkDialogContainer(activity)

        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
        }

        val titleTv = TextView(activity).apply {
            text = L10n.t(activity, "🍄 Збережені мітки", "🍄 Saved Markers")
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnClose = Button(activity).apply {
            text = "✕"
            setTextColor(Color.LTGRAY)
            setBackgroundColor(Color.TRANSPARENT)
            textSize = 18f
            setOnClickListener { dialog.dismiss() }
        }

        headerRow.addView(titleTv)
        headerRow.addView(btnClose)
        container.addView(headerRow)

        val btnAddMarker = Button(activity).apply {
            text = L10n.t(activity, "➕ Створити нову мітку", "➕ Create New Marker")
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2E7D32"))
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            val addParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 8)
            }
            layoutParams = addParams
            setOnClickListener {
                dialog.dismiss()
                ItemEditDialog.showAddMarker(
                    activity,
                    dbHelper,
                    currentLat ?: 50.4501,
                    currentLon ?: 30.5234,
                    0.0
                ) {
                    onVisibilityChanged()
                }
            }
        }
        container.addView(btnAddMarker)

        val btnPasteClip = UiUtils.createStyledButton(activity, L10n.t(activity, "📋 Вставити координати з буфера", "📋 Paste Coordinates from Clipboard")) {
            val clipText = com.olegskal.mushroom.util.GeoDataExchange.getClipboardText(activity)
            val coords = com.olegskal.mushroom.util.GeoDataExchange.parseCoordinates(clipText)
            if (coords != null) {
                dialog.dismiss()
                ItemEditDialog.showAddMarker(
                    activity,
                    dbHelper,
                    coords.first,
                    coords.second,
                    initialName = L10n.t(activity, "Отримана мітка", "Received Marker"),
                    initialType = L10n.t(activity, "📍 Знайдена локація", "📍 Found Location")
                ) {
                    onVisibilityChanged()
                }
            } else {
                Toast.makeText(activity, L10n.t(activity, "Координати не знайдено у буфері\n(формат: 50.4501, 30.5234 або посилання)", "No coordinates found in clipboard\n(format: 50.4501, 30.5234 or link)"), Toast.LENGTH_LONG).show()
            }
        }
        val pasteParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, 12)
        }
        btnPasteClip.layoutParams = pasteParams
        container.addView(btnPasteClip)

        val scrollView = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val listContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun populateMarkers() {
            listContainer.removeAllViews()
            val markers = dbHelper.getAllMarkers()

            if (markers.isEmpty()) {
                val emptyTv = TextView(activity).apply {
                    text = L10n.t(activity, "Немає збережених міток.\nНатисніть \"➕ Створити нову мітку\".", "No saved markers.\nTap \"➕ Create New Marker\".")
                    setTextColor(Color.GRAY)
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setPadding(0, 48, 0, 48)
                }
                listContainer.addView(emptyTv)
                return
            }

            for (marker in markers) {
                val itemRow = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundColor(Color.parseColor("#222222"))
                    setPadding(12, 8, 12, 8)
                    val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    params.setMargins(0, 0, 0, 8)
                    layoutParams = params
                }

                val chkVisible = CheckBox(activity).apply {
                    isChecked = marker.isVisible
                    setOnCheckedChangeListener { _, isChecked ->
                        marker.isVisible = isChecked
                        dbHelper.setMarkerVisibility(marker.id, isChecked)
                        onVisibilityChanged()
                    }
                }

                val infoCol = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        dialog.dismiss()
                        onSelectMarker(marker)
                    }
                }

                val nameTv = TextView(activity).apply {
                    text = marker.name
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    setTypeface(null, Typeface.BOLD)
                }

                var distStr = ""
                if (currentLat != null && currentLon != null && (currentLat != 0.0 || currentLon != 0.0)) {
                    val geo = GeoMath.calculateDistanceAndBearing(currentLat, currentLon, marker.lat, marker.lon)
                    val distM = geo[0]
                    val bearingDeg = geo[1]
                    val direction = L10n.bearingDirection(activity, bearingDeg)
                    distStr = if (distM >= 1000f) {
                        val unit = L10n.t(activity, "км", "km")
                        String.format(Locale.US, "%.1f %s (%s)", distM / 1000f, unit, direction)
                    } else {
                        val unit = L10n.t(activity, "м", "m")
                        String.format(Locale.US, "%d %s (%s)", distM.toInt(), unit, direction)
                    }
                }

                val subTv = TextView(activity).apply {
                    text = if (distStr.isNotEmpty()) "${marker.type} • $distStr" else marker.type
                    setTextColor(marker.color)
                    textSize = 12f
                }

                infoCol.addView(nameTv)
                infoCol.addView(subTv)

                val btnRename = Button(activity).apply {
                    text = "✏️"
                    setTextColor(Color.parseColor("#FFD54F"))
                    setBackgroundColor(Color.TRANSPARENT)
                    textSize = 15f
                    setPadding(8, 0, 8, 0)
                    setOnClickListener {
                        ItemEditDialog.showEditMarker(activity, dbHelper, marker) {
                            onVisibilityChanged()
                            populateMarkers()
                        }
                    }
                }

                val btnShare = Button(activity).apply {
                    text = "📤"
                    setTextColor(Color.parseColor("#00E5FF"))
                    setBackgroundColor(Color.TRANSPARENT)
                    textSize = 15f
                    setPadding(8, 0, 8, 0)
                    setOnClickListener {
                        com.olegskal.mushroom.util.GeoDataExchange.shareMarker(activity, marker)
                    }
                }

                val btnDelete = Button(activity).apply {
                    text = "🗑"
                    setTextColor(Color.parseColor("#FF5252"))
                    setBackgroundColor(Color.TRANSPARENT)
                    textSize = 15f
                    setPadding(8, 0, 8, 0)
                    setOnClickListener {
                        AlertDialog.Builder(activity)
                            .setTitle(L10n.t(activity, "Видалити мітку?", "Delete Marker?"))
                            .setMessage(L10n.t(activity, "Видалити \"${marker.name}\"?", "Delete \"${marker.name}\"?"))
                            .setPositiveButton(L10n.t(activity, "Видалити", "Delete")) { _, _ ->
                                dbHelper.deleteMarker(marker.id)
                                onVisibilityChanged()
                                populateMarkers()
                            }
                            .setNegativeButton(L10n.t(activity, "Скасувати", "Cancel"), null)
                            .show()
                    }
                }

                itemRow.addView(chkVisible)
                itemRow.addView(infoCol)
                itemRow.addView(btnRename)
                itemRow.addView(btnShare)
                itemRow.addView(btnDelete)

                listContainer.addView(itemRow)
            }
        }

        populateMarkers()
        scrollView.addView(listContainer)
        container.addView(scrollView)

        dialog.setContentView(container)
        dialog.show()
    }

    private fun getBearingDirection(bearing: Float): String {
        val b = if (bearing < 0) bearing + 360f else bearing
        return when {
            b in 22.5..67.5 -> "NE"
            b in 67.5..112.5 -> "E"
            b in 112.5..157.5 -> "SE"
            b in 157.5..202.5 -> "S"
            b in 202.5..247.5 -> "SW"
            b in 247.5..292.5 -> "W"
            b in 292.5..337.5 -> "NW"
            else -> "N"
        }
    }
}
