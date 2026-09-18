package com.olegskal.mushroom.mushrooms

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.olegskal.mushroom.ui.UiUtils
import com.olegskal.mushroom.util.AppPrefs

class MushroomEncyclopediaTab(
    private val activity: Activity,
    private var currentLang: String = "uk"
) {

    val view: LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setPadding(12, 12, 12, 12)
    }

    private val searchEditText: EditText
    private val itemsContainer: LinearLayout
    private val statusTextView: TextView
    private val regionToggleBtn: Button
    private val filterSpinner: Spinner
    private val scrollView: ScrollView

    private var currentQuery = ""
    private var isUkraineOnly = true
    private var currentFilter = "all"
    private var currentPage = 1
    private var isLoading = false
    private var hasMore = true
    private val allLoadedTaxa = mutableListOf<MushroomTaxon>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var searchDebounceRunnable: Runnable? = null

    private val filterKeys = listOf(
        "all",
        "edible",
        "cond-edible",
        "toxic",
        "deadly",
        "toxic_deadly",
        "tubes",
        "gills"
    )

    init {
        val density = activity.resources.displayMetrics.density

        // Load saved preferences
        isUkraineOnly = AppPrefs.getMushroomIsUkraine(activity)
        currentFilter = AppPrefs.getMushroomFilter(activity)

        // 1. Search Bar & Clear Button
        val searchRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1B2A22"))
            setPadding(12, 6, 12, 6)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 10)
            }
        }

        searchEditText = EditText(activity).apply {
            hint = if (currentLang == "uk") "Пошук (білий, печериця, boletus, amanita)..." else "Search (porcini, amanita, boletus)..."
            setHintTextColor(Color.parseColor("#6B7280"))
            setTextColor(Color.WHITE)
            textSize = 14f
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            isSingleLine = true
        }

        val btnClearSearch = Button(activity).apply {
            text = "✕"
            setTextColor(Color.LTGRAY)
            setBackgroundColor(Color.TRANSPARENT)
            textSize = 14f
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(90, 90)
            setOnClickListener {
                searchEditText.setText("")
            }
        }

        searchRow.addView(searchEditText)
        searchRow.addView(btnClearSearch)
        view.addView(searchRow)

        // 2. Region Toggle & Dropdown Filter Row
        val controlsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 10)
            }
        }

        regionToggleBtn = Button(activity).apply {
            text = if (isUkraineOnly) "🇺🇦 Україна" else "🌍 Світ"
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setBackgroundColor(Color.parseColor("#22362C"))
            val padH = (12 * density).toInt()
            val padV = (8 * density).toInt()
            setPadding(padH, padV, padH, padV)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 8, 0)
            }
            setOnClickListener {
                isUkraineOnly = !isUkraineOnly
                text = if (isUkraineOnly) "🇺🇦 Україна" else "🌍 Світ"
                AppPrefs.setMushroomIsUkraine(activity, isUkraineOnly)
                resetAndReload()
            }
        }
        controlsRow.addView(regionToggleBtn)

        filterSpinner = Spinner(activity).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setBackgroundColor(Color.parseColor("#1B2A22"))
            setPadding(8, 8, 8, 8)
        }
        setupFilterSpinner()
        controlsRow.addView(filterSpinner)
        view.addView(controlsRow)

        // 3. Status text
        statusTextView = TextView(activity).apply {
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 12)
            visibility = View.GONE
        }
        view.addView(statusTextView)

        // 4. Scrollable List of Mushrooms with Infinite Scroll
        scrollView = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        itemsContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(itemsContainer)
        view.addView(scrollView)

        // Infinite scroll listener
        scrollView.viewTreeObserver.addOnScrollChangedListener {
            val child = scrollView.getChildAt(0)
            if (child != null) {
                val diff = child.bottom - (scrollView.height + scrollView.scrollY)
                if (diff <= 600 && !isLoading && hasMore) {
                    loadNextPage()
                }
            }
        }

        // 5. Search text watcher (debounce 400ms)
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchDebounceRunnable?.let { mainHandler.removeCallbacks(it) }
                val runnable = Runnable {
                    currentQuery = s?.toString()?.trim() ?: ""
                    resetAndReload()
                }
                searchDebounceRunnable = runnable
                mainHandler.postDelayed(runnable, 400)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Initial load
        resetAndReload()
    }

    private fun getFilterLabels(): List<String> {
        return if (currentLang == "uk") {
            listOf(
                "📋 Всі види",
                "🟢 Їстівні",
                "🟡 Умовно-їстівні",
                "🟠 Отруйні",
                "🔴 Смертельно отруйні",
                "⚠️ Отруйні та смертельні",
                "🧽 Трубчасті (губка)",
                "🍂 Пластинчасті"
            )
        } else {
            listOf(
                "📋 All species",
                "🟢 Edible",
                "🟡 Cond. Edible",
                "🟠 Toxic",
                "🔴 Deadly",
                "⚠️ Toxic & Deadly",
                "🧽 Tubes / Porous",
                "🍂 Gilled"
            )
        }
    }

    private fun setupFilterSpinner() {
        val labels = getFilterLabels()
        val adapter = object : ArrayAdapter<String>(activity, android.R.layout.simple_spinner_item, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = super.getView(position, convertView, parent) as TextView
                tv.setTextColor(Color.WHITE)
                tv.textSize = 13.5f
                tv.setTypeface(null, Typeface.BOLD)
                tv.setPadding(12, 6, 12, 6)
                return tv
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = super.getDropDownView(position, convertView, parent) as TextView
                tv.setTextColor(Color.WHITE)
                tv.setBackgroundColor(Color.parseColor("#1B2A22"))
                tv.textSize = 14f
                val pad = (12 * activity.resources.displayMetrics.density).toInt()
                tv.setPadding(pad, pad, pad, pad)
                return tv
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        filterSpinner.adapter = adapter

        val initialIdx = filterKeys.indexOf(currentFilter).let { if (it >= 0) it else 0 }
        filterSpinner.setSelection(initialIdx, false)

        filterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newFilter = filterKeys.getOrElse(position) { "all" }
                if (newFilter != currentFilter) {
                    currentFilter = newFilter
                    AppPrefs.setMushroomFilter(activity, currentFilter)
                    applyFilter()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    fun setLanguage(lang: String) {
        currentLang = lang
        searchEditText.hint = if (currentLang == "uk") "Пошук (білий, печериця, boletus, amanita)..." else "Search (porcini, amanita, boletus)..."
        regionToggleBtn.text = if (isUkraineOnly) "🇺🇦 Україна" else "🌍 Світ"
        setupFilterSpinner()
        applyFilter()
    }

    private fun resetAndReload() {
        currentPage = 1
        allLoadedTaxa.clear()
        itemsContainer.removeAllViews()
        hasMore = true
        loadNextPage()
    }

    private fun loadNextPage() {
        if (isLoading || !hasMore) return
        isLoading = true
        statusTextView.visibility = View.VISIBLE
        statusTextView.text = if (currentLang == "uk") "Завантаження з iNaturalist..." else "Loading from iNaturalist..."

        val callback: (List<MushroomTaxon>, Boolean) -> Unit = { taxa, moreAvailable ->
            isLoading = false
            statusTextView.visibility = View.GONE
            hasMore = moreAvailable

            if (taxa.isNotEmpty()) {
                for (t in taxa) {
                    if (!allLoadedTaxa.any { it.scientificName.equals(t.scientificName, ignoreCase = true) }) {
                        allLoadedTaxa.add(t)
                    }
                }
                currentPage++
            }

            applyFilter()

            // If filtered list is still small and more pages exist, automatically fetch next page to fill screen
            if (itemsContainer.childCount < 8 && hasMore && !isLoading) {
                mainHandler.postDelayed({ loadNextPage() }, 200)
            }
        }

        if (currentQuery.isNotEmpty()) {
            MushroomApiClient.searchTaxa(currentQuery, currentLang, currentPage, 24, callback)
        } else {
            MushroomApiClient.getPopularTaxa(isUkraineOnly, currentLang, currentPage, 24, callback)
        }
    }

    private fun matchesFilter(taxon: MushroomTaxon, filterKey: String): Boolean {
        val meta = MycoKnowledge.resolveMetadata(taxon.scientificName)
        val edibility = if (taxon.edibility != "unknown") taxon.edibility else meta.edibility
        val hymenium = if (meta.hymenium != "other") meta.hymenium else taxon.hymenium

        return when (filterKey) {
            "all" -> true
            "edible" -> edibility == "edible"
            "cond-edible" -> edibility == "cond-edible"
            "toxic" -> edibility == "toxic"
            "deadly" -> edibility == "deadly"
            "toxic_deadly" -> edibility == "toxic" || edibility == "deadly"
            "tubes" -> hymenium == "tubes"
            "gills" -> hymenium == "gills"
            else -> true
        }
    }

    private fun applyFilter() {
        itemsContainer.removeAllViews()

        val matching = allLoadedTaxa.filter { matchesFilter(it, currentFilter) }

        if (matching.isEmpty()) {
            if (isLoading) {
                statusTextView.visibility = View.VISIBLE
                statusTextView.text = if (currentLang == "uk") "Пошук грибів за фільтром..." else "Searching species for filter..."
            } else if (!hasMore) {
                statusTextView.visibility = View.VISIBLE
                statusTextView.text = if (currentLang == "uk") "Грибів за обраним фільтром не знайдено" else "No species found matching the selected filter"
            }
        } else {
            statusTextView.visibility = View.GONE
            for (taxon in matching) {
                renderCard(taxon)
            }
        }
    }

    private fun renderCard(taxon: MushroomTaxon) {
        val density = activity.resources.displayMetrics.density
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1B2A22"))
            val pad = (8 * density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 4, 0, 6)
            }
            setOnClickListener {
                MushroomApiClient.getTaxonDetails(taxon.id, taxon.scientificName, currentLang) { detailedTaxon ->
                    MushroomDetailDialog.show(activity, detailedTaxon ?: taxon, currentLang)
                }
            }
        }

        // Thumbnail
        val thumbSize = (64 * density).toInt()
        val thumbIv = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(thumbSize, thumbSize)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#0C130F"))
        }
        MushroomApiClient.loadBitmap(taxon.defaultPhotoUrl) { bmp ->
            if (bmp != null) thumbIv.setImageBitmap(bmp)
        }
        card.addView(thumbIv)

        // Info column
        val infoCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins((12 * density).toInt(), 0, (8 * density).toInt(), 0)
            }
        }

        val tvCommon = TextView(activity).apply {
            text = taxon.commonName
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
        }

        val tvScientific = TextView(activity).apply {
            text = taxon.scientificName
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 12f
            setTypeface(null, Typeface.ITALIC)
        }

        val meta = MycoKnowledge.resolveMetadata(taxon.scientificName)
        val edibility = if (taxon.edibility != "unknown") taxon.edibility else meta.edibility

        val badgeRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 4, 0, 0)
        }

        val tvBadge = TextView(activity).apply {
            text = MycoKnowledge.getEdibilityLabel(edibility, currentLang)
            setTextColor(Color.WHITE)
            textSize = 10.5f
            setTypeface(null, Typeface.BOLD)
            setBackgroundColor(MycoKnowledge.getEdibilityColor(edibility))
            setPadding(10, 4, 10, 4)
        }
        badgeRow.addView(tvBadge)

        if (!meta.lookalikesUk.isNullOrEmpty()) {
            val tvLookalikeAlert = TextView(activity).apply {
                text = if (currentLang == "uk") "⚠️ Двійники" else "⚠️ Lookalikes"
                setTextColor(Color.parseColor("#FCA5A5"))
                textSize = 10.5f
                setTypeface(null, Typeface.BOLD)
                setBackgroundColor(Color.parseColor("#7F1D1D"))
                setPadding(10, 4, 10, 4)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(8, 0, 8, 0)
                }
            }
            badgeRow.addView(tvLookalikeAlert)
        }

        val hymeniumIcon = if (meta.hymenium == "tubes" || taxon.hymenium == "tubes") "🧽" else "🍂"
        val tvHymenium = TextView(activity).apply {
            text = hymeniumIcon
            textSize = 13f
            setPadding(4, 0, 4, 0)
        }
        badgeRow.addView(tvHymenium)

        infoCol.addView(tvCommon)
        infoCol.addView(tvScientific)
        infoCol.addView(badgeRow)
        card.addView(infoCol)

        val arrowTv = TextView(activity).apply {
            text = "›"
            setTextColor(Color.parseColor("#6B7280"))
            textSize = 22f
        }
        card.addView(arrowTv)

        itemsContainer.addView(card)
    }
}
