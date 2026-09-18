package com.olegskal.mushroom.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * Floating action button for centering the map on the user's current GPS location.
 * Features a circular body with an ultra-crisp cyan crosshair target icon.
 */
class CenterLocationButton(context: Context) : View(context) {

    private var isTouchPressed: Boolean = false

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF") // Tactical Cyan
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.FILL
    }

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val defaultSize = (48 * density).toInt()
        val w = resolveSize(defaultSize, widthMeasureSpec)
        val h = resolveSize(defaultSize, heightMeasureSpec)
        val size = min(w, h).coerceAtLeast(defaultSize)
        setMeasuredDimension(size, size)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isTouchPressed = true
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                isTouchPressed = false
                invalidate()
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                isTouchPressed = false
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val density = resources.displayMetrics.density
        val padding = 3f * density
        val radius = min(cx, cy) - padding

        canvas.save()
        if (isTouchPressed) {
            canvas.scale(0.92f, 0.92f, cx, cy)
        }

        // 70% transparent circular background
        bgPaint.color = if (isTouchPressed) Color.parseColor("#80111111") else Color.parseColor("#4D222222")
        canvas.drawCircle(cx, cy, radius, bgPaint)

        // Subtle border
        borderPaint.color = Color.parseColor("#33FFFFFF")
        borderPaint.strokeWidth = 1.2f * density
        canvas.drawCircle(cx, cy, radius, borderPaint)

        // Crosshairs / Target icon
        targetPaint.strokeWidth = 2.2f * density
        val targetRadius = radius * 0.44f

        // Center ring
        canvas.drawCircle(cx, cy, targetRadius, targetPaint)

        // Center pinpoint dot
        canvas.drawCircle(cx, cy, 2.5f * density, centerDotPaint)

        // 4 Crosshair ticks extending outside the ring
        val tickInner = targetRadius
        val tickOuter = radius * 0.68f

        // Top tick
        canvas.drawLine(cx, cy - tickInner, cx, cy - tickOuter, targetPaint)
        // Bottom tick
        canvas.drawLine(cx, cy + tickInner, cx, cy + tickOuter, targetPaint)
        // Left tick
        canvas.drawLine(cx - tickInner, cy, cx - tickOuter, cy, targetPaint)
        // Right tick
        canvas.drawLine(cx + tickInner, cy, cx + tickOuter, cy, targetPaint)

        canvas.restore()
    }
}

/**
 * Floating action button for adding a mushroom marker with a red flag icon.
 */
class FlagMarkerButton(context: Context) : View(context) {

    private var isTouchPressed: Boolean = false

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val polePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CFD8DC")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val finialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD54F")
        style = Paint.Style.FILL
    }
    private val flagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F44336") // Crisp red flag
        style = Paint.Style.FILL
    }
    private val flagFoldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D32F2F") // Shaded fold for 3D depth
        style = Paint.Style.FILL
    }

    private val flagPath = Path()
    private val foldPath = Path()

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val defaultSize = (48 * density).toInt()
        val w = resolveSize(defaultSize, widthMeasureSpec)
        val h = resolveSize(defaultSize, heightMeasureSpec)
        val size = min(w, h).coerceAtLeast(defaultSize)
        setMeasuredDimension(size, size)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isTouchPressed = true
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                isTouchPressed = false
                invalidate()
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                isTouchPressed = false
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val density = resources.displayMetrics.density
        val padding = 3f * density
        val radius = min(cx, cy) - padding

        canvas.save()
        if (isTouchPressed) {
            canvas.scale(0.92f, 0.92f, cx, cy)
        }

        // 70% transparent circular background
        bgPaint.color = if (isTouchPressed) Color.parseColor("#80111111") else Color.parseColor("#4D222222")
        canvas.drawCircle(cx, cy, radius, bgPaint)

        // Subtle border
        borderPaint.color = Color.parseColor("#33FFFFFF")
        borderPaint.strokeWidth = 1.2f * density
        canvas.drawCircle(cx, cy, radius, borderPaint)

        // Flag pole
        val poleX = cx - radius * 0.28f
        val poleTop = cy - radius * 0.54f
        val poleBottom = cy + radius * 0.58f

        polePaint.strokeWidth = 2.4f * density
        canvas.drawLine(poleX, poleTop, poleX, poleBottom, polePaint)

        // Gold finial on top of pole
        canvas.drawCircle(poleX, poleTop, 2.2f * density, finialPaint)

        // Red flag cloth
        val fTop = poleTop + 2.5f * density
        val fWidth = radius * 0.82f
        val fHeight = radius * 0.52f

        flagPath.reset()
        flagPath.moveTo(poleX, fTop)
        flagPath.cubicTo(
            poleX + fWidth * 0.35f, fTop - 2.5f * density,
            poleX + fWidth * 0.65f, fTop + 2.5f * density,
            poleX + fWidth, fTop
        )
        flagPath.lineTo(poleX + fWidth, fTop + fHeight)
        flagPath.cubicTo(
            poleX + fWidth * 0.65f, fTop + fHeight + 2.5f * density,
            poleX + fWidth * 0.35f, fTop + fHeight - 2.5f * density,
            poleX, fTop + fHeight
        )
        flagPath.close()
        canvas.drawPath(flagPath, flagPaint)

        // Subtle shaded fold on the wave
        foldPath.reset()
        foldPath.moveTo(poleX + fWidth * 0.45f, fTop)
        foldPath.cubicTo(
            poleX + fWidth * 0.55f, fTop + 1.5f * density,
            poleX + fWidth * 0.70f, fTop + 2f * density,
            poleX + fWidth, fTop
        )
        foldPath.lineTo(poleX + fWidth, fTop + fHeight)
        foldPath.cubicTo(
            poleX + fWidth * 0.70f, fTop + fHeight + 2f * density,
            poleX + fWidth * 0.55f, fTop + fHeight + 1.5f * density,
            poleX + fWidth * 0.45f, fTop + fHeight
        )
        foldPath.close()
        canvas.drawPath(foldPath, flagFoldPaint)

        canvas.restore()
    }
}

/**
 * Floating action button for starting and stopping track recording with a blue polyline icon.
 */
class TrackRecordButton(context: Context) : View(context) {

    private var isRecording: Boolean = false
    private var isTouchPressed: Boolean = false

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2196F3") // Blue polyline
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val pointBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1565C0")
        style = Paint.Style.STROKE
    }
    private val recBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5252") // Red recording indicator
        style = Paint.Style.FILL
    }
    private val stopSquarePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val polylinePath = Path()

    init {
        isClickable = true
        isFocusable = true
    }

    fun setRecording(recording: Boolean) {
        if (isRecording != recording) {
            isRecording = recording
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val defaultSize = (48 * density).toInt()
        val w = resolveSize(defaultSize, widthMeasureSpec)
        val h = resolveSize(defaultSize, heightMeasureSpec)
        val size = min(w, h).coerceAtLeast(defaultSize)
        setMeasuredDimension(size, size)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isTouchPressed = true
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                isTouchPressed = false
                invalidate()
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                isTouchPressed = false
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val density = resources.displayMetrics.density
        val padding = 3f * density
        val radius = min(cx, cy) - padding

        canvas.save()
        if (isTouchPressed) {
            canvas.scale(0.92f, 0.92f, cx, cy)
        }

        // 70% transparent circular background (tinted dark red when recording)
        if (isRecording) {
            bgPaint.color = if (isTouchPressed) Color.parseColor("#88351212") else Color.parseColor("#55351212")
            borderPaint.color = Color.parseColor("#FF5252")
            borderPaint.strokeWidth = 2.4f * density
        } else {
            bgPaint.color = if (isTouchPressed) Color.parseColor("#80111111") else Color.parseColor("#4D222222")
            borderPaint.color = Color.parseColor("#33FFFFFF")
            borderPaint.strokeWidth = 1.2f * density
        }
        canvas.drawCircle(cx, cy, radius, bgPaint)
        canvas.drawCircle(cx, cy, radius, borderPaint)

        // Blue polyline (синя ламана)
        val p1x = cx - radius * 0.50f
        val p1y = cy + radius * 0.35f

        val p2x = cx - radius * 0.15f
        val p2y = cy - radius * 0.30f

        val p3x = cx + radius * 0.15f
        val p3y = cy + radius * 0.20f

        val p4x = cx + radius * 0.52f
        val p4y = cy - radius * 0.40f

        trackPaint.strokeWidth = 3.2f * density
        polylinePath.reset()
        polylinePath.moveTo(p1x, p1y)
        polylinePath.lineTo(p2x, p2y)
        polylinePath.lineTo(p3x, p3y)
        polylinePath.lineTo(p4x, p4y)
        canvas.drawPath(polylinePath, trackPaint)

        // Waypoints on vertices
        val ptRadius = 2.8f * density
        pointBorderPaint.strokeWidth = 1.2f * density

        val pts = arrayOf(p1x to p1y, p2x to p2y, p3x to p3y, p4x to p4y)
        for ((px, py) in pts) {
            canvas.drawCircle(px, py, ptRadius, pointPaint)
            canvas.drawCircle(px, py, ptRadius, pointBorderPaint)
        }

        // When recording: show red stop indicator in the top-right corner
        if (isRecording) {
            val badgeX = cx + radius * 0.50f
            val badgeY = cy - radius * 0.45f
            val badgeR = 6f * density

            canvas.drawCircle(badgeX, badgeY, badgeR, recBadgePaint)

            // Small white stop square in center of badge
            val sqHalf = 2.5f * density
            canvas.drawRect(
                badgeX - sqHalf,
                badgeY - sqHalf,
                badgeX + sqHalf,
                badgeY + sqHalf,
                stopSquarePaint
            )
        }

        canvas.restore()
    }
}
