package com.daylight.window

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import java.util.Calendar

/**
 * Draws the day's UV as a curve, with night shaded out, the gentle windows highlighted,
 * your limit marked, and a line showing where "now" sits. The point is that you can see
 * at a glance why the app is telling you what it is telling you.
 */
class UvCurveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var samples: List<SunModel.Sample> = emptyList()
    private var windows: List<SunModel.Window> = emptyList()
    private var sunriseMinute = 0
    private var sunsetMinute = 0
    private var uvLimit = 0.0

    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val nightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val windowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(1f) }
    private val limitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
    }
    private val nowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(1.5f) }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(10f) }

    fun setDay(
        samples: List<SunModel.Sample>,
        windows: List<SunModel.Window>,
        sunriseMinute: Int,
        sunsetMinute: Int,
        uvLimit: Double
    ) {
        this.samples = samples
        this.windows = windows
        this.sunriseMinute = sunriseMinute
        this.sunsetMinute = sunsetMinute
        this.uvLimit = uvLimit
        invalidate()
    }

    fun applyPalette(
        curve: Int, grid: Int, night: Int, window: Int, limit: Int, now: Int, label: Int
    ) {
        curvePaint.color = curve
        fillPaint.color = curve
        fillPaint.alpha = 26
        gridPaint.color = grid
        nightPaint.color = night
        windowPaint.color = window
        windowPaint.alpha = 46
        limitPaint.color = limit
        nowPaint.color = now
        labelPaint.color = label
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (samples.isEmpty()) return

        val left = dp(30f)
        val right = width - dp(12f)
        val top = dp(16f)
        val bottom = height - dp(22f)
        if (right <= left || bottom <= top) return

        val peak = samples.maxOf { it.uv }
        val axisTop = maxOf(3.0, Math.ceil(peak + 0.5))

        fun x(minute: Int) = left + (minute / (24f * 60f)) * (right - left)
        fun y(uv: Double) = top + ((1.0 - uv / axisTop) * (bottom - top)).toFloat()

        // Night either side of daylight.
        canvas.drawRect(left, top, x(sunriseMinute), bottom, nightPaint)
        canvas.drawRect(x(sunsetMinute), top, right, bottom, nightPaint)

        // The stretches worth going out in.
        for (w in windows) {
            canvas.drawRect(x(w.startMinute), top, x(w.endMinute), bottom, windowPaint)
        }

        // Horizontal guides with their UV values.
        val step = if (axisTop > 8) 4 else 2
        var level = 0
        while (level <= axisTop) {
            val yy = y(level.toDouble())
            canvas.drawLine(left, yy, right, yy, gridPaint)
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(level.toString(), left - dp(6f), yy + sp(3.5f), labelPaint)
            level += step
        }

        // Hour labels.
        labelPaint.textAlign = Paint.Align.CENTER
        for (hour in intArrayOf(6, 9, 12, 15, 18, 21)) {
            canvas.drawText(Format.clock(hour * 60), x(hour * 60), bottom + dp(15f), labelPaint)
        }

        // Your limit.
        if (uvLimit <= axisTop) {
            val yy = y(uvLimit)
            canvas.drawLine(left, yy, right, yy, limitPaint)
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(
                context.getString(R.string.chart_your_limit),
                right, yy - dp(4f), labelPaint
            )
        }

        // The curve itself, filled underneath.
        val line = Path()
        val area = Path()
        samples.forEachIndexed { i, s ->
            val px = x(s.minuteOfDay)
            val py = y(s.uv)
            if (i == 0) { line.moveTo(px, py); area.moveTo(px, py) }
            else { line.lineTo(px, py); area.lineTo(px, py) }
        }
        area.lineTo(x(samples.last().minuteOfDay), y(0.0))
        area.lineTo(x(samples.first().minuteOfDay), y(0.0))
        area.close()
        canvas.drawPath(area, fillPaint)
        canvas.drawPath(line, curvePaint)

        // Where we are now.
        val calendar = Calendar.getInstance()
        val nowMinute = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        val nx = x(nowMinute)
        canvas.drawLine(nx, top, nx, bottom, nowPaint)
        labelPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(context.getString(R.string.chart_now), nx, top - dp(4f), labelPaint)
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
    private fun sp(value: Float) = value * resources.displayMetrics.scaledDensity
}
