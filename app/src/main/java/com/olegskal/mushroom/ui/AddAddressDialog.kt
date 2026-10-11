package com.olegskal.mushroom.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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

        // Input Field
        val searchInput = EditText(activity).apply {
            hint = if (isUk) "Введіть адресу (місто, вулиця, будинок)..." else "Enter address (city, street, house)..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#263226"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            textSize = 14f
            val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            p.setMargins(0, 0, 0, dp(10))
            layoutParams = p
        }
        container.addView(searchInput)

        // Scrollable list of suggestions (max 30 items)
        val scrollView = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val listContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(listContainer)
        container.addView(scrollView)

        // Status / empty label
        val statusTv = TextView(activity).apply {
            text = if (isUk) "Почніть вводити назву вулиці або міста" else "Start typing street or city name"
            setTextColor(Color.GRAY)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(24))
        }
        listContainer.addView(statusTv)

        val handler = Handler(Looper.getMainLooper())
        var searchRunnable: Runnable? = null

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
                    orientation = LinearLayout.VERTICAL
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
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                }

                val subLine = TextView(activity).apply {
                    val coordsStr = String.format(java.util.Locale.US, "%.5f, %.5f", addr.lat, addr.lon)
                    text = "${addr.getShortDisplay()} • $coordsStr"
                    setTextColor(Color.parseColor("#9CA3AF"))
                    textSize = 12f
                    setPadding(0, dp(2), 0, 0)
                }

                itemRow.addView(titleLine)
                itemRow.addView(subLine)

                itemRow.setOnClickListener {
                    dialog.dismiss()
                    ItemEditDialog.showAddMarker(
                        activity = activity,
                        dbHelper = dbHelper,
                        lat = addr.lat,
                        lon = addr.lon,
                        initialName = addr.getFullTitle(),
                        initialType = if (isUk) "📍 Адреса" else "📍 Address"
                    ) {
                        onMarkerAdded()
                    }
                }

                listContainer.addView(itemRow)
            }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                searchRunnable?.let { handler.removeCallbacks(it) }

                if (query.isBlank()) {
                    listContainer.removeAllViews()
                    statusTv.text = if (isUk) "Почніть вводити назву вулиці або міста" else "Start typing street or city name"
                    listContainer.addView(statusTv)
                    return
                }

                searchRunnable = Runnable {
                    Thread {
                        try {
                            val results = AddressSearchManager.search(activity, query, limit = 30)
                            activity.runOnUiThread {
                                renderResults(results)
                            }
                        } catch (e: Exception) {
                            activity.runOnUiThread {
                                AlertDialog.Builder(activity)
                                    .setTitle(if (isUk) "Помилка пошуку" else "Search Error")
                                    .setMessage(e.message ?: "Unknown search error")
                                    .setPositiveButton("OK", null)
                                    .show()
                            }
                        }
                    }.start()
                }
                handler.postDelayed(searchRunnable!!, 200L)
            }
        })

        // Check if database exists
        val dbFile = AddressDb.getDatabaseFile(activity)
        if (!dbFile.exists() || dbFile.length() < 1024L) {
            val alertMsg = if (isUk) {
                "Офлайн-база адрес (addr.db) ще не завантажена.\nЗавантажте базу адрес у вікні оновлень/карт."
            } else {
                "Offline address database (addr.db) is not downloaded yet.\nPlease download it from the updates/maps screen."
            }
            AlertDialog.Builder(activity)
                .setTitle(if (isUk) "База адрес відсутня" else "Address Database Missing")
                .setMessage(alertMsg)
                .setPositiveButton("OK", null)
                .show()
        }

        dialog.setContentView(container)
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.92).toInt(),
            (activity.resources.displayMetrics.heightPixels * 0.85).toInt()
        )
        dialog.show()
    }
}
