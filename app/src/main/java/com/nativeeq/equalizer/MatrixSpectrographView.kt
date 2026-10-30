package com.nativeeq.equalizer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class MatrixSpectrographView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class VisualMode { OUTRUN_SYNTHWAVE, TECHNICS_VFD_88, VECTREX_SCOPE, LASER_STARFIELD }

    var isPaused = false
    var currentMode = VisualMode.OUTRUN_SYNTHWAVE

    private var smoothLow = 0.08f
    private var smoothMed = 0.08f
    private var smoothHigh = 0.08f
    private var phase = 0f
    private var beatPulse = 0f

    private val numCols = 24
    private val vfdLevels = FloatArray(numCols) { 0.1f }
    private val vfdPeaks = FloatArray(numCols) { 0.15f }

    private val starX = FloatArray(45) { (Math.random().toFloat() - 0.5f) * 2f }
    private val starY = FloatArray(45) { (Math.random().toFloat() - 0.5f) * 2f }
    private val starZ = FloatArray(45) { Math.random().toFloat().coerceAtLeast(0.1f) }

    private val bgPaint = Paint().apply { color = Color.parseColor("#05030a") }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 8f
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00f2fe"); textSize = 20f; isFakeBoldText = true
    }
    private val path = Path()

    fun cycleNextMode() {
        val modes = VisualMode.entries
        currentMode = modes[(currentMode.ordinal + 1) % modes.size]
        invalidate()
    }

    fun updateSpectralBands(low: Float, med: Float, high: Float) {
        val boostL = (low * 2.8f).coerceIn(0.02f, 1f)
        val boostM = (med * 2.8f).coerceIn(0.02f, 1f)
        val boostH = (high * 2.8f).coerceIn(0.02f, 1f)
        if (boostL > smoothLow + 0.12f) beatPulse = 1f
        smoothLow = if (boostL > smoothLow) boostL else smoothLow * 0.82f + boostL * 0.18f
        smoothMed = if (boostM > smoothMed) boostM else smoothMed * 0.82f + boostM * 0.18f
        smoothHigh = if (boostH > smoothHigh) boostH else smoothHigh * 0.82f + boostH * 0.18f
        for (i in 0 until numCols) {
            val ratio = i.toFloat() / (numCols - 1)
            val base = when {
                ratio < 0.35f -> smoothLow * (1f - 0.5f * ratio)
                ratio < 0.7f -> smoothMed * (sin(phase * 3f + i) * 0.3f + 0.85f)
                else -> smoothHigh * (cos(phase * 4f + i) * 0.4f + 0.8f)
            }
            val target = (abs(sin(phase * 2.5f + i * 0.7f)) * 0.08f + base)
                .coerceIn(0.04f, 1f)
            vfdLevels[i] = if (target > vfdLevels[i]) target else vfdLevels[i] * 0.8f
            if (vfdLevels[i] >= vfdPeaks[i]) {
                vfdPeaks[i] = vfdLevels[i]
            } else {
                vfdPeaks[i] = (vfdPeaks[i] - 0.018f).coerceAtLeast(0.04f)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        val energySpeed = smoothLow * 0.18f + 0.045f + smoothMed * 0.08f
        phase += energySpeed
        beatPulse = (beatPulse * 0.86f).coerceAtLeast(0f)
        when (currentMode) {
            VisualMode.OUTRUN_SYNTHWAVE -> drawOutrunSynthwave(canvas, w, h)
            VisualMode.TECHNICS_VFD_88 -> drawTechnicsVfd(canvas, w, h)
            VisualMode.VECTREX_SCOPE -> drawVectrexScope(canvas, w, h)
            VisualMode.LASER_STARFIELD -> drawLaserStarfield(canvas, w, h)
        }
        linePaint.strokeWidth = 1f
        linePaint.color = Color.argb(38, 0, 0, 0)
        var sy = 0f
        while (sy < h) {
            canvas.drawLine(0f, sy, w, sy, linePaint)
            sy += 6f
        }
        textPaint.color = Color.parseColor("#38bdf8")
        canvas.drawText("[80s DECK: ${currentMode.name} (TAP TO SWITCH)]", 18f, 26f, textPaint)
        if (!isPaused) postInvalidateOnAnimation()
    }

    private fun drawOutrunSynthwave(canvas: Canvas, w: Float, h: Float) {
        val cx = w * 0.5f
        val horizonY = h * 0.54f
        val sunRadius = min(w, h) * (smoothLow * 0.12f + 0.24f + beatPulse * 0.05f)
        val sunCy = horizonY - 0.25f * sunRadius
        fillPaint.shader = LinearGradient(
            cx, sunCy - sunRadius, cx, horizonY,
            intArrayOf(
                Color.parseColor("#ff007f"),
                Color.parseColor("#ff5500"),
                Color.parseColor("#ffcc00")
            ),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, sunCy, sunRadius, fillPaint)
        fillPaint.shader = null
        fillPaint.color = Color.parseColor("#05030a")
        for (s in 0 until 6) {
            val sliceY = sunCy + s * sunRadius * 0.16f
            val sliceH = 2.5f + s * 2.2f
            if (sliceY < horizonY) {
                canvas.drawRect(
                    cx - sunRadius - 4f, sliceY,
                    cx + sunRadius + 4f, sliceY + sliceH, fillPaint
                )
            }
        }
        path.reset()
        path.moveTo(0f, horizonY)
        for (i in 0..16) {
            val px = i / 16f * w
            val distFromCenter = abs(i - 16 / 2f) / (16 / 2f)
            val mHeight = distFromCenter * h * 0.26f *
                (smoothMed * 0.7f * abs(sin(i * 1.3f + phase * 0.5f)) + 0.4f)
            path.lineTo(px, horizonY - mHeight)
        }
        path.lineTo(w, horizonY)
        path.close()
        fillPaint.color = Color.parseColor("#090414")
        canvas.drawPath(path, fillPaint)
        linePaint.color = Color.parseColor("#00f2fe")
        linePaint.strokeWidth = 2.5f
        canvas.drawPath(path, linePaint)
        linePaint.color = Color.parseColor("#ff00a0")
        linePaint.strokeWidth = 2.2f
        for (i in -14..14) {
            val bottomX = cx + i * 0.14f * w
            canvas.drawLine(cx + i * 12f, horizonY, bottomX, h, linePaint)
        }
        val gridScroll = (phase * 1.4f) % 1f
        for (r in 0 until 8) {
            val norm = (r + gridScroll) / 8f
            val perspective = norm * norm * norm
            val gy = horizonY + (h - horizonY) * perspective
            linePaint.alpha = ((175 * norm).toInt() + 80).coerceIn(0, 255)
            canvas.drawLine(0f, gy, w, gy, linePaint)
        }
        linePaint.alpha = 255
    }

    private fun drawTechnicsVfd(canvas: Canvas, w: Float, h: Float) {
        val padX = 20f
        val topY = 42f
        val mainBottom = 0.78f * h
        val colW = (w - 2 * padX) / numCols
        val segH = (mainBottom - topY) / 14
        for (c in 0 until numCols) {
            val x0 = c * colW + padX + 2f
            val x1 = (c + 1) * colW + padX - 2f
            val activeSegs = (vfdLevels[c] * 14).toInt().coerceIn(1, 14)
            val peakSeg = (vfdPeaks[c] * 14).toInt().coerceIn(0, 13)
            for (s in 0 until 14) {
                val y1 = mainBottom - s * segH - 2f
                val y0 = y1 - segH + 3f
                fillPaint.color = if (s < activeSegs) {
                    when {
                        s >= 11 -> Color.parseColor("#ff1e56")
                        s >= 8 -> Color.parseColor("#ffac41")
                        else -> Color.parseColor("#00ffcc")
                    }
                } else {
                    Color.parseColor("#111d24")
                }
                canvas.drawRect(x0, y0, x1, y1, fillPaint)
                if (s == peakSeg) {
                    fillPaint.color = Color.WHITE
                    canvas.drawRect(x0, y0 - 2f, x1, y0 + 2f, fillPaint)
                }
            }
            val reflSegs = max(activeSegs / 3, 1)
            for (r in 0 until reflSegs) {
                val ry0 = 6f + mainBottom + r * 0.7f * segH
                val ry1 = ry0 + 0.5f * segH
                fillPaint.color = Color.argb((70 - r * 18).coerceAtLeast(15), 0, 255, 204)
                canvas.drawRect(x0, ry0, x1, ry1, fillPaint)
            }
        }
    }

    private fun drawVectrexScope(canvas: Canvas, w: Float, h: Float) {
        val cx = 0.5f * w
        val cy = 0.54f * h
        path.reset()
        for (i in 0..96) {
            val x = i / 96f * w
            val norm = i / 96f
            val env = sin(norm * Math.PI.toFloat())
            val yOff = (sin(14f * norm + phase * 3f) * smoothLow * h * 0.32f +
                cos(38f * norm - phase * 5f) * smoothHigh * h * 0.18f) * env
            if (i == 0) path.moveTo(x, cy + yOff) else path.lineTo(x, cy + yOff)
        }
        glowPaint.color = Color.argb(85, 0, 255, 128)
        canvas.drawPath(path, glowPaint)
        linePaint.color = Color.parseColor("#00ff88")
        linePaint.strokeWidth = 3.5f
        canvas.drawPath(path, linePaint)
        path.reset()
        val rad = min(w, h) * (smoothMed * 0.18f + 0.18f)
        for (i in 0..72) {
            val t = i / 72f * 2f * Math.PI.toFloat()
            val rx = cos(2f * t + phase) * rad * (smoothLow * 0.4f + 1f) + cx
            val ry = sin(t * 3f - phase * 1.3f) * rad * (smoothHigh * 0.4f + 1f) + cy
            if (i == 0) path.moveTo(rx, ry) else path.lineTo(rx, ry)
        }
        linePaint.color = Color.parseColor("#00f2fe")
        linePaint.strokeWidth = 2.5f
        canvas.drawPath(path, linePaint)
    }

    private fun drawLaserStarfield(canvas: Canvas, w: Float, h: Float) {
        val cx = w * 0.5f
        val cy = 0.54f * h
        val speed = smoothLow * 0.07f + 0.02f
        for (i in starX.indices) {
            starZ[i] -= speed
            if (starZ[i] <= 0.05f) {
                starZ[i] = 1f
                starX[i] = (Math.random().toFloat() - 0.5f) * 2f
                starY[i] = (Math.random().toFloat() - 0.5f) * 2f
            }
            val sx = starX[i] / starZ[i] * w * 0.45f + cx
            val sy = cy + starY[i] / starZ[i] * h * 0.45f
            val prevZ = (starZ[i] + 1.8f * speed).coerceAtMost(1f)
            val px = cx + starX[i] / prevZ * w * 0.45f
            val py = cy + starY[i] / prevZ * 0.45f * h
            linePaint.color =
                Color.parseColor(if (i % 2 == 0) "#00f2fe" else "#ff00a0")
            linePaint.strokeWidth = (1f - starZ[i]) * 4.5f + 1f
            canvas.drawLine(px, py, sx, sy, linePaint)
        }
        val polyR = min(w, h) * (smoothLow * 0.22f + 0.14f)
        path.reset()
        for (i in 0 until 9) {
            val a = i * Math.PI / 4.0 + phase * 0.6
            val vx = (cos(a) * polyR).toFloat() + cx
            val vy = (sin(a) * polyR).toFloat() + cy
            if (i == 0) path.moveTo(vx, vy) else path.lineTo(vx, vy)
        }
        linePaint.color = Color.parseColor("#00ff66")
        linePaint.strokeWidth = 3f
        canvas.drawPath(path, linePaint)
    }
}
