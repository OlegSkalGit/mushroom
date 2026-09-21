package com.olegskal.mushroom.mushrooms

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
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
    private val modeToggleBtn: Button
    private val filterButton: LinearLayout
    private val tvFilterLabel: TextView
    private val viewModeSpinner: Spinner
    private val dbStatusRow: LinearLayout
    private val tvDbStatus: TextView
    private val btnDownloadDb: Button
    private val scrollView: ScrollView

    private var currentQuery = ""
    private var isOfflineMode = false
    private var currentEdibilityFilter = "all"
    private var currentHymeniumFilter = "all"
    private var currentViewMode = "list"
    private val viewModeKeys = listOf("list", "grid")
    private var currentPage = 1
    private var isLoading = false
    private var hasMore = true
    private var searchGeneration = 0
    private val allLoadedTaxa = mutableListOf<MushroomTaxon>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var searchDebounceRunnable: Runnable? = null

    init {
        val density = activity.resources.displayMetrics.density
        MushroomApiClient.setContext(activity)

        // Load saved preferences
        isOfflineMode = AppPrefs.getMushroomOfflineMode(activity)
        val legacyFilter = AppPrefs.getMushroomFilter(activity)
        currentEdibilityFilter = AppPrefs.getMushroomEdibilityFilter(activity)
        currentHymeniumFilter = AppPrefs.getMushroomHymeniumFilter(activity)
        if (currentEdibilityFilter == "all" && currentHymeniumFilter == "all" && legacyFilter != "all") {
            if (legacyFilter == "tubes" || legacyFilter == "gills") {
                currentHymeniumFilter = legacyFilter
            } else {
                currentEdibilityFilter = legacyFilter
            }
        }
        currentViewMode = AppPrefs.getMushroomViewMode(activity)

        // 1. Controls Row (Mode Toggle, Filter & View Mode) - Placed ABOVE search bar
        val controlsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 8)
            }
        }

        modeToggleBtn = Button(activity).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setBackgroundColor(Color.parseColor("#22362C"))
            val padH = (8 * density).toInt()
            val padV = (6 * density).toInt()
            setPadding(padH, padV, padH, padV)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 6, 0)
            }
            setOnClickListener {
                isOfflineMode = !isOfflineMode
                AppPrefs.setMushroomOfflineMode(activity, isOfflineMode)
                updateModeToggleText()
                updateDbStatusBadge()
                resetAndReload()
            }
        }
        updateModeToggleText()
        controlsRow.addView(modeToggleBtn)

        filterButton = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1B2A22"))
            val padH = (10 * density).toInt()
            val padV = (8 * density).toInt()
            setPadding(padH, padV, padH, padV)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 6, 0)
            }
            setOnClickListener {
                showFilterDialog()
            }
        }

        tvFilterLabel = TextView(activity).apply {
            setTextColor(Color.WHITE)
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        filterButton.addView(tvFilterLabel)

        val tvFilterArrow = TextView(activity).apply {
            text = "▾"
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 12f
            setPadding((4 * density).toInt(), 0, 0, 0)
        }
        filterButton.addView(tvFilterArrow)

        updateFilterButtonText()
        controlsRow.addView(filterButton)

        viewModeSpinner = Spinner(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundColor(Color.parseColor("#1B2A22"))
            setPadding(6, 6, 6, 6)
        }
        setupViewModeSpinner()
        controlsRow.addView(viewModeSpinner)
        view.addView(controlsRow)

        // 2. Database Status Banner (shown in offline mode when DB is missing)
        dbStatusRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padH = (10 * density).toInt()
            val padV = (6 * density).toInt()
            setPadding(padH, padV, padH, padV)
            setBackgroundColor(Color.parseColor("#231C14"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 8)
            }
            visibility = View.GONE
        }

        tvDbStatus = TextView(activity).apply {
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F59E0B"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        dbStatusRow.addView(tvDbStatus)

        btnDownloadDb = UiUtils.createStyledButton(activity, if (currentLang == "uk") "Завантажити" else "Download") {
            promptAndDownloadDatabase()
        }.apply {
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            val padBtnH = (12 * density).toInt()
            val padBtnV = (4 * density).toInt()
            setPadding(padBtnH, padBtnV, padBtnH, padBtnV)
            setBackgroundColor(Color.parseColor("#059669"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(6, 0, 0, 0)
            }
        }
        dbStatusRow.addView(btnDownloadDb)
        view.addView(dbStatusRow)
        updateDbStatusBadge()

        // 3. Search Bar & Clear Button (Placed BELOW controls)
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
                if (diff <= 800 && !isLoading && hasMore) {
                    loadNextPage()
                }
            }
        }

        // 5. Search text watcher (debounce 350ms)
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchDebounceRunnable?.let { mainHandler.removeCallbacks(it) }
                val query = s?.toString()?.trim() ?: ""
                val runnable = Runnable {
                    if (query != currentQuery) {
                        currentQuery = query
                        resetAndReload()
                    }
                }
                searchDebounceRunnable = runnable
                mainHandler.postDelayed(runnable, 350)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Initial load
        resetAndReload()
    }

    private fun getEdibilityTitle(key: String): String {
        return when (key) {
            "edible" -> if (currentLang == "uk") "🟢 Їстівні" else "🟢 Edible"
            "cond-edible" -> if (currentLang == "uk") "🟡 Умовно-їстівні" else "🟡 Cond. Edible"
            "toxic" -> if (currentLang == "uk") "🟠 Отруйні" else "🟠 Toxic"
            "deadly" -> if (currentLang == "uk") "🔴 Смертельно отруйні" else "🔴 Deadly"
            "toxic_deadly" -> if (currentLang == "uk") "⚠️ Отруйні/Смертельні" else "⚠️ Toxic & Deadly"
            else -> if (currentLang == "uk") "Всі види" else "All species"
        }
    }

    private fun getHymeniumTitle(key: String): String {
        return when (key) {
            "tubes" -> if (currentLang == "uk") "🧽 Трубчасті" else "🧽 Tubes"
            "gills" -> if (currentLang == "uk") "🍂 Пластинчасті" else "🍂 Gilled"
            else -> if (currentLang == "uk") "Всі типи" else "All types"
        }
    }

    private fun updateFilterButtonText() {
        val text = when {
            currentEdibilityFilter == "all" && currentHymeniumFilter == "all" -> {
                if (currentLang == "uk") "📋 Всі види" else "📋 All species"
            }
            currentEdibilityFilter != "all" && currentHymeniumFilter == "all" -> {
                getEdibilityTitle(currentEdibilityFilter)
            }
            currentEdibilityFilter == "all" && currentHymeniumFilter != "all" -> {
                getHymeniumTitle(currentHymeniumFilter)
            }
            else -> {
                "${getEdibilityTitle(currentEdibilityFilter)} • ${getHymeniumTitle(currentHymeniumFilter)}"
            }
        }
        tvFilterLabel.text = text
    }

    private fun showFilterDialog() {
        val density = activity.resources.displayMetrics.density
        var tempEdibility = currentEdibilityFilter
        var tempHymenium = currentHymeniumFilter

        val scroll = ScrollView(activity)
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1B2A22"))
            val pad = (16 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(container)

        val emerald = ColorStateList.valueOf(Color.parseColor("#10B981"))

        // Section 1: Їстівність
        val tvHeader1 = TextView(activity).apply {
            text = if (currentLang == "uk") "Їстівність:" else "Edibility:"
            setTextColor(Color.parseColor("#10B981"))
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, (6 * density).toInt())
        }
        container.addView(tvHeader1)

        val edibilityOptions = listOf(
            "all" to (if (currentLang == "uk") "📋 Всі види (без обмежень)" else "📋 All species"),
            "edible" to (if (currentLang == "uk") "🟢 Їстівні" else "🟢 Edible"),
            "cond-edible" to (if (currentLang == "uk") "🟡 Умовно-їстівні" else "🟡 Conditionally Edible"),
            "toxic" to (if (currentLang == "uk") "🟠 Отруйні" else "🟠 Toxic"),
            "deadly" to (if (currentLang == "uk") "🔴 Смертельно отруйні" else "🔴 Deadly"),
            "toxic_deadly" to (if (currentLang == "uk") "⚠️ Отруйні та смертельні" else "⚠️ Toxic & Deadly")
        )

        val edibilityRadioGroup = RadioGroup(activity).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, 0, 0, (14 * density).toInt())
        }

        for ((key, label) in edibilityOptions) {
            val rb = RadioButton(activity).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 13.5f
                buttonTintList = emerald
                id = View.generateViewId()
                isChecked = (key == tempEdibility)
                setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
                setOnClickListener {
                    tempEdibility = key
                }
            }
            edibilityRadioGroup.addView(rb)
        }
        container.addView(edibilityRadioGroup)

        // Divider
        val divider = View(activity).apply {
            setBackgroundColor(Color.parseColor("#2D3748"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (1 * density).toInt()).apply {
                setMargins(0, 0, 0, (12 * density).toInt())
            }
        }
        container.addView(divider)

        // Section 2: Гіменофор
        val tvHeader2 = TextView(activity).apply {
            text = if (currentLang == "uk") "Гіменофор (будова шапинки знизу):" else "Hymenophore (underside):"
            setTextColor(Color.parseColor("#10B981"))
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, (6 * density).toInt())
        }
        container.addView(tvHeader2)

        val hymeniumOptions = listOf(
            "all" to (if (currentLang == "uk") "📋 Всі типи" else "📋 All types"),
            "tubes" to (if (currentLang == "uk") "🧽 Трубчасті (губка, пори)" else "🧽 Tubes / Porous"),
            "gills" to (if (currentLang == "uk") "🍂 Пластинчасті" else "🍂 Gilled")
        )

        val hymeniumRadioGroup = RadioGroup(activity).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, 0, 0, (8 * density).toInt())
        }

        for ((key, label) in hymeniumOptions) {
            val rb = RadioButton(activity).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 13.5f
                buttonTintList = emerald
                id = View.generateViewId()
                isChecked = (key == tempHymenium)
                setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
                setOnClickListener {
                    tempHymenium = key
                }
            }
            hymeniumRadioGroup.addView(rb)
        }
        container.addView(hymeniumRadioGroup)

        AlertDialog.Builder(activity)
            .setTitle(if (currentLang == "uk") "Фільтри енциклопедії" else "Encyclopedia Filters")
            .setView(scroll)
            .setNeutralButton(if (currentLang == "uk") "Скинути всі" else "Reset") { _, _ ->
                currentEdibilityFilter = "all"
                currentHymeniumFilter = "all"
                AppPrefs.setMushroomEdibilityFilter(activity, "all")
                AppPrefs.setMushroomHymeniumFilter(activity, "all")
                AppPrefs.setMushroomFilter(activity, "all")
                updateFilterButtonText()
                if (isOfflineMode) resetAndReload() else applyFilter()
            }
            .setNegativeButton(if (currentLang == "uk") "Скасувати" else "Cancel", null)
            .setPositiveButton(if (currentLang == "uk") "Застосувати" else "Apply") { _, _ ->
                if (tempEdibility != currentEdibilityFilter || tempHymenium != currentHymeniumFilter) {
                    currentEdibilityFilter = tempEdibility
                    currentHymeniumFilter = tempHymenium
                    AppPrefs.setMushroomEdibilityFilter(activity, currentEdibilityFilter)
                    AppPrefs.setMushroomHymeniumFilter(activity, currentHymeniumFilter)
                    AppPrefs.setMushroomFilter(activity, currentEdibilityFilter)
                    updateFilterButtonText()
                    if (isOfflineMode) resetAndReload() else applyFilter()
                }
            }
            .show()
    }

    private fun updateModeToggleText() {
        modeToggleBtn.text = if (isOfflineMode) {
            if (currentLang == "uk") "💾 Без інтернету" else "💾 Offline"
        } else {
            if (currentLang == "uk") "🌐 Інтернет" else "🌐 Online"
        }
    }

    private fun updateDbStatusBadge() {
        if (!isOfflineMode) {
            dbStatusRow.visibility = View.GONE
            return
        }
        val available = MushroomDatabaseManager.isDatabaseAvailable(activity)
        if (available) {
            dbStatusRow.visibility = View.GONE
        } else {
            dbStatusRow.visibility = View.VISIBLE
            tvDbStatus.text = if (currentLang == "uk") {
                "База не завантажена. Потрібно завантажити базу для офлайн-режиму."
            } else {
                "Database not downloaded. Please download database for offline mode."
            }
            btnDownloadDb.text = if (currentLang == "uk") "Завантажити" else "Download"
        }
    }

    private fun promptAndDownloadDatabase(onReady: ((Boolean) -> Unit)? = null) {
        val title = if (currentLang == "uk") "Завантаження бази грибів" else "Download Mushroom Database"
        val msg = if (currentLang == "uk") {
            "Для роботи енциклопедії без інтернету потрібна локальна база даних (~534 МБ). Завантажити зараз?"
        } else {
            "Offline mushroom encyclopedia requires local database (~534 MB). Download now?"
        }

        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(msg)
            .setCancelable(false)
            .setNegativeButton(if (currentLang == "uk") "Скасувати" else "Cancel") { d, _ ->
                d.dismiss()
                onReady?.invoke(false)
            }
            .setPositiveButton(if (currentLang == "uk") "Завантажити" else "Download") { d, _ ->
                d.dismiss()
                showDbDownloadProgressDialog(onReady)
            }
            .show()
    }

    private fun showDbDownloadProgressDialog(onReady: ((Boolean) -> Unit)? = null) {
        val progressDialogView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 24, 30, 24)
            setBackgroundColor(Color.parseColor("#1B2A22"))
        }

        val tvTitle = TextView(activity).apply {
            text = if (currentLang == "uk") "Завантаження бази даних..." else "Downloading database..."
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 12)
        }

        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 8)
            }
        }

        val tvPercent = TextView(activity).apply {
            text = "0%"
            setTextColor(Color.parseColor("#10B981"))
            textSize = 13f
            gravity = Gravity.RIGHT
        }

        progressDialogView.addView(tvTitle)
        progressDialogView.addView(progressBar)
        progressDialogView.addView(tvPercent)

        val dialog = AlertDialog.Builder(activity)
            .setView(progressDialogView)
            .setCancelable(false)
            .create()

        dialog.show()

        MushroomDatabaseManager.downloadDatabase(
            context = activity,
            onProgress = { percent, statusText ->
                mainHandler.post {
                    progressBar.progress = percent
                    tvPercent.text = statusText
                }
            },
            onComplete = { success, errorMsg ->
                mainHandler.post {
                    dialog.dismiss()
                    if (success) {
                        updateDbStatusBadge()
                        Toast.makeText(activity, if (currentLang == "uk") "Базу успішно завантажено!" else "Database downloaded successfully!", Toast.LENGTH_SHORT).show()
                        onReady?.invoke(true)
                        resetAndReload()
                    } else {
                        Toast.makeText(activity, "Download failed: $errorMsg", Toast.LENGTH_LONG).show()
                        onReady?.invoke(false)
                    }
                }
            }
        )
    }

    private fun getViewModeLabels(): List<String> {
        return if (currentLang == "uk") {
            listOf("📋 Список", "🔲 Картки")
        } else {
            listOf("📋 List", "🔲 Cards")
        }
    }

    private fun setupViewModeSpinner() {
        val labels = getViewModeLabels()
        val adapter = object : ArrayAdapter<String>(activity, android.R.layout.simple_spinner_item, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = super.getView(position, convertView, parent) as TextView
                tv.setTextColor(Color.WHITE)
                tv.textSize = 12.5f
                tv.setTypeface(null, Typeface.BOLD)
                tv.setPadding(6, 6, 6, 6)
                return tv
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = super.getDropDownView(position, convertView, parent) as TextView
                tv.setTextColor(Color.WHITE)
                tv.setBackgroundColor(Color.parseColor("#1B2A22"))
                tv.textSize = 13.5f
                val pad = (10 * activity.resources.displayMetrics.density).toInt()
                tv.setPadding(pad, pad, pad, pad)
                return tv
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        viewModeSpinner.adapter = adapter

        val initialIdx = viewModeKeys.indexOf(currentViewMode).let { if (it >= 0) it else 0 }
        viewModeSpinner.setSelection(initialIdx, false)

        viewModeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newMode = viewModeKeys.getOrElse(position) { "list" }
                if (newMode != currentViewMode) {
                    currentViewMode = newMode
                    AppPrefs.setMushroomViewMode(activity, currentViewMode)
                    applyFilter()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    fun setLanguage(lang: String) {
        currentLang = lang
        searchEditText.hint = if (currentLang == "uk") "Пошук (білий, печериця, boletus, amanita)..." else "Search (porcini, amanita, boletus)..."
        updateModeToggleText()
        updateDbStatusBadge()
        updateFilterButtonText()
        setupViewModeSpinner()
        resetAndReload()
    }

    fun setSearchQuery(query: String) {
        val clean = query.trim()
        searchDebounceRunnable?.let { mainHandler.removeCallbacks(it) }
        currentQuery = clean
        currentEdibilityFilter = "all"
        currentHymeniumFilter = "all"
        AppPrefs.setMushroomEdibilityFilter(activity, "all")
        AppPrefs.setMushroomHymeniumFilter(activity, "all")
        AppPrefs.setMushroomFilter(activity, "all")
        updateFilterButtonText()
        searchEditText.setText(clean)
        searchEditText.setSelection(clean.length)
        resetAndReload()
    }

    private fun resetAndReload() {
        searchGeneration++
        currentPage = 1
        allLoadedTaxa.clear()
        itemsContainer.removeAllViews()
        hasMore = true
        isLoading = false
        loadNextPage()
    }

    private fun loadNextPage() {
        if (isLoading || !hasMore) return
        isLoading = true
        val requestGen = searchGeneration
        statusTextView.visibility = View.VISIBLE
        statusTextView.text = if (isOfflineMode) {
            if (currentLang == "uk") "Завантаження з локальної бази..." else "Loading from local database..."
        } else {
            if (currentLang == "uk") "Завантаження з iNaturalist..." else "Loading from iNaturalist..."
        }

        val callback: (List<MushroomTaxon>, Boolean) -> Unit = { taxa, moreAvailable ->
            if (requestGen == searchGeneration) {
                isLoading = false
                statusTextView.visibility = View.GONE
                hasMore = moreAvailable

                if (taxa.isNotEmpty()) {
                    for (t in taxa) {
                        if (!allLoadedTaxa.any { it.id == t.id || it.scientificName.equals(t.scientificName, ignoreCase = true) }) {
                            allLoadedTaxa.add(t)
                        }
                    }
                    currentPage++
                }

                applyFilter()
            }
        }

        if (isOfflineMode) {
            if (!MushroomDatabaseManager.isDatabaseAvailable(activity)) {
                isLoading = false
                hasMore = false
                statusTextView.visibility = View.VISIBLE
                statusTextView.text = if (currentLang == "uk") {
                    "База не завантажена. Натисніть 'Завантажити' вище."
                } else {
                    "Database not downloaded. Click 'Download' above."
                }
                return
            }

            Thread {
                val (taxa, moreAvailable) = MushroomDatabaseManager.queryTaxa(
                    context = activity,
                    query = currentQuery,
                    edibilityFilter = currentEdibilityFilter,
                    hymeniumFilter = currentHymeniumFilter,
                    lang = currentLang,
                    page = currentPage,
                    perPage = 24
                )
                mainHandler.post {
                    callback(taxa, moreAvailable)
                }
            }.start()
            return
        }

        if (currentQuery.isNotEmpty()) {
            MushroomApiClient.searchTaxa(currentQuery, currentLang, currentPage, 24, callback)
        } else {
            MushroomApiClient.getPopularTaxa(false, currentLang, currentPage, 24, callback)
        }
    }

    private fun matchesFilter(taxon: MushroomTaxon): Boolean {
        val meta = MycoKnowledge.resolveMetadata(taxon.scientificName)
        val edibility = if (meta.edibility != "unknown") meta.edibility else taxon.edibility
        val hymenium = if (meta.hymenium != "other") meta.hymenium else taxon.hymenium

        val matchesEdibility = when (currentEdibilityFilter) {
            "all" -> true
            "edible" -> edibility == "edible"
            "cond-edible" -> edibility == "cond-edible"
            "toxic" -> edibility == "toxic"
            "deadly" -> edibility == "deadly"
            "toxic_deadly" -> edibility == "toxic" || edibility == "deadly"
            else -> true
        }

        val matchesHymenium = when (currentHymeniumFilter) {
            "all" -> true
            "tubes" -> hymenium == "tubes"
            "gills" -> hymenium == "gills"
            else -> true
        }

        return matchesEdibility && matchesHymenium
    }

    private fun applyFilter() {
        itemsContainer.removeAllViews()

        val matching = allLoadedTaxa.filter { matchesFilter(it) }

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
            if (currentViewMode == "grid") {
                val density = activity.resources.displayMetrics.density
                val pairs = matching.chunked(2)
                for (pair in pairs) {
                    val row = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(0, 4, 0, 4)
                        }
                    }
                    row.addView(createGridCard(pair[0]))
                    if (pair.size > 1) {
                        row.addView(createGridCard(pair[1]))
                    } else {
                        val spacer = View(activity).apply {
                            val margin = (3 * density).toInt()
                            layoutParams = LinearLayout.LayoutParams(0, 0, 1f).apply {
                                setMargins(margin, 0, margin, 0)
                            }
                        }
                        row.addView(spacer)
                    }
                    itemsContainer.addView(row)
                }
            } else {
                for (taxon in matching) {
                    renderListCard(taxon)
                }
            }
        }

        if (matching.size < 12 && hasMore && !isLoading) {
            mainHandler.postDelayed({ loadNextPage() }, 150)
        }
    }

    private fun createGridCard(taxon: MushroomTaxon): View {
        val density = activity.resources.displayMetrics.density
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1B2A22"))
            val pad = (6 * density).toInt()
            setPadding(pad, pad, pad, pad)
            val margin = (3 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(margin, 0, margin, 0)
            }
            setOnClickListener {
                if (isOfflineMode) {
                    MushroomDetailDialog.show(activity, taxon, currentLang)
                } else {
                    MushroomApiClient.getTaxonDetails(taxon.id, taxon.scientificName, currentLang) { detailedTaxon ->
                        MushroomDetailDialog.show(activity, detailedTaxon ?: taxon, currentLang)
                    }
                }
            }
        }

        // Large photo on top
        val photoHeight = (120 * density).toInt()
        val ivPhoto = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, photoHeight)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#0C130F"))
        }
        MushroomApiClient.loadBitmap(taxon.defaultPhotoUrl) { bmp ->
            if (bmp != null) ivPhoto.setImageBitmap(bmp)
        }
        card.addView(ivPhoto)

        // Caption info container at the bottom
        val captionCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (6 * density).toInt(), 0, (2 * density).toInt())
        }

        val tvCommon = TextView(activity).apply {
            text = taxon.commonName
            setTextColor(Color.WHITE)
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }

        val tvScientific = TextView(activity).apply {
            text = taxon.scientificName
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 11f
            setTypeface(null, Typeface.ITALIC)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        val meta = MycoKnowledge.resolveMetadata(taxon.scientificName)
        val edibility = if (meta.edibility != "unknown") meta.edibility else taxon.edibility

        val badgeRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 4, 0, 0)
        }

        val tvBadge = TextView(activity).apply {
            text = MycoKnowledge.getEdibilityLabel(edibility, currentLang)
            setTextColor(Color.WHITE)
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setBackgroundColor(MycoKnowledge.getEdibilityColor(edibility))
            setPadding(8, 3, 8, 3)
        }
        badgeRow.addView(tvBadge)

        if (!meta.lookalikesUk.isNullOrEmpty()) {
            val tvLookalikeAlert = TextView(activity).apply {
                text = "⚠️"
                textSize = 11f
                setPadding(6, 0, 2, 0)
            }
            badgeRow.addView(tvLookalikeAlert)
        }

        val hymeniumIcon = if (meta.hymenium == "tubes" || taxon.hymenium == "tubes") "🧽" else "🍂"
        val tvHymenium = TextView(activity).apply {
            text = hymeniumIcon
            textSize = 11.5f
            setPadding(4, 0, 0, 0)
        }
        badgeRow.addView(tvHymenium)

        captionCol.addView(tvCommon)
        captionCol.addView(tvScientific)
        captionCol.addView(badgeRow)
        card.addView(captionCol)

        return card
    }

    private fun renderListCard(taxon: MushroomTaxon) {
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
                if (isOfflineMode) {
                    MushroomDetailDialog.show(activity, taxon, currentLang)
                } else {
                    MushroomApiClient.getTaxonDetails(taxon.id, taxon.scientificName, currentLang) { detailedTaxon ->
                        MushroomDetailDialog.show(activity, detailedTaxon ?: taxon, currentLang)
                    }
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
        val edibility = if (meta.edibility != "unknown") meta.edibility else taxon.edibility

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
