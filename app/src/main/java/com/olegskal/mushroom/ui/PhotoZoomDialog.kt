package com.olegskal.mushroom.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.olegskal.mushroom.mushrooms.MushroomApiClient

class ZoomImageView(context: Context) : ImageView(context) {

    private val baseMatrix = Matrix()
    private val currentMatrix = Matrix()
    private var currentScale = 1f
    private val minScale = 1f
    private val maxScale = 5f

    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    init {
        scaleType = ScaleType.MATRIX

        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                val targetScale = currentScale * factor
                val clampedFactor = when {
                    targetScale < minScale -> minScale / currentScale
                    targetScale > maxScale -> maxScale / currentScale
                    else -> factor
                }
                currentScale *= clampedFactor
                currentMatrix.postScale(clampedFactor, clampedFactor, detector.focusX, detector.focusY)
                clampBounds()
                imageMatrix = currentMatrix
                return true
            }
        })

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (currentScale > 1.2f) {
                    resetZoom()
                } else {
                    val targetScale = 2.5f
                    val factor = targetScale / currentScale
                    currentScale = targetScale
                    currentMatrix.postScale(factor, factor, e.x, e.y)
                    clampBounds()
                    imageMatrix = currentMatrix
                }
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (currentScale > 1f) {
                    currentMatrix.postTranslate(-distanceX, -distanceY)
                    clampBounds()
                    imageMatrix = currentMatrix
                    return true
                }
                return false
            }
        })
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        resetZoom()
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        resetZoom()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetZoom()
    }

    fun resetZoom() {
        val d = drawable ?: return
        if (width <= 0 || height <= 0) return

        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (dw <= 0 || dh <= 0) return

        val scale = minOf(viewW / dw, viewH / dh)
        val dx = (viewW - dw * scale) / 2f
        val dy = (viewH - dh * scale) / 2f

        baseMatrix.reset()
        baseMatrix.postScale(scale, scale)
        baseMatrix.postTranslate(dx, dy)

        currentMatrix.set(baseMatrix)
        currentScale = 1f
        imageMatrix = currentMatrix
    }

    private fun clampBounds() {
        val d = drawable ?: return
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val rect = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        currentMatrix.mapRect(rect)

        var dx = 0f
        var dy = 0f

        if (rect.width() <= viewW) {
            dx = (viewW - rect.width()) / 2f - rect.left
        } else if (rect.left > 0f) {
            dx = -rect.left
        } else if (rect.right < viewW) {
            dx = viewW - rect.right
        }

        if (rect.height() <= viewH) {
            dy = (viewH - rect.height()) / 2f - rect.top
        } else if (rect.top > 0f) {
            dy = -rect.top
        } else if (rect.bottom < viewH) {
            dy = viewH - rect.bottom
        }

        currentMatrix.postTranslate(dx, dy)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        var handled = scaleDetector.onTouchEvent(event)
        handled = gestureDetector.onTouchEvent(event) || handled
        if (event.actionMasked == MotionEvent.ACTION_UP && currentScale < minScale) {
            resetZoom()
        }
        return handled || super.onTouchEvent(event)
    }
}

object PhotoZoomDialog {

    fun show(activity: Activity, bitmap: Bitmap) {
        showBitmaps(activity, listOf(bitmap), 0)
    }

    fun showBitmaps(activity: Activity, bitmaps: List<Bitmap>, initialIndex: Int = 0) {
        if (bitmaps.isEmpty() || activity.isFinishing || activity.isDestroyed) return
        createDialog(activity, bitmaps.size, initialIndex) { zoomView, index, _ ->
            zoomView.setImageBitmap(bitmaps[index])
        }
    }

    fun show(activity: Activity, photos: List<String>, initialIndex: Int = 0) {
        if (photos.isEmpty() || activity.isFinishing || activity.isDestroyed) return
        createDialog(activity, photos.size, initialIndex) { zoomView, index, spinner ->
            spinner.visibility = View.VISIBLE
            zoomView.setImageBitmap(null)
            MushroomApiClient.loadBitmap(photos[index]) { bmp ->
                if (!activity.isFinishing && !activity.isDestroyed) {
                    spinner.visibility = View.GONE
                    if (bmp != null) {
                        zoomView.setImageBitmap(bmp)
                    }
                }
            }
        }
    }

    private fun createDialog(
        activity: Activity,
        totalCount: Int,
        initialIndex: Int,
        loadAction: (zoomView: ZoomImageView, index: Int, spinner: ProgressBar) -> Unit
    ) {
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        var currentIndex = initialIndex.coerceIn(0, totalCount - 1)
        val density = activity.resources.displayMetrics.density

        val root = FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val zoomView = ZoomImageView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        root.addView(zoomView)

        val spinner = ProgressBar(activity).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
            visibility = View.GONE
        }
        root.addView(spinner)

        // Top bar (counter + close)
        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#99000000"))
            val pad = (12 * density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP
            }
        }

        val tvCounter = TextView(activity).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            text = if (totalCount > 1) "${currentIndex + 1} / $totalCount" else ""
        }
        topBar.addView(tvCounter)

        val btnClose = TextView(activity).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            val p = (6 * density).toInt()
            setPadding(p, 0, p, 0)
            isClickable = true
            setOnClickListener { dialog.dismiss() }
        }
        topBar.addView(btnClose)
        root.addView(topBar)

        fun updatePhoto(idx: Int) {
            currentIndex = idx.coerceIn(0, totalCount - 1)
            if (totalCount > 1) {
                tvCounter.text = "${currentIndex + 1} / $totalCount"
            }
            loadAction(zoomView, currentIndex, spinner)
        }

        if (totalCount > 1) {
            val btnPrev = TextView(activity).apply {
                text = "◀"
                setTextColor(Color.WHITE)
                textSize = 20f
                setBackgroundColor(Color.parseColor("#77000000"))
                gravity = Gravity.CENTER
                val size = (46 * density).toInt()
                layoutParams = FrameLayout.LayoutParams(size, size).apply {
                    gravity = Gravity.CENTER_VERTICAL or Gravity.START
                    marginStart = (8 * density).toInt()
                }
                isClickable = true
                setOnClickListener {
                    val nextIdx = if (currentIndex > 0) currentIndex - 1 else totalCount - 1
                    updatePhoto(nextIdx)
                }
            }
            root.addView(btnPrev)

            val btnNext = TextView(activity).apply {
                text = "▶"
                setTextColor(Color.WHITE)
                textSize = 20f
                setBackgroundColor(Color.parseColor("#77000000"))
                gravity = Gravity.CENTER
                val size = (46 * density).toInt()
                layoutParams = FrameLayout.LayoutParams(size, size).apply {
                    gravity = Gravity.CENTER_VERTICAL or Gravity.END
                    marginEnd = (8 * density).toInt()
                }
                isClickable = true
                setOnClickListener {
                    val nextIdx = if (currentIndex < totalCount - 1) currentIndex + 1 else 0
                    updatePhoto(nextIdx)
                }
            }
            root.addView(btnNext)
        }

        updatePhoto(currentIndex)

        dialog.setContentView(root)
        dialog.show()
    }
}
