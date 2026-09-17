package com.example.radardetector.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import com.example.radardetector.map.MapCountry
import com.example.radardetector.map.MapDownloadManager
import com.example.radardetector.map.MapRegion
import java.util.Locale

object RegionDownloadDialog {

    fun show(activity: Activity, onDownloadStarted: () -> Unit = {}) {
        val countries = MapDownloadManager.countries
        showCountrySelection(activity, countries, onDownloadStarted)
    }

    private fun showCountrySelection(
        activity: Activity,
        countries: List<MapCountry>,
        onDownloadStarted: () -> Unit
    ) {
        val dialog = Dialog(activity)
        dialog.setTitle("Вибір країни")

        val container = UiUtils.createDarkDialogContainer(activity)

        val titleTv = TextView(activity).apply {
            text = "🗺️ Завантаження карт: Виберіть країну"
            setTextColor(Color.WHITE)
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 16)
        }
        container.addView(titleTv)

        val scrollView = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val listContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        val itemParams = UiUtils.createStandardItemParams()
        for (country in countries) {
            val btn = UiUtils.createStyledButton(
                activity,
                "${country.name} (${country.regions.size} регіонів)",
                itemParams
            ) {
                dialog.dismiss()
                showRegionSelection(activity, country, onDownloadStarted)
            }
            listContainer.addView(btn)
        }

        scrollView.addView(listContainer)
        container.addView(scrollView)

        val btnClose = UiUtils.createStyledButton(activity, "Закрити") {
            dialog.dismiss()
        }
        container.addView(UiUtils.createDialogDivider(activity))
        container.addView(btnClose)

        dialog.setContentView(container)
        dialog.show()
    }

    private fun showRegionSelection(
        activity: Activity,
        country: MapCountry,
        onDownloadStarted: () -> Unit
    ) {
        val dialog = Dialog(activity)
        dialog.setTitle("Регіони: ${country.name}")

        val container = UiUtils.createDarkDialogContainer(activity)

        val titleTv = TextView(activity).apply {
            text = "🗺️ ${country.name}: виберіть регіони"
            setTextColor(Color.WHITE)
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        }
        container.addView(titleTv)

        val selectedRegions = HashSet<MapRegion>()
        val infoTv = TextView(activity).apply {
            text = "Вибрано: 0 регіонів (0 тайлів)"
            setTextColor(Color.parseColor("#4CAF50"))
            textSize = 13f
            setPadding(0, 0, 0, 12)
        }
        container.addView(infoTv)

        fun updateInfo() {
            val count = selectedRegions.size
            val tiles = MapDownloadManager.calculateTileCount(selectedRegions.toList(), 10, 13)
            infoTv.text = "Вибрано: $count регіонів (~$tiles тайлів)"
        }

        val btnSelectAll = Button(activity).apply {
            text = "Вибрати всі / Зняти всі"
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

        val checkboxes = ArrayList<CheckBox>()

        for (region in country.regions) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(8, 8, 8, 8)
            }

            val chk = CheckBox(activity).apply {
                text = region.name
                setTextColor(Color.WHITE)
                textSize = 14f
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        selectedRegions.add(region)
                    } else {
                        selectedRegions.remove(region)
                    }
                    updateInfo()
                }
            }
            checkboxes.add(chk)
            row.addView(chk)
            listContainer.addView(row)
        }

        btnSelectAll.setOnClickListener {
            val allChecked = selectedRegions.size == country.regions.size
            for (chk in checkboxes) {
                chk.isChecked = !allChecked
            }
        }

        scrollView.addView(listContainer)
        container.addView(scrollView)

        val btnDownload = UiUtils.createStyledButton(activity, "Завантажити вибрані регіони") {
            if (selectedRegions.isEmpty()) {
                Toast.makeText(activity, "Виберіть хоча б один регіон!", Toast.LENGTH_SHORT).show()
                return@createStyledButton
            }
            dialog.dismiss()
            startDownloadWithProgress(activity, selectedRegions.toList(), onDownloadStarted)
        }

        val btnBack = UiUtils.createStyledButton(activity, "Назад до країн") {
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
        val progressDialog = Dialog(activity).apply {
            setCancelable(false)
        }

        val container = UiUtils.createDarkDialogContainer(activity)

        val titleTv = TextView(activity).apply {
            text = "Завантаження OSM карт..."
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 12)
        }

        val statusTv = TextView(activity).apply {
            text = "Підготовка списку тайлів..."
            setTextColor(Color.LTGRAY)
            textSize = 13f
            setPadding(0, 0, 0, 8)
        }

        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
        }

        val percentTv = TextView(activity).apply {
            text = "0%"
            setTextColor(Color.parseColor("#4CAF50"))
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 16)
        }

        val btnCancel = UiUtils.createStyledButton(activity, "Зупинити") {
            MapDownloadManager.cancelDownload()
            progressDialog.dismiss()
            Toast.makeText(activity, "Завантаження зупинено", Toast.LENGTH_SHORT).show()
        }

        container.addView(titleTv)
        container.addView(statusTv)
        container.addView(progressBar)
        container.addView(percentTv)
        container.addView(btnCancel)

        progressDialog.setContentView(container)
        progressDialog.show()

        onDownloadStarted()

        MapDownloadManager.downloadRegions(
            regions = regions,
            minZoom = 10,
            maxZoom = 13,
            onProgress = { current, total, curRegion ->
                activity.runOnUiThread {
                    if (progressDialog.isShowing) {
                        val pct = if (total > 0) (current * 100) / total else 0
                        progressBar.progress = pct
                        percentTv.text = "$pct% ($current / $total)"
                        statusTv.text = "Регіон: $curRegion"
                    }
                }
            },
            onFinished = { success, skipped, failed ->
                activity.runOnUiThread {
                    if (progressDialog.isShowing) {
                        progressDialog.dismiss()
                        Toast.makeText(
                            activity,
                            "Завантаження завершено: $success нових, $skipped в кеші, $failed помилок",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        )
    }
}
