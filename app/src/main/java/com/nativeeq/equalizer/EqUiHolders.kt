package com.nativeeq.equalizer

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

class EqUiHolders(context: Context) {
    val scrollContainer = ScrollView(context).apply {
        setBackgroundColor(Color.parseColor("#070a0f"))
    }
    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(20, 12, 20, 14)
    }
    val titleLabel = TextView(context).apply {
        textSize = 18f
        setTextColor(Color.parseColor("#00ff66"))
        typeface = Typeface.MONOSPACE
        gravity = android.view.Gravity.CENTER
    }
    val statusLabel = TextView(context).apply {
        textSize = 11f
        setTextColor(Color.parseColor("#718096"))
        typeface = Typeface.MONOSPACE
        gravity = android.view.Gravity.CENTER
        text = "BINDING HARDWARE ENGINE..."
    }
    val npuStatusLabel = TextView(context).apply {
        textSize = 11f
        setTextColor(Color.parseColor("#38bdf8"))
        typeface = Typeface.MONOSPACE
        gravity = android.view.Gravity.CENTER
        text = "CLOSED-LOOP DSP: READY (TAP MATRIX TO CYCLE MODE)"
        setPadding(0, 2, 0, 8)
    }
    val matrixVisualizer = MatrixSpectrographView(context).apply {
        layoutParams = LinearLayout.LayoutParams(-1, 240).apply { setMargins(0, 0, 0, 8) }
    }
    val visualizerView = RetroVisualizerView(context).apply {
        layoutParams = LinearLayout.LayoutParams(-1, 240)
    }
    val bitButtons = ArrayList<Button>()
    val bassLabel = TextView(context).apply {
        text = "BASS: 0%"
        textSize = 11f
        setTextColor(Color.parseColor("#ffaa00"))
        typeface = Typeface.MONOSPACE
    }
    val bassBar = SeekBar(context).apply { max = 1000 }
    val virtLabel = TextView(context).apply {
        text = "3D: 0%"
        textSize = 11f
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
        setPadding(0, 6, 0, 2)
    }
    val crtScrollView = ScrollView(context).apply {
        layoutParams = LinearLayout.LayoutParams(-1, 145)
        setBackgroundColor(Color.parseColor("#04070a"))
        setPadding(12, 6, 12, 6)
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
        setBackgroundColor(Color.parseColor("#0c131f"))
        setPadding(16, 16, 16, 16)
    }
    val dbStatsLabel = TextView(context).apply {
        textSize = 11f
        setTextColor(Color.parseColor("#94a3b8"))
        typeface = Typeface.MONOSPACE
        text = "SQLITE: 0 snapshots"
    }
    val modelStatusLabel = TextView(context).apply {
        textSize = 10f
        setTextColor(Color.parseColor("#a78bfa"))
        typeface = Typeface.MONOSPACE
        setPadding(0, 4, 0, 4)
    }
    val modelBtn = Button(context)
    val promptInput = EditText(context).apply {
        hint = "Type tune: e.g. 'punchy kick, less harsh'"
        setHintTextColor(Color.parseColor("#64748b"))
        setTextColor(Color.parseColor("#00ff66"))
        textSize = 11f
        typeface = Typeface.MONOSPACE
        setBackgroundColor(Color.parseColor("#04070a"))
        setPadding(14, 12, 14, 12)
        setSingleLine(true)
    }
    val promptTuneBtn = Button(context).apply {
        text = "TUNE"
        typeface = Typeface.MONOSPACE
        textSize = 10f
        setTextColor(Color.BLACK)
        setBackgroundColor(Color.parseColor("#00f2fe"))
    }
    val analyzeBtn = Button(context)
    val heatmapBtn = Button(context)
    val clearDbBtn = Button(context)
    val advisorResultLabel = TextView(context).apply {
        textSize = 11f
        setTextColor(Color.parseColor("#facc15"))
        typeface = Typeface.MONOSPACE
        text = "Tap ANALYZE for an on-device spectral read, or TUNE a prompt."
        setPadding(0, 6, 0, 8)
    }
    val applyRecBtn = Button(context).apply {
        text = "APPLY RECOMMENDED PRESET"
        typeface = Typeface.MONOSPACE
        textSize = 11f
        setTextColor(Color.BLACK)
        setBackgroundColor(Color.parseColor("#00ff66"))
        visibility = View.GONE
    }
    val tipBtn = Button(context).apply {
        val dp = context.resources.displayMetrics.density
        layoutParams = LinearLayout.LayoutParams(-1, (56 * dp).toInt()).apply {
            setMargins(0, (10 * dp).toInt(), 0, 0)
        }
        text = "SUPPORT / TIP — KO-FI"
        typeface = Typeface.MONOSPACE
        textSize = 18f
        setTextColor(Color.BLACK)
        setBackgroundColor(Color.parseColor("#facc15"))
    }
    val updateBtn = Button(context).apply {
        val dp = context.resources.displayMetrics.density
        layoutParams = LinearLayout.LayoutParams(-1, (56 * dp).toInt()).apply {
            setMargins(0, (10 * dp).toInt(), 0, 0)
        }
        text = "UPDATE AVAILABLE"
        typeface = Typeface.MONOSPACE
        textSize = 16f
        setTextColor(Color.BLACK)
        setBackgroundColor(Color.parseColor("#38bdf8"))
        visibility = View.GONE
    }

    init {
        scrollContainer.addView(root)
        root.addView(titleLabel)
        root.addView(statusLabel)
        root.addView(npuStatusLabel)
        root.addView(matrixVisualizer)
        root.addView(visualizerView)
        matrixVisualizer.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            matrixVisualizer.cycleNextMode()
        }

        val bitRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 4f
            setPadding(0, 12, 0, 10)
        }
        for (i in 3 downTo 0) {
            val btn = Button(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, 96, 1f).apply {
                    setMargins(4, 0, 4, 0)
                }
                typeface = Typeface.MONOSPACE
                textSize = 13f
            }
            bitButtons.add(btn)
            bitRow.addView(btn)
        }
        root.addView(bitRow)

        val sliderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
            setPadding(0, 4, 0, 6)
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
        root.addView(sliderRow)

        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 3f
            setPadding(0, 6, 0, 6)
        }
        npuToggleBtn.apply {
            layoutParams = LinearLayout.LayoutParams(0, 92, 1.15f).apply {
                setMargins(0, 0, 4, 0)
            }
            text = "AUTO: OFF"
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1e293b"))
        }
        sweepBtn.apply {
            layoutParams = LinearLayout.LayoutParams(0, 92, 0.95f).apply {
                setMargins(4, 0, 4, 0)
            }
            text = "SWEEP CAL"
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.parseColor("#38bdf8"))
        }
        resetBtn.apply {
            layoutParams = LinearLayout.LayoutParams(0, 92, 0.9f).apply {
                setMargins(4, 0, 0, 0)
            }
            text = "FLAT 0x0"
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.parseColor("#ffaa00"))
        }
        actionRow.addView(npuToggleBtn)
        actionRow.addView(sweepBtn)
        actionRow.addView(resetBtn)
        root.addView(actionRow)

        root.addView(crtHeader)
        crtScrollView.addView(crtLogView)
        root.addView(crtScrollView)

        val drawerToggle = Button(context).apply {
            text = "▼ OPEN TELEMETRY DATABASE & AI ADVISOR ▼"
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Color.parseColor("#38bdf8"))
            setBackgroundColor(Color.parseColor("#0f172a"))
            setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                drawerLayout.visibility =
                    if (drawerLayout.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
        root.addView(drawerToggle)

        drawerLayout.addView(dbStatsLabel)
        drawerLayout.addView(modelStatusLabel)
        modelBtn.apply {
            layoutParams = LinearLayout.LayoutParams(-1, 84).apply {
                setMargins(0, 0, 0, 8)
            }
            text = "GET GEMMA MODEL (584 MB)"
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.parseColor("#a78bfa"))
        }
        drawerLayout.addView(modelBtn)

        val promptRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 4f
            setPadding(0, 4, 0, 8)
        }
        promptInput.layoutParams = LinearLayout.LayoutParams(0, 88, 3.1f).apply {
            setMargins(0, 0, 6, 0)
        }
        promptTuneBtn.layoutParams = LinearLayout.LayoutParams(0, 88, 0.9f)
        promptRow.addView(promptInput)
        promptRow.addView(promptTuneBtn)
        drawerLayout.addView(promptRow)

        val advRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 3f
            setPadding(0, 6, 0, 8)
        }
        analyzeBtn.apply {
            layoutParams = LinearLayout.LayoutParams(0, 88, 1.1f).apply {
                setMargins(0, 0, 4, 0)
            }
            text = "ANALYZE"
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#6366f1"))
        }
        heatmapBtn.apply {
            layoutParams = LinearLayout.LayoutParams(0, 88, 1.1f).apply {
                setMargins(4, 0, 4, 0)
            }
            text = "HEATMAP"
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.parseColor("#38bdf8"))
        }
        clearDbBtn.apply {
            layoutParams = LinearLayout.LayoutParams(0, 88, 0.8f).apply {
                setMargins(4, 0, 0, 0)
            }
            text = "PURGE"
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#dc2626"))
        }
        advRow.addView(analyzeBtn)
        advRow.addView(heatmapBtn)
        advRow.addView(clearDbBtn)
        drawerLayout.addView(advRow)
        drawerLayout.addView(advisorResultLabel)
        drawerLayout.addView(applyRecBtn)
        drawerLayout.addView(tipBtn)
        drawerLayout.addView(updateBtn)
        root.addView(drawerLayout)
    }
}
