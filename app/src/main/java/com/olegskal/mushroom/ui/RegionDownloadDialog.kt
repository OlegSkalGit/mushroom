package com.olegskal.mushroom.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import com.olegskal.mushroom.map.CountryStatus
import com.olegskal.mushroom.map.MapCountry
import com.olegskal.mushroom.map.MapDownloadManager
import com.olegskal.mushroom.util.AppPrefs
import java.util.Locale

object RegionDownloadDialog {

    fun show(activity: Activity, onDownloadStarted: () -> Unit = {}) {
        if (InitialSetupDialog.isDownloading() || MapDownloadManager.isCurrentlyDownloading()) {
            val isUk = AppPrefs.isUk(activity)
            val msg = if (isUk) "Завантаження компонентів уже виконується..." else "Component download is already running..."
            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
            return
        }
        showCountrySelection(activity, onDownloadStarted)
    }

    private fun showCountrySelection(
        activity: Activity,
        onDownloadStarted: () -> Unit
    ) {
        val dialog = Dialog(activity)
        dialog.setTitle("Select Country")

        val isUk = AppPrefs.isUk(activity)
        val container = UiUtils.createDarkDialogContainer(activity)

        val titleTv = TextView(activity).apply {
            text = if (isUk) "🗺️ Завантаження карт по країнах" else "🗺️ Download Maps by Country"
            setTextColor(Color.WHITE)
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 12)
        }
        container.addView(titleTv)

        val subtitleTv = TextView(activity).apply {
            text = if (isUk) "Завантажуються векторна карта (.map), точки POI (.poi), навігація (.rd5) та оглядова карта світу (world.map)"
            else "Downloads vector map (.map), POI points (.poi), navigation (.rd5), and world overview map (world.map)"
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 12f
            setPadding(0, 0, 0, 10)
        }
        container.addView(subtitleTv)

        val searchInput = EditText(activity).apply {
            hint = if (isUk) "🔍 Пошук країни..." else "🔍 Search country..."
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

        val countryStatuses = HashMap<String, CountryStatus>()
        val itemParams = UiUtils.createStandardItemParams()

        fun renderCountries(list: List<MapCountry>) {
            listContainer.removeAllViews()
            if (list.isEmpty()) {
                val emptyTv = TextView(activity).apply {
                    text = if (isUk) "Країн не знайдено" else "No countries found"
                    setTextColor(Color.LTGRAY)
                    textSize = 14f
                    setPadding(16, 24, 16, 24)
                }
                listContainer.addView(emptyTv)
                return
            }

            for (country in list) {
                val status = countryStatuses[country.code] ?: CountryStatus.NOT_DOWNLOADED
                val statusText = when (status) {
                    CountryStatus.READY -> if (isUk) "  [✓ Завантажено]" else "  [✓ Ready]"
                    CountryStatus.INCOMPLETE -> if (isUk) "  [⏳ Не повністю]" else "  [⏳ Incomplete]"
                    CountryStatus.NEEDS_UPDATE -> if (isUk) "  [⚠️ Оновлення]" else "  [⚠️ Update]"
                    CountryStatus.NOT_DOWNLOADED -> ""
                }

                val displayName = if (isUk) country.nameUk else country.name
                val btn = UiUtils.createStyledButton(
                    activity,
                    "$displayName (${country.code})$statusText",
                    itemParams
                ) {
                    dialog.dismiss()
                    confirmAndDownloadCountry(activity, country, onDownloadStarted)
                }
                listContainer.addView(btn)
            }
        }

        fun refreshStatuses() {
            Thread {
                for (c in MapDownloadManager.countries) {
                    countryStatuses[c.code] = MapDownloadManager.getCountryStatus(c)
                }
                activity.runOnUiThread {
                    val q = searchInput.text.toString().trim().lowercase(Locale.getDefault())
                    val toShow = if (q.isEmpty()) MapDownloadManager.countries else MapDownloadManager.countries.filter {
                        it.name.lowercase(Locale.getDefault()).contains(q) ||
                        it.nameUk.lowercase(Locale.getDefault()).contains(q) ||
                        it.code.lowercase(Locale.getDefault()).contains(q)
                    }
                    renderCountries(toShow)
                }
            }.start()
        }

        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.trim()?.lowercase(Locale.getDefault()) ?: ""
                val filtered = if (q.isEmpty()) MapDownloadManager.countries else MapDownloadManager.countries.filter {
                    it.name.lowercase(Locale.getDefault()).contains(q) ||
                    it.nameUk.lowercase(Locale.getDefault()).contains(q) ||
                    it.code.lowercase(Locale.getDefault()).contains(q)
                }
                renderCountries(filtered)
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        renderCountries(MapDownloadManager.countries)
        refreshStatuses()

        scrollView.addView(listContainer)
        container.addView(scrollView)

        val btnClose = UiUtils.createStyledButton(activity, if (isUk) "Закрити" else "Close") {
            dialog.dismiss()
        }
        container.addView(UiUtils.createDialogDivider(activity))
        container.addView(btnClose)

        dialog.setContentView(container)
        dialog.show()
    }

    fun confirmAndDownloadCountry(
        activity: Activity,
        country: MapCountry,
        onDownloadStarted: () -> Unit = {}
    ) {
        if (InitialSetupDialog.isDownloading() || MapDownloadManager.isCurrentlyDownloading()) {
            val isUk = AppPrefs.isUk(activity)
            val msg = if (isUk) "Завантаження компонентів уже виконується..." else "Component download is already running..."
            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
            return
        }
        val isUk = AppPrefs.isUk(activity)
        val status = MapDownloadManager.getCountryStatus(country)
        val isUpdate = (status == CountryStatus.NEEDS_UPDATE)
        val segments = country.getRd5Segments()
        val needsWorldMap = !MapDownloadManager.isWorldMapReady()
        val regCount = InitialSetupDialog.getCountryRegionCount(country)
        val regSuffix = if (isUk) "$regCount регіонів" else "$regCount regions"

        val displayName = if (isUk) country.nameUk else country.name
        val title = if (isUpdate) {
            if (isUk) "Оновити компоненти: $displayName?" else "Update components: ${country.name}?"
        } else {
            if (isUk) "Завантажити компоненти: $displayName?" else "Download components: ${country.name}?"
        }
        val worldItem = if (needsWorldMap) {
            if (isUk) "• Оглядова карта світу (world.map)\n" else "• World overview map (world.map)\n"
        } else ""
        val actionBtn = if (isUpdate) {
            if (isUk) "Оновити" else "Update"
        } else {
            if (isUk) "Завантажити" else "Download"
        }
        val msg = if (isUpdate) {
            if (isUk) {
                "Будуть завантажені відсутні та оновлені компоненти для $displayName ($regSuffix):\n" +
                worldItem +
                "• Карта країни (.map)\n" +
                "• Точки інтересу (.poi)\n" +
                "• Навігаційні сегменти BRouter (${segments.size} файлів .rd5)\n" +
                "• База адрес (addr.db, усі $regCount регіонів)\n\n" +
                "Почати оновлення?"
            } else {
                "Missing and updated components will be downloaded for $displayName ($regSuffix):\n" +
                worldItem +
                "• Country map (.map)\n" +
                "• Points of Interest (.poi)\n" +
                "• BRouter navigation segments (${segments.size} files .rd5)\n" +
                "• Address database (addr.db, all $regCount regions)\n\n" +
                "Start update?"
            }
        } else {
            if (isUk) {
                "Буде завантажено для $displayName ($regSuffix):\n" +
                worldItem +
                "• Векторна карта (.map)\n" +
                "• Точки інтересу (.poi)\n" +
                "• Навігаційні сегменти BRouter (${segments.size} файлів .rd5)\n" +
                "• База адрес (addr.db, усі $regCount регіонів)\n\n" +
                "Почати завантаження?"
            } else {
                "Will download for $displayName ($regSuffix):\n" +
                worldItem +
                "• Vector map (.map)\n" +
                "• Points of Interest (.poi)\n" +
                "• BRouter navigation segments (${segments.size} files .rd5)\n" +
                "• Address database (addr.db, all $regCount regions)\n\n" +
                "Start download?"
            }
        }

        android.app.AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton(actionBtn) { _, _ ->
                onDownloadStarted()
                InitialSetupDialog.startCountryDownload(activity, country)
            }
            .setNegativeButton(if (isUk) "Скасувати" else "Cancel", null)
            .show()
    }
}
