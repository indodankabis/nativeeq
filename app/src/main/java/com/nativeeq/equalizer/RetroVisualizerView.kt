package com.nativeeq.equalizer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Interactive 5-band EQ graph. RAW (system FFT), DSP (applied curve, draggable
 * nodes) and MIC RTA splines. Hold near the top edge for A/B bypass.
 */
class RetroVisualizerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onGainDraggedListener: ((Int, Short) -> Unit)? = null
    var onBypassToggleListener: ((Boolean) -> Unit)? = null

    private val bgPaint = Paint().apply { color = Color.parseColor("#080c10") }
    private val gridPaint = Paint().apply {
        color = Color.parseColor("#1e293b"); strokeWidth = 1.5f
    }
    private val blockPaint = Paint().apply { isAntiAlias = false }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00ff66"); textSize = 20f; isFakeBoldText = true
    }
    private val dbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38bdf8"); textSize = 18f; isFakeBoldText = true
    }
    private val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 17f; isFakeBoldText = true
    }
    private val rawCurvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94a3b8"); strokeWidth = 4f; style = Paint.Style.STROKE
    }
    private val dspCurvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00f2fe"); strokeWidth = 6f; style = Paint.Style.STROKE
    }
    private val micCurvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#f43f5e"); strokeWidth = 4.5f; style = Paint.Style.STROKE
    }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val calloutBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#e60f172a"); style = Paint.Style.FILL
    }
    private val calloutStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00f2fe"); strokeWidth = 2.5f; style = Paint.Style.STROKE
    }

    private val rawPath = Path()
    private val dspPath = Path()
    private val micPath = Path()
    private val tempRect = RectF()
    private val calloutRect = RectF()

    private val labels = arrayOf("60Hz", "230Hz", "910Hz", "3.6k", "14k")
    private val rawLevels = FloatArray(5) { 0.15f }
    private val micLevels = FloatArray(5) { 0.1f }
    private val targetGains = ShortArray(5)

    private val centersX = FloatArray(5)
    private val rawY = FloatArray(5)
    private val dspY = FloatArray(5)
    private val micY = FloatArray(5)

    private val maxBlocks = 10
    private var activeBandIndex = -1
    private var isBypassed = false
    private var limiterGrDb = 0f
    private var padX = 18f
    private var chartTop = 36f
    private var chartBottom = 180f
    private var colWidth = 50f

    fun setTargetGains(gains: ShortArray) {
        if (!isBypassed) {
            for (i in 0 until minOf(5, gains.size)) targetGains[i] = gains[i]
            postInvalidateOnAnimation()
        }
    }

    fun getTargetGains(): ShortArray = targetGains.copyOf()

    fun updateMicBands(bands: FloatArray) {
        for (i in 0 until minOf(5, bands.size)) {
            micLevels[i] = bands[i].coerceIn(0f, 1f)
        }
        postInvalidateOnAnimation()
    }

    fun updateSpectralTriplet(low: Float, med: Float, high: Float) {
        rawLevels[0] = rawLevels[0] * 0.6f + low.coerceIn(0.05f, 1f) * 0.4f
        rawLevels[1] = rawLevels[1] * 0.6f +
            (0.65f * low + 0.35f * med).coerceIn(0.05f, 1f) * 0.4f
        rawLevels[2] = rawLevels[2] * 0.6f + med.coerceIn(0.05f, 1f) * 0.4f
        rawLevels[3] = rawLevels[3] * 0.6f +
            (med * 0.4f + high * 0.6f).coerceIn(0.05f, 1f) * 0.4f
        rawLevels[4] = rawLevels[4] * 0.6f + high.coerceIn(0.05f, 1f) * 0.4f
        postInvalidateOnAnimation()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        val hY = max(chartBottom - chartTop, 1f)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (y < chartTop + 8f) {
                    isBypassed = true
                    onBypassToggleListener?.invoke(true)
                    postInvalidateOnAnimation()
                    return true
                }
                for (b in 0 until 5) {
                    if (abs(x - centersX[b]) < colWidth * 0.55f) {
                        activeBandIndex = b
                        val rawNormY = ((chartBottom - y) / hY).coerceIn(0f, 1f)
                        val gainMb =
                            ((rawNormY - 0.5f) * 2000f).toInt().coerceIn(-1000, 1000).toShort()
                        targetGains[b] = gainMb
                        onGainDraggedListener?.invoke(b, gainMb)
                        postInvalidateOnAnimation()
                        return true
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (isBypassed) {
                    isBypassed = false
                    onBypassToggleListener?.invoke(false)
                }
                activeBandIndex = -1
                postInvalidateOnAnimation()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isBypassed && activeBandIndex != -1) {
                    val rawNormY = ((chartBottom - y) / hY).coerceIn(0f, 1f)
                    val gainMb =
                        ((rawNormY - 0.5f) * 2000f).toInt().coerceIn(-1000, 1000).toShort()
                    targetGains[activeBandIndex] = gainMb
                    onGainDraggedListener?.invoke(activeBandIndex, gainMb)
                    postInvalidateOnAnimation()
                }
                return true
            }
        }
        return true
    }

    private fun buildSmoothSpline(path: Path, x: FloatArray, y: FloatArray) {
        path.reset()
        path.moveTo(x[0], y[0])
        for (i in 0 until 4) {
            val cx = (x[i] + x[i + 1]) * 0.5f
            path.cubicTo(cx, y[i], cx, y[i + 1], x[i + 1], y[i + 1])
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        padX = 16f
        val grMeterW = 24f
        chartTop = 32f
        chartBottom = h - 46f
        val chartH = chartBottom - chartTop
        colWidth = (w - padX * 2 - grMeterW) / 5f

        legendPaint.color = Color.parseColor("#94a3b8")
        canvas.drawText("─ RAW", padX, 22f, legendPaint)
        legendPaint.color =
            if (isBypassed) Color.parseColor("#ffaa00") else Color.parseColor("#00f2fe")
        canvas.drawText(
            if (isBypassed) "━ BYPASSED (0dB)" else "━ DSP (DRAG / HOLD=A-B)",
            0.22f * w, 22f, legendPaint
        )
        legendPaint.color = Color.parseColor("#f43f5e")
        canvas.drawText("─ MIC RTA", 0.72f * w, 22f, legendPaint)

        val zeroDbY = chartTop + chartH * 0.5f
        canvas.drawLine(padX, zeroDbY, w - padX - grMeterW, zeroDbY, gridPaint)

        var peakOvershoot = 0f
        val bH = chartH / maxBlocks
        for (b in 0 until 5) {
            val xStart = padX + b * colWidth + 6f
            val xEnd = padX + (b + 1) * colWidth - 6f
            val cx = (xStart + xEnd) * 0.5f
            centersX[b] = cx
            val gainMb = if (isBypassed) 0 else targetGains[b].toInt()
            val gainNorm = (gainMb / 1000f).coerceIn(-0.48f, 0.48f)
            val postLevel = (rawLevels[b] + 0.75f * gainNorm).coerceIn(0f, 1.15f)
            if (postLevel > 0.82f) peakOvershoot = max(peakOvershoot, postLevel - 0.82f)
            val activeCount = (postLevel.coerceAtMost(1f) * maxBlocks).toInt()
            for (block in 0 until maxBlocks) {
                val top = chartBottom - (block + 1) * bH + 2f
                tempRect.set(xStart, top, xEnd, top + bH - 3f)
                blockPaint.color = if (block < activeCount) {
                    when {
                        block >= 8 -> Color.parseColor("#44ff3344")
                        block >= 5 -> Color.parseColor("#44ffaa00")
                        else -> Color.parseColor("#4400ff66")
                    }
                } else {
                    Color.parseColor("#101820")
                }
                canvas.drawRect(tempRect, blockPaint)
            }
            rawY[b] = chartBottom - rawLevels[b].coerceIn(0.05f, 0.95f) * chartH
            dspY[b] =
                (zeroDbY - gainNorm * chartH).coerceIn(chartTop + 6f, chartBottom - 6f)
            micY[b] = chartBottom - micLevels[b].coerceIn(0.05f, 0.95f) * chartH
            val dbStr = String.format(Locale.US, "%+.1f", gainMb / 100f)
            dbPaint.color = when {
                gainMb > 0 -> Color.parseColor("#00f2fe")
                gainMb < 0 -> Color.parseColor("#ffaa00")
                else -> Color.parseColor("#64748b")
            }
            canvas.drawText(
                dbStr, cx - dbPaint.measureText(dbStr) * 0.5f, h - 24f, dbPaint
            )
            val lbl = labels[b]
            canvas.drawText(lbl, cx - textPaint.measureText(lbl) * 0.5f, h - 4f, textPaint)
        }

        buildSmoothSpline(rawPath, centersX, rawY)
        buildSmoothSpline(micPath, centersX, micY)
        buildSmoothSpline(dspPath, centersX, dspY)
        canvas.drawPath(rawPath, rawCurvePaint)
        canvas.drawPath(micPath, micCurvePaint)
        canvas.drawPath(dspPath, dspCurvePaint)

        for (b in 0 until 5) {
            nodePaint.color = Color.parseColor("#94a3b8")
            canvas.drawCircle(centersX[b], rawY[b], 4.5f, nodePaint)
            nodePaint.color = Color.parseColor("#f43f5e")
            canvas.drawCircle(centersX[b], micY[b], 5.5f, nodePaint)
            nodePaint.color =
                if (b == activeBandIndex) Color.WHITE else Color.parseColor("#00f2fe")
            canvas.drawCircle(
                centersX[b], dspY[b], if (b == activeBandIndex) 14f else 10f, nodePaint
            )
        }

        limiterGrDb =
            if (peakOvershoot > 0f) 3.2f * peakOvershoot else limiterGrDb * 0.85f
        val grX = w - padX - grMeterW + 4f
        tempRect.set(grX, chartTop, w - padX, chartBottom)
        blockPaint.color = Color.parseColor("#101820")
        canvas.drawRect(tempRect, blockPaint)
        val grHeight = limiterGrDb.coerceIn(0f, 1f) * chartH
        if (grHeight > 2f) {
            tempRect.set(grX, chartTop, w - padX, chartTop + grHeight)
            blockPaint.color = Color.parseColor("#f59e0b")
            canvas.drawRect(tempRect, blockPaint)
        }
        canvas.drawText("GR", grX - 2f, h - 4f, dbPaint)

        if (activeBandIndex in 0 until 5) {
            val b3 = activeBandIndex
            val calloutTxt = String.format(
                Locale.US, "[ %s : %+.1f dB ]", labels[b3], targetGains[b3] / 100f
            )
            val tw = textPaint.measureText(calloutTxt) + 28f
            val cx2 = centersX[b3].coerceIn(tw * 0.5f + 8f, w - tw * 0.5f - 8f)
            val cy = (dspY[b3] - 42f).coerceAtLeast(chartTop + 24f)
            calloutRect.set(cx2 - tw * 0.5f, cy - 26f, cx2 + tw * 0.5f, 10f + cy)
            canvas.drawRoundRect(calloutRect, 8f, 8f, calloutBgPaint)
            canvas.drawRoundRect(calloutRect, 8f, 8f, calloutStrokePaint)
            canvas.drawText(calloutTxt, cx2 - (tw - 28f) * 0.5f, cy - 2f, textPaint)
        }
    }
}
