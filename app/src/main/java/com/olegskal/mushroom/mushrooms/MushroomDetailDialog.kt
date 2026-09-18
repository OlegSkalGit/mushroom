package com.olegskal.mushroom.mushrooms

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.*
import com.olegskal.mushroom.ui.UiUtils

object MushroomDetailDialog {

    fun show(activity: Activity, taxon: MushroomTaxon, currentLang: String = "uk") {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#16221C"))
            setPadding(20, 20, 20, 20)
        }

        // Header
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 12)
        }

        val titlesBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvCommon = TextView(activity).apply {
            text = taxon.commonName
            setTextColor(Color.WHITE)
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
        }

        val tvScientific = TextView(activity).apply {
            text = taxon.scientificName
            setTextColor(Color.parseColor("#10B981"))
            textSize = 14f
            setTypeface(null, Typeface.ITALIC)
        }

        titlesBox.addView(tvCommon)
        titlesBox.addView(tvScientific)

        val btnClose = Button(activity).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            textSize = 20f
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(100, 100)
            setOnClickListener { dialog.dismiss() }
        }

        header.addView(titlesBox)
        header.addView(btnClose)
        root.addView(header)

        val scroll = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 1. Photos Section (Multiple photos support)
        val photos = taxon.photoUrls.ifEmpty {
            listOfNotNull(taxon.defaultPhotoUrl)
        }

        if (photos.isNotEmpty()) {
            var currentPhotoIdx = 0
            val photoHeight = (220 * activity.resources.displayMetrics.density).toInt()

            val photoContainer = FrameLayout(activity).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, photoHeight).apply {
                    setMargins(0, 4, 0, 12)
                }
                setBackgroundColor(Color.parseColor("#0C130F"))
            }

            val mainImageView = ImageView(activity).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            photoContainer.addView(mainImageView)

            val tvPhotoCount = TextView(activity).apply {
                setTextColor(Color.WHITE)
                textSize = 12f
                setBackgroundColor(Color.parseColor("#99000000"))
                setPadding(12, 6, 12, 6)
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.BOTTOM or Gravity.RIGHT
                    setMargins(0, 0, 12, 12)
                }
            }
            photoContainer.addView(tvPhotoCount)

            fun displayPhoto(idx: Int) {
                if (idx in photos.indices) {
                    currentPhotoIdx = idx
                    tvPhotoCount.text = "${idx + 1} / ${photos.size}"
                    MushroomApiClient.loadBitmap(photos[idx]) { bmp ->
                        if (bmp != null) {
                            mainImageView.setImageBitmap(bmp)
                        }
                    }
                }
            }

            displayPhoto(0)

            if (photos.size > 1) {
                val btnPrev = Button(activity).apply {
                    text = "◀"
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#77000000"))
                    textSize = 14f
                    layoutParams = FrameLayout.LayoutParams(110, 110).apply {
                        gravity = Gravity.CENTER_VERTICAL or Gravity.LEFT
                        setMargins(8, 0, 0, 0)
                    }
                    setOnClickListener {
                        val prev = if (currentPhotoIdx > 0) currentPhotoIdx - 1 else photos.size - 1
                        displayPhoto(prev)
                    }
                }
                val btnNext = Button(activity).apply {
                    text = "▶"
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#77000000"))
                    textSize = 14f
                    layoutParams = FrameLayout.LayoutParams(110, 110).apply {
                        gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
                        setMargins(0, 0, 8, 0)
                    }
                    setOnClickListener {
                        val next = if (currentPhotoIdx < photos.size - 1) currentPhotoIdx + 1 else 0
                        displayPhoto(next)
                    }
                }
                photoContainer.addView(btnPrev)
                photoContainer.addView(btnNext)

                // Thumbnail strip
                val thumbScroll = HorizontalScrollView(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (64 * activity.resources.displayMetrics.density).toInt()).apply {
                        setMargins(0, 0, 0, 12)
                    }
                }
                val thumbRow = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                }

                val thumbSize = (56 * activity.resources.displayMetrics.density).toInt()
                photos.forEachIndexed { i, url ->
                    val thumbIv = ImageView(activity).apply {
                        layoutParams = LinearLayout.LayoutParams(thumbSize, thumbSize).apply {
                            setMargins(4, 4, 4, 4)
                        }
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setBackgroundColor(Color.parseColor("#22362C"))
                        setOnClickListener { displayPhoto(i) }
                    }
                    MushroomApiClient.loadBitmap(url) { bmp ->
                        if (bmp != null) thumbIv.setImageBitmap(bmp)
                    }
                    thumbRow.addView(thumbIv)
                }
                thumbScroll.addView(thumbRow)
                content.addView(photoContainer)
                content.addView(thumbScroll)
            } else {
                content.addView(photoContainer)
            }
        }

        // 2. Status & Edibility Badge Row
        val statusRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 6, 0, 12)
        }

        val meta = MycoKnowledge.resolveMetadata(taxon.scientificName)
        val edibility = if (taxon.edibility != "unknown") taxon.edibility else meta.edibility
        val edibilityBadge = TextView(activity).apply {
            text = MycoKnowledge.getEdibilityLabel(edibility, currentLang)
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setBackgroundColor(MycoKnowledge.getEdibilityColor(edibility))
            setPadding(16, 8, 16, 8)
        }
        statusRow.addView(edibilityBadge)

        val hymeniumBadge = TextView(activity).apply {
            val isTubes = meta.hymenium == "tubes" || taxon.hymenium == "tubes"
            text = if (isTubes) {
                if (currentLang == "uk") "🧽 Трубчастий (губка)" else "🧽 Porous / Tubes"
            } else {
                if (currentLang == "uk") "🍂 Пластинчастий" else "🍂 Gilled"
            }
            setTextColor(Color.parseColor("#E5E7EB"))
            textSize = 13f
            setBackgroundColor(Color.parseColor("#2A3E34"))
            setPadding(16, 8, 16, 8)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(12, 0, 0, 0)
            }
        }
        statusRow.addView(hymeniumBadge)
        content.addView(statusRow)

        // 3. Danger Warning / Lookalikes
        val lookalikeText = if (currentLang == "uk") meta.lookalikesUk else meta.lookalikesEn
        if (!lookalikeText.isNullOrEmpty()) {
            val alertBox = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#3D1616"))
                setPadding(16, 12, 16, 12)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 8, 0, 14)
                }
            }
            val alertTitle = TextView(activity).apply {
                text = if (currentLang == "uk") "⚠️ УВАГА! ДВІЙНИКИ ТА БЕЗПЕКА:" else "⚠️ WARNING! LOOKALIKES & SAFETY:"
                setTextColor(Color.parseColor("#FF6B6B"))
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 0, 0, 4)
            }
            val alertDesc = TextView(activity).apply {
                text = lookalikeText
                setTextColor(Color.parseColor("#FCA5A5"))
                textSize = 12.5f
                setLineSpacing(4f, 1f)
            }
            alertBox.addView(alertTitle)
            alertBox.addView(alertDesc)
            content.addView(alertBox)
        }

        // 4. Morphological Properties Table
        val props = if (currentLang == "uk") meta.propsUk else meta.propsEn
        if (props != null) {
            val propsContainer = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#1B2A22"))
                setPadding(16, 12, 16, 12)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, 0, 14)
                }
            }

            fun addPropRow(label: String, value: String) {
                if (value.isBlank()) return
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 4, 0, 4)
                }
                val tvLabel = TextView(activity).apply {
                    text = label
                    setTextColor(Color.parseColor("#9CA3AF"))
                    textSize = 12.5f
                    setTypeface(null, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams((110 * activity.resources.displayMetrics.density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                val tvVal = TextView(activity).apply {
                    text = value
                    setTextColor(Color.WHITE)
                    textSize = 12.5f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                row.addView(tvLabel)
                row.addView(tvVal)
                propsContainer.addView(row)
            }

            val isUk = currentLang == "uk"
            addPropRow(if (isUk) "Шапинка:" else "Cap:", props.cap)
            addPropRow(if (isUk) "Гіменофор:" else "Hymenophore:", props.hymenophore)
            addPropRow(if (isUk) "Ніжка:" else "Stem:", props.stem)
            addPropRow(if (isUk) "М'якуш:" else "Flesh:", props.flesh)
            addPropRow(if (isUk) "Сезон:" else "Season:", props.season)
            addPropRow(if (isUk) "Середовище:" else "Habitat:", props.habitat)

            content.addView(propsContainer)
        }

        // 5. Wikipedia Summary
        val summary = taxon.wikipediaSummary
        if (!summary.isNullOrBlank()) {
            val tvDescTitle = TextView(activity).apply {
                text = if (currentLang == "uk") "📖 Опис:" else "📖 Overview:"
                setTextColor(Color.parseColor("#10B981"))
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 6, 0, 4)
            }
            val tvDesc = TextView(activity).apply {
                text = summary
                setTextColor(Color.parseColor("#D1D5DB"))
                textSize = 12.5f
                setLineSpacing(4f, 1f)
                setPadding(0, 0, 0, 14)
            }
            content.addView(tvDescTitle)
            content.addView(tvDesc)
        }

        // 6. External Links Row
        val linksRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 6, 0, 12)
        }
        val btnInat = UiUtils.createStyledButton(activity, if (currentLang == "uk") "🌐 iNaturalist" else "🌐 iNaturalist") {
            val url = "https://www.inaturalist.org/taxa/${taxon.id}"
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (ignored: Exception) {}
        }
        btnInat.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(0, 0, 8, 0)
        }
        val btnWiki = UiUtils.createStyledButton(activity, if (currentLang == "uk") "📖 Вікіпедія" else "📖 Wikipedia") {
            val url = "https://${currentLang}.wikipedia.org/wiki/${Uri.encode(taxon.scientificName)}"
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (ignored: Exception) {}
        }
        btnWiki.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

        linksRow.addView(btnInat)
        linksRow.addView(btnWiki)
        content.addView(linksRow)

        scroll.addView(content)
        root.addView(scroll)

        dialog.setContentView(root)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (activity.resources.displayMetrics.heightPixels * 0.90).toInt()
        )
        dialog.show()
    }
}
