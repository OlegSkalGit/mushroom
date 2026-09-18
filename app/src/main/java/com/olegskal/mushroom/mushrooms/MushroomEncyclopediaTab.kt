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
    private val btnLoadMore: Button
    private val regionToggleBtn: Button

    private var currentQuery = ""
    private var isUkraineOnly = true
    private var currentFilter = "all" // all, edible, cond-edible, toxic, deadly, tubes, gills
    private var currentPage = 1
    private var isLoading = false
    private var hasMore = true
    private val loadedTaxa = mutableListOf<MushroomTaxon>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var searchDebounceRunnable: Runnable? = null

    init {
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

        // 2. Region & Quick Filters Bar
        val filterScroll = HorizontalScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 10)
            }
            isHorizontalScrollBarEnabled = false
        }
        val filterRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        regionToggleBtn = createFilterChip(if (isUkraineOnly) "🇺🇦 Україна" else "🌍 Світ", true) { btn ->
            isUkraineOnly = !isUkraineOnly
            btn.text = if (isUkraineOnly) "🇺🇦 Україна" else "🌍 Світ"
            resetAndReload()
        }
        filterRow.addView(regionToggleBtn)

        val chips = listOf(
            "all" to (if (currentLang == "uk") "Всі види" else "All species"),
            "edible" to (if (currentLang == "uk") "🟢 Їстівні" else "🟢 Edible"),
            "cond-edible" to (if (currentLang == "uk") "🟡 Умовно-їстівні" else "🟡 Cond. Edible"),
            "toxic" to (if (currentLang == "uk") "🟠 Отруйні" else "🟠 Toxic"),
            "deadly" to (if (currentLang == "uk") "🔴 Смертельно отруйні" else "🔴 Deadly"),
            "tubes" to (if (currentLang == "uk") "🧽 Трубчасті" else "🧽 Tubes"),
            "gills" to (if (currentLang == "uk") "🍂 Пластинчасті" else "🍂 Gills")
        )

        val chipButtons = mutableListOf<Button>()
        for ((filterKey, label) in chips) {
            val btn = createFilterChip(label, filterKey == currentFilter) {
                currentFilter = filterKey
                chipButtons.forEach { it.setBackgroundColor(Color.parseColor("#22362C")) }
                // it.setBackgroundColor(Color.parseColor("#10B981"))
                resetAndReload()
            }
            chipButtons.add(btn)
            filterRow.addView(btn)
        }
        filterScroll.addView(filterRow)
        view.addView(filterScroll)

        // 3. Status text
        statusTextView = TextView(activity).apply {
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 12)
            visibility = View.GONE
        }
        view.addView(statusTextView)

        // 4. Scrollable List of Mushrooms
        val scrollView = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val scrollContent = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        itemsContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollContent.addView(itemsContainer)

        btnLoadMore = UiUtils.createStyledButton(activity, if (currentLang == "uk") "Завантажити ще гриби..." else "Load more mushrooms...") {
            loadNextPage()
        }.apply {
            visibility = View.GONE
        }
        scrollContent.addView(btnLoadMore)

        scrollView.addView(scrollContent)
        view.addView(scrollView)

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

    fun setLanguage(lang: String) {
        currentLang = lang
        searchEditText.hint = if (currentLang == "uk") "Пошук (білий, печериця, boletus, amanita)..." else "Search (porcini, amanita, boletus)..."
        btnLoadMore.text = if (currentLang == "uk") "Завантажити ще гриби..." else "Load more mushrooms..."
        resetAndReload()
    }

    private fun createFilterChip(text: String, isSelected: Boolean, onClick: (Button) -> Unit): Button {
        val density = activity.resources.displayMetrics.density
        return Button(activity).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 12f
            setBackgroundColor(if (isSelected) Color.parseColor("#10B981") else Color.parseColor("#22362C"))
            val padH = (12 * density).toInt()
            val padV = (6 * density).toInt()
            setPadding(padH, padV, padH, padV)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(4, 0, 4, 0)
            }
            setOnClickListener { onClick(this) }
        }
    }

    private fun resetAndReload() {
        currentPage = 1
        loadedTaxa.clear()
        itemsContainer.removeAllViews()
        hasMore = true
        btnLoadMore.visibility = View.GONE
        loadNextPage()
    }

    private fun loadNextPage() {
        if (isLoading) return
        isLoading = true
        statusTextView.visibility = View.VISIBLE
        statusTextView.text = if (currentLang == "uk") "Завантаження грибів з iNaturalist..." else "Loading mushrooms from iNaturalist..."

        val callback: (List<MushroomTaxon>, Boolean) -> Unit = { taxa, moreAvailable ->
            isLoading = false
            statusTextView.visibility = View.GONE
            hasMore = moreAvailable

            if (taxa.isEmpty() && loadedTaxa.isEmpty()) {
                statusTextView.visibility = View.VISIBLE
                statusTextView.text = if (currentLang == "uk") "Грибів не знайдено за вашим запитом" else "No species found matching your query"
            } else {
                loadedTaxa.addAll(taxa)
                renderTaxa(taxa)
                currentPage++
                btnLoadMore.visibility = if (hasMore) View.VISIBLE else View.GONE
            }
        }

        if (currentQuery.isNotEmpty()) {
            MushroomApiClient.searchTaxa(currentQuery, currentLang, currentPage, 24, callback)
        } else {
            MushroomApiClient.getPopularTaxa(isUkraineOnly, currentLang, currentPage, 24, callback)
        }
    }

    private fun renderTaxa(taxa: List<MushroomTaxon>) {
        val density = activity.resources.displayMetrics.density
        val filtered = taxa.filter { taxon ->
            val meta = MycoKnowledge.resolveMetadata(taxon.scientificName)
            val edibility = if (taxon.edibility != "unknown") taxon.edibility else meta.edibility
            val hymenium = if (meta.hymenium != "other") meta.hymenium else taxon.hymenium

            when (currentFilter) {
                "all" -> true
                "edible" -> edibility == "edible"
                "cond-edible" -> edibility == "cond-edible"
                "toxic" -> edibility == "toxic"
                "deadly" -> edibility == "deadly"
                "tubes" -> hymenium == "tubes"
                "gills" -> hymenium == "gills"
                else -> true
            }
        }

        for (taxon in filtered) {
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
                    text = "⚠️ Двійники"
                    setTextColor(Color.parseColor("#FCA5A5"))
                    textSize = 10.5f
                    setTypeface(null, Typeface.BOLD)
                    setBackgroundColor(Color.parseColor("#7F1D1D"))
                    setPadding(10, 4, 10, 4)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(8, 0, 0, 0)
                    }
                }
                badgeRow.addView(tvLookalikeAlert)
            }

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
}
