package com.nativeeq.equalizer

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/** Programmatic CRT-terminal UI, neon arcade palette. */
class EqUiHolders(context: Context) {

    companion object {
        val BG = Color.parseColor("#0a0a12")
        val PANEL = Color.parseColor("#0e0e1a")
        val WELL = Color.parseColor("#06060c")
        val CYAN = Color.parseColor("#00f2fe")
        val MAGENTA = Color.parseColor("#ff2e97")
        val PURPLE = Color.parseColor("#a78bfa")
        val LIME = Color.parseColor("#b8ff29")
        val AMBER = Color.parseColor("#ffb300")
        val RED = Color.parseColor("#ff3b3b")
        val DIM = Color.parseColor("#4a4a66")
        private const val BAR_BLOCKS = 10
    }

    private val ctx = context
    private val d = context.resources.displayMetrics.density

    private fun dp(v: Float) = (v * d).toInt()

    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    private fun boxed(fill: Int, stroke: Int?) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = 4f * d
        if (stroke != null) setStroke(dp(1f).coerceAtLeast(1), stroke)
    }

    /** Outlined (off) or filled (on) squared neon button; safe to re-apply. */
    fun neonButton(b: Button, accent: Int, filled: Boolean = false) {
        val shape = if (filled) boxed(accent, accent) else boxed(PANEL, accent)
        b.background = RippleDrawable(ColorStateList.valueOf(withAlpha(accent, 90)), shape, shape)
        b.setTextColor(if (filled) BG else accent)
    }

    private fun Button.neon(accent: Int, sp: Float = 13f, filled: Boolean = false): Button = apply {
        neonButton(this, accent, filled)
        textSize = sp
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        isAllCaps = true
        stateListAnimator = null
        elevation = 0f
        minHeight = dp(44f)
        minimumHeight = dp(44f)
        setPadding(dp(8f), 0, dp(8f), 0)
    }

    private fun mono(sp: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        textSize = sp
        setTextColor(color)
        typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun header(title: String, accent: Int) = mono(11f, accent, bold = true).apply {
        text = "[ $title ]"
        letterSpacing = 0.08f
        setPadding(0, 0, 0, dp(6f))
    }

    /** Color-blocked section: 1px stroke tinted to the section accent, no elevation. */
    private fun panel(accent: Int): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = boxed(PANEL, withAlpha(accent, 200))
        setPadding(dp(10f), dp(8f), dp(10f), dp(10f))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, dp(10f))
        }
    }

    private fun lerpColor(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )

    /** `BASS [██████----] 62%` with filled blocks graded cyan→magenta. */
    fun setBar(label: TextView, name: String, percent: Int) {
        val pct = percent.coerceIn(0, 100)
        val filled = (pct * BAR_BLOCKS + 50) / 100
        val sb = SpannableStringBuilder(name.padEnd(4)).append(" [")
        for (i in 0 until BAR_BLOCKS) {
            val start = sb.length
            val on = i < filled
            sb.append(if (on) '█' else '-')
            val c = if (on) lerpColor(CYAN, MAGENTA, i / (BAR_BLOCKS - 1f)) else DIM
            sb.setSpan(ForegroundColorSpan(c), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        sb.append("] ").append("$pct%".padStart(4))
        label.text = sb
    }

    private fun logColor(line: String): Int {
        val l = line.trimStart()
        return when {
            l.contains("FAIL", true) || l.startsWith("[ERR") -> RED
            l.startsWith("[SWEEP") || l.startsWith("[UPDATE") || l.startsWith("[MBC") -> AMBER
            l.startsWith("[MODEL") || l.startsWith("[LITERT") || l.startsWith("[GEMINI") ||
                l.startsWith("[TFLITE") || l.startsWith("[HEURISTIC") ||
                l.startsWith("[PROMPT") || l.startsWith("[APPLY") -> PURPLE
            l.startsWith("[A/B") || l.startsWith("[2.FEEDBACK DAMP") -> MAGENTA
            l.startsWith("[1.ACT") || l.startsWith("[OBSERVE") || l.startsWith("[2.FEEDBACK") -> CYAN
            else -> LIME
        }
    }

    /** Colorizes each log line by its `[TAG]` level. */
    fun renderLog(lines: List<String>): CharSequence {
        val sb = SpannableStringBuilder()
        lines.forEachIndexed { i, line ->
            if (i > 0) sb.append('\n')
            val start = sb.length
            sb.append(line)
            sb.setSpan(
                ForegroundColorSpan(logColor(line)), start, sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return sb
    }

    private fun SeekBar.neonTint() = apply {
        progressTintList = ColorStateList.valueOf(CYAN)
        progressBackgroundTintList = ColorStateList.valueOf(DIM)
        thumbTintList = ColorStateList.valueOf(MAGENTA)
    }

    val scrollContainer = ScrollView(context).apply {
        setBackgroundColor(BG)
        fitsSystemWindows = true
    }
    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14f), dp(12f), dp(14f), dp(10f))
    }
    val titleLabel = mono(17f, CYAN, bold = true).apply { gravity = Gravity.CENTER }
    val statusLabel = mono(11f, AMBER).apply {
        gravity = Gravity.CENTER
        text = "BINDING HARDWARE ENGINE..."
    }
    val npuStatusLabel = mono(11f, PURPLE).apply {
        gravity = Gravity.CENTER
        text = "CLOSED-LOOP DSP: READY"
        setPadding(0, dp(2f), 0, dp(10f))
    }
    val visualizerView = RetroVisualizerView(context).apply {
        layoutParams = LinearLayout.LayoutParams(-1, dp(190f))
    }
    val bitButtons = ArrayList<Button>()
    val bassLabel = mono(12f, CYAN)
    val bassBar = SeekBar(context).apply { max = 1000 }.neonTint()
    val virtLabel = mono(12f, CYAN)
    val virtBar = SeekBar(context).apply { max = 1000 }.neonTint()
    val npuToggleBtn = Button(context)
    val resetBtn = Button(context)
    val sweepBtn = Button(context)
    val crtHeader = header("DSP TELEMETRY", LIME).apply { textSize = 10f }
    val crtScrollView = ScrollView(context).apply {
        layoutParams = LinearLayout.LayoutParams(-1, dp(150f))
        background = boxed(WELL, null)
        setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
    }
    val crtLogView = mono(10f, LIME).apply {
        text = "[INIT] 60FPS Morphing + DSP Pipeline Ready."
    }
    val drawerLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setPadding(0, dp(8f), 0, 0)
    }
    val dbStatsLabel = mono(11f, CYAN).apply {
        text = "SQLite: 0 snapshots"
        setPadding(0, 0, 0, dp(6f))
    }
    val modelStatusLabel = mono(11f, PURPLE).apply { setPadding(0, dp(2f), 0, dp(8f)) }
    val modelBtn = Button(context)
    val promptInput = EditText(context).apply {
        hint = "> punchy kick, less harsh"
        setHintTextColor(DIM)
        setTextColor(CYAN)
        textSize = 12f
        typeface = Typeface.MONOSPACE
        background = boxed(WELL, withAlpha(PURPLE, 160))
        setPadding(dp(10f), 0, dp(10f), 0)
        setSingleLine(true)
    }
    val promptTuneBtn = Button(context)
    val analyzeBtn = Button(context)
    val heatmapBtn = Button(context)
    val clearDbBtn = Button(context)
    val advisorResultLabel = mono(11f, MAGENTA).apply {
        text = "Tap ANALYZE for an on-device spectral read, or TUNE a prompt."
        setPadding(0, dp(8f), 0, dp(8f))
    }
    val applyRecBtn = Button(context)
    val tipBtn = Button(context)
    val updateBtn = Button(context)

    private fun row(vararg views: Pair<View, Float>, gapDp: Float = 6f) =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            views.forEachIndexed { i, (v, w) ->
                v.layoutParams = LinearLayout.LayoutParams(0, dp(48f), w).apply {
                    if (i > 0) marginStart = dp(gapDp)
                }
                addView(v)
            }
        }

    private fun fullWidth(b: Button, topDp: Float = 0f) {
        b.layoutParams = LinearLayout.LayoutParams(-1, dp(48f)).apply {
            setMargins(0, dp(topDp), 0, dp(8f))
        }
    }

    init {
        scrollContainer.addView(root)
        root.addView(titleLabel)
        root.addView(statusLabel)
        root.addView(npuStatusLabel)

        // Visualizer: tap the header to cycle the background layer; the EQ curve
        // stays draggable in every mode.
        val vizPanel = panel(CYAN)
        val vizHeader = header("VIZ", CYAN).apply {
            minHeight = dp(32f)
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            text = "[ VIZ · ${visualizerView.mode.name} ]  ⇄ TAP"
        }
        vizHeader.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            vizHeader.text = "[ VIZ · ${visualizerView.cycleMode().name} ]  ⇄ TAP"
        }
        vizPanel.addView(vizHeader)
        vizPanel.addView(visualizerView)
        root.addView(vizPanel)

        // DSP: 4-bit preset register + bass/3D bars in one section.
        val dspPanel = panel(MAGENTA)
        dspPanel.addView(header("DSP", MAGENTA))
        val bitRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 3 downTo 0) {
            val btn = Button(context).neon(LIME, 13f).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(52f), 1f).apply {
                    if (i != 3) marginStart = dp(6f)
                }
            }
            bitButtons.add(btn)
            bitRow.addView(btn)
        }
        dspPanel.addView(bitRow)
        setBar(bassLabel, "BASS", 0)
        setBar(virtLabel, "3D", 0)
        bassLabel.setPadding(0, dp(10f), 0, 0)
        virtLabel.setPadding(0, dp(6f), 0, 0)
        dspPanel.addView(bassLabel)
        dspPanel.addView(bassBar)
        dspPanel.addView(virtLabel)
        dspPanel.addView(virtBar)
        root.addView(dspPanel)

        // Control: [SWEEP] [FLAT] [AUTO], each its own accent.
        val ctlPanel = panel(AMBER)
        ctlPanel.addView(header("CONTROL", AMBER))
        sweepBtn.neon(AMBER).text = "[SWEEP]"
        resetBtn.neon(MAGENTA).text = "[FLAT]"
        npuToggleBtn.neon(LIME).text = "[AUTO]"
        ctlPanel.addView(row(sweepBtn to 1f, resetBtn to 1f, npuToggleBtn to 1f))
        root.addView(ctlPanel)

        val crtPanel = panel(LIME)
        crtPanel.addView(crtHeader)
        crtScrollView.addView(crtLogView)
        crtPanel.addView(crtScrollView)
        root.addView(crtPanel)

        updateBtn.neon(AMBER, filled = true).text = "Update available"
        updateBtn.visibility = View.GONE
        fullWidth(updateBtn)
        root.addView(updateBtn)

        // [ LOG / AI ] drawer, closed by default.
        val aiPanel = panel(PURPLE)
        val drawerToggle = header("LOG / AI", PURPLE).apply {
            text = "[ LOG / AI ] ▾"
            minHeight = dp(40f)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)
            isClickable = true
        }
        drawerToggle.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            val open = drawerLayout.visibility != View.VISIBLE
            drawerLayout.visibility = if (open) View.VISIBLE else View.GONE
            drawerToggle.text = if (open) "[ LOG / AI ] ▴" else "[ LOG / AI ] ▾"
        }
        aiPanel.addView(drawerToggle)

        drawerLayout.addView(dbStatsLabel)
        drawerLayout.addView(modelStatusLabel)
        modelBtn.neon(PURPLE).text = "Get Gemma model (584 MB)"
        fullWidth(modelBtn)
        drawerLayout.addView(modelBtn)

        promptTuneBtn.neon(PURPLE, filled = true).text = "[TUNE]"
        drawerLayout.addView(
            row(promptInput to 3f, promptTuneBtn to 1.1f).apply { setPadding(0, 0, 0, dp(8f)) }
        )

        analyzeBtn.neon(PURPLE, 12f).text = "[ANALYZE]"
        heatmapBtn.neon(CYAN, 12f).text = "[LOGBOOK]"
        clearDbBtn.neon(RED, 12f).text = "[PURGE]"
        drawerLayout.addView(row(analyzeBtn to 1.15f, heatmapBtn to 1.15f, clearDbBtn to 0.9f))
        drawerLayout.addView(advisorResultLabel)

        applyRecBtn.neon(LIME, filled = true).text = "Apply recommended preset"
        applyRecBtn.visibility = View.GONE
        fullWidth(applyRecBtn)
        drawerLayout.addView(applyRecBtn)

        aiPanel.addView(drawerLayout)
        root.addView(aiPanel)

        // Ko-fi: small footer link.
        tipBtn.apply {
            text = "<3 ko-fi.com/indodankabis"
            isAllCaps = false
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(withAlpha(MAGENTA, 200))
            background = RippleDrawable(
                ColorStateList.valueOf(withAlpha(MAGENTA, 60)), null, ColorDrawable(Color.WHITE)
            )
            stateListAnimator = null
            minHeight = dp(36f)
            minimumHeight = dp(36f)
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(8f), 0, dp(8f), 0)
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }
        root.addView(tipBtn)
    }
}
