package com.olegskal.mushroom.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * Native vector drawable for Markers, exactly matching FlagMarkerButton on the map.
 */
class MarkerIconDrawable(
    private val density: Float,
    var color: Int = Color.parseColor("#F44336"),
    private val sizeDp: Int = 20
) : Drawable() {

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
        style = Paint.Style.FILL
    }
    private val flagFoldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33000000") // subtle 20% shadow for 3D fold
        style = Paint.Style.FILL
    }
    private val flagPath = Path()
    private val foldPath = Path()

    override fun getIntrinsicWidth(): Int = (sizeDp * density).toInt()
    override fun getIntrinsicHeight(): Int = (sizeDp * density).toInt()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = if (b.width() > 0) b.width().toFloat() else intrinsicWidth.toFloat()
        val h = if (b.height() > 0) b.height().toFloat() else intrinsicHeight.toFloat()
        val cx = b.left + w / 2f
        val cy = b.top + h / 2f
        val radius = Math.min(w, h) / 2f

        // Flag pole
        val poleX = cx - radius * 0.32f
        val poleTop = cy - radius * 0.65f
        val poleBottom = cy + radius * 0.75f

        polePaint.strokeWidth = 2.0f * density
        canvas.drawLine(poleX, poleTop, poleX, poleBottom, polePaint)

        // Gold finial on top of pole
        canvas.drawCircle(poleX, poleTop, 2.0f * density, finialPaint)

        // Flag cloth
        flagPaint.color = color
        val fTop = poleTop + 2.0f * density
        val fWidth = radius * 1.05f
        val fHeight = radius * 0.65f

        flagPath.reset()
        flagPath.moveTo(poleX, fTop)
        flagPath.cubicTo(
            poleX + fWidth * 0.35f, fTop - 2.0f * density,
            poleX + fWidth * 0.65f, fTop + 2.0f * density,
            poleX + fWidth, fTop
        )
        flagPath.lineTo(poleX + fWidth, fTop + fHeight)
        flagPath.cubicTo(
            poleX + fWidth * 0.65f, fTop + fHeight + 2.0f * density,
            poleX + fWidth * 0.35f, fTop + fHeight - 2.0f * density,
            poleX, fTop + fHeight
        )
        flagPath.close()
        canvas.drawPath(flagPath, flagPaint)

        // Subtle shaded fold on the wave
        foldPath.reset()
        foldPath.moveTo(poleX + fWidth * 0.45f, fTop)
        foldPath.cubicTo(
            poleX + fWidth * 0.55f, fTop + 1.2f * density,
            poleX + fWidth * 0.70f, fTop + 1.6f * density,
            poleX + fWidth, fTop
        )
        foldPath.lineTo(poleX + fWidth, fTop + fHeight)
        foldPath.cubicTo(
            poleX + fWidth * 0.70f, fTop + fHeight + 1.6f * density,
            poleX + fWidth * 0.55f, fTop + fHeight + 1.2f * density,
            poleX + fWidth * 0.45f, fTop + fHeight
        )
        foldPath.close()
        canvas.drawPath(foldPath, flagFoldPaint)
    }

    override fun setAlpha(alpha: Int) {
        flagPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        flagPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Native vector drawable for Tracks, exactly matching TrackRecordButton on the map.
 */
class TrackIconDrawable(
    private val density: Float,
    var color: Int = Color.parseColor("#2196F3"),
    private val sizeDp: Int = 20,
    var isRecording: Boolean = false
) : Drawable() {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val pointBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val recBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5252")
        style = Paint.Style.FILL
    }
    private val stopSquarePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val polylinePath = Path()

    override fun getIntrinsicWidth(): Int = (sizeDp * density).toInt()
    override fun getIntrinsicHeight(): Int = (sizeDp * density).toInt()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = if (b.width() > 0) b.width().toFloat() else intrinsicWidth.toFloat()
        val h = if (b.height() > 0) b.height().toFloat() else intrinsicHeight.toFloat()
        val cx = b.left + w / 2f
        val cy = b.top + h / 2f
        val radius = Math.min(w, h) / 2f

        // Blue / custom colored polyline
        val p1x = cx - radius * 0.65f
        val p1y = cy + radius * 0.45f

        val p2x = cx - radius * 0.20f
        val p2y = cy - radius * 0.35f

        val p3x = cx + radius * 0.20f
        val p3y = cy + radius * 0.25f

        val p4x = cx + radius * 0.65f
        val p4y = cy - radius * 0.45f

        trackPaint.color = color
        trackPaint.strokeWidth = 2.4f * density
        polylinePath.reset()
        polylinePath.moveTo(p1x, p1y)
        polylinePath.lineTo(p2x, p2y)
        polylinePath.lineTo(p3x, p3y)
        polylinePath.lineTo(p4x, p4y)
        canvas.drawPath(polylinePath, trackPaint)

        // Waypoints on vertices
        val ptRadius = 2.2f * density
        pointBorderPaint.color = color
        pointBorderPaint.strokeWidth = 1.0f * density

        val pts = arrayOf(p1x to p1y, p2x to p2y, p3x to p3y, p4x to p4y)
        for ((px, py) in pts) {
            canvas.drawCircle(px, py, ptRadius, pointPaint)
            canvas.drawCircle(px, py, ptRadius, pointBorderPaint)
        }

        // When recording: show red stop indicator badge in top-right
        if (isRecording) {
            val badgeX = cx + radius * 0.55f
            val badgeY = cy - radius * 0.55f
            val badgeR = 5.0f * density
            canvas.drawCircle(badgeX, badgeY, badgeR, recBadgePaint)

            val sqHalf = 2.0f * density
            canvas.drawRect(
                badgeX - sqHalf,
                badgeY - sqHalf,
                badgeX + sqHalf,
                badgeY + sqHalf,
                stopSquarePaint
            )
        }
    }

    override fun setAlpha(alpha: Int) {
        trackPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        trackPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
