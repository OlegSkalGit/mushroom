package com.olegskal.mushroom.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
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
import com.olegskal.mushroom.address.AddressSearchManager
import com.olegskal.mushroom.address.AddressSearchResult
import com.olegskal.mushroom.db.DatabaseHelper
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
            text = if (isUk) "🔍 Додати адресу" else "🔍 Add Address"
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
            text = if (isUk) "Введіть назву вулиці або міста та натисніть «Пошук»" else "Enter street or city name and press 'Search'"
            setTextColor(Color.GRAY)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(24))
        }
        listContainer.addView(statusTv)

        fun renderResults(results: List<AddressSearchResult>) {
            listContainer.removeAllViews()
            if (results.isEmpty()) {
                val emptyTv = TextView(activity).apply {
                    text = if (isUk) "Нічого не знайдено за цим запитом" else "No addresses found for this query"
                    setTextColor(Color.GRAY)
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setPadding(0, dp(24), 0, dp(24))
                }
                listContainer.addView(emptyTv)
                return
            }

            for (addr in results.take(30)) {
                val itemRow = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundColor(Color.parseColor("#1B261E"))
                    val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, 0, 0, dp(6))
                    }
                    layoutParams = p
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    isClickable = true
                    isFocusable = true
                }

                val titleLine = TextView(activity).apply {
                    text = addr.getFullTitle()
                    setTextColor(Color.WHITE)
                    textSize = 13.5f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }

                itemRow.addView(titleLine)

                itemRow.setOnClickListener {
                    dialog.dismiss()
                    ItemEditDialog.showAddMarker(
                        activity = activity,
                        dbHelper = dbHelper,
                        lat = addr.lat,
                        lon = addr.lon,
                        initialName = addr.getFullTitle(),
                        initialType = if (isUk) "📍 Адреса" else "📍 Address"
                    ) { marker ->
                        val mapActivity = activity as? MushroomMapActivity
                        mapActivity?.centerOnLocation(marker.lat, marker.lon)
                        onMarkerAdded()
                    }
                }

                listContainer.addView(itemRow)
            }
        }

        fun executeSearch() {
            val query = searchInput?.text?.toString()?.trim() ?: ""
            if (query.isBlank()) {
                listContainer.removeAllViews()
                statusTv.text = if (isUk) "Введіть назву вулиці або міста" else "Enter street or city name"
                listContainer.addView(statusTv)
                return
            }

            listContainer.removeAllViews()
            statusTv.text = if (isUk) "Пошук..." else "Searching..."
            listContainer.addView(statusTv)

            Thread {
                try {
                    val results = AddressSearchManager.search(activity, query, limit = 30)
                    activity.runOnUiThread {
                        renderResults(results)
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
            hint = if (isUk) "Введіть адресу (місто, вулиця, будинок)..." else "Enter address (city, street, house)..."
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
    }
}
