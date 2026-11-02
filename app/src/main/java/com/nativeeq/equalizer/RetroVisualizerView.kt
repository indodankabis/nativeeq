package com.nativeeq.equalizer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Unified neon visualizer: a background layer (BARS / GRID / SCOPE / MIN) under the
 * draggable 5-band EQ curve, RAW + MIC RTA traces, peak hold and limiter GR meter.
 * Drag a node to set gain (±10 dB, snaps at 0); hold the top legend strip for A/B bypass.
 * Inputs arrive at ~10-30 Hz and are smoothed per frame, so motion stays fluid at 60 fps.
 */
class RetroVisualizerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Mode { BARS, GRID, SCOPE, MIN }

    var onGainDraggedListener: ((Int, Short) -> Unit)? = null
    var onBypassToggleListener: ((Boolean) -> Unit)? = null

    var isPaused = false
        set(v) {
            field = v
            lastFrameNs = 0L
            if (!v) postInvalidateOnAnimation()
        }

    var mode = Mode.BARS
        private set

    fun cycleMode(): Mode {
        mode = Mode.entries[(mode.ordinal + 1) % Mode.entries.size]
        postInvalidateOnAnimation()
        return mode
    }

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private fun alpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    private val cyan = EqUiHolders.CYAN
    private val magenta = EqUiHolders.MAGENTA
    private val purple = EqUiHolders.PURPLE
    private val lime = EqUiHolders.LIME
    private val amber = EqUiHolders.AMBER
    private val red = EqUiHolders.RED
    private val dim = EqUiHolders.DIM
    private val bg = EqUiHolders.WELL

    private val mono = Typeface.MONOSPACE
    private val monoBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val flat = Paint().apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val grid = Paint().apply { style = Paint.Style.STROKE; strokeWidth = dp(1f) }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        pathEffect = DashPathEffect(floatArrayOf(dp(3f), dp(4f)), 0f)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = mono; textSize = dp(9f) }
    private val textBold = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = monoBold; textSize = dp(10f) }
    private val barPaint = Paint().apply { style = Paint.Style.FILL }
    private val curveFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val curvePath = Path()
    private val areaPath = Path()
    private val tracePath = Path()
    private val rect = RectF()

    private val labels = arrayOf("60", "230", "910", "3.6k", "14k")
    private val targetGains = ShortArray(5)
    private val dispGain = FloatArray(5)
    private val rawTarget = FloatArray(5) { 0.05f }
    private val raw = FloatArray(5) { 0.05f }
    private val micTarget = FloatArray(5)
    private val mic = FloatArray(5)

    private val nBars = 28
    private val bars = FloatArray(nBars)
    private val peaks = FloatArray(nBars)
    private val peakHold = FloatArray(nBars)

    private val nodeX = FloatArray(5)
    private val nodeY = FloatArray(5)
    private val curveX = FloatArray(7)
    private val curveY = FloatArray(7)

    private var activeBand = -1
    private var isBypassed = false
    private var limiterGr = 0f
    private var phase = 0f
    private var beat = 0f
    private var prevLow = 0f
    private var lastFrameNs = 0L
    private var lastSignalMs = 0L
    private var blinkMs = 0f

    private var chartL = 0f
    private var chartR = 0f
    private var chartT = 0f
    private var chartB = 0f
    private var zeroY = 0f
    private var grL = 0f

    init {
        contentDescription = "Equalizer curve. Drag a band to change gain; hold the top edge to bypass."
    }

    fun setTargetGains(gains: ShortArray) {
        if (!isBypassed) {
            for (i in 0 until minOf(5, gains.size)) {
                if (i != activeBand) targetGains[i] = gains[i].coerceIn(-1000, 1000).toShort()
            }
            postInvalidateOnAnimation()
        }
    }

    fun getTargetGains(): ShortArray = targetGains.copyOf()

    fun updateMicBands(bands: FloatArray) {
        for (i in 0 until minOf(5, bands.size)) {
            val v = bands[i]
            micTarget[i] = if (v.isFinite()) v.coerceIn(0f, 1f) else 0f
        }
        postInvalidateOnAnimation()
    }

    fun updateSpectralTriplet(low: Float, med: Float, high: Float) {
        val l = if (low.isFinite()) low.coerceIn(0f, 1f) else 0f
        val m = if (med.isFinite()) med.coerceIn(0f, 1f) else 0f
        val h = if (high.isFinite()) high.coerceIn(0f, 1f) else 0f
        rawTarget[0] = l
        rawTarget[1] = 0.65f * l + 0.35f * m
        rawTarget[2] = m
        rawTarget[3] = 0.4f * m + 0.6f * h
        rawTarget[4] = h
        if (l - prevLow > 0.1f) beat = 1f
        prevLow = l
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val legendH = dp(18f)
        val labelsH = dp(26f)
        val pad = dp(6f)
        val grW = dp(5f)
        grL = w - pad - grW
        chartL = pad
        chartR = grL - dp(6f)
        chartT = legendH + dp(4f)
        chartB = h - labelsH
        zeroY = (chartT + chartB) * 0.5f
        barPaint.shader = LinearGradient(
            0f, chartB, 0f, chartT, intArrayOf(cyan, purple, magenta), null, Shader.TileMode.CLAMP
        )
        curveFill.shader = LinearGradient(
            0f, chartT, 0f, chartB,
            intArrayOf(alpha(cyan, 70), alpha(cyan, 10), alpha(cyan, 70)), null, Shader.TileMode.CLAMP
        )
        for (b in 0 until 5) nodeX[b] = chartL + (b + 0.5f) / 5f * (chartR - chartL)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        lastFrameNs = 0L
        if (visibility == VISIBLE) postInvalidateOnAnimation()
    }

    private fun halfH() = max((chartB - chartT) * 0.5f, 1f)
    private fun gainToY(mb: Float) = zeroY - (mb / 1000f) * halfH()
    private fun yToGain(y: Float): Short {
        var g = ((zeroY - y) / halfH() * 1000f).roundToInt().coerceIn(-1000, 1000)
        g = (g / 10) * 10
        if (abs(g) < 30) g = 0
        return g.toShort()
    }

    private fun nearestBand(x: Float): Int {
        val colW = (chartR - chartL) / 5f
        return ((x - chartL) / colW).toInt().coerceIn(0, 4)
    }

    private fun drag(y: Float) {
        val b = activeBand
        if (b !in 0 until 5) return
        val g = yToGain(y)
        val old = targetGains[b].toInt()
        if (g.toInt() == old) return
        if ((old != 0 && g.toInt() == 0) || (old > 0 && g < 0) || (old < 0 && g > 0)) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        targetGains[b] = g
        dispGain[b] = g.toFloat()
        onGainDraggedListener?.invoke(b, g)
        postInvalidateOnAnimation()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (event.y < chartT) {
                    isBypassed = true
                    blinkMs = 0f
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onBypassToggleListener?.invoke(true)
                } else {
                    activeBand = nearestBand(event.x)
                    drag(event.y)
                }
            }
            MotionEvent.ACTION_MOVE -> if (!isBypassed) drag(event.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (isBypassed) {
                    isBypassed = false
                    onBypassToggleListener?.invoke(false)
                }
                activeBand = -1
            }
        }
        postInvalidateOnAnimation()
        return true
    }

    private fun k(dt: Float, tau: Float) = 1f - exp(-dt / tau)

    private fun step(dt: Float) {
        phase += dt * (1.2f + raw[0] * 3f)
        beat *= exp(-dt / 0.18f)
        blinkMs += dt * 1000f
        var signal = false
        for (i in 0 until 5) {
            raw[i] += (rawTarget[i] - raw[i]) * k(dt, if (rawTarget[i] > raw[i]) 0.05f else 0.25f)
            mic[i] += (micTarget[i] - mic[i]) * k(dt, if (micTarget[i] > mic[i]) 0.05f else 0.3f)
            val g = if (isBypassed) 0f else targetGains[i].toFloat()
            dispGain[i] += (g - dispGain[i]) * if (i == activeBand) 1f else k(dt, 0.07f)
            if (rawTarget[i] > 0.08f || micTarget[i] > 0.05f) signal = true
        }
        if (signal) lastSignalMs = System.currentTimeMillis()
        var over = 0f
        for (i in 0 until nBars) {
            val bx = (i + 0.5f) / nBars * 5f - 0.5f
            val level = (sample(raw, bx) + sample(dispGain, bx) / 4000f).coerceIn(0f, 1.1f)
            if (level > 0.85f) over = max(over, level - 0.85f)
            val v = min(level, 1f)
            bars[i] += (v - bars[i]) * k(dt, if (v > bars[i]) 0.03f else 0.18f)
            if (bars[i] >= peaks[i]) {
                peaks[i] = bars[i]; peakHold[i] = 0.6f
            } else if (peakHold[i] > 0f) {
                peakHold[i] -= dt
            } else {
                peaks[i] = max(bars[i], peaks[i] - dt * 0.6f)
            }
        }
        limiterGr = if (over > 0f) max(limiterGr, over * 4f) else limiterGr * exp(-dt / 0.25f)
    }

    /** Catmull-Rom sample of a 5-point band array at fractional band index x. */
    private fun sample(a: FloatArray, x: Float): Float {
        val cx = x.coerceIn(0f, 4f)
        val i = floor(cx).toInt().coerceAtMost(3)
        val t = cx - i
        val p0 = a[max(i - 1, 0)]
        val p1 = a[i]
        val p2 = a[i + 1]
        val p3 = a[min(i + 2, 4)]
        val t2 = t * t
        val t3 = t2 * t
        return 0.5f * (2f * p1 + (-p0 + p2) * t + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 +
            (-p0 + 3f * p1 - 3f * p2 + p3) * t3)
    }

    private fun spline(path: Path, xs: FloatArray, ys: FloatArray, n: Int) {
        path.reset()
        path.moveTo(xs[0], ys[0])
        for (i in 0 until n - 1) {
            val cx = (xs[i] + xs[i + 1]) * 0.5f
            path.cubicTo(cx, ys[i], cx, ys[i + 1], xs[i + 1], ys[i + 1])
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 0.016f else ((now - lastFrameNs) / 1e9f).coerceIn(0.001f, 0.05f)
        lastFrameNs = now
        step(dt)

        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(bg)

        canvas.save()
        canvas.clipRect(chartL, chartT, chartR, chartB)
        when (mode) {
            Mode.BARS -> drawBars(canvas)
            Mode.GRID -> drawGrid(canvas)
            Mode.SCOPE -> drawScope(canvas)
            Mode.MIN -> Unit
        }
        drawDbGrid(canvas)
        if (mode != Mode.BARS) drawTrace(canvas, raw, alpha(purple, 170), dp(1.5f))
        if (mic.any { it > 0.02f }) drawTrace(canvas, mic, magenta, dp(1.5f))
        drawCurve(canvas)
        canvas.restore()

        drawNodes(canvas)
        drawLabels(canvas, h)
        drawGr(canvas)
        drawLegend(canvas, w)
        if (activeBand in 0 until 5) drawCallout(canvas, w)
        if (mode != Mode.MIN && System.currentTimeMillis() - lastSignalMs > 1500) {
            textBold.color = alpha(dim, 200)
            val s = "· NO SIGNAL ·"
            canvas.drawText(s, (chartL + chartR - textBold.measureText(s)) * 0.5f, chartT + dp(14f), textBold)
        }

        grid.color = Color.argb(28, 0, 0, 0)
        var sy = 0f
        while (sy < h) { canvas.drawLine(0f, sy, w, sy, grid); sy += dp(3f) }

        if (!isPaused && isShown && windowVisibility == VISIBLE) postInvalidateOnAnimation()
    }

    private fun drawBars(canvas: Canvas) {
        val cw = (chartR - chartL) / nBars
        val gap = max(dp(1.5f), cw * 0.18f)
        val hgt = chartB - chartT
        for (i in 0 until nBars) {
            val x0 = chartL + i * cw + gap * 0.5f
            val x1 = x0 + cw - gap
            canvas.drawRect(x0, chartB - bars[i] * hgt, x1, chartB, barPaint)
            flat.color = if (peaks[i] > 0.85f) red else Color.WHITE
            val py = chartB - peaks[i] * hgt
            canvas.drawRect(x0, py - dp(1.5f), x1, py, flat)
        }
        flat.color = bg
        var y = chartB - dp(4f)
        while (y > chartT) { canvas.drawRect(chartL, y, chartR, y + dp(1.2f), flat); y -= dp(4f) }
    }

    private fun drawGrid(canvas: Canvas) {
        val cx = (chartL + chartR) * 0.5f
        val horizon = chartT + (chartB - chartT) * 0.52f
        val r = (chartB - chartT) * (0.26f + raw[0] * 0.12f + beat * 0.06f)
        val sunCy = horizon - r * 0.15f
        val step = dp(2.5f)
        var y = sunCy - r
        var row = 0
        while (y < horizon) {
            val t = ((y - (sunCy - r)) / (horizon - sunCy + r)).coerceIn(0f, 1f)
            val gapEvery = if (t > 0.55f) 3 else 99
            if (row % gapEvery != gapEvery - 1) {
                val dy = y - sunCy
                val half = kotlin.math.sqrt(max(r * r - dy * dy, 0f))
                line.strokeWidth = step
                line.color = alpha(lerp(magenta, amber, t), 190)
                line.strokeCap = Paint.Cap.BUTT
                canvas.drawLine(cx - half, y, cx + half, y, line)
            }
            y += step; row++
        }
        line.strokeCap = Paint.Cap.ROUND
        line.strokeWidth = dp(1f)
        line.color = alpha(magenta, 150)
        for (i in -12..12) canvas.drawLine(cx + i * dp(6f), horizon, cx + i * (chartR - chartL) * 0.12f, chartB, line)
        val scroll = (phase * 0.6f) % 1f
        for (rI in 0 until 7) {
            val n = (rI + scroll) / 7f
            val gy = horizon + (chartB - horizon) * n * n * n
            line.color = alpha(magenta, (60 + 160 * n).toInt().coerceIn(0, 255))
            canvas.drawLine(chartL, gy, chartR, gy, line)
        }
        line.color = alpha(cyan, 200)
        canvas.drawLine(chartL, horizon, chartR, horizon, line)
    }

    private fun drawScope(canvas: Canvas) {
        val mid = (chartT + chartB) * 0.5f
        val amp = (chartB - chartT) * 0.42f
        val freqs = floatArrayOf(1.5f, 3.2f, 6.5f, 13f, 26f)
        tracePath.reset()
        val n = 120
        for (s in 0..n) {
            val u = s / n.toFloat()
            var v = 0f
            for (b in 0 until 5) v += sin(u * freqs[b] * 6.283f + phase * (2f + b)) * raw[b] * (1f / (1f + b * 0.35f))
            val env = sin(u * Math.PI.toFloat())
            val x = chartL + u * (chartR - chartL)
            val y = mid - (v * 0.55f).coerceIn(-1f, 1f) * amp * env
            if (s == 0) tracePath.moveTo(x, y) else tracePath.lineTo(x, y)
        }
        line.color = alpha(lime, 60); line.strokeWidth = dp(5f)
        canvas.drawPath(tracePath, line)
        line.color = lime; line.strokeWidth = dp(1.5f)
        canvas.drawPath(tracePath, line)
    }

    private fun drawDbGrid(canvas: Canvas) {
        text.color = alpha(dim, 220)
        for (db in intArrayOf(10, 5, 0, -5, -10)) {
            val y = gainToY(db * 100f)
            if (db == 0) {
                grid.color = alpha(cyan, 70)
                canvas.drawLine(chartL, y, chartR, y, grid)
            } else {
                dashed.color = alpha(dim, 110)
                canvas.drawLine(chartL, y, chartR, y, dashed)
            }
            val s = if (db > 0) "+$db" else "$db"
            canvas.drawText(s, chartL + dp(2f), (y - dp(2f)).coerceAtLeast(chartT + dp(9f)), text)
        }
        grid.color = alpha(dim, 60)
        for (b in 0 until 5) canvas.drawLine(nodeX[b], chartT, nodeX[b], chartB, grid)
    }

    private fun drawTrace(canvas: Canvas, a: FloatArray, color: Int, width: Float) {
        val hgt = chartB - chartT
        for (b in 0 until 5) curveY[b] = chartB - a[b].coerceIn(0.02f, 0.98f) * hgt
        spline(tracePath, nodeX, curveY, 5)
        line.color = color
        line.strokeWidth = width
        canvas.drawPath(tracePath, line)
    }

    private fun drawCurve(canvas: Canvas) {
        curveX[0] = chartL; curveX[6] = chartR
        for (b in 0 until 5) {
            nodeY[b] = gainToY(dispGain[b]).coerceIn(chartT + dp(2f), chartB - dp(2f))
            curveX[b + 1] = nodeX[b]; curveY[b + 1] = nodeY[b]
        }
        curveY[0] = nodeY[0]; curveY[6] = nodeY[4]
        spline(curvePath, curveX, curveY, 7)
        areaPath.set(curvePath)
        areaPath.lineTo(chartR, zeroY); areaPath.lineTo(chartL, zeroY); areaPath.close()
        val c = if (isBypassed) amber else cyan
        if (!isBypassed) canvas.drawPath(areaPath, curveFill)
        line.color = alpha(c, if (isBypassed) 40 else 70); line.strokeWidth = dp(6f)
        canvas.drawPath(curvePath, line)
        line.color = alpha(c, if (isBypassed) 110 else 255); line.strokeWidth = dp(2f)
        canvas.drawPath(curvePath, line)
        if (isBypassed) {
            dashed.color = amber; dashed.strokeWidth = dp(2f)
            canvas.drawLine(chartL, zeroY, chartR, zeroY, dashed)
            dashed.strokeWidth = dp(1f)
        }
    }

    private fun drawNodes(canvas: Canvas) {
        for (b in 0 until 5) {
            val active = b == activeBand
            val s = if (active) dp(7f) else dp(5f)
            rect.set(nodeX[b] - s, nodeY[b] - s, nodeX[b] + s, nodeY[b] + s)
            if (active) {
                grid.color = alpha(cyan, 120)
                canvas.drawLine(nodeX[b], chartT, nodeX[b], chartB, grid)
            }
            fill.color = if (active) Color.WHITE else bg
            canvas.drawRect(rect, fill)
            line.color = if (isBypassed) amber else cyan
            line.strokeWidth = dp(1.5f)
            canvas.drawRect(rect, line)
        }
    }

    private fun drawLabels(canvas: Canvas, h: Float) {
        for (b in 0 until 5) {
            val mb = targetGains[b].toInt()
            val db = String.format(Locale.US, "%+.1f", mb / 100f)
            textBold.color = when {
                isBypassed -> amber
                mb > 0 -> cyan
                mb < 0 -> magenta
                else -> dim
            }
            canvas.drawText(db, nodeX[b] - textBold.measureText(db) * 0.5f, h - dp(14f), textBold)
            text.color = alpha(purple, 220)
            val f = labels[b]
            canvas.drawText(f, nodeX[b] - text.measureText(f) * 0.5f, h - dp(3f), text)
        }
        text.color = alpha(amber, 220)
        canvas.drawText("GR", grL - dp(5f), h - dp(3f), text)
    }

    private fun drawGr(canvas: Canvas) {
        flat.color = Color.parseColor("#14141f")
        canvas.drawRect(grL, chartT, width - dp(6f), chartB, flat)
        val gh = limiterGr.coerceIn(0f, 1f) * (chartB - chartT)
        if (gh > 1f) {
            flat.color = if (limiterGr > 0.6f) red else amber
            canvas.drawRect(grL, chartT, width - dp(6f), chartT + gh, flat)
        }
    }

    private fun drawLegend(canvas: Canvas, w: Float) {
        val base = dp(13f)
        textBold.color = cyan
        val m = "[${mode.name}]"
        canvas.drawText(m, chartL, base, textBold)
        var x = chartL + textBold.measureText(m) + dp(8f)
        if (isBypassed) {
            textBold.color = if ((blinkMs / 400f).toInt() % 2 == 0) amber else alpha(amber, 110)
            canvas.drawText("■ BYPASS · 0 dB", x, base, textBold)
            return
        }
        fun key(label: String, color: Int) {
            flat.color = color
            canvas.drawRect(x, base - dp(4.5f), x + dp(8f), base - dp(2.5f), flat)
            x += dp(10f)
            text.color = alpha(color, 220)
            canvas.drawText(label, x, base, text)
            x += text.measureText(label) + dp(8f)
        }
        key("EQ", cyan)
        if (mode != Mode.BARS) key("RAW", purple)
        key("MIC", magenta)
        text.color = alpha(dim, 230)
        val hint = "HOLD ▔ A/B"
        val hx = w - dp(6f) - text.measureText(hint)
        if (hx > x) canvas.drawText(hint, hx, base, text)
    }

    private fun drawCallout(canvas: Canvas, w: Float) {
        val b = activeBand
        val s = String.format(Locale.US, "%s Hz  %+.1f dB", labels[b], targetGains[b] / 100f)
        val tw = textBold.measureText(s) + dp(12f)
        val cx = nodeX[b].coerceIn(tw * 0.5f + dp(4f), w - tw * 0.5f - dp(4f))
        val above = nodeY[b] - dp(16f) > chartT + dp(14f)
        val cy = if (above) nodeY[b] - dp(16f) else nodeY[b] + dp(26f)
        rect.set(cx - tw * 0.5f, cy - dp(13f), cx + tw * 0.5f, cy + dp(4f))
        fill.color = alpha(bg, 235)
        canvas.drawRoundRect(rect, dp(3f), dp(3f), fill)
        line.color = cyan; line.strokeWidth = dp(1f)
        canvas.drawRoundRect(rect, dp(3f), dp(3f), line)
        textBold.color = cyan
        canvas.drawText(s, cx - tw * 0.5f + dp(6f), cy, textBold)
    }

    private fun lerp(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )
}
