package com.olegskal.mushroom.mushrooms

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.olegskal.mushroom.ui.UiUtils

class MushroomActivity : Activity() {

    private var currentLang = "uk"
    private var activeTab = 0 // 0 = Encyclopedia, 1 = Classifier

    private lateinit var contentFrame: FrameLayout
    private lateinit var encyclopediaTab: MushroomEncyclopediaTab
    private lateinit var classifierTab: MushroomClassifierTab

    private lateinit var btnTabEncyclopedia: Button
    private lateinit var btnTabClassifier: Button
    private lateinit var tvTitle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        currentLang = com.olegskal.mushroom.util.AppPrefs.getAppLang(this)

        // Initialize disk cache for images
        MushroomApiClient.initDiskCache(filesDir)
        MycoKnowledge.init(this)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0C130F"))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // 1. Top Header Row
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#132219"))
            setPadding(12, 12, 12, 12)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val btnBack = Button(this).apply {
            text = "←"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(80, 80)
            setOnClickListener { finish() }
        }
        header.addView(btnBack)

        tvTitle = TextView(this).apply {
            text = if (currentLang == "uk") "🍄 Гриби" else "🍄 Mushrooms"
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(8, 0, 8, 0)
            }
        }
        header.addView(tvTitle)
        rootLayout.addView(header)

        // 2. Tab Selector Row
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#16221C"))
            setPadding(8, 6, 8, 6)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        btnTabEncyclopedia = Button(this).apply {
            text = if (currentLang == "uk") "📖 Енциклопедія" else "📖 Encyclopedia"
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(4, 0, 4, 0)
            }
            setOnClickListener { switchTab(0) }
        }

        btnTabClassifier = Button(this).apply {
            text = if (currentLang == "uk") "🔍 Визначник" else "🔍 Identifier"
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(4, 0, 4, 0)
            }
            setOnClickListener { switchTab(1) }
        }

        tabRow.addView(btnTabEncyclopedia)
        tabRow.addView(btnTabClassifier)
        rootLayout.addView(tabRow)

        // 3. Tab Content Frame
        contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        encyclopediaTab = MushroomEncyclopediaTab(this, currentLang)
        classifierTab = MushroomClassifierTab(
            activity = this,
            currentLang = currentLang,
            onOpenEncyclopediaDetails = { scientificName ->
                MushroomApiClient.getTaxonDetails(0, scientificName, currentLang) { taxon ->
                    if (taxon != null) {
                        MushroomDetailDialog.show(this@MushroomActivity, taxon, currentLang)
                    } else {
                        val fallback = MushroomTaxon(
                            id = 0,
                            scientificName = scientificName,
                            commonName = scientificName,
                            defaultPhotoUrl = null
                        )
                        MushroomDetailDialog.show(this@MushroomActivity, fallback, currentLang)
                    }
                }
            },
            onSearchInEncyclopedia = { query ->
                encyclopediaTab.setSearchQuery(query)
                switchTab(0)
            }
        )

        contentFrame.addView(encyclopediaTab.view)
        contentFrame.addView(classifierTab.view)
        rootLayout.addView(contentFrame)

        setContentView(rootLayout)

        switchTab(0)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIntent(it) }
    }

    private fun handleIntent(intent: Intent) {
        if (Intent.ACTION_SEND == intent.action && intent.type?.startsWith("image/") == true) {
            val imageUri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            if (imageUri != null) {
                try {
                    contentResolver.openInputStream(imageUri)?.use { stream ->
                        val bmp = BitmapFactory.decodeStream(stream)
                        if (bmp != null) {
                            switchTab(1)
                            classifierTab.setInputBitmap(bmp)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun switchTab(tabIndex: Int) {
        activeTab = tabIndex
        if (tabIndex == 0) {
            encyclopediaTab.view.visibility = android.view.View.VISIBLE
            classifierTab.view.visibility = android.view.View.GONE
            btnTabEncyclopedia.setBackgroundColor(Color.parseColor("#10B981"))
            btnTabClassifier.setBackgroundColor(Color.parseColor("#22362C"))
        } else {
            encyclopediaTab.view.visibility = android.view.View.GONE
            classifierTab.view.visibility = android.view.View.VISIBLE
            btnTabEncyclopedia.setBackgroundColor(Color.parseColor("#22362C"))
            btnTabClassifier.setBackgroundColor(Color.parseColor("#10B981"))
        }
    }

    private fun updateLanguage(lang: String) {
        currentLang = lang
        if (::tvTitle.isInitialized) {
            tvTitle.text = if (lang == "uk") "🍄 Гриби" else "🍄 Mushrooms"
        }
        btnTabEncyclopedia.text = if (lang == "uk") "📖 Енциклопедія" else "📖 Encyclopedia"
        btnTabClassifier.text = if (lang == "uk") "🔍 Визначник" else "🔍 Identifier"
        encyclopediaTab.setLanguage(lang)
        classifierTab.setLanguage(lang)
    }

    override fun onResume() {
        super.onResume()
        val lang = com.olegskal.mushroom.util.AppPrefs.getAppLang(this)
        if (lang != currentLang) {
            updateLanguage(lang)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            when (requestCode) {
                MushroomClassifierTab.REQ_CAMERA -> {
                    val bmp = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        data?.extras?.getParcelable("data", Bitmap::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        data?.extras?.get("data") as? Bitmap
                    }
                    if (bmp != null) {
                        classifierTab.addInputBitmap(bmp)
                    }
                }
                MushroomClassifierTab.REQ_GALLERY -> {
                    val clipData = data?.clipData
                    if (clipData != null && clipData.itemCount > 0) {
                        val bitmaps = mutableListOf<Bitmap>()
                        val count = minOf(clipData.itemCount, 3)
                        for (i in 0 until count) {
                            val uri = clipData.getItemAt(i).uri
                            try {
                                contentResolver.openInputStream(uri)?.use { stream ->
                                    val bmp = BitmapFactory.decodeStream(stream)
                                    if (bmp != null) bitmaps.add(bmp)
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                        if (bitmaps.isNotEmpty()) {
                            classifierTab.addInputBitmaps(bitmaps)
                        }
                    } else {
                        val uri = data?.data
                        if (uri != null) {
                            try {
                                contentResolver.openInputStream(uri)?.use { stream ->
                                    val bmp = BitmapFactory.decodeStream(stream)
                                    if (bmp != null) {
                                        classifierTab.addInputBitmap(bmp)
                                    }
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        classifierTab.onDestroy()
    }
}
