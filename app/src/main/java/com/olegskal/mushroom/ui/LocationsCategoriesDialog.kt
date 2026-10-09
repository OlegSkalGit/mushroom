package com.olegskal.mushroom.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.olegskal.mushroom.map.PoiManager
import com.olegskal.mushroom.util.AppPrefs

object LocationsCategoriesDialog {

    fun show(activity: Activity, onSelectionChanged: () -> Unit) {
        val dialog = Dialog(activity)
        dialog.setTitle("POI Categories")

        val isUk = AppPrefs.isUk(activity)
        val density = activity.resources.displayMetrics.density
        val container = UiUtils.createDarkDialogContainer(activity)

        val titleIcon = LocationIconDrawable(density, Color.parseColor("#10B981"), 20)
        titleIcon.setBounds(0, 0, titleIcon.intrinsicWidth, titleIcon.intrinsicHeight)
        val titleTv = TextView(activity).apply {
            text = if (isUk) "Локації (Точки інтересу)" else "Locations (Points of Interest)"
            setTextColor(Color.WHITE)
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setCompoundDrawables(titleIcon, null, null, null)
            compoundDrawablePadding = (8 * density).toInt()
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (6 * density).toInt())
        }
        container.addView(titleTv)

        val descTv = TextView(activity).apply {
            text = if (isUk) "Оберіть категорії точок для відображення на карті:"
                   else "Select POI categories to display on map:"
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 12.5f
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        container.addView(descTv)

        // Тимчасовий набір для вибору категорій без негайного збереження
        val tempSelected = PoiManager.getActiveCategories().toMutableSet()

        val scrollView = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val listContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        for (cat in PoiManager.DEFAULT_CATEGORIES) {
            val title = if (isUk) cat.titleUk else cat.titleEn
            val isChecked = tempSelected.contains(cat.id)

            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val p = (8 * density).toInt()
                setPadding(p, p, p, p)
                val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                params.setMargins(0, 0, 0, (4 * density).toInt())
                layoutParams = params
                setBackgroundColor(Color.parseColor("#1C241E"))
            }

            val chk = CheckBox(activity).apply {
                text = " $title"
                setTextColor(Color.WHITE)
                textSize = 13.5f
                this.isChecked = isChecked
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        tempSelected.add(cat.id)
                    } else {
                        tempSelected.remove(cat.id)
                    }
                }
            }
            row.addView(chk)
            listContainer.addView(row)
        }

        scrollView.addView(listContainer)
        container.addView(scrollView)

        container.addView(UiUtils.createDialogDivider(activity))

        // Кнопки "Скасувати" та "Готово"
        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, (6 * density).toInt(), 0, 0)
            }
        }

        val cancelParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(0, 0, (4 * density).toInt(), 0)
        }
        val doneParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins((4 * density).toInt(), 0, 0, 0)
        }

        val btnCancel = UiUtils.createStyledButton(activity, if (isUk) "Скасувати" else "Cancel", cancelParams) {
            dialog.dismiss()
        }
        val btnDone = UiUtils.createStyledButton(activity, if (isUk) "Готово" else "Done", doneParams) {
            PoiManager.setActiveCategories(tempSelected)
            dialog.dismiss()
            onSelectionChanged()
        }

        btnRow.addView(btnCancel)
        btnRow.addView(btnDone)
        container.addView(btnRow)

        dialog.setContentView(container)
        dialog.show()

        val dm = activity.resources.displayMetrics
        dialog.window?.setLayout((dm.widthPixels * 0.92f).toInt(), (dm.heightPixels * 0.82f).toInt())
    }
}
