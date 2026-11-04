package com.nativeeq.equalizer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * MilkDrop-style feedback visualizer. Each frame the previous frame is redrawn through a
 * warped mesh (zoom / rotation / swirl / sine warp) with decay + hue drift, then neon
 * waveforms synthesized from the low/mid/high bands are drawn on top. A [Flavor] is the
 * full parameter genome; the AI flavor generates seeded genomes and morphs between them.
 * Renders into a low-res buffer and upscales, which keeps it cheap and CRT-soft.
 */
class HypnoVisualizerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Shape { RING, LINE, LISSAJOUS, SPIRAL, STAR, BURST }

    data class Flavor(
        val name: String,
        val zoom: Float, val rot: Float, val swirl: Float,
        val warp: Float, val warpFreq: Float, val warpSpeed: Float,
        val decay: Float, val hue: Float, val drift: Float,
        val shape: Shape, val sym: Int, val mirror: Boolean,
        val palette: IntArray, val amp: Float, val thick: Float,
        val kick: Float, val flash: Boolean,
    )

    var isPaused = false
        set(v) {
            field = v
            lastFrameNs = 0L
            if (!v) postInvalidateOnAnimation()
        }

    var onFlavorChanged: (() -> Unit)? = null

    val flavorName: String get() = if (flavorIdx == AI) "AI · ${to.name}" else to.name

    private val cyan = EqUiHolders.CYAN
    private val magenta = EqUiHolders.MAGENTA
    private val purple = EqUiHolders.PURPLE
    private val lime = EqUiHolders.LIME
    private val amber = EqUiHolders.AMBER
    private val neon = intArrayOf(cyan, magenta, purple, lime, amber, Color.parseColor("#3d7bff"))

    private val adjectives = arrayOf("NEON", "VOID", "ACID", "CHROME", "LUNAR", "HYPER", "GHOST",
        "PLASMA", "SOLAR", "ULTRA", "VECTOR", "ASTRAL", "QUANTUM", "TURBO", "ZERO")
    private val nouns = arrayOf("BLOOM", "WARP", "DRIFT", "HELIX", "ECHO", "FLUX", "PRISM", "SPIRAL",
        "STORM", "ORBIT", "MIRAGE", "PULSE", "TIDE", "LATTICE", "CORONA")

    private val presets = listOf(
        Flavor("TUNNEL", 1.045f, 0.004f, 0f, 0.4f, 3f, 0.6f, 0.94f, 0.6f, 0.04f,
            Shape.RING, 1, false, intArrayOf(cyan, magenta, purple), 0.30f, 1.6f, 0.05f, true),
        Flavor("VORTEX", 1.02f, 0.02f, 0.05f, 0.6f, 4f, 1.1f, 0.955f, 1.2f, 0.06f,
            Shape.SPIRAL, 3, false, intArrayOf(magenta, purple, cyan), 0.32f, 1.4f, 0.03f, false),
        Flavor("NEBULA", 1.008f, -0.006f, 0.02f, 2.4f, 6f, 0.7f, 0.97f, 0.35f, 0.12f,
            Shape.LISSAJOUS, 2, true, intArrayOf(purple, cyan, lime), 0.38f, 1.2f, 0.02f, false),
        Flavor("KALEIDO", 1.03f, 0.012f, -0.03f, 0.8f, 5f, 1.6f, 0.93f, -1.5f, 0.03f,
            Shape.STAR, 6, true, intArrayOf(lime, cyan, magenta), 0.28f, 1.3f, 0.04f, true),
        Flavor("PULSAR", 0.965f, -0.01f, 0.04f, 0.5f, 3f, 0.9f, 0.95f, 0.9f, 0.05f,
            Shape.BURST, 8, false, intArrayOf(amber, magenta, cyan), 0.42f, 1.5f, 0.06f, true),
        Flavor("DRIFT", 1.012f, 0.002f, 0f, 1.6f, 2.5f, 0.4f, 0.965f, 0.25f, 0.15f,
            Shape.LINE, 1, true, intArrayOf(cyan, lime, purple), 0.40f, 1.4f, 0.02f, false),
    )
    private val AI = presets.size
    private var flavorIdx = AI
    private val rng = Random(System.nanoTime())
    private var cur: Flavor = genome(rng.nextInt())
    private var from: Flavor = cur
    private var to: Flavor = cur
    private var morph = 1f
    private var aiTimer = 0f
    private var nameFade = 3f

    fun nextFlavor() {
        flavorIdx = (flavorIdx + 1) % (presets.size + 1)
        transitionTo(if (flavorIdx == AI) genome(rng.nextInt()) else presets[flavorIdx])
    }

    fun newAiFlavor() {
        flavorIdx = AI
        transitionTo(genome(rng.nextInt()))
    }

    private fun transitionTo(f: Flavor) {
        from = cur; to = f; morph = 0f; aiTimer = 0f; nameFade = 3f
        onFlavorChanged?.invoke()
        postInvalidateOnAnimation()
    }

    /** Seeded random genome, biased toward combinations that stay readable. */
    private fun genome(seed: Int): Flavor {
        val r = Random(seed)
        fun f(a: Float, b: Float) = a + r.nextFloat() * (b - a)
        val shape = Shape.entries[r.nextInt(Shape.entries.size)]
        val sym = intArrayOf(1, 2, 3, 4, 5, 6, 8)[r.nextInt(7)]
        val pal = neon.copyOf().also { it.shuffle(r) }.copyOf(3)
        val zoom = if (r.nextFloat() < 0.75f) f(1.005f, 1.05f) else f(0.96f, 0.995f)
        return Flavor(
            "${adjectives[r.nextInt(adjectives.size)]} ${nouns[r.nextInt(nouns.size)]} " +
                "#${(seed and 0xffff).toString(16).uppercase().padStart(4, '0')}",
            zoom, f(-0.022f, 0.022f), f(-0.05f, 0.05f),
            f(0f, 2.6f), f(2f, 9f), f(0.3f, 2.2f),
            f(0.915f, 0.975f), f(-2f, 2f), f(0f, 0.14f),
            shape, sym, r.nextBoolean(), pal, f(0.22f, 0.42f), f(1f, 2.2f),
            f(0f, 0.06f), r.nextFloat() < 0.5f,
        )
    }

    private fun blend(a: Flavor, b: Flavor, t: Float): Flavor {
        if (t >= 1f) return b
        fun l(x: Float, y: Float) = x + (y - x) * t
        val disc = if (t < 0.5f) a else b
        return Flavor(b.name, l(a.zoom, b.zoom), l(a.rot, b.rot), l(a.swirl, b.swirl),
            l(a.warp, b.warp), l(a.warpFreq, b.warpFreq), l(a.warpSpeed, b.warpSpeed),
            l(a.decay, b.decay), l(a.hue, b.hue), l(a.drift, b.drift),
            disc.shape, disc.sym, disc.mirror,
            IntArray(3) { lerpColor(a.palette[it], b.palette[it], t) },
            l(a.amp, b.amp), l(a.thick, b.thick), l(a.kick, b.kick), disc.flash)
    }

    // ── audio ────────────────────────────────────────────────────────────────
    private var lowT = 0f; private var midT = 0f; private var highT = 0f
    private var low = 0f; private var mid = 0f; private var high = 0f
    private var lowAvg = 0f
    private var beat = 0f
    private var lastBeatMs = 0L
    private var lastSignalMs = 0L

    fun updateSpectralBands(low: Float, med: Float, high: Float) {
        lowT = low.coerceIn(0f, 1f); midT = med.coerceIn(0f, 1f); highT = high.coerceIn(0f, 1f)
        val now = System.currentTimeMillis()
        if (lowT + midT + highT > 0.06f) lastSignalMs = now
        if (lowT > lowAvg * 1.35f + 0.04f && now - lastBeatMs > 220) {
            beat = 1f; lastBeatMs = now; beatPending = true
        }
        lowAvg += (lowT - lowAvg) * 0.08f
    }

    private val realWave = FloatArray(96)
    private var waveGain = 1f
    private var lastWaveMs = 0L

    /** Real PCM frame from [AudioTap] (unsigned 8-bit, 128 = 0). Auto-gained to fill the shape. */
    fun updateWaveform(w: ByteArray) {
        if (w.isEmpty()) return
        var peak = 0f
        for (i in realWave.indices) {
            val v = ((w[i * w.size / realWave.size].toInt() and 0xff) - 128) / 128f
            realWave[i] = v
            peak = max(peak, abs(v))
        }
        if (peak < 0.01f) return
        waveGain += ((0.9f / peak).coerceIn(1f, 12f) - waveGain) * 0.1f
        lastWaveMs = System.currentTimeMillis()
    }

    /** Real FFT frame from [AudioTap]: drives low/mid/high when the DSP telemetry is idle. */
    fun updateFft(fft: ByteArray, samplingRateMilliHz: Int) {
        val n = fft.size
        if (n < 8 || samplingRateMilliHz <= 0) return
        val binHz = samplingRateMilliHz / 1000f / n
        var l = 0f; var m = 0f; var h = 0f
        for (k in 1 until n / 2) {
            val f = k * binHz
            val v = AudioTap.level(AudioTap.mag(fft, k))
            when {
                f < 250f -> l = max(l, v)
                f < 4000f -> m = max(m, v)
                else -> h = max(h, v)
            }
        }
        if (l + m + h > 0.05f) updateSpectralBands(l, m, h)
    }

    // ── buffers ──────────────────────────────────────────────────────────────
    private var front: Bitmap? = null
    private var back: Bitmap? = null
    private var backCanvas: Canvas? = null
    private var frontCanvas: Canvas? = null
    private var bw = 1; private var bh = 1
    private val GX = 24; private val GY = 14
    private val verts = FloatArray((GX + 1) * (GY + 1) * 2)
    private val cm = ColorMatrix()
    private val feedback = Paint(Paint.FILTER_BITMAP_FLAG)
    private val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val blit = Paint(Paint.FILTER_BITMAP_FLAG)
    private val scan = Paint().apply { color = Color.argb(46, 0, 0, 0) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val d = resources.displayMetrics.density
    private val path = Path()
    private val src = Rect()
    private val dst = RectF()
    private var t = 0f
    private var beatPending = false
    private var lastFrameNs = 0L
    private val N = 96
    private val samples = FloatArray(N)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0) return
        val scale = max(1f, w / 360f)
        bw = max(1, (w / scale).toInt()); bh = max(1, (h / scale).toInt())
        front?.recycle(); back?.recycle()
        front = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.BLACK) }
        back = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.BLACK) }
        backCanvas = Canvas(back!!)
        frontCanvas = Canvas(front!!)
        src.set(0, 0, bw, bh)
        dst.set(0f, 0f, w.toFloat(), h.toFloat())
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        front?.recycle(); back?.recycle()
        front = null; back = null; backCanvas = null; frontCanvas = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (width > 0 && height > 0 && front == null) onSizeChanged(width, height, 0, 0)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        lastFrameNs = 0L
        if (visibility == VISIBLE) postInvalidateOnAnimation()
    }

    private fun k(dt: Float, tau: Float) = 1f - exp(-dt / tau)

    private fun step(dt: Float) {
        t += dt
        val idle = System.currentTimeMillis() - lastSignalMs > 1500
        // With no audio, breathe on a slow synthetic pulse so the screen never dies.
        val il = if (idle) 0.22f + 0.12f * sin(t * 1.7f) else lowT
        val im = if (idle) 0.18f + 0.08f * sin(t * 0.9f + 1f) else midT
        val ih = if (idle) 0.10f + 0.06f * sin(t * 2.3f + 2f) else highT
        low += (il - low) * k(dt, if (il > low) 0.04f else 0.2f)
        mid += (im - mid) * k(dt, if (im > mid) 0.05f else 0.25f)
        high += (ih - high) * k(dt, if (ih > high) 0.03f else 0.15f)
        if (idle && (t % 2.4f) < dt) { beat = 0.7f; beatPending = true }
        beat *= exp(-dt / 0.2f)

        if (morph < 1f) morph = min(1f, morph + dt / 2.5f)
        cur = blend(from, to, smooth(morph))
        if (nameFade > 0f) nameFade -= dt
        if (flavorIdx == AI) {
            aiTimer += dt
            if (aiTimer > 14f && morph >= 1f) transitionTo(genome(rng.nextInt()))
        }
    }

    private fun smooth(x: Float) = x * x * (3f - 2f * x)

    private fun render(dt: Float) {
        val fr = front ?: return
        val bk = back ?: return
        val c = backCanvas ?: return
        val f = cur
        val frames = dt * 60f
        val w = bw.toFloat(); val h = bh.toFloat()

        // 1. feedback: previous frame through the warp mesh
        c.drawColor(Color.BLACK)
        val cx = w * (0.5f + f.drift * sin(t * 0.37f))
        val cy = h * (0.5f + f.drift * cos(t * 0.29f))
        val zoom = (f.zoom + f.kick * beat).pow(frames)
        val rot = (f.rot + f.rot * mid * 2f) * frames
        val norm = 1f / max(w, h)
        var vi = 0
        for (j in 0..GY) {
            val v = j / GY.toFloat()
            for (i in 0..GX) {
                val u = i / GX.toFloat()
                val dx = u * w - cx
                val dy = v * h - cy
                val rr = sqrt(dx * dx + dy * dy) * norm * 2f
                val a = rot + f.swirl * frames * (1f - min(rr, 1f))
                val ca = cos(a); val sa = sin(a)
                val ww = f.warp * frames * (0.5f + mid)
                verts[vi++] = cx + (dx * ca - dy * sa) * zoom + ww * sin(v * f.warpFreq + t * f.warpSpeed)
                verts[vi++] = cy + (dx * sa + dy * ca) * zoom + ww * cos(u * f.warpFreq * 1.1f + t * f.warpSpeed * 1.3f)
            }
        }
        val decay = f.decay.pow(frames)
        cm.setScale(decay, decay, decay, 1f)
        cm.postConcat(ColorMatrix(floatArrayOf(
            1f, 0f, 0f, 0f, -3f * frames,
            0f, 1f, 0f, 0f, -3f * frames,
            0f, 0f, 1f, 0f, -3f * frames,
            0f, 0f, 0f, 1f, 0f,
        )))
        cm.preConcat(hueRotate(f.hue * frames))
        sat.setSaturation(1f + 0.025f * frames)
        cm.preConcat(sat)
        feedback.colorFilter = ColorMatrixColorFilter(cm)
        c.drawBitmapMesh(fr, GX, GY, verts, 0, null, 0, feedback)

        // 2. waveforms
        synth()
        val sw = (f.thick + beat * 1.5f) * w / 360f
        val ccx = w * 0.5f; val ccy = h * 0.5f
        val base = min(w, h)
        for (s in 0 until f.sym) {
            for (m in 0 until if (f.mirror) 2 else 1) {
                c.save()
                c.translate(ccx, ccy)
                c.rotate(360f / f.sym * s + t * 12f * f.rot * 60f)
                if (m == 1) c.scale(-1f, 1f)
                val col = palette(f, s * 0.33f + m * 0.5f)
                buildShape(f.shape, base, w)
                wave.color = col
                wave.alpha = 60
                wave.strokeWidth = sw * 3f
                c.drawPath(path, wave)
                wave.alpha = 255
                wave.strokeWidth = sw
                c.drawPath(path, wave)
                c.restore()
            }
        }
        if (beatPending) {
            beatPending = false
            if (f.flash) {
                wave.color = palette(f, 0.66f)
                wave.alpha = 200
                wave.strokeWidth = sw * 1.5f
                c.drawCircle(ccx, ccy, base * (0.08f + 0.1f * low), wave)
            }
        }

        front = bk; back = fr
        backCanvas = frontCanvas.also { frontCanvas = c }
    }

    private val hueTmp = ColorMatrix()
    private val sat = ColorMatrix()
    private fun hueRotate(deg: Float): ColorMatrix {
        val r = deg * PI.toFloat() / 180f
        val cs = cos(r); val sn = sin(r)
        val lr = 0.213f; val lg = 0.715f; val lb = 0.072f
        hueTmp.set(floatArrayOf(
            lr + cs * (1 - lr) + sn * (-lr), lg + cs * (-lg) + sn * (-lg), lb + cs * (-lb) + sn * (1 - lb), 0f, 0f,
            lr + cs * (-lr) + sn * 0.143f, lg + cs * (1 - lg) + sn * 0.14f, lb + cs * (-lb) + sn * (-0.283f), 0f, 0f,
            lr + cs * (-lr) + sn * (-(1 - lr)), lg + cs * (-lg) + sn * lg, lb + cs * (1 - lb) + sn * lb, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ))
        return hueTmp
    }

    /** Pseudo-waveform from the three bands (only band energies are available). */
    private fun synth() {
        if (System.currentTimeMillis() - lastWaveMs < 300) {
            for (i in 0 until N) samples[i] += (realWave[i] * waveGain * 0.8f - samples[i]) * 0.5f
            return
        }
        for (i in 0 until N) {
            val x = i / N.toFloat() * 2f * PI.toFloat()
            samples[i] = low * sin(x + t * 2f) * 0.6f +
                mid * sin(3f * x - t * 3.1f) * 0.35f +
                high * sin(9f * x + t * 7f) * 0.25f +
                high * 0.15f * sin(23f * x - t * 11f)
        }
    }

    private fun buildShape(shape: Shape, base: Float, w: Float) {
        path.reset()
        val f = cur
        val amp = base * f.amp * (0.6f + low * 0.8f)
        when (shape) {
            Shape.RING, Shape.STAR -> {
                val r0 = base * (0.16f + 0.1f * low)
                for (i in 0..N) {
                    val k = i % N
                    val a = k / N.toFloat() * 2f * PI.toFloat()
                    val spike = if (shape == Shape.STAR) abs(sin(a * 2.5f)) * amp * 0.5f else 0f
                    val r = r0 + samples[k] * amp * 0.5f + spike
                    val x = cos(a) * r; val y = sin(a) * r
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
            }
            Shape.LINE -> {
                for (i in 0 until N) {
                    val x = (i / (N - 1f) - 0.5f) * w * 0.9f
                    val y = samples[i] * amp
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
            }
            Shape.LISSAJOUS -> {
                val pa = 3f + (mid * 2f); val pb = 2f
                for (i in 0..N * 2) {
                    val s = i / (N * 2f) * 2f * PI.toFloat()
                    val x = sin(pa * s + t * 0.7f) * amp * 1.1f
                    val y = sin(pb * s) * amp * 0.8f + samples[i % N] * amp * 0.2f
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
            }
            Shape.SPIRAL -> {
                for (i in 0 until N) {
                    val p = i / N.toFloat()
                    val a = p * 4f * PI.toFloat() + t
                    val r = p * base * 0.42f + samples[i] * amp * 0.3f
                    val x = cos(a) * r; val y = sin(a) * r
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
            }
            Shape.BURST -> {
                for (i in 0 until 12) {
                    val a = i / 12f * 2f * PI.toFloat()
                    val r = base * 0.14f + (abs(samples[i * 8]) + low) * amp * 1.4f
                    path.moveTo(cos(a) * base * 0.04f, sin(a) * base * 0.04f)
                    path.lineTo(cos(a) * r, sin(a) * r)
                }
            }
        }
    }

    private fun palette(f: Flavor, off: Float): Int {
        val p = ((t * 0.15f + off) % 1f) * 3f
        val i = p.toInt() % 3
        return lerpColor(f.palette[i], f.palette[(i + 1) % 3], p - p.toInt())
    }

    private fun lerpColor(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 0.016f else ((now - lastFrameNs) / 1e9f).coerceIn(0.001f, 0.05f)
        lastFrameNs = now
        step(dt)
        render(dt)
        val w = width.toFloat(); val h = height.toFloat()
        front?.let { canvas.drawBitmap(it, src, dst, blit) } ?: canvas.drawColor(Color.BLACK)

        var y = 0f
        val gap = 3f * d
        while (y < h) { canvas.drawRect(0f, y, w, y + d, scan); y += gap }

        label.textSize = 10f * d
        label.color = Color.argb(170, 255, 255, 255)
        canvas.drawText("[${if (flavorIdx == AI) "AI" else "${flavorIdx + 1}/${presets.size}"}]", 6f * d, 13f * d, label)
        if (nameFade > 0f) {
            val a = (min(1f, nameFade) * 255).toInt()
            label.textSize = 16f * d
            label.isFakeBoldText = true
            val name = to.name
            label.color = Color.argb(a, 0, 0, 0)
            val tx = (w - label.measureText(name)) / 2f
            canvas.drawText(name, tx + d, h / 2f + d, label)
            label.color = Color.argb(a, Color.red(cyan), Color.green(cyan), Color.blue(cyan))
            canvas.drawText(name, tx, h / 2f, label)
            label.isFakeBoldText = false
            label.textSize = 9f * d
            label.color = Color.argb(a * 2 / 3, 255, 255, 255)
            val hint = "TAP ▸ NEXT   HOLD ▸ NEW AI"
            canvas.drawText(hint, (w - label.measureText(hint)) / 2f, h / 2f + 16f * d, label)
        }
        if (!isPaused && isShown && windowVisibility == VISIBLE) postInvalidateOnAnimation()
    }
}
