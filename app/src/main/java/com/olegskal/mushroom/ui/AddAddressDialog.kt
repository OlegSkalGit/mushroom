package com.olegskal.mushroom.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.olegskal.mushroom.MushroomMapActivity
import com.olegskal.mushroom.address.AddressDb
import com.olegskal.mushroom.address.AddressDownloadManager
import com.olegskal.mushroom.address.AddressSearchManager
import com.olegskal.mushroom.address.AddressSearchResult
import com.olegskal.mushroom.db.DatabaseHelper
import com.olegskal.mushroom.map.MapDownloadManager
import com.olegskal.mushroom.util.AppPrefs

object AddAddressDialog {

    fun show(
        activity: Activity,
        dbHelper: DatabaseHelper,
        onMarkerAdded: () -> Unit
    ) {
        val dialog = Dialog(activity)
        dialog.setTitle("Add Address")

        val isUk = AppPrefs.isUk(activity)
        val density = activity.resources.displayMetrics.density
        val dp = { value: Int -> (value * density).toInt() }

        val container = UiUtils.createDarkDialogContainer(activity)

        // Header
        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }

        val titleTv = TextView(activity).apply {
            text = if (isUk) "🔍 Пошук адрес та точок (POI)" else "🔍 Search Addresses & POI"
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

        var searchInput: EditText? = null

        // Scrollable list of suggestions (max 30 items)
        val scrollView = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val listContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(listContainer)

        // Status / empty label
        val statusTv = TextView(activity).apply {
            text = if (isUk) "Введіть назву вулиці, міста або точки інтересу та натисніть «Пошук»" else "Enter street, city or POI name and press 'Search'"
            setTextColor(Color.GRAY)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(24))
        }
        listContainer.addView(statusTv)

        val bannerContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        container.addView(bannerContainer)

        fun renderResults(results: List<AddressSearchResult>, refLocation: android.location.Location?) {
            listContainer.removeAllViews()
            if (results.isEmpty()) {
                val emptyTv = TextView(activity).apply {
                    text = if (isUk) "Нічого не знайдено за цим запитом" else "No results found for this query"
                    setTextColor(Color.GRAY)
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setPadding(0, dp(24), 0, dp(24))
                }
                listContainer.addView(emptyTv)
                return
            }

            val settlements = results.filter { it.isSettlement }
            val streets = results.filter { !it.isSettlement && !it.isPoiOnly && it.houseNumber.isEmpty() }
            val addresses = results.filter { !it.isSettlement && !it.isPoiOnly && it.houseNumber.isNotEmpty() }
            val pois = results.filter { it.isPoiOnly }

            fun renderBlockHeader(title: String) {
                val header = TextView(activity).apply {
                    text = title
                    setTextColor(Color.parseColor("#4CAF50"))
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    setPadding(dp(4), dp(10), dp(4), dp(4))
                }
                listContainer.addView(header)
            }

            fun renderItemRow(addr: AddressSearchResult) {
                val distM = if (refLocation != null && (addr.lat != 0.0 || addr.lon != 0.0)) {
                    AddressSearchManager.calculateDistanceMeters(refLocation.latitude, refLocation.longitude, addr.lat, addr.lon)
                } else null
                val distStr = if (distM != null) AddressSearchManager.formatDistance(distM) else null

                val itemRow = LinearLayout(activity).apply {
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

                val isStreetOnly = !addr.isSettlement && !addr.isPoiOnly && addr.houseNumber.isEmpty()
                val iconTv = TextView(activity).apply {
                    text = if (addr.isSettlement) "🏙️" else if (addr.isPoiOnly) (addr.poiIcon ?: "📍") else if (isStreetOnly) "🛣️" else if (!addr.poiIcon.isNullOrBlank()) addr.poiIcon else "📍"
                    textSize = 18f
                    setPadding(0, 0, dp(10), 0)
                }
                itemRow.addView(iconTv)

                val textCol = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }

                val titleText = if (addr.isSettlement) {
                    addr.cityName
                } else if (addr.isPoiOnly) {
                    addr.poiName ?: addr.getFullTitle()
                } else {
                    val streetPart = listOf(addr.streetName.ifBlank { addr.cityName }, addr.houseNumber).filter { it.isNotBlank() }.joinToString(", ")
                    if (!addr.poiName.isNullOrBlank()) {
                        "$streetPart • ${addr.poiName}"
                    } else {
                        streetPart.ifBlank { addr.cityName }
                    }
                }

                val itemTitleTv = TextView(activity).apply {
                    text = titleText
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }

                val subtitleText = if (addr.isSettlement) {
                    addr.regionName
                } else if (addr.isPoiOnly) {
                    val loc = listOf(
                        listOf(addr.streetName, addr.houseNumber).filter { it.isNotBlank() }.joinToString(", "),
                        addr.cityName,
                        addr.regionName
                    ).filter { it.isNotBlank() }.joinToString(", ")
                    val cat = addr.poiCategory ?: ""
                    if (cat.isNotBlank() && loc.isNotBlank()) "$cat • $loc" else cat.ifBlank { loc }
                } else {
                    listOf(addr.cityName, addr.regionName).filter { it.isNotBlank() }.joinToString(", ")
                }

                val subtitleTv = TextView(activity).apply {
                    text = subtitleText
                    setTextColor(Color.parseColor("#9CA3AF"))
                    textSize = 11.5f
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }

                textCol.addView(itemTitleTv)
                if (subtitleText.isNotBlank()) {
                    textCol.addView(subtitleTv)
                }
                itemRow.addView(textCol)

                if (!distStr.isNullOrBlank()) {
                    val distTv = TextView(activity).apply {
                        text = distStr
                        setTextColor(Color.parseColor("#10B981"))
                        textSize = 12f
                        setTypeface(null, Typeface.BOLD)
                        setPadding(dp(8), 0, 0, 0)
                        gravity = Gravity.CENTER_VERTICAL or Gravity.END
                    }
                    itemRow.addView(distTv)
                }

                itemRow.setOnClickListener {
                    dialog.dismiss()
                    val initialType = if (addr.isSettlement) {
                        if (isUk) "🏙️ Населений пункт" else "🏙️ Settlement"
                    } else if (addr.isPoiOnly) {
                        if (!addr.poiCategory.isNullOrBlank()) {
                            "${addr.poiIcon ?: "📍"} ${addr.poiCategory}"
                        } else {
                            if (isUk) "📍 Точка інтересу" else "📍 Point of Interest"
                        }
                    } else if (isStreetOnly) {
                        if (isUk) "🛣️ Вулиця" else "🛣️ Street"
                    } else {
                        if (isUk) "📍 Адреса" else "📍 Address"
                    }
                    val initialName = if (addr.isSettlement) {
                        addr.cityName
                    } else if (addr.isPoiOnly) {
                        addr.poiName ?: addr.getFullTitle()
                    } else {
                        addr.getFullTitle()
                    }
                    ItemEditDialog.showAddMarker(
                        activity = activity,
                        dbHelper = dbHelper,
                        lat = addr.lat,
                        lon = addr.lon,
                        initialName = initialName,
                        initialType = initialType
                    ) { marker ->
                        val mapActivity = activity as? MushroomMapActivity
                        mapActivity?.centerOnLocation(marker.lat, marker.lon)
                        onMarkerAdded()
                    }
                }

                listContainer.addView(itemRow)
            }

            // 1. Населені пункти
            if (settlements.isNotEmpty()) {
                val headerText = if (isUk) "🏙️ Населені пункти (${settlements.size}):" else "🏙️ Settlements (${settlements.size}):"
                renderBlockHeader(headerText)
                for (s in settlements) {
                    renderItemRow(s)
                }
            }

            // 2. Вулиці
            if (streets.isNotEmpty()) {
                val headerText = if (isUk) "🛣️ Вулиці (${streets.size}):" else "🛣️ Streets (${streets.size}):"
                renderBlockHeader(headerText)
                for (st in streets) {
                    renderItemRow(st)
                }
            }

            // 3. Адреси
            if (addresses.isNotEmpty()) {
                val headerText = if (isUk) "📍 Адреси (${addresses.size}):" else "📍 Addresses (${addresses.size}):"
                renderBlockHeader(headerText)
                for (a in addresses) {
                    renderItemRow(a)
                }
            }

            // 4. Точки інтересу
            if (pois.isNotEmpty()) {
                val headerText = if (isUk) "✨ Точки інтересу (${pois.size}):" else "✨ Points of Interest (${pois.size}):"
                renderBlockHeader(headerText)
                for (p in pois) {
                    renderItemRow(p)
                }
            }
        }

        fun executeSearch() {
            val query = searchInput?.text?.toString()?.trim() ?: ""
            if (query.isBlank()) {
                listContainer.removeAllViews()
                statusTv.text = if (isUk) "Введіть назву вулиці, міста або точки інтересу" else "Enter street, city or POI name"
                listContainer.addView(statusTv)
                return
            }

            val mapActivity = activity as? MushroomMapActivity
            val gpsLoc = com.olegskal.mushroom.util.LocationUtils.getUserLocation(activity)
            val refLocation = gpsLoc ?: android.location.Location("map_center").apply {
                latitude = mapActivity?.mapCenterLat ?: 50.4501
                longitude = mapActivity?.mapCenterLon ?: 30.5234
            }

            listContainer.removeAllViews()
            statusTv.text = if (isUk) "Пошук..." else "Searching..."
            listContainer.addView(statusTv)

            Thread {
                try {
                    val results = AddressSearchManager.search(activity, query, limit = 30, userLocation = refLocation)
                    activity.runOnUiThread {
                        renderResults(results, refLocation)
                    }
                } catch (e: Exception) {
                    activity.runOnUiThread {
                        val errText = if (isUk) "Помилка пошуку: ${e.message}" else "Search error: ${e.message}"
                        listContainer.removeAllViews()
                        statusTv.text = errText
                        listContainer.addView(statusTv)
                    }
                }
            }.start()
        }

        // Input Field + Search Button row
        val searchRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            p.setMargins(0, 0, 0, dp(10))
            layoutParams = p
        }

        searchInput = EditText(activity).apply {
            hint = if (isUk) "Адреса або точка інтересу (аптека, готель, джерело)..." else "Address or POI (pharmacy, hotel, spring)..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#263226"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            textSize = 14f
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            val p = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, dp(8), 0)
            }
            layoutParams = p
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                    executeSearch()
                    true
                } else false
            }
        }

        val btnSearch = UiUtils.createStyledButton(activity, if (isUk) "Пошук" else "Search") {
            executeSearch()
        }.apply {
            textSize = 13f
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }

        searchRow.addView(searchInput)
        searchRow.addView(btnSearch)
        container.addView(searchRow)
        container.addView(scrollView)

        // Check if database exists
        val dbFile = AddressDb.getDatabaseFile(activity)
        if (!dbFile.exists() || dbFile.length() < 1024L) {
            val alertMsg = if (isUk) {
                "Офлайн-база адрес (addr.db) ще не завантажена. Завантажте базу адрес у вікні оновлень/карт."
            } else {
                "Offline address database (addr.db) is not downloaded yet. Please download it from the updates/maps screen."
            }
            Toast.makeText(activity, alertMsg, Toast.LENGTH_LONG).show()
            statusTv.text = alertMsg
        }

        dialog.setContentView(container)
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.92).toInt(),
            (activity.resources.displayMetrics.heightPixels * 0.85).toInt()
        )
        dialog.show()

        // Background check for POI upgrade banner without blocking the UI
        Thread {
            try {
                val detectedCountry = MapDownloadManager.detectCurrentCountry(activity)
                if (AddressDownloadManager.needsPoiUpdate(activity, detectedCountry)) {
                    activity.runOnUiThread {
                        if (!activity.isFinishing && !activity.isDestroyed && dialog.isShowing) {
                            val banner = LinearLayout(activity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                                setBackgroundColor(Color.parseColor("#1B2E24"))
                                val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                                p.setMargins(0, 0, 0, dp(10))
                                layoutParams = p
                                setPadding(dp(12), dp(8), dp(12), dp(8))
                            }

                            val bannerTv = TextView(activity).apply {
                                text = if (isUk) "💡 Доступне оновлення: додати точки інтересу (POI) без інтернету"
                                       else "💡 Update available: add points of interest (POI) from local file"
                                setTextColor(Color.parseColor("#86EFAC"))
                                textSize = 12f
                                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            }

                            val btnUpdate = UiUtils.createStyledButton(activity, if (isUk) "Оновити" else "Update") {
                                banner.visibility = View.GONE
                                statusTv.text = if (isUk) "Оновлення точок інтересу (POI)..." else "Updating POI..."
                                listContainer.removeAllViews()
                                listContainer.addView(statusTv)
                                Thread {
                                    val imported = AddressDownloadManager.importPoiLocationsToAddressDb(
                                        context = activity,
                                        country = detectedCountry,
                                        onProgress = { _, statusMsg ->
                                            activity.runOnUiThread {
                                                statusTv.text = statusMsg
                                            }
                                        }
                                    )
                                    activity.runOnUiThread {
                                        if (imported > 0) {
                                            Toast.makeText(
                                                activity,
                                                if (isUk) "Успішно додано $imported точок інтересу!" else "Successfully added $imported POI points!",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        } else {
                                            Toast.makeText(
                                                activity,
                                                if (isUk) "Не вдалося оновити POI або файл відсутній" else "Failed to update POI or file missing",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                        statusTv.text = if (isUk) "Введіть назву вулиці, міста або точки інтересу" else "Enter street, city or POI name"
                                    }
                                }.start()
                            }.apply {
                                textSize = 12f
                                setPadding(dp(10), dp(4), dp(10), dp(4))
                            }

                            banner.addView(bannerTv)
                            banner.addView(btnUpdate)
                            bannerContainer.removeAllViews()
                            bannerContainer.addView(banner)
                        }
                    }
                }
            } catch (_: Exception) {}
        }.start()
    }
}
