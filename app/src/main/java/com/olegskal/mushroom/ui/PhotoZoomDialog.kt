package com.olegskal.mushroom.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Region
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
import android.widget.Toast
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

    var isZoomEnabled = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isZoomEnabled) return false
        var handled = scaleDetector.onTouchEvent(event)
        handled = gestureDetector.onTouchEvent(event) || handled
        if (event.actionMasked == MotionEvent.ACTION_UP && currentScale < minScale) {
            resetZoom()
        }
        return handled || super.onTouchEvent(event)
    }
}

class CropOverlayView(context: Context) : View(context) {

    init {
        setWillNotDraw(false)
    }

    enum class TouchMode {
        NONE, DRAW, MOVE, TL, TR, BL, BR, L, T, R, B
    }

    val cropRect = RectF()
    var isCropActive = false
        private set
    var hasDrawnRect = false
        private set

    private var touchMode = TouchMode.NONE
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f

    private val density = resources.displayMetrics.density
    private val handleTouchRadius = 28f * density
    private val minSize = 40f * density
    private val cornerLineLength = 20f * density

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#10B981")
        strokeWidth = 3f * density
    }

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#10B981")
        strokeWidth = 5f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#66FFFFFF")
        strokeWidth = 1.2f * density
    }

    private val shadePaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#88000000")
    }

    private var imageBounds = RectF()
    private var zoomViewRef: ZoomImageView? = null
    var topBarView: View? = null
    var bottomPanelView: View? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (isCropActive && (!hasDrawnRect || cropRect.isEmpty || cropRect.width() < minSize)) {
            resetToDefaultCropRect()
        }
    }

    fun resetToDefaultCropRect() {
        val zv = zoomViewRef
        val d = zv?.drawable
        val vW = if (width > 0) width.toFloat() else (zv?.width?.toFloat() ?: resources.displayMetrics.widthPixels.toFloat())
        val vH = if (height > 0) height.toFloat() else (zv?.height?.toFloat() ?: resources.displayMetrics.heightPixels.toFloat())

        if (zv != null && d != null && vW > 0f && vH > 0f && d.intrinsicWidth > 0 && d.intrinsicHeight > 0) {
            val imgRect = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
            zv.imageMatrix.mapRect(imgRect)

            imageBounds.set(
                maxOf(0f, imgRect.left),
                maxOf(0f, imgRect.top),
                minOf(vW, imgRect.right),
                minOf(vH, imgRect.bottom)
            )

            if (imageBounds.width() < minSize || imageBounds.height() < minSize) {
                imageBounds.set(0f, 0f, vW, vH)
            }

            val w = imageBounds.width()
            val h = imageBounds.height()
            val insetX = w * 0.12f
            val insetY = h * 0.12f

            cropRect.set(
                imageBounds.left + insetX,
                imageBounds.top + insetY,
                imageBounds.right - insetX,
                imageBounds.bottom - insetY
            )
        } else {
            imageBounds.set(0f, 0f, vW, vH)
            val insetX = vW * 0.12f
            val insetY = vH * 0.12f
            cropRect.set(insetX, insetY, vW - insetX, vH - insetY)
        }
        hasDrawnRect = true
        touchMode = TouchMode.NONE
        invalidate()
    }

    fun startCrop(zoomView: ZoomImageView) {
        zoomViewRef = zoomView
        isCropActive = true
        visibility = VISIBLE
        resetToDefaultCropRect()
    }

    fun clearCrop() {
        resetToDefaultCropRect()
    }

    fun stopCrop() {
        isCropActive = false
        hasDrawnRect = false
        cropRect.set(0f, 0f, 0f, 0f)
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isCropActive) return

        val viewW = width.toFloat()
        val viewH = height.toFloat()

        if (!hasDrawnRect || cropRect.isEmpty || cropRect.width() < 10f || cropRect.height() < 10f) {
            canvas.drawRect(0f, 0f, viewW, viewH, shadePaint)
            return
        }

        // 1. Надійне затінення 4 областей навколо рамки без використання clipRect (працює на всіх версіях Android)
        if (cropRect.top > 0f) {
            canvas.drawRect(0f, 0f, viewW, cropRect.top, shadePaint)
        }
        if (cropRect.bottom < viewH) {
            canvas.drawRect(0f, cropRect.bottom, viewW, viewH, shadePaint)
        }
        if (cropRect.left > 0f) {
            canvas.drawRect(0f, cropRect.top, cropRect.left, cropRect.bottom, shadePaint)
        }
        if (cropRect.right < viewW) {
            canvas.drawRect(cropRect.right, cropRect.top, viewW, cropRect.bottom, shadePaint)
        }

        // 2. Смарагдова рамка
        canvas.drawRect(cropRect, borderPaint)

        // 3. Сітка правила третин
        val thirdW = cropRect.width() / 3f
        val thirdH = cropRect.height() / 3f
        canvas.drawLine(cropRect.left + thirdW, cropRect.top, cropRect.left + thirdW, cropRect.bottom, gridPaint)
        canvas.drawLine(cropRect.left + 2 * thirdW, cropRect.top, cropRect.left + 2 * thirdW, cropRect.bottom, gridPaint)
        canvas.drawLine(cropRect.left, cropRect.top + thirdH, cropRect.right, cropRect.top + thirdH, gridPaint)
        canvas.drawLine(cropRect.left, cropRect.top + 2 * thirdH, cropRect.right, cropRect.top + 2 * thirdH, gridPaint)

        // 4. Яскраві кутові маркери
        val l = cropRect.left
        val t = cropRect.top
        val r = cropRect.right
        val b = cropRect.bottom
        val len = cornerLineLength.coerceAtMost(minOf(cropRect.width(), cropRect.height()) / 3f)

        canvas.drawLine(l, t, l + len, t, cornerPaint)
        canvas.drawLine(l, t, l, t + len, cornerPaint)
        canvas.drawLine(r, t, r - len, t, cornerPaint)
        canvas.drawLine(r, t, r, t + len, cornerPaint)
        canvas.drawLine(l, b, l + len, b, cornerPaint)
        canvas.drawLine(l, b, l, b - len, cornerPaint)
        canvas.drawLine(r, b, r - len, b, cornerPaint)
        canvas.drawLine(r, b, r, b - len, cornerPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isCropActive) return false

        val tb = topBarView
        if (tb != null && tb.visibility == VISIBLE) {
            val loc = IntArray(2)
            tb.getLocationOnScreen(loc)
            val tbBottom = loc[1] + tb.height
            val myLoc = IntArray(2)
            getLocationOnScreen(myLoc)
            val touchScreenY = myLoc[1] + event.y
            if (touchScreenY <= tbBottom) {
                return false
            }
        }

        val bp = bottomPanelView
        if (bp != null && bp.visibility == VISIBLE) {
            val loc = IntArray(2)
            bp.getLocationOnScreen(loc)
            val bpTop = loc[1]
            val myLoc = IntArray(2)
            getLocationOnScreen(myLoc)
            val touchScreenY = myLoc[1] + event.y
            if (touchScreenY >= bpTop) {
                return false
            }
        }

        parent?.requestDisallowInterceptTouchEvent(true)

        val rawX = event.x
        val rawY = event.y
        val minX = minOf(imageBounds.left, imageBounds.right)
        val maxX = maxOf(imageBounds.left, imageBounds.right)
        val minY = minOf(imageBounds.top, imageBounds.bottom)
        val maxY = maxOf(imageBounds.top, imageBounds.bottom)
        val x = if (maxX > minX) rawX.coerceIn(minX, maxX) else rawX
        val y = if (maxY > minY) rawY.coerceIn(minY, maxY) else rawY

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val mode = if (hasDrawnRect && !cropRect.isEmpty && cropRect.width() >= minSize && cropRect.height() >= minSize) {
                    detectTouchMode(rawX, rawY)
                } else {
                    TouchMode.NONE
                }

                if (mode != TouchMode.NONE) {
                    touchMode = mode
                } else {
                    // Нове малювання рамки одним пальцем
                    touchMode = TouchMode.DRAW
                    startX = x
                    startY = y
                    cropRect.set(x, y, x, y)
                    hasDrawnRect = true
                }
                lastX = x
                lastY = y
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                when (touchMode) {
                    TouchMode.DRAW -> {
                        val l = minOf(startX, x)
                        val t = minOf(startY, y)
                        val r = maxOf(startX, x)
                        val b = maxOf(startY, y)
                        cropRect.set(l, t, r, b)
                        hasDrawnRect = true
                        invalidate()
                    }
                    TouchMode.MOVE -> {
                        val dx = x - lastX
                        val dy = y - lastY
                        var ndx = dx
                        var ndy = dy
                        if (cropRect.left + ndx < minX) ndx = minX - cropRect.left
                        if (cropRect.right + ndx > maxX) ndx = maxX - cropRect.right
                        if (cropRect.top + ndy < minY) ndy = minY - cropRect.top
                        if (cropRect.bottom + ndy > maxY) ndy = maxY - cropRect.bottom
                        cropRect.offset(ndx, ndy)
                        lastX = x
                        lastY = y
                        invalidate()
                    }
                    TouchMode.TL -> {
                        cropRect.left = (cropRect.left + (x - lastX)).coerceIn(minX, cropRect.right - minSize)
                        cropRect.top = (cropRect.top + (y - lastY)).coerceIn(minY, cropRect.bottom - minSize)
                        lastX = x
                        lastY = y
                        invalidate()
                    }
                    TouchMode.TR -> {
                        cropRect.right = (cropRect.right + (x - lastX)).coerceIn(cropRect.left + minSize, maxX)
                        cropRect.top = (cropRect.top + (y - lastY)).coerceIn(minY, cropRect.bottom - minSize)
                        lastX = x
                        lastY = y
                        invalidate()
                    }
                    TouchMode.BL -> {
                        cropRect.left = (cropRect.left + (x - lastX)).coerceIn(minX, cropRect.right - minSize)
                        cropRect.bottom = (cropRect.bottom + (y - lastY)).coerceIn(cropRect.top + minSize, maxY)
                        lastX = x
                        lastY = y
                        invalidate()
                    }
                    TouchMode.BR -> {
                        cropRect.right = (cropRect.right + (x - lastX)).coerceIn(cropRect.left + minSize, maxX)
                        cropRect.bottom = (cropRect.bottom + (y - lastY)).coerceIn(cropRect.top + minSize, maxY)
                        lastX = x
                        lastY = y
                        invalidate()
                    }
                    TouchMode.L -> {
                        cropRect.left = (cropRect.left + (x - lastX)).coerceIn(minX, cropRect.right - minSize)
                        lastX = x
                        lastY = y
                        invalidate()
                    }
                    TouchMode.T -> {
                        cropRect.top = (cropRect.top + (y - lastY)).coerceIn(minY, cropRect.bottom - minSize)
                        lastX = x
                        lastY = y
                        invalidate()
                    }
                    TouchMode.R -> {
                        cropRect.right = (cropRect.right + (x - lastX)).coerceIn(cropRect.left + minSize, maxX)
                        lastX = x
                        lastY = y
                        invalidate()
                    }
                    TouchMode.B -> {
                        cropRect.bottom = (cropRect.bottom + (y - lastY)).coerceIn(cropRect.top + minSize, maxY)
                        lastX = x
                        lastY = y
                        invalidate()
                    }
                    else -> {}
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (touchMode == TouchMode.DRAW) {
                    if (cropRect.width() < minSize || cropRect.height() < minSize) {
                        resetToDefaultCropRect()
                    }
                }
                touchMode = TouchMode.NONE
                invalidate()
                return true
            }
        }
        return true
    }

    private fun detectTouchMode(x: Float, y: Float): TouchMode {
        val r = handleTouchRadius
        if (Math.hypot((x - cropRect.left).toDouble(), (y - cropRect.top).toDouble()) <= r) return TouchMode.TL
        if (Math.hypot((x - cropRect.right).toDouble(), (y - cropRect.top).toDouble()) <= r) return TouchMode.TR
        if (Math.hypot((x - cropRect.left).toDouble(), (y - cropRect.bottom).toDouble()) <= r) return TouchMode.BL
        if (Math.hypot((x - cropRect.right).toDouble(), (y - cropRect.bottom).toDouble()) <= r) return TouchMode.BR

        if (Math.abs(x - cropRect.left) <= r && y in cropRect.top..cropRect.bottom) return TouchMode.L
        if (Math.abs(x - cropRect.right) <= r && y in cropRect.top..cropRect.bottom) return TouchMode.R
        if (Math.abs(y - cropRect.top) <= r && x in cropRect.left..cropRect.right) return TouchMode.T
        if (Math.abs(y - cropRect.bottom) <= r && x in cropRect.left..cropRect.right) return TouchMode.B

        if (cropRect.contains(x, y)) return TouchMode.MOVE

        return TouchMode.NONE
    }

    fun cropBitmap(originalBitmap: Bitmap, zoomView: ZoomImageView): Bitmap? {
        if (!hasDrawnRect || cropRect.width() < 10f || cropRect.height() < 10f) return null
        val d = zoomView.drawable ?: return null
        val matrix = zoomView.imageMatrix
        val invMatrix = Matrix()
        if (!matrix.invert(invMatrix)) return null

        val mapped = RectF()
        invMatrix.mapRect(mapped, cropRect)

        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        val scaleX = if (dw > 0f) originalBitmap.width.toFloat() / dw else 1f
        val scaleY = if (dh > 0f) originalBitmap.height.toFloat() / dh else 1f

        val left = (mapped.left * scaleX).toInt().coerceIn(0, originalBitmap.width - 1)
        val top = (mapped.top * scaleY).toInt().coerceIn(0, originalBitmap.height - 1)
        val right = (mapped.right * scaleX).toInt().coerceIn(left + 1, originalBitmap.width)
        val bottom = (mapped.bottom * scaleY).toInt().coerceIn(top + 1, originalBitmap.height)

        val w = right - left
        val h = bottom - top
        if (w < 10 || h < 10) return null

        return Bitmap.createBitmap(originalBitmap, left, top, w, h)
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

    fun showWithCrop(
        activity: Activity,
        originalBitmaps: List<Bitmap>,
        isCroppedList: MutableList<Boolean>,
        initialIndex: Int = 0,
        currentLang: String = "uk",
        onCropResult: (index: Int, bitmap: Bitmap, isCropped: Boolean) -> Unit
    ) {
        if (originalBitmaps.isEmpty() || activity.isFinishing || activity.isDestroyed) return
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val totalCount = originalBitmaps.size
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

        val cropOverlay = CropOverlayView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            visibility = View.VISIBLE
        }
        root.addView(cropOverlay)

        // Top bar
        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#99000000"))
            elevation = 30f * density
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

        val btnCropToggle = TextView(activity).apply {
            setTextColor(Color.WHITE)
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setBackgroundColor(Color.parseColor("#164E33"))
            val padH = (12 * density).toInt()
            val padV = (6 * density).toInt()
            setPadding(padH, padV, padH, padV)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (12 * density).toInt()
            }
            isClickable = true
        }
        topBar.addView(btnCropToggle)

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

        // Bottom Crop Panel
        val bottomCropPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#CC111915"))
            elevation = 30f * density
            val pad = (12 * density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
            }
            visibility = View.GONE
        }

        val tvCropHint = TextView(activity).apply {
            text = if (currentLang == "uk") "💡 Перетягніть рамку навколо гриба або намалюйте нову пальцем" else "💡 Drag frame around mushroom or draw a new one with finger"
            setTextColor(Color.parseColor("#10B981"))
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        bottomCropPanel.addView(tvCropHint)

        val cropButtonsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // Cancel crop button
        val btnCancelCrop = TextView(activity).apply {
            text = if (currentLang == "uk") "Скасувати" else "Cancel"
            setTextColor(Color.LTGRAY)
            textSize = 13f
            setBackgroundColor(Color.parseColor("#374151"))
            val padH = (14 * density).toInt()
            val padV = (8 * density).toInt()
            setPadding(padH, padV, padH, padV)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (8 * density).toInt()
            }
            gravity = Gravity.CENTER
            isClickable = true
        }
        cropButtonsRow.addView(btnCancelCrop)

        // Apply crop button
        val btnApplyCrop = TextView(activity).apply {
            text = if (currentLang == "uk") "✓ Готово" else "✓ Apply"
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setBackgroundColor(Color.parseColor("#059669"))
            val padH = (14 * density).toInt()
            val padV = (8 * density).toInt()
            setPadding(padH, padV, padH, padV)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER
            isClickable = true
        }
        cropButtonsRow.addView(btnApplyCrop)
        bottomCropPanel.addView(cropButtonsRow)
        root.addView(bottomCropPanel)

        cropOverlay.topBarView = topBar
        cropOverlay.bottomPanelView = bottomCropPanel

        var btnPrev: TextView? = null
        var btnNext: TextView? = null

        fun updateCropToggleText() {
            val isCropped = isCroppedList.getOrElse(currentIndex) { false }
            btnCropToggle.text = if (isCropped) {
                if (currentLang == "uk") "✂ Змінити рамку" else "✂ Edit Crop"
            } else {
                if (currentLang == "uk") "✂ Виділити гриб" else "✂ Crop Frame"
            }
        }

        fun exitCropMode() {
            cropOverlay.stopCrop()
            zoomView.isZoomEnabled = true
            bottomCropPanel.visibility = View.GONE
            btnCropToggle.visibility = View.VISIBLE
            if (totalCount > 1) {
                btnPrev?.visibility = View.VISIBLE
                btnNext?.visibility = View.VISIBLE
                btnPrev?.bringToFront()
                btnNext?.bringToFront()
            }
            topBar.bringToFront()
        }

        fun enterCropMode() {
            zoomView.resetZoom()
            zoomView.isZoomEnabled = false
            bottomCropPanel.visibility = View.VISIBLE
            btnCropToggle.visibility = View.GONE
            btnPrev?.visibility = View.GONE
            btnNext?.visibility = View.GONE

            cropOverlay.visibility = View.VISIBLE
            cropOverlay.startCrop(zoomView)

            cropOverlay.bringToFront()
            topBar.bringToFront()
            bottomCropPanel.bringToFront()
        }

        btnCropToggle.setOnClickListener {
            enterCropMode()
        }

        btnCancelCrop.setOnClickListener {
            exitCropMode()
        }

        btnApplyCrop.setOnClickListener {
            if (!cropOverlay.hasDrawnRect || cropOverlay.cropRect.isEmpty) {
                val err = if (currentLang == "uk") "Спочатку проведіть пальцем по грибу" else "Drag your finger over the mushroom first"
                Toast.makeText(activity, err, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val orig = originalBitmaps[currentIndex]
            val cropped = cropOverlay.cropBitmap(orig, zoomView)
            if (cropped != null) {
                onCropResult(currentIndex, cropped, true)
                if (currentIndex in isCroppedList.indices) {
                    isCroppedList[currentIndex] = true
                }
                val msg = if (currentLang == "uk") "Рамку застосовано!" else "Crop applied!"
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            } else {
                val err = if (currentLang == "uk") "Занадто мала рамка" else "Crop area too small"
                Toast.makeText(activity, err, Toast.LENGTH_SHORT).show()
            }
        }

        fun updatePhoto(idx: Int) {
            currentIndex = idx.coerceIn(0, totalCount - 1)
            if (totalCount > 1) {
                tvCounter.text = "${currentIndex + 1} / $totalCount"
            }
            zoomView.setImageBitmap(originalBitmaps[currentIndex])
            updateCropToggleText()
        }

        if (totalCount > 1) {
            btnPrev = TextView(activity).apply {
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

            btnNext = TextView(activity).apply {
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
