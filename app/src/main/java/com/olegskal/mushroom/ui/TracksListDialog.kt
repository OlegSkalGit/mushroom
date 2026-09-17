package com.olegskal.mushroom.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import com.olegskal.mushroom.db.DatabaseHelper
import com.olegskal.mushroom.model.MushroomTrack
import java.text.SimpleDateFormat
import java.util.*

object TracksListDialog {

    fun show(
        activity: Activity,
        dbHelper: DatabaseHelper,
        onSelectTrack: (MushroomTrack) -> Unit,
        onVisibilityChanged: () -> Unit
    ) {
        val dialog = Dialog(activity)
        dialog.setTitle("Список треків")

        val container = UiUtils.createDarkDialogContainer(activity)

        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
        }

        val titleTv = TextView(activity).apply {
            text = "🗺️ Записані треки"
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

        val isRecording = com.olegskal.mushroom.service.MushroomTrackingService.instance?.isRecording == true
        val btnRecordTrack = Button(activity).apply {
            text = if (isRecording) "⏹️ Зупинити запис треку" else "⏺️ Записати новий трек"
            setTextColor(Color.WHITE)
            setBackgroundColor(if (isRecording) Color.parseColor("#C62828") else Color.parseColor("#2E7D32"))
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            val btnParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 8)
            }
            layoutParams = btnParams
            setOnClickListener {
                val act = activity as? com.olegskal.mushroom.MushroomMapActivity
                val s = com.olegskal.mushroom.service.MushroomTrackingService.instance
                if (s?.isRecording == true) {
                    act?.stopTrackRecording()
                    dialog.dismiss()
                } else {
                    dialog.dismiss()
                    ItemEditDialog.showCreateTrack(activity) { title, color ->
                        act?.startTrackRecording(title, color)
                    }
                }
            }
        }
        container.addView(btnRecordTrack)

        val btnImportGpx = UiUtils.createStyledButton(activity, "📂 Імпортувати GPX файл") {
            dialog.dismiss()
            (activity as? com.olegskal.mushroom.MushroomMapActivity)?.openGpxFilePicker()
        }
        val importParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, 12)
        }
        btnImportGpx.layoutParams = importParams
        container.addView(btnImportGpx)

        val scrollView = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val listContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun populateTracks() {
            listContainer.removeAllViews()
            val activeId = if (com.olegskal.mushroom.service.MushroomTrackingService.instance?.isRecording == true) {
                com.olegskal.mushroom.service.MushroomTrackingService.instance?.currentActiveTrack?.id
            } else null
            val tracks = dbHelper.getAllTracks().filter { activeId == null || it.id != activeId }

            if (tracks.isEmpty()) {
                val emptyTv = TextView(activity).apply {
                    text = "Немає збережених треків.\nНатисніть \"⏺️ Записати новий трек\"."
                    setTextColor(Color.GRAY)
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setPadding(0, 48, 0, 48)
                }
                listContainer.addView(emptyTv)
                return
            }

            val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

            for (track in tracks) {
                val itemRow = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundColor(Color.parseColor("#222222"))
                    setPadding(12, 8, 12, 8)
                    val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    params.setMargins(0, 0, 0, 8)
                    layoutParams = params
                }

                val chkVisible = CheckBox(activity).apply {
                    isChecked = track.isVisible
                    setOnCheckedChangeListener { _, isChecked ->
                        track.isVisible = isChecked
                        dbHelper.setTrackVisibility(track.id, isChecked)
                        onVisibilityChanged()
                    }
                }

                val infoCol = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        dialog.dismiss()
                        onSelectTrack(track)
                    }
                }

                val nameTv = TextView(activity).apply {
                    text = track.title
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    setTypeface(null, Typeface.BOLD)
                }

                val km = track.distanceMeters / 1000f
                val h = track.durationSec / 3600
                val m = (track.durationSec % 3600) / 60
                val s = track.durationSec % 60
                val timeStr = if (h > 0) String.format(Locale.US, "%d год %02d хв", h, m) else String.format(Locale.US, "%d хв %02d с", m, s)
                val dateStr = sdf.format(Date(track.startTime))

                val subTv = TextView(activity).apply {
                    text = String.format(Locale.US, "%.2f км • %s • %d точок", km, timeStr, track.points.size)
                    setTextColor(track.color)
                    textSize = 12f
                }

                val dateTv = TextView(activity).apply {
                    text = dateStr
                    setTextColor(Color.GRAY)
                    textSize = 11f
                }

                infoCol.addView(nameTv)
                infoCol.addView(subTv)
                infoCol.addView(dateTv)

                val btnRename = Button(activity).apply {
                    text = "✏️"
                    setTextColor(Color.parseColor("#FFD54F"))
                    setBackgroundColor(Color.TRANSPARENT)
                    textSize = 15f
                    setPadding(8, 0, 8, 0)
                    setOnClickListener {
                        ItemEditDialog.showEditTrack(activity, dbHelper, track) {
                            onVisibilityChanged()
                            populateTracks()
                        }
                    }
                }

                val btnShare = Button(activity).apply {
                    text = "📤"
                    setTextColor(Color.parseColor("#00E5FF"))
                    setBackgroundColor(Color.TRANSPARENT)
                    textSize = 15f
                    setPadding(8, 0, 8, 0)
                    setOnClickListener {
                        com.olegskal.mushroom.util.GeoDataExchange.shareTrackGpx(activity, track)
                    }
                }

                val btnDelete = Button(activity).apply {
                    text = "🗑"
                    setTextColor(Color.parseColor("#FF5252"))
                    setBackgroundColor(Color.TRANSPARENT)
                    textSize = 15f
                    setPadding(8, 0, 8, 0)
                    setOnClickListener {
                        AlertDialog.Builder(activity)
                            .setTitle("Видалити трек?")
                            .setMessage("Видалити \"${track.title}\"?")
                            .setPositiveButton("Видалити") { _, _ ->
                                dbHelper.deleteTrack(track.id)
                                onVisibilityChanged()
                                populateTracks()
                            }
                            .setNegativeButton("Скасувати", null)
                            .show()
                    }
                }

                itemRow.addView(chkVisible)
                itemRow.addView(infoCol)
                itemRow.addView(btnRename)
                itemRow.addView(btnShare)
                itemRow.addView(btnDelete)

                listContainer.addView(itemRow)
            }
        }

        populateTracks()
        scrollView.addView(listContainer)
        container.addView(scrollView)

        dialog.setContentView(container)
        dialog.show()
    }
}
