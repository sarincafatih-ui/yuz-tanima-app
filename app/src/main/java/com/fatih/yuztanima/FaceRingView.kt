package com.fatih.yuztanima

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * Bankalardaki gibi: ekranı karartır, ortada oval bir yüz penceresi bırakır
 * ve ovalin etrafında dolan bir ilerleme halkası çizer.
 */
class FaceRingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        /** Ovalin merkezinin ekran yüksekliğine oranı (analizde de aynı değer kullanılır). */
        const val CENTER_Y = 0.40f
        const val WIDTH_RATIO = 0.70f
    }

    var progress = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }

    var ringColor = ContextCompat.getColor(context, R.color.accent)
        set(value) { field = value; ringPaint.color = value; invalidate() }

    private val density = resources.displayMetrics.density
    private val oval = RectF()
    private val maskPath = Path()

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D90B1730")
        style = Paint.Style.FILL
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#40FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 6 * density
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ringColor
        style = Paint.Style.STROKE
        strokeWidth = 8 * density
        strokeCap = Paint.Cap.ROUND
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val ow = w * WIDTH_RATIO
        val oh = ow * 1.32f
        val cx = w / 2f
        val cy = h * CENTER_Y
        oval.set(cx - ow / 2, cy - oh / 2, cx + ow / 2, cy + oh / 2)
        maskPath.reset()
        maskPath.fillType = Path.FillType.EVEN_ODD
        maskPath.addRect(0f, 0f, w.toFloat(), h.toFloat(), Path.Direction.CW)
        maskPath.addOval(oval, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(maskPath, maskPaint)
        canvas.drawOval(oval, trackPaint)
        if (progress > 0f) canvas.drawArc(oval, -90f, 360f * progress, false, ringPaint)
    }
}
