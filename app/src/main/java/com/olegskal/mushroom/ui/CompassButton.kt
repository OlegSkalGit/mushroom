package com.olegskal.mushroom.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * Tactical forest navigation compass button.
 * Renders a crisp 3D faceted compass needle that rotates with map bearing.
 * Tapping aligns the map to North or toggles heading-up compass mode.
 */
class CompassButton(context: Context) : View(context) {

    private var bearing: Float = 0f
    private var isCompassActive: Boolean = false
    private var isTouchPressed: Boolean = false

    // Cached paints for zero allocation in onDraw
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4D222222")
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val northLeftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF3B30") // Bright red
        style = Paint.Style.FILL
    }

    private val northRightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C62828") // Shaded red (3D effect)
        style = Paint.Style.FILL
    }

    private val southLeftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#ECEFF1") // Bright silver
        style = Paint.Style.FILL
    }

    private val southRightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B0BEC5") // Shaded silver (3D effect)
        style = Paint.Style.FILL
    }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#77FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val northTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5252")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val pinBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1B1B1B")
        style = Paint.Style.FILL
    }

    private val pinCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD54F") // Brass gold center pin
        style = Paint.Style.FILL
    }

    private val textNPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val pathNorthLeft = Path()
    private val pathNorthRight = Path()
    private val pathSouthLeft = Path()
    private val pathSouthRight = Path()

    init {
        isClickable = true
        isFocusable = true
    }

    fun setBearing(angle: Float, active: Boolean = (kotlin.math.abs(angle % 360f) > 0.5f)) {
        if (bearing != angle || isCompassActive != active) {
            bearing = angle
            isCompassActive = active
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

        // 1. Dark Circular Background (70% transparent)
        bgPaint.color = if (isTouchPressed) Color.parseColor("#80111111") else Color.parseColor("#4D222222")
        canvas.drawCircle(cx, cy, radius, bgPaint)

        // 2. Active Mode Rim
        if (isCompassActive) {
            borderPaint.color = Color.parseColor("#00E5FF") // Active cyan rim
            borderPaint.strokeWidth = 2.5f * density
        } else {
            borderPaint.color = Color.parseColor("#33FFFFFF")
            borderPaint.strokeWidth = 1.2f * density
        }
        canvas.drawCircle(cx, cy, radius, borderPaint)

        // 3. Fixed Outer Cardinal Ticks (N, E, S, W) on button body
        val tickLen = 3.5f * density
        // North tick
        canvas.drawLine(cx, cy - radius, cx, cy - radius + tickLen, northTickPaint)
        // South tick
        canvas.drawLine(cx, cy + radius - tickLen, cx, cy + radius, tickPaint)
        // West tick
        canvas.drawLine(cx - radius, cy, cx - radius + tickLen, cy, tickPaint)
        // East tick
        canvas.drawLine(cx + radius - tickLen, cy, cx + radius, cy, tickPaint)

        // 4. Rotating Compass Needle
        canvas.save()
        canvas.rotate(bearing, cx, cy)

        val needleLen = radius * 0.72f
        val needleHalfWidth = radius * 0.22f

        // North Needle (Left half - bright red)
        pathNorthLeft.reset()
        pathNorthLeft.moveTo(cx, cy - needleLen)
        pathNorthLeft.lineTo(cx - needleHalfWidth, cy)
        pathNorthLeft.lineTo(cx, cy)
        pathNorthLeft.close()
        canvas.drawPath(pathNorthLeft, northLeftPaint)

        // North Needle (Right half - shaded red)
        pathNorthRight.reset()
        pathNorthRight.moveTo(cx, cy - needleLen)
        pathNorthRight.lineTo(cx + needleHalfWidth, cy)
        pathNorthRight.lineTo(cx, cy)
        pathNorthRight.close()
        canvas.drawPath(pathNorthRight, northRightPaint)

        // South Needle (Left half - bright silver)
        pathSouthLeft.reset()
        pathSouthLeft.moveTo(cx, cy + needleLen)
        pathSouthLeft.lineTo(cx - needleHalfWidth, cy)
        pathSouthLeft.lineTo(cx, cy)
        pathSouthLeft.close()
        canvas.drawPath(pathSouthLeft, southLeftPaint)

        // South Needle (Right half - shaded silver)
        pathSouthRight.reset()
        pathSouthRight.moveTo(cx, cy + needleLen)
        pathSouthRight.lineTo(cx + needleHalfWidth, cy)
        pathSouthRight.lineTo(cx, cy)
        pathSouthRight.close()
        canvas.drawPath(pathSouthRight, southRightPaint)

        // Tiny "N" label on North needle
        textNPaint.textSize = radius * 0.28f
        canvas.drawText("N", cx, cy - needleLen * 0.35f, textNPaint)

        // Center Pivot Pin
        canvas.drawCircle(cx, cy, radius * 0.16f, pinBgPaint)
        canvas.drawCircle(cx, cy, radius * 0.08f, pinCenterPaint)

        canvas.restore() // Restore needle rotation
        canvas.restore() // Restore press scale
    }
}
