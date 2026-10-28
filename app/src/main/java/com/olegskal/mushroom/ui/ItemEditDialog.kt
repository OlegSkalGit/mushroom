package com.olegskal.mushroom.ui

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.olegskal.mushroom.address.AddressSearchManager
import com.olegskal.mushroom.db.DatabaseHelper
import com.olegskal.mushroom.model.MushroomMarker
import com.olegskal.mushroom.model.MushroomTrack
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ItemEditDialog {

    private val COLORS = intArrayOf(
        0xFFF44336.toInt(), // Red
        0xFF2196F3.toInt(), // Blue
        0xFF4CAF50.toInt(), // Green
        0xFFFF9800.toInt(), // Orange
        0xFFFFEB3B.toInt(), // Yellow
        0xFF00E5FF.toInt(), // Cyan
        0xFF9C27B0.toInt()  // Purple
    )

    private fun formatDate(): String {
        return SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date())
    }

    private data class MarkerContext(
        val dbHelper: DatabaseHelper,
        var lat: Double,
        var lon: Double,
        var altitude: Double = 0.0,
        var type: String,
        val originalMarker: MushroomMarker? = null,
        val onMarkerAdded: ((MushroomMarker) -> Unit)? = null,
        val onMarkerUpdated: ((MushroomMarker) -> Unit)? = null
    )

    // 1. Create new marker
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
        val lang = com.olegskal.mushroom.util.AppPrefs.getAppLang(activity)
        val defaultName = initialName ?: (if (lang == "uk") "Маркер (${formatDate()})" else "Marker (${formatDate()})")
        val density = activity.resources.displayMetrics.density
        val markerColor = 0xFFF44336.toInt()
        val markerCtx = MarkerContext(
            dbHelper = dbHelper,
            lat = lat,
            lon = lon,
            altitude = altitude,
            type = initialType ?: (if (lang == "uk") "Маркер" else "Marker"),
            originalMarker = null,
            onMarkerAdded = onMarkerAdded
        )
        showDialog(
            activity = activity,
            dialogTitle = if (lang == "uk") "Новий маркер" else "New Marker",
            initialName = defaultName,
            colorTitle = if (lang == "uk") "Колір маркера:" else "Marker color:",
            initialColor = markerColor,
            saveButtonText = if (lang == "uk") "Зберегти" else "Save",
            iconDrawable = MarkerIconDrawable(density, markerColor, 20),
            markerContext = markerCtx
        ) { _, _ -> }
    }

    // 2. Edit marker
    fun showEditMarker(
        activity: Activity,
        dbHelper: DatabaseHelper,
        marker: MushroomMarker,
        onMarkerUpdated: (MushroomMarker) -> Unit
    ) {
        val lang = com.olegskal.mushroom.util.AppPrefs.getAppLang(activity)
        val density = activity.resources.displayMetrics.density
        val markerCtx = MarkerContext(
            dbHelper = dbHelper,
            lat = marker.lat,
            lon = marker.lon,
            altitude = marker.altitude,
            type = marker.type,
            originalMarker = marker,
            onMarkerUpdated = onMarkerUpdated
        )
        showDialog(
            activity = activity,
            dialogTitle = if (lang == "uk") "Редагувати маркер" else "Edit Marker",
            initialName = marker.name,
            colorTitle = if (lang == "uk") "Колір маркера:" else "Marker color:",
            initialColor = marker.color,
            saveButtonText = if (lang == "uk") "Зберегти" else "Save",
            iconDrawable = MarkerIconDrawable(density, marker.color, 20),
            markerContext = markerCtx
        ) { _, _ -> }
    }

    // 3. Start recording new track
    fun showCreateTrack(
        activity: Activity,
        onStartRecording: (title: String, color: Int) -> Unit
    ) {
        val lang = com.olegskal.mushroom.util.AppPrefs.getAppLang(activity)
        val defaultTitle = if (lang == "uk") "Трек (${formatDate()})" else "Track (${formatDate()})"
        val density = activity.resources.displayMetrics.density
        val trackColor = 0xFF2196F3.toInt()
        showDialog(
            activity = activity,
            dialogTitle = if (lang == "uk") "Новий трек" else "New Track",
            initialName = defaultTitle,
            colorTitle = if (lang == "uk") "Колір треку:" else "Track color:",
            initialColor = trackColor,
            saveButtonText = if (lang == "uk") "Запис" else "Record",
            iconDrawable = TrackIconDrawable(density, trackColor, 20)
        ) { title, color ->
            onStartRecording(title, color)
        }
    }

    // 4. Edit track
    fun showEditTrack(
        activity: Activity,
        dbHelper: DatabaseHelper,
        track: MushroomTrack,
        onTrackUpdated: (MushroomTrack) -> Unit
    ) {
        val lang = com.olegskal.mushroom.util.AppPrefs.getAppLang(activity)
        val density = activity.resources.displayMetrics.density
        showDialog(
            activity = activity,
            dialogTitle = if (lang == "uk") "Редагувати трек" else "Edit Track",
            initialName = track.title,
            colorTitle = if (lang == "uk") "Колір треку:" else "Track color:",
            initialColor = track.color,
            saveButtonText = if (lang == "uk") "Зберегти" else "Save",
            iconDrawable = TrackIconDrawable(density, track.color, 20)
        ) { title, color ->
            dbHelper.updateTrackInfo(track.id, title, color)
            track.title = title
            track.color = color
            onTrackUpdated(track)
            val msg = if (lang == "uk") "Трек \"$title\" оновлено!" else "Track \"$title\" updated!"
            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // 5. Save track from ruler points
    fun showSaveTrackFromRuler(
        activity: Activity,
        dbHelper: DatabaseHelper,
        points: List<com.olegskal.mushroom.model.TrackPoint>,
        distanceMeters: Float,
        onTrackSaved: (MushroomTrack) -> Unit
    ) {
        val lang = com.olegskal.mushroom.util.AppPrefs.getAppLang(activity)
        val defaultTitle = if (lang == "uk") "Трек (${formatDate()})" else "Track (${formatDate()})"
        val density = activity.resources.displayMetrics.density
        val trackColor = 0xFF2196F3.toInt()
        showDialog(
            activity = activity,
            dialogTitle = if (lang == "uk") "Зберегти трек" else "Save Track",
            initialName = defaultTitle,
            colorTitle = if (lang == "uk") "Колір треку:" else "Track color:",
            initialColor = trackColor,
            saveButtonText = if (lang == "uk") "Зберегти" else "Save",
            iconDrawable = TrackIconDrawable(density, trackColor, 20)
        ) { title, color ->
            val now = System.currentTimeMillis()
            val track = MushroomTrack(
                id = now,
                title = title,
                startTime = now,
                endTime = now,
                distanceMeters = distanceMeters,
                durationSec = 0L,
                points = points.toMutableList(),
                color = color,
                isVisible = true
            )
            dbHelper.insertTrack(track)
            com.olegskal.mushroom.storage.MushroomStorageManager.saveTrackToGpx(track)
            onTrackSaved(track)
            val msg = if (lang == "uk") "Трек \"$title\" збережено!" else "Track \"$title\" saved!"
            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // Unified edit dialog
    private fun showDialog(
        activity: Activity,
        dialogTitle: String,
        initialName: String,
        colorTitle: String,
        initialColor: Int,
        saveButtonText: String,
        iconDrawable: android.graphics.drawable.Drawable? = null,
        markerContext: MarkerContext? = null,
        onSave: (name: String, color: Int) -> Unit
    ) {
        val dialog = Dialog(activity)
        val container = UiUtils.createDarkDialogContainer(activity)
        val isUk = com.olegskal.mushroom.util.AppPrefs.isUk(activity)
        val density = activity.resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        var isForceNewMarker = false

        // Header row: Title on left, optional Info button on right
        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }

        val titleTv = TextView(activity).apply {
            text = dialogTitle
            setTextColor(Color.parseColor("#4CAF50"))
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            if (iconDrawable != null) {
                iconDrawable.setBounds(0, 0, iconDrawable.intrinsicWidth, iconDrawable.intrinsicHeight)
                setCompoundDrawables(iconDrawable, null, null, null)
                compoundDrawablePadding = dp(8)
                gravity = Gravity.CENTER_VERTICAL
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(titleTv)

        val nameInput = EditText(activity).apply {
            setText(initialName)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#333333"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            textSize = 15f
            selectAll()
        }

        if (markerContext != null) {
            val btnInfo = TextView(activity).apply {
                text = "ℹ️"
                textSize = 20f
                setPadding(dp(8), 0, dp(4), 0)
                isClickable = true
                setOnClickListener {
                    showMarkerInfoDialog(
                        activity = activity,
                        lat = markerContext.lat,
                        lon = markerContext.lon,
                        currentMarkerName = nameInput.text.toString().trim().ifEmpty { initialName },
                        isUk = isUk
                    ) { selectedName, selectedType, selectedLat, selectedLon ->
                        nameInput.setText(selectedName)
                        markerContext.lat = selectedLat
                        markerContext.lon = selectedLon
                        markerContext.type = selectedType
                        isForceNewMarker = true
                        titleTv.text = if (isUk) "Новий маркер" else "New Marker"
                    }
                }
            }
            headerRow.addView(btnInfo)
        }

        container.addView(headerRow)
        container.addView(nameInput)

        val colorTitleTv = TextView(activity).apply {
            text = colorTitle
            setTextColor(Color.LTGRAY)
            textSize = 13f
            setPadding(0, dp(12), 0, dp(8))
        }
        container.addView(colorTitleTv)

        var selectedColor = initialColor
        val colorRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }

        val colorViews = ArrayList<TextView>()
        fun updateColorSelection() {
            for ((idx, tv) in colorViews.withIndex()) {
                val c = COLORS[idx]
                tv.text = if (c == selectedColor) "✓" else ""
            }
            if (iconDrawable is MarkerIconDrawable) {
                iconDrawable.color = selectedColor
                titleTv.invalidate()
            } else if (iconDrawable is TrackIconDrawable) {
                iconDrawable.color = selectedColor
                titleTv.invalidate()
            }
        }

        val sz = dp(36)
        val m = dp(4)

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
            setPadding(0, dp(8), 0, 0)
        }

        val rowParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(6), 0, dp(6), 0)
        }

        val btnSave = UiUtils.createStyledButton(activity, saveButtonText) {
            val defaultFallback = if (colorTitle.contains("track", ignoreCase = true)) "Track" else "Marker"
            val name = nameInput.text.toString().trim().ifEmpty { defaultFallback }

            if (markerContext != null) {
                val orig = markerContext.originalMarker
                if (isForceNewMarker || orig == null) {
                    val newMarker = MushroomMarker(
                        id = System.currentTimeMillis(),
                        name = name,
                        type = markerContext.type,
                        lat = markerContext.lat,
                        lon = markerContext.lon,
                        altitude = markerContext.altitude,
                        timestamp = System.currentTimeMillis(),
                        note = "",
                        color = selectedColor,
                        isVisible = true
                    )
                    markerContext.dbHelper.insertMarker(newMarker)
                    markerContext.onMarkerAdded?.invoke(newMarker) ?: markerContext.onMarkerUpdated?.invoke(newMarker)
                    val msg = if (isUk) "Маркер \"$name\" збережено!" else "Marker \"$name\" saved!"
                    Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                } else {
                    markerContext.dbHelper.updateMarker(orig.id, name, selectedColor)
                    val updatedMarker = MushroomMarker(
                        id = orig.id,
                        name = name,
                        type = markerContext.type,
                        lat = markerContext.lat,
                        lon = markerContext.lon,
                        altitude = markerContext.altitude,
                        timestamp = orig.timestamp,
                        note = orig.note,
                        color = selectedColor,
                        isVisible = orig.isVisible
                    )
                    markerContext.onMarkerUpdated?.invoke(updatedMarker)
                    val msg = if (isUk) "Маркер \"$name\" оновлено!" else "Marker \"$name\" updated!"
                    Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                }
            } else {
                onSave(name, selectedColor)
            }
            dialog.dismiss()
        }
        btnSave.layoutParams = rowParams

        val btnCancel = UiUtils.createStyledButton(activity, if (isUk) "Скасувати" else "Cancel") {
            dialog.dismiss()
        }
        btnCancel.layoutParams = rowParams

        buttonRow.addView(btnSave)
        buttonRow.addView(btnCancel)
        container.addView(buttonRow)

        dialog.setContentView(container)
        dialog.show()
    }

    fun showMarkerInfoDialog(
        activity: Activity,
        lat: Double,
        lon: Double,
        currentMarkerName: String,
        isUk: Boolean,
        onItemSelected: (name: String, type: String, lat: Double, lon: Double) -> Unit
    ) {
        val infoDialog = Dialog(activity)
        val density = activity.resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val root = UiUtils.createDarkDialogContainer(activity)

        // Header
        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }

        val titleTv = TextView(activity).apply {
            text = if (isUk) "ℹ️ Інформація про маркер" else "ℹ️ Marker Info"
            setTextColor(Color.WHITE)
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnClose = Button(activity).apply {
            text = "✕"
            setTextColor(Color.LTGRAY)
            setBackgroundColor(Color.TRANSPARENT)
            textSize = 18f
            setOnClickListener { infoDialog.dismiss() }
        }

        headerRow.addView(titleTv)
        headerRow.addView(btnClose)
        root.addView(headerRow)

        val scroll = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val contentCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(contentCol)
        root.addView(scroll)

        // Top info card
        val topCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1B261E"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            p.setMargins(0, 0, 0, dp(8))
            layoutParams = p
        }

        val nameTv = TextView(activity).apply {
            text = currentMarkerName
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
        }
        topCard.addView(nameTv)

        val coordStr = String.format(Locale.US, "%.6f, %.6f", lat, lon)
        val coordRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            isClickable = true
            setOnClickListener {
                val clip = ClipData.newPlainText("Coordinates", coordStr)
                (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                Toast.makeText(activity, if (isUk) "Координати скопійовано: $coordStr" else "Coordinates copied: $coordStr", Toast.LENGTH_SHORT).show()
            }
        }

        val coordTv = TextView(activity).apply {
            text = "📍 $coordStr 📋"
            setTextColor(Color.parseColor("#90CAF9"))
            textSize = 13f
        }
        coordRow.addView(coordTv)
        topCard.addView(coordRow)

        val spinner = ProgressBar(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
                setMargins(0, dp(6), 0, dp(6))
            }
        }
        topCard.addView(spinner)

        val adminTv = TextView(activity).apply {
            setTextColor(Color.parseColor("#E0E0E0"))
            textSize = 13f
            visibility = View.GONE
            setPadding(0, dp(2), 0, dp(2))
        }
        topCard.addView(adminTv)

        val settlementTv = TextView(activity).apply {
            setTextColor(Color.parseColor("#81C784"))
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            visibility = View.GONE
            setPadding(0, dp(2), 0, dp(2))
        }
        topCard.addView(settlementTv)

        val addressTv = TextView(activity).apply {
            setTextColor(Color.parseColor("#FFD54F"))
            textSize = 13f
            visibility = View.GONE
            setPadding(0, dp(2), 0, dp(2))
        }
        topCard.addView(addressTv)

        contentCol.addView(topCard)

        val listsContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        contentCol.addView(listsContainer)

        Thread {
            val info = AddressSearchManager.findMarkerLocationInfo(activity, lat, lon)
            activity.runOnUiThread {
                spinner.visibility = View.GONE
                if (info != null) {
                    val adminParts = listOf(info.regionName, info.districtName, info.communityName).filter { it.isNotBlank() }
                    if (adminParts.isNotEmpty()) {
                        adminTv.text = "🏛️ ${adminParts.joinToString(" • ")}"
                        adminTv.visibility = View.VISIBLE
                    }
                    if (info.settlementName.isNotBlank()) {
                        settlementTv.text = "🏙️ ${info.settlementName}"
                        settlementTv.visibility = View.VISIBLE
                        settlementTv.isClickable = true
                        settlementTv.setOnClickListener {
                            infoDialog.dismiss()
                            onItemSelected(
                                info.settlementName,
                                if (isUk) "🏙️ Населений пункт" else "🏙️ Settlement",
                                info.settlementLat ?: lat,
                                info.settlementLon ?: lon
                            )
                        }
                    }
                    if (info.nearestHouseAddress != null && info.nearestHouseDistanceMeters != null) {
                        val distStr = AddressSearchManager.formatDistance(info.nearestHouseDistanceMeters)
                        addressTv.text = "🏠 ${info.nearestHouseAddress} ($distStr)"
                        addressTv.visibility = View.VISIBLE
                    } else if (info.nearestStreetName != null && info.nearestStreetDistanceMeters != null && info.nearestStreetDistanceMeters < 50.0f) {
                        val distStr = AddressSearchManager.formatDistance(info.nearestStreetDistanceMeters)
                        addressTv.text = "🛣️ ${info.nearestStreetName} ($distStr)"
                        addressTv.visibility = View.VISIBLE
                    }

                    fun renderRow(
                        iconText: String,
                        titleText: String,
                        subtitleText: String,
                        distMeters: Float,
                        onClick: () -> Unit
                    ) {
                        val row = LinearLayout(activity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setBackgroundColor(Color.parseColor("#1B261E"))
                            val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                                setMargins(0, 0, 0, dp(6))
                            }
                            layoutParams = p
                            setPadding(dp(12), dp(8), dp(12), dp(8))
                            isClickable = true
                            isFocusable = true
                        }

                        val iconTv = TextView(activity).apply {
                            text = iconText
                            textSize = 18f
                            setPadding(0, 0, dp(10), 0)
                        }
                        row.addView(iconTv)

                        val tCol = LinearLayout(activity).apply {
                            orientation = LinearLayout.VERTICAL
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        }

                        val rTitle = TextView(activity).apply {
                            text = titleText
                            setTextColor(Color.WHITE)
                            textSize = 14f
                            setTypeface(null, Typeface.BOLD)
                            maxLines = 2
                            ellipsize = TextUtils.TruncateAt.END
                        }
                        tCol.addView(rTitle)

                        if (subtitleText.isNotBlank()) {
                            val rSub = TextView(activity).apply {
                                text = subtitleText
                                setTextColor(Color.parseColor("#9CA3AF"))
                                textSize = 11.5f
                                maxLines = 2
                                ellipsize = TextUtils.TruncateAt.END
                            }
                            tCol.addView(rSub)
                        }
                        row.addView(tCol)

                        val distStr = AddressSearchManager.formatDistance(distMeters)
                        val distTv = TextView(activity).apply {
                            text = distStr
                            setTextColor(Color.parseColor("#10B981"))
                            textSize = 12f
                            setTypeface(null, Typeface.BOLD)
                            setPadding(dp(8), 0, 0, 0)
                            gravity = Gravity.CENTER_VERTICAL or Gravity.END
                        }
                        row.addView(distTv)

                        row.setOnClickListener {
                            infoDialog.dismiss()
                            onClick()
                        }
                        listsContainer.addView(row)
                    }

                    // 0. Combined nearest items within 200 meters (up to 15 items)
                    class NearbyItem(
                        val icon: String,
                        val title: String,
                        val subtitle: String,
                        val dist: Float,
                        val onClick: () -> Unit
                    )
                    val maxNearbyDist = 200.0f
                    val nearbyList = mutableListOf<NearbyItem>()

                    if (info.settlementName.isNotBlank() && info.settlementDistanceMeters != null && info.settlementDistanceMeters <= maxNearbyDist) {
                        val sub = listOf(info.districtName, info.regionName).filter { it.isNotBlank() }.joinToString(", ")
                        nearbyList.add(NearbyItem("🏙️", info.settlementName, sub, info.settlementDistanceMeters) {
                            onItemSelected(
                                info.settlementName,
                                if (isUk) "🏙️ Населений пункт" else "🏙️ Settlement",
                                info.settlementLat ?: lat,
                                info.settlementLon ?: lon
                            )
                        })
                    }

                    for ((st, dist) in info.nearestStreets) {
                        if (dist <= maxNearbyDist) {
                            val title = st.streetName.ifBlank { st.cityName }
                            val sub = listOf(st.cityName, st.regionName).filter { it.isNotBlank() }.joinToString(", ")
                            nearbyList.add(NearbyItem("🛣️", title, sub, dist) {
                                onItemSelected(
                                    st.getFullTitle(),
                                    if (isUk) "🛣️ Вулиця" else "🛣️ Street",
                                    st.lat,
                                    st.lon
                                )
                            })
                        }
                    }

                    for ((addr, dist) in info.nearestAddresses) {
                        if (dist <= maxNearbyDist) {
                            val streetPart = listOf(addr.streetName.ifBlank { addr.cityName }, addr.houseNumber).filter { it.isNotBlank() }.joinToString(", ")
                            val title = streetPart.ifBlank { addr.cityName }
                            val sub = listOf(addr.cityName, addr.regionName).filter { it.isNotBlank() }.joinToString(", ")
                            nearbyList.add(NearbyItem("📍", title, sub, dist) {
                                onItemSelected(
                                    addr.getFullTitle(),
                                    if (isUk) "📍 Адреса" else "📍 Address",
                                    addr.lat,
                                    addr.lon
                                )
                            })
                        }
                    }

                    for ((poi, dist) in info.nearestPois) {
                        if (dist <= maxNearbyDist) {
                            val title = poi.poiName ?: poi.getFullTitle()
                            val loc = listOf(
                                listOf(poi.streetName, poi.houseNumber).filter { it.isNotBlank() }.joinToString(", "),
                                poi.cityName,
                                poi.regionName
                            ).filter { it.isNotBlank() }.joinToString(", ")
                            val cat = poi.poiCategory ?: ""
                            val sub = if (cat.isNotBlank() && loc.isNotBlank()) "$cat • $loc" else cat.ifBlank { loc }
                            val icon = poi.poiIcon ?: "📍"
                            nearbyList.add(NearbyItem(icon, title, sub, dist) {
                                val chosenType = "${poi.poiIcon ?: "📍"} ${poi.poiCategory ?: ""}".trim()
                                onItemSelected(
                                    poi.poiName ?: poi.getFullTitle(),
                                    chosenType.ifBlank { if (isUk) "📍 Точка інтересу" else "📍 Point of Interest" },
                                    poi.lat,
                                    poi.lon
                                )
                            })
                        }
                    }

                    nearbyList.sortBy { it.dist }
                    val topNearby = nearbyList.take(15)

                    if (topNearby.isNotEmpty()) {
                        val nearbyHeader = TextView(activity).apply {
                            text = if (isUk) "🎯 Найближчі (до 200 м):" else "🎯 Nearest (within 200 m):"
                            setTextColor(Color.parseColor("#4CAF50"))
                            textSize = 14f
                            setTypeface(null, Typeface.BOLD)
                            setPadding(dp(4), dp(8), dp(4), dp(4))
                        }
                        listsContainer.addView(nearbyHeader)

                        for (item in topNearby) {
                            renderRow(item.icon, item.title, item.subtitle, item.dist, item.onClick)
                        }
                    }

                    // Render up to 15 nearest streets
                    if (info.nearestStreets.isNotEmpty()) {
                        val streetHeader = TextView(activity).apply {
                            text = if (isUk) "🛣️ Найближчі вулиці (до 15):" else "🛣️ Nearest streets (up to 15):"
                            setTextColor(Color.parseColor("#4CAF50"))
                            textSize = 14f
                            setTypeface(null, Typeface.BOLD)
                            setPadding(dp(4), dp(8), dp(4), dp(4))
                        }
                        listsContainer.addView(streetHeader)

                        for ((st, dist) in info.nearestStreets) {
                            val title = st.streetName.ifBlank { st.cityName }
                            val sub = listOf(st.cityName, st.regionName).filter { it.isNotBlank() }.joinToString(", ")
                            renderRow("🛣️", title, sub, dist) {
                                onItemSelected(
                                    st.getFullTitle(),
                                    if (isUk) "🛣️ Вулиця" else "🛣️ Street",
                                    st.lat,
                                    st.lon
                                )
                            }
                        }
                    }

                    // Render up to 15 nearest addresses
                    if (info.nearestAddresses.isNotEmpty()) {
                        val addrHeader = TextView(activity).apply {
                            text = if (isUk) "📍 Найближчі адреси (до 15):" else "📍 Nearest addresses (up to 15):"
                            setTextColor(Color.parseColor("#4CAF50"))
                            textSize = 14f
                            setTypeface(null, Typeface.BOLD)
                            setPadding(dp(4), dp(8), dp(4), dp(4))
                        }
                        listsContainer.addView(addrHeader)

                        for ((addr, dist) in info.nearestAddresses) {
                            val streetPart = listOf(addr.streetName.ifBlank { addr.cityName }, addr.houseNumber).filter { it.isNotBlank() }.joinToString(", ")
                            val title = streetPart.ifBlank { addr.cityName }
                            val sub = listOf(addr.cityName, addr.regionName).filter { it.isNotBlank() }.joinToString(", ")
                            renderRow("📍", title, sub, dist) {
                                onItemSelected(
                                    addr.getFullTitle(),
                                    if (isUk) "📍 Адреса" else "📍 Address",
                                    addr.lat,
                                    addr.lon
                                )
                            }
                        }
                    }

                    // Render up to 15 nearest POIs
                    if (info.nearestPois.isNotEmpty()) {
                        val poiHeader = TextView(activity).apply {
                            text = if (isUk) "✨ Найближчі точки інтересу (до 15):" else "✨ Nearest POIs (up to 15):"
                            setTextColor(Color.parseColor("#4CAF50"))
                            textSize = 14f
                            setTypeface(null, Typeface.BOLD)
                            setPadding(dp(4), dp(12), dp(4), dp(4))
                        }
                        listsContainer.addView(poiHeader)

                        for ((poi, dist) in info.nearestPois) {
                            val title = poi.poiName ?: poi.getFullTitle()
                            val loc = listOf(
                                listOf(poi.streetName, poi.houseNumber).filter { it.isNotBlank() }.joinToString(", "),
                                poi.cityName,
                                poi.regionName
                            ).filter { it.isNotBlank() }.joinToString(", ")
                            val cat = poi.poiCategory ?: ""
                            val sub = if (cat.isNotBlank() && loc.isNotBlank()) "$cat • $loc" else cat.ifBlank { loc }
                            val icon = poi.poiIcon ?: "📍"
                            renderRow(icon, title, sub, dist) {
                                val chosenType = "${poi.poiIcon ?: "📍"} ${poi.poiCategory ?: ""}".trim()
                                onItemSelected(
                                    poi.poiName ?: poi.getFullTitle(),
                                    chosenType.ifBlank { if (isUk) "📍 Точка інтересу" else "📍 Point of Interest" },
                                    poi.lat,
                                    poi.lon
                                )
                            }
                        }
                    }
                }
            }
        }.start()

        infoDialog.setContentView(root)
        infoDialog.show()
    }
}
