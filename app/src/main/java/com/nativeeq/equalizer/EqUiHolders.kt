package com.nativeeq.equalizer

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

class EqUiHolders(context: Context) {

    private val d = context.resources.displayMetrics.density

    private fun dp(v: Float) = (v * d).toInt()

    private fun roundRect(color: String, radiusDp: Float) = GradientDrawable().apply {
        setColor(Color.parseColor(color))
        cornerRadius = radiusDp * d
    }

    /** Pill button background: rounded fill + ripple clipped to the shape. */
    private fun pill(color: String, radiusDp: Float = 16f): RippleDrawable {
        val content = roundRect(color, radiusDp)
        return RippleDrawable(
            ColorStateList.valueOf(Color.argb(70, 255, 255, 255)),
            content, content
        )
    }

    /** Re-style an existing button (keeps ripple + radius; safe to re-apply). */
    fun styleButton(b: Button, bg: String, fg: Int) {
        b.background = pill(bg)
        b.setTextColor(fg)
    }

    private fun Button.style(bg: String, fg: Int, sp: Float = 15f): Button = apply {
        background = pill(bg)
        setTextColor(fg)
        textSize = sp
        typeface = Typeface.DEFAULT
        isAllCaps = false
        minHeight = dp(48f)
        minimumHeight = dp(48f)
        setPadding(dp(12f), 0, dp(12f), 0)
    }

    private fun card(): LinearLayout = LinearLayout(contextRef).apply {
        orientation = LinearLayout.VERTICAL
        background = roundRect("#10151e", 18f)
        elevation = 2 * d
        setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, dp(10f))
        }
    }

    private lateinit var contextRef: Context

    val scrollContainer = ScrollView(context).apply {
        setBackgroundColor(Color.parseColor("#070a0f"))
    }
    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16f), dp(12f), dp(16f), dp(14f))
    }
    val titleLabel = TextView(context).apply {
        textSize = 18f
        setTextColor(Color.parseColor("#00ff66"))
        typeface = Typeface.MONOSPACE
        gravity = android.view.Gravity.CENTER
    }
    val statusLabel = TextView(context).apply {
        textSize = 12f
        setTextColor(Color.parseColor("#718096"))
        typeface = Typeface.MONOSPACE
        gravity = android.view.Gravity.CENTER
        text = "BINDING HARDWARE ENGINE..."
    }
    val npuStatusLabel = TextView(context).apply {
        textSize = 12f
        setTextColor(Color.parseColor("#38bdf8"))
        typeface = Typeface.MONOSPACE
        gravity = android.view.Gravity.CENTER
        text = "CLOSED-LOOP DSP: READY (TAP MATRIX TO CYCLE MODE)"
        setPadding(0, 2, 0, dp(8f))
    }
    val matrixVisualizer = MatrixSpectrographView(context).apply {
        layoutParams = LinearLayout.LayoutParams(-1, dp(110f)).apply {
            setMargins(0, 0, 0, dp(8f))
        }
    }
    val visualizerView = RetroVisualizerView(context).apply {
        layoutParams = LinearLayout.LayoutParams(-1, dp(110f))
    }
    val bitButtons = ArrayList<Button>()
    val bassLabel = TextView(context).apply {
        text = "Bass: 0%"
        textSize = 12f
        setTextColor(Color.parseColor("#ffaa00"))
        typeface = Typeface.MONOSPACE
    }
    val bassBar = SeekBar(context).apply { max = 1000 }
    val virtLabel = TextView(context).apply {
        text = "3D: 0%"
        textSize = 12f
        setTextColor(Color.parseColor("#38bdf8"))
        typeface = Typeface.MONOSPACE
    }
    val virtBar = SeekBar(context).apply { max = 1000 }
    val npuToggleBtn = Button(context)
    val resetBtn = Button(context)
    val sweepBtn = Button(context)
    val crtHeader = TextView(context).apply {
        text = "[●] --- [ DSP TELEMETRY ] ---"
        textSize = 10f
        setTextColor(Color.parseColor("#4ade80"))
        typeface = Typeface.MONOSPACE
        gravity = android.view.Gravity.CENTER
        setPadding(0, dp(6f), 0, 2)
    }
    val crtScrollView = ScrollView(context).apply {
        layoutParams = LinearLayout.LayoutParams(-1, dp(150f))
        background = roundRect("#04070a", 12f)
        setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
    }
    val crtLogView = TextView(context).apply {
        textSize = 10f
        setTextColor(Color.parseColor("#00ff66"))
        typeface = Typeface.MONOSPACE
        text = "[INIT] 60FPS Morphing + DSP Pipeline Ready."
    }
    val drawerLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        background = roundRect("#10151e", 18f)
        elevation = 2 * d
        setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, dp(10f))
        }
    }
    val dbStatsLabel = TextView(context).apply {
        textSize = 12f
        setTextColor(Color.parseColor("#94a3b8"))
        typeface = Typeface.MONOSPACE
        text = "SQLite: 0 snapshots"
        setPadding(0, 0, 0, dp(6f))
    }
    val modelStatusLabel = TextView(context).apply {
        textSize = 11f
        setTextColor(Color.parseColor("#a78bfa"))
        typeface = Typeface.MONOSPACE
        setPadding(0, 4, 0, dp(8f))
    }
    val modelBtn = Button(context)
    val promptInput = EditText(context).apply {
        hint = "Type a tune, e.g. 'punchy kick, less harsh'"
        setHintTextColor(Color.parseColor("#64748b"))
        setTextColor(Color.parseColor("#00ff66"))
        textSize = 12f
        typeface = Typeface.MONOSPACE
        background = roundRect("#04070a", 12f)
        setPadding(dp(14f), 0, dp(14f), 0)
        setSingleLine(true)
    }
    val promptTuneBtn = Button(context)
    val analyzeBtn = Button(context)
    val heatmapBtn = Button(context)
    val clearDbBtn = Button(context)
    val exportDatasetBtn = Button(context)
    val advisorResultLabel = TextView(context).apply {
        textSize = 12f
        setTextColor(Color.parseColor("#facc15"))
        typeface = Typeface.MONOSPACE
        text = "Tap ANALYZE for an on-device spectral read, or TUNE a prompt."
        setPadding(0, dp(8f), 0, dp(8f))
    }
    val applyRecBtn = Button(context)
    val tipBtn = Button(context)
    val updateBtn = Button(context)

    init {
        contextRef = context

        scrollContainer.addView(root)
        root.addView(titleLabel)
        root.addView(statusLabel)
        root.addView(npuStatusLabel)

        val vizCard = card()
        vizCard.addView(matrixVisualizer)
        vizCard.addView(visualizerView)
        root.addView(vizCard)
        matrixVisualizer.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            matrixVisualizer.cycleNextMode()
        }

        val bitRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 4f
        }
        for (i in 3 downTo 0) {
            val btn = Button(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(56f), 1f).apply {
                    setMargins(dp(4f), 0, dp(4f), 0)
                }
                typeface = Typeface.MONOSPACE
            }.style("#1f2937", Color.parseColor("#9ca3af"), 14f).apply {
                typeface = Typeface.MONOSPACE
            }
            bitButtons.add(btn)
            bitRow.addView(btn)
        }
        val bitCard = card()
        bitCard.addView(bitRow)
        root.addView(bitCard)

        val sliderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        val bassCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            addView(bassLabel)
            addView(bassBar)
        }
        val virtCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            addView(virtLabel)
            addView(virtBar)
        }
        sliderRow.addView(bassCol)
        sliderRow.addView(virtCol)
        val sliderCard = card()
        sliderCard.addView(sliderRow)
        root.addView(sliderCard)

        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 3f
        }
        npuToggleBtn.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(56f), 1.15f).apply {
                setMargins(0, 0, dp(4f), 0)
            }
            text = "Auto: off"
        }.style("#1e293b", Color.WHITE, 13f)
        sweepBtn.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(56f), 0.95f).apply {
                setMargins(dp(4f), 0, dp(4f), 0)
            }
            text = "Sweep"
        }.style("#38bdf8", Color.BLACK, 13f)
        resetBtn.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(56f), 0.9f).apply {
                setMargins(dp(4f), 0, 0, 0)
            }
            text = "Flat"
        }.style("#ffaa00", Color.BLACK, 13f)
        actionRow.addView(npuToggleBtn)
        actionRow.addView(sweepBtn)
        actionRow.addView(resetBtn)
        val actionCard = card()
        actionCard.addView(actionRow)
        root.addView(actionCard)

        val crtCard = card()
        crtCard.addView(crtHeader)
        crtScrollView.addView(crtLogView)
        crtCard.addView(crtScrollView)
        root.addView(crtCard)

        val drawerToggle = Button(context).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(52f)).apply {
                setMargins(0, 0, 0, dp(10f))
            }
            text = "Telemetry & AI advisor ▾"
        }.style("#1e293b", Color.parseColor("#7dd3fc"), 14f)
        drawerToggle.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            val open = drawerLayout.visibility != View.VISIBLE
            drawerLayout.visibility = if (open) View.VISIBLE else View.GONE
            drawerToggle.text =
                if (open) "Telemetry & AI advisor ▴" else "Telemetry & AI advisor ▾"
        }
        root.addView(drawerToggle)

        drawerLayout.addView(dbStatsLabel)
        drawerLayout.addView(modelStatusLabel)
        modelBtn.apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(56f)).apply {
                setMargins(0, 0, 0, dp(8f))
            }
            text = "Get Gemma model (584 MB)"
        }.style("#a78bfa", Color.BLACK, 14f)
        drawerLayout.addView(modelBtn)

        val promptRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 4f
            setPadding(0, dp(4f), 0, dp(8f))
        }
        promptInput.layoutParams = LinearLayout.LayoutParams(0, dp(56f), 3.1f).apply {
            setMargins(0, 0, dp(6f), 0)
        }
        promptTuneBtn.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(56f), 0.9f)
            text = "Tune"
        }.style("#00f2fe", Color.BLACK, 14f)
        promptRow.addView(promptInput)
        promptRow.addView(promptTuneBtn)
        drawerLayout.addView(promptRow)

        val advRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 3f
            setPadding(0, dp(6f), 0, dp(8f))
        }
        analyzeBtn.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(56f), 1.1f).apply {
                setMargins(0, 0, dp(4f), 0)
            }
            text = "Analyze"
        }.style("#6366f1", Color.WHITE, 13f)
        heatmapBtn.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(56f), 1.1f).apply {
                setMargins(dp(4f), 0, dp(4f), 0)
            }
            text = "Logbook"
        }.style("#38bdf8", Color.BLACK, 13f)
        clearDbBtn.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(56f), 0.8f).apply {
                setMargins(dp(4f), 0, 0, 0)
            }
            text = "Purge"
        }.style("#dc2626", Color.WHITE, 13f)
        advRow.addView(analyzeBtn)
        advRow.addView(heatmapBtn)
        advRow.addView(clearDbBtn)
        drawerLayout.addView(advRow)
        exportDatasetBtn.apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(48f)).apply {
                setMargins(0, 0, 0, dp(8f))
            }
            text = "Export training dataset (JSON)"
        }.style("#1e293b", Color.parseColor("#a78bfa"), 13f)
        drawerLayout.addView(exportDatasetBtn)
        drawerLayout.addView(advisorResultLabel)

        applyRecBtn.apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(56f)).apply {
                setMargins(0, 0, 0, dp(8f))
            }
            text = "Apply recommended preset"
            visibility = View.GONE
        }.style("#00ff66", Color.BLACK, 14f)
        drawerLayout.addView(applyRecBtn)

        tipBtn.apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(56f)).apply {
                setMargins(0, dp(10f), 0, 0)
            }
            text = "Support on Ko-fi"
        }.style("#facc15", Color.BLACK, 18f)
        drawerLayout.addView(tipBtn)

        updateBtn.apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(56f)).apply {
                setMargins(0, dp(10f), 0, 0)
            }
            text = "Update available"
            visibility = View.GONE
        }.style("#38bdf8", Color.BLACK, 16f)
        drawerLayout.addView(updateBtn)

        root.addView(drawerLayout)
    }
}
