package com.olegskal.mushroom.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import com.olegskal.mushroom.db.DatabaseHelper
import com.olegskal.mushroom.map.MapCountry
import com.olegskal.mushroom.map.MapDownloadManager
import com.olegskal.mushroom.map.MapRegion
import com.olegskal.mushroom.network.OverpassSyncManager
import com.olegskal.mushroom.util.L10n
import java.util.Locale

object RegionDownloadDialog {

    fun show(activity: Activity, onDownloadStarted: () -> Unit = {}) {
        if (MapDownloadManager.isCurrentlyDownloading()) {
            showActiveDownloadProgress(activity)
            return
        }
        val dbHelper = DatabaseHelper(activity)
        showCountrySelection(activity, dbHelper, onDownloadStarted)
    }

    private fun showCountrySelection(
        activity: Activity,
        dbHelper: DatabaseHelper,
        onDownloadStarted: () -> Unit
    ) {
        val dialog = Dialog(activity)
        dialog.setTitle("Select Country")

        val container = UiUtils.createDarkDialogContainer(activity)

        val titleTv = TextView(activity).apply {
            text = L10n.t(activity, "🗺️ Завантаження карт: оберіть країну", "🗺️ Download Maps: Select Country")
            setTextColor(Color.WHITE)
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 12)
        }
        container.addView(titleTv)

        val searchInput = EditText(activity).apply {
            hint = L10n.t(activity, "🔍 Пошук країни...", "🔍 Search country...")
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            setPadding(20, 14, 20, 14)
            textSize = 14f
        }
        val searchParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, 12)
        }
        searchInput.layoutParams = searchParams
        container.addView(searchInput)

        val scrollView = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val listContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        var fullCountryList = emptyList<MapCountry>()
        val countryStatuses = HashMap<String, String>()
        val itemParams = UiUtils.createStandardItemParams()

        fun renderCountries(list: List<MapCountry>) {
            listContainer.removeAllViews()
            if (list.isEmpty()) {
                val emptyTv = TextView(activity).apply {
                    val fetchingMsg = L10n.t(activity, "⏳ Завантаження списку країн з OpenStreetMap...", "⏳ Fetching country list from OpenStreetMap...")
                    val notFoundMsg = L10n.t(activity, "Країн не знайдено", "No countries found")
                    text = if (fullCountryList.isEmpty()) fetchingMsg else notFoundMsg
                    setTextColor(Color.LTGRAY)
                    textSize = 14f
                    setPadding(16, 24, 16, 24)
                }
                listContainer.addView(emptyTv)
                return
            }
            for (country in list) {
                val status = countryStatuses[country.code] ?: ""
                val btn = UiUtils.createStyledButton(
                    activity,
                    "${country.name} (${country.code})$status",
                    itemParams
                ) {
                    dialog.dismiss()
                    loadAndShowRegions(activity, country, dbHelper, onDownloadStarted)
                }
                listContainer.addView(btn)
            }
        }

        fun checkCountryStatuses(countries: List<MapCountry>) {
            Thread {
                var changed = false
                for (c in countries) {
                    val cached = dbHelper.getCachedRegions(c.code)
                    if (cached.isNotEmpty()) {
                        var totalAll = 0
                        var existingAll = 0
                        for (r in cached) {
                            val (ex, tot) = MapDownloadManager.getRegionDownloadStatus(r)
                            existingAll += ex
                            totalAll += tot
                        }
                        if (totalAll > 0 && existingAll >= totalAll) {
                            countryStatuses[c.code] = "  [✓ Fully downloaded]"
                            changed = true
                        } else if (existingAll > 0) {
                            val pct = (existingAll * 100) / totalAll
                            countryStatuses[c.code] = "  [⏳ Incomplete ($pct%)]"
                            changed = true
                        }
                    }
                }
                if (changed) {
                    activity.runOnUiThread {
                        val q = searchInput.text.toString().trim().lowercase(Locale.getDefault())
                        val toShow = if (q.isEmpty()) fullCountryList else fullCountryList.filter {
                            it.name.lowercase(Locale.getDefault()).contains(q) ||
                                    it.code.lowercase(Locale.getDefault()).contains(q)
                        }
                        renderCountries(toShow)
                    }
                }
            }.start()
        }

        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim()?.lowercase(Locale.getDefault()) ?: ""
                val filtered = if (query.isEmpty()) {
                    fullCountryList
                } else {
                    fullCountryList.filter {
                        it.name.lowercase(Locale.getDefault()).contains(query) ||
                                it.code.lowercase(Locale.getDefault()).contains(query)
                    }
                }
                renderCountries(filtered)
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        OverpassSyncManager.fetchCountries(dbHelper) { countries ->
            activity.runOnUiThread {
                fullCountryList = countries
                val q = searchInput.text.toString().trim().lowercase(Locale.getDefault())
                val toShow = if (q.isEmpty()) countries else countries.filter {
                    it.name.lowercase(Locale.getDefault()).contains(q) ||
                            it.code.lowercase(Locale.getDefault()).contains(q)
                }
                renderCountries(toShow)
                checkCountryStatuses(countries)
            }
        }

        scrollView.addView(listContainer)
        container.addView(scrollView)

        val btnClose = UiUtils.createStyledButton(activity, "Close") {
            dialog.dismiss()
        }
        container.addView(UiUtils.createDialogDivider(activity))
        container.addView(btnClose)

        dialog.setContentView(container)
        dialog.show()
    }

    private fun loadAndShowRegions(
        activity: Activity,
        country: MapCountry,
        dbHelper: DatabaseHelper,
        onDownloadStarted: () -> Unit
    ) {
        val cached = dbHelper.getCachedRegions(country.code)
        if (cached.isNotEmpty()) {
            showRegionSelection(activity, country, cached, onDownloadStarted)
            return
        }

        val loadingDialog = Dialog(activity).apply { setCancelable(false) }
        val loadContainer = UiUtils.createDarkDialogContainer(activity)
        val loadTv = TextView(activity).apply {
            text = "⏳ Fetching regions of ${country.name} from OSM..."
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(0, 16, 0, 16)
        }
        val pBar = ProgressBar(activity)
        loadContainer.addView(loadTv)
        loadContainer.addView(pBar)
        loadingDialog.setContentView(loadContainer)
        loadingDialog.show()

        OverpassSyncManager.fetchRegions(country.code, dbHelper) { regions ->
            activity.runOnUiThread {
                loadingDialog.dismiss()
                if (regions.isNotEmpty()) {
                    showRegionSelection(activity, country, regions, onDownloadStarted)
                } else {
                    Toast.makeText(activity, "Failed to fetch regions for ${country.name}. Check your internet connection.", Toast.LENGTH_LONG).show()
                    show(activity, onDownloadStarted)
                }
            }
        }
    }

    private fun showRegionSelection(
        activity: Activity,
        country: MapCountry,
        regions: List<MapRegion>,
        onDownloadStarted: () -> Unit
    ) {
        val dialog = Dialog(activity)
        dialog.setTitle("Regions: ${country.name}")

        val container = UiUtils.createDarkDialogContainer(activity)

        val titleTv = TextView(activity).apply {
            text = L10n.t(activity, "🗺️ ${country.name}: оберіть регіони", "🗺️ ${country.name}: select regions")
            setTextColor(Color.WHITE)
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        }
        container.addView(titleTv)

        val selectedRegions = HashSet<MapRegion>()
        val infoTv = TextView(activity).apply {
            val count = 0
            val tiles = 0
            text = String.format(Locale.US, L10n.t(activity, "Обрано: %d регіонів (%d тайлів)", "Selected: %d regions (%d tiles)"), count, tiles)
            setTextColor(Color.parseColor("#4CAF50"))
            textSize = 13f
            setPadding(0, 0, 0, 8)
        }
        container.addView(infoTv)

        val searchInput = EditText(activity).apply {
            hint = L10n.t(activity, "🔍 Пошук регіону...", "🔍 Search region...")
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            setPadding(20, 14, 20, 14)
            textSize = 14f
        }
        val searchParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, 8)
        }
        searchInput.layoutParams = searchParams
        container.addView(searchInput)

        fun updateInfo() {
            val count = selectedRegions.size
            val tiles = MapDownloadManager.calculateTileCount(selectedRegions.toList())
            infoTv.text = String.format(Locale.US, L10n.t(activity, "Обрано: %d регіонів (~%d тайлів)", "Selected: %d regions (~%d tiles)"), count, tiles)
        }

        val btnSelectAll = Button(activity).apply {
            text = L10n.t(activity, "Обрати всі / Зняти всі", "Select All / Deselect All")
            setTextColor(Color.parseColor("#00E5FF"))
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            textSize = 13f
        }
        container.addView(btnSelectAll)

        val scrollView = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            setPadding(0, 8, 0, 8)
        }

        val listContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        val regionStatuses = HashMap<String, Pair<Int, Int>>()
        var currentFiltered = regions

        fun renderRegions(list: List<MapRegion>) {
            currentFiltered = list
            listContainer.removeAllViews()
            if (list.isEmpty()) {
                val emptyTv = TextView(activity).apply {
                    text = L10n.t(activity, "Регіонів не знайдено", "No regions found")
                    setTextColor(Color.LTGRAY)
                    textSize = 14f
                    setPadding(16, 24, 16, 24)
                }
                listContainer.addView(emptyTv)
                return
            }

            for (region in list) {
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(6, 4, 6, 4)
                }

                val chk = CheckBox(activity).apply {
                    text = region.name
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    isChecked = selectedRegions.contains(region)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) {
                            selectedRegions.add(region)
                        } else {
                            selectedRegions.remove(region)
                        }
                        updateInfo()
                    }
                }
                row.addView(chk)

                val stat = regionStatuses[region.id]
                val statusTv = TextView(activity).apply {
                    textSize = 12f
                    setPadding(8, 0, 4, 0)
                    if (stat == null) {
                        text = ""
                    } else {
                        val (existing, total) = stat
                        if (total > 0 && existing >= total) {
                            text = "✓ Fully downloaded"
                            setTextColor(Color.parseColor("#4CAF50"))
                        } else if (existing > 0) {
                            val pct = (existing * 100) / total
                            text = "⏳ Incomplete ($pct%)"
                            setTextColor(Color.parseColor("#FFB300"))
                        } else {
                            text = "Not downloaded"
                            setTextColor(Color.GRAY)
                        }
                    }
                }
                row.addView(statusTv)

                listContainer.addView(row)
            }
        }

        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.trim() ?: ""
                val filtered = if (q.isEmpty()) regions else regions.filter { it.name.contains(q, ignoreCase = true) }
                renderRegions(filtered)
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        btnSelectAll.setOnClickListener {
            if (selectedRegions.size == currentFiltered.size) {
                selectedRegions.removeAll(currentFiltered.toSet())
            } else {
                selectedRegions.addAll(currentFiltered)
            }
            updateInfo()
            renderRegions(currentFiltered)
        }

        renderRegions(regions)

        // Background query for downloaded tile counts
        Thread {
            val map = HashMap<String, Pair<Int, Int>>()
            for (r in regions) {
                map[r.id] = MapDownloadManager.getRegionDownloadStatus(r)
            }
            activity.runOnUiThread {
                regionStatuses.putAll(map)
                renderRegions(currentFiltered)
            }
        }.start()

        scrollView.addView(listContainer)
        container.addView(scrollView)

        val btnDownload = UiUtils.createStyledButton(activity, L10n.t(activity, "⬇️ Завантажити обрані регіони", "Download Selected Regions")) {
            if (selectedRegions.isEmpty()) {
                Toast.makeText(activity, L10n.t(activity, "Будь ласка, оберіть хоча б один регіон!", "Please select at least one region!"), Toast.LENGTH_SHORT).show()
                return@createStyledButton
            }
            dialog.dismiss()
            startDownloadWithProgress(activity, selectedRegions.toList(), onDownloadStarted)
        }

        val btnBack = UiUtils.createStyledButton(activity, L10n.t(activity, "← До списку країн", "Back to Countries")) {
            dialog.dismiss()
            show(activity, onDownloadStarted)
        }

        container.addView(UiUtils.createDialogDivider(activity))
        container.addView(btnDownload)
        container.addView(btnBack)

        dialog.setContentView(container)
        dialog.show()
    }

    private fun startDownloadWithProgress(
        activity: Activity,
        regions: List<MapRegion>,
        onDownloadStarted: () -> Unit
    ) {
        onDownloadStarted()
        MapDownloadManager.downloadRegions(
            regions = regions,
            onFinished = { success, skipped, failed ->
                com.olegskal.mushroom.map.OsmTileEngine.clearMissingTileCache()
                activity.runOnUiThread {
                    val msg = String.format(
                        Locale.US,
                        L10n.t(activity, "Завантаження завершено: %d нових, %d у кеші, %d помилок", "Download finished: %d new, %d in cache, %d failed"),
                        success, skipped, failed
                    )
                    Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                }
            }
        )
        showActiveDownloadProgress(activity)
    }

    private fun showActiveDownloadProgress(activity: Activity) {
        val progressDialog = Dialog(activity).apply {
            setCancelable(false)
        }

        val container = UiUtils.createDarkDialogContainer(activity)

        val titleTv = TextView(activity).apply {
            text = L10n.t(activity, "Завантаження карт CyclOSM...", "Downloading CyclOSM maps...")
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 12)
        }

        val statusTv = TextView(activity).apply {
            val info = MapDownloadManager.lastProgressInfo
            text = if (info != null) {
                String.format(Locale.US, L10n.t(activity, "Масштаб: z%d | Регіон: %s", "Zoom: z%d | Region: %s"), info.currentZoom, info.currentRegion)
            } else {
                L10n.t(activity, "Підготовка списку тайлів...", "Preparing tile list...")
            }
            setTextColor(Color.LTGRAY)
            textSize = 13f
            setPadding(0, 0, 0, 8)
        }

        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            val info = MapDownloadManager.lastProgressInfo
            progress = if (info != null && info.total > 0) (info.current * 100) / info.total else 0
        }

        val percentTv = TextView(activity).apply {
            val info = MapDownloadManager.lastProgressInfo
            val pct = if (info != null && info.total > 0) (info.current * 100) / info.total else 0
            text = if (info != null) "$pct% (${info.current} / ${info.total})" else "0%"
            setTextColor(Color.parseColor("#4CAF50"))
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 16)
        }

        val btnBackground = UiUtils.createStyledButton(activity, L10n.t(activity, "Згорнути у фон", "Hide to background")) {
            progressDialog.dismiss()
            Toast.makeText(activity, L10n.t(activity, "Завантаження триває у фоні. Можна користуватися картою.", "Download continues in background. You can use the map."), Toast.LENGTH_SHORT).show()
        }

        val btnCancel = UiUtils.createStyledButton(activity, L10n.t(activity, "Зупинити", "Stop")) {
            MapDownloadManager.cancelDownload()
            progressDialog.dismiss()
            Toast.makeText(activity, L10n.t(activity, "Завантаження зупинено", "Download stopped"), Toast.LENGTH_SHORT).show()
        }

        container.addView(titleTv)
        container.addView(statusTv)
        container.addView(progressBar)
        container.addView(percentTv)
        container.addView(btnBackground)
        container.addView(btnCancel)

        progressDialog.setContentView(container)
        progressDialog.show()

        MapDownloadManager.onProgressUpdate = { info ->
            activity.runOnUiThread {
                if (progressDialog.isShowing) {
                    val pct = if (info.total > 0) (info.current * 100) / info.total else 0
                    progressBar.progress = pct
                    percentTv.text = "$pct% (${info.current} / ${info.total})"
                    statusTv.text = "Zoom: z${info.currentZoom} | Region: ${info.currentRegion}"
                }
            }
        }

        val originalFinished = MapDownloadManager.onDownloadCompleted
        MapDownloadManager.onDownloadCompleted = { success, skipped, failed ->
            originalFinished?.invoke(success, skipped, failed)
            activity.runOnUiThread {
                if (progressDialog.isShowing) {
                    progressDialog.dismiss()
                }
            }
        }
    }
}
