package com.nativeeq.equalizer

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.SeekBar
import java.util.LinkedList
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var ui: EqUiHolders
    private lateinit var dbHelper: TelemetryDbHelper
    private lateinit var lmEngine: LiteRtLmAcousticEngine
    private lateinit var logbook: TuningLogbook
    private var eqService: EqualizerService? = null
    private var npuAgent: NpuAudioAgent? = null
    private val micAnalyzer = RoomMicAnalyzer()
    private val audioTap = AudioTap(
        onWave = { ui.hypnoView.updateWaveform(it) },
        onFft = { fft, sr ->
            ui.hypnoView.updateFft(fft, sr)
            ui.visualizerView.updateFft(fft, sr)
        },
    )
    private var audioTapLogged = false

    private fun startAudioTap() {
        val live = audioTap.start()
        if (!audioTapLogged) {
            audioTapLogged = true
            appendLog(if (live) "[VIZ] Output tap live: real waveform + FFT" else "[VIZ] Output tap unavailable; using DSP bands")
        }
    }
    private val sweeper = AcousticSweeper()
    private val genreClassifier = AcousticGenreClassifier()
    private val transferEngine = AcousticTransferEngine()

    private var isBound = false
    private var isNpuAutoMode = false
    private var hasCustomCurve = false
    private var blinkState = false
    private var snapshotTick = 0
    private var recommendedState = -1
    private var recommendedGains: ShortArray? = null
    private var currentRoute = "dev_internal"
    private var sweepRunning = false
    private var lastTelemetryUiMs = 0L
    private var lastMicUiMs = 0L
    private var lastAgentUiMs = 0L

    private val terminalLines = LinkedList<String>()
    private val maxLogLines = 9

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? EqualizerService.LocalBinder ?: return
            eqService = binder.getService()
            isBound = true
            renderState(EqualizerService.currentState)
            val floats = dbHelper.getRouteProfile(currentRoute)
            if (floats != null) {
                hasCustomCurve = true
                val shorts = ShortArray(5) { floats[it].toInt().toShort() }
                EqualizerService.profiles[EqualizerService.currentState] = shorts.clone()
                ui.visualizerView.setTargetGains(shorts)
                eqService?.setTargetGains(floats, immediate = true)
                ui.statusLabel.text = "RESTORED SQLITE CURVE | ROUTE: $currentRoute"
                appendLog("[SQLITE] Restored saved curve for $currentRoute")
            }
            eqService?.loadCustomRouteCurve(currentRoute)?.let { saved ->
                ui.visualizerView.setTargetGains(saved)
                ui.statusLabel.text = "LOADED SQLITE PROFILE | ROUTE: $currentRoute"
            }
            EqualizerService.onTelemetryListener = { low, med, high ->
                // Runs on the Visualizer callback thread ~10-20Hz.
                // DSP/classifier work stays off-UI; view repaints throttled.
                DirectAcousticBus.writeRawTriplet(low, med, high)
                npuAgent?.pushSpectralFrame(low, med, high)
                genreClassifier.ingestFrame(low, med, high)?.let { archetype ->
                    npuAgent?.genreBiasMb = archetype.eqBiasMb
                    if (isNpuAutoMode) {
                        eqService?.updateMbcEnvelope(
                            archetype.attackMs, archetype.releaseMs,
                            archetype.ratio, archetype.thresholdDb
                        )
                    }
                    appendLog(
                        "[MBC KNEE] ${archetype.label} -> " +
                            "${archetype.attackMs.toInt()}ms/${archetype.ratio}:1"
                    )
                }
                transferEngine.updateRawTriplet(low, med, high)
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastTelemetryUiMs >= 100) {
                    lastTelemetryUiMs = now
                    runOnUiThread {
                        ui.hypnoView.updateSpectralBands(low, med, high)
                        ui.visualizerView.updateSpectralTriplet(low, med, high)
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            EqualizerService.onTelemetryListener = null
            eqService = null
            isBound = false
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state =
                intent.getIntExtra(EqualizerService.EXTRA_STATE, EqualizerService.currentState)
            currentRoute =
                intent.getStringExtra(EqualizerService.EXTRA_ROUTE_KEY) ?: "dev_internal"
            ui.statusLabel.text = "60FPS MORPH ENGINE ACTIVE | ROUTE: $currentRoute"
            renderState(state)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = EqUiHolders(this)
        setContentView(ui.scrollContainer)
        dbHelper = TelemetryDbHelper(this)
        lmEngine = LiteRtLmAcousticEngine(this)
        ui.hypnoView.onFullscreenToggle = { toggleVizFullscreen() }
        ui.hypnoView.onAiRequest = {
            val prompt = ui.hypnoView.flavorPrompt(genreClassifier.currentArchetype.name)
            lmEngine.generateRawAsync(prompt) { raw, tag ->
                runOnUiThread {
                    val f = raw?.let { ui.hypnoView.parseFlavor(it) }
                    ui.hypnoView.finishAi(f, tag)
                    appendLog(if (f != null) "[VIZ] [$tag] wrote flavor ${f.name}" else "[VIZ] No LM flavor; evolved locally")
                }
            }
        }
        logbook = TuningLogbook(this)
        npuAgent = NpuAudioAgent(this).apply {
            onTelemetryListener = { gains, msg, logLine, low, med, high -> onAgentTick(gains, msg, logLine, low, med, high) }
        }

        for (i in 0 until 4) {
            val bitMask = 1 shl (3 - i)
            ui.bitButtons[i].setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                val newState = EqualizerService.currentState xor bitMask
                ui.advisorResultLabel.text = "REGISTER SHIFTED: MANUAL CONTROL ENGAGED"
                setHardwareState(newState)
            }
        }
        ui.bassBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    ui.setBar(ui.bassLabel, "BASS", p / 10)
                    startService(
                        Intent(this@MainActivity, EqualizerService::class.java).apply {
                            action = EqualizerService.ACTION_SET_BASS
                            putExtra(EqualizerService.EXTRA_BASS_STRENGTH, p.toShort())
                        }
                    )
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        ui.virtBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    ui.setBar(ui.virtLabel, "3D", p / 10)
                    startService(
                        Intent(this@MainActivity, EqualizerService::class.java).apply {
                            action = EqualizerService.ACTION_SET_VIRT
                            putExtra(EqualizerService.EXTRA_VIRT_STRENGTH, p.toShort())
                        }
                    )
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        ui.npuToggleBtn.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            isNpuAutoMode = !isNpuAutoMode
            ui.npuToggleBtn.text = if (isNpuAutoMode) "[AUTO:ON]" else "[AUTO]"
            ui.neonButton(ui.npuToggleBtn, EqUiHolders.LIME, filled = isNpuAutoMode)
        }
        ui.resetBtn.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            setHardwareState(0)
        }
        ui.sweepBtn.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            runSweepCalibration()
        }
        ui.promptTuneBtn.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            runPromptTune()
        }
        ui.modelBtn.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            val mm = lmEngine.getModelManager()
            if (mm.isModelPresent()) {
                ui.modelStatusLabel.text = mm.statusText()
            } else {
                appendLog("[MODEL] Downloading Gemma 3 1B-IT (584 MB)…")
                mm.download { ok ->
                    runOnUiThread {
                        ui.modelStatusLabel.text = mm.statusText()
                        appendLog(
                            if (ok) "[MODEL] Gemma ready — LiteRT-LM backend live"
                            else "[MODEL] download failed: ${mm.downloadError}"
                        )
                    }
                }
                ui.modelStatusLabel.text = "Downloading Gemma…"
            }
        }
        ui.analyzeBtn.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            runLmSessionInference()
        }
        ui.heatmapBtn.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            appendLog(dbHelper.generateAsciiHeatmap())
            ui.advisorResultLabel.text = logbook.readRecentEntries()
            updateDbStats()
        }
        ui.clearDbBtn.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            dbHelper.clearTelemetry()
            updateDbStats()
            ui.advisorResultLabel.text = "DATABASE PURGED."
            ui.applyRecBtn.visibility = View.GONE
        }
        ui.applyRecBtn.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            applyRecommendation()
        }
        ui.tipBtn.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://ko-fi.com/indodankabis"))
            )
        }

        // The graph's drag/bypass hooks are real now: drag a node -> write the
        // curve straight into the DSP chain; hold top edge -> true A/B bypass.
        ui.visualizerView.onGainDraggedListener = { band, gainMb ->
            val gains = ui.visualizerView.getTargetGains()
            eqService?.setTargetGains(gains, immediate = true)
            hasCustomCurve = true
            ui.advisorResultLabel.text =
                "BAND ${arrayOf("60Hz", "230Hz", "910Hz", "3.6k", "14k")[band]} -> " +
                    "%+.1fdB".format(Locale.US, gainMb / 100f)
        }
        ui.visualizerView.onBypassToggleListener = { bypassed ->
            if (bypassed) {
                eqService?.setTargetGains(FloatArray(5), immediate = true)
                appendLog("[A/B] DSP bypassed — flat reference")
            } else {
                eqService?.setTargetGains(ui.visualizerView.getTargetGains(), immediate = true)
                appendLog("[A/B] DSP re-engaged")
            }
        }

        refreshModelStatus()
        checkAudioPermission()
        checkForUpdates()
    }

    private fun checkForUpdates() {
        Thread {
            try {
                val conn = java.net.URL(
                    "https://api.github.com/repos/indodankabis/nativeeq/releases/latest"
                ).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.setRequestProperty("User-Agent", "nativeeq")
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val latest = Regex("\"tag_name\"\\s*:\\s*\"v?([^\"]+)\"")
                    .find(body)?.groupValues?.get(1) ?: return@Thread
                if (!isNewerVersion(latest, BuildConfig.VERSION_NAME)) return@Thread
                runOnUiThread {
                    ui.updateBtn.text = "UPDATE AVAILABLE: v$latest — GET APK"
                    ui.updateBtn.visibility = View.VISIBLE
                    ui.updateBtn.setOnClickListener {
                        startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/indodankabis/nativeeq/releases/latest")
                            )
                        )
                    }
                    appendLog("[UPDATE] v$latest on GitHub Releases")
                }
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun isNewerVersion(latest: String, current: String): Boolean {
        val l = latest.split('.').map { it.toIntOrNull() ?: 0 }
        val c = current.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(l.size, c.size)) {
            val a = l.getOrElse(i) { 0 }
            val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun onAgentTick(
        gains: ShortArray, msg: String, logLine: String,
        low: Float, med: Float, high: Float,
    ) {
        snapshotTick++
        if (snapshotTick % 4 == 0) {
            dbHelper.insertSnapshot(currentRoute, low, med, high, gains)
        }
        if (isNpuAutoMode) {
            eqService?.setTargetGains(gains)
        }
        appendLog(logLine)
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastAgentUiMs >= 150) {
            lastAgentUiMs = now
            runOnUiThread {
                ui.npuStatusLabel.text = msg
                blinkState = !blinkState
                val us = DirectAcousticBus.readMicBands(FloatArray(5))
                ui.crtHeader.text =
                    (if (blinkState) "[●]" else "[○]") +
                        " [ DSP TELEMETRY ] BUS ${us}µs · GOERTZEL"
                if (isNpuAutoMode) {
                    ui.visualizerView.setTargetGains(gains)
                }
            }
        }
    }

    private fun runPromptTune() {
        val txt = ui.promptInput.text.toString().trim()
        if (txt.isEmpty()) return
        val base = EqualizerService.profiles[EqualizerService.currentState]
        ui.advisorResultLabel.text = "Tuning…"
        appendLog("[PROMPT] '$txt'")
        lmEngine.evaluateNaturalPromptAsync(
            txt, base,
            onToken = { tok -> runOnUiThread { appendLog(tok.trim()) } },
            onComplete = { resp ->
                runOnUiThread {
                    hasCustomCurve = true
                    EqualizerService.profiles[EqualizerService.currentState] =
                        resp.targetGains.clone()
                    ui.visualizerView.setTargetGains(resp.targetGains)
                    eqService?.setTargetGains(resp.targetGains)
                    dbHelper.saveRouteProfile(
                        currentRoute,
                        FloatArray(5) { resp.targetGains[it].toFloat() }, 0f
                    )
                    logbook.recordEntry(
                        currentRoute, "AI Prompt: $txt",
                        resp.targetGains, "[${resp.backendTag}] ${resp.reasoning}"
                    )
                    ui.advisorResultLabel.text =
                        "[${resp.backendTag}] ${resp.reasoning}"
                    appendLog("[${resp.backendTag}] ${resp.reasoning}")
                    ui.promptInput.setText("")
                }
            },
        )
    }

    private fun runSweepCalibration() {
        if (sweepRunning) {
            appendLog("[SWEEP] already running")
            return
        }
        sweepRunning = true
        ui.statusLabel.text = "SWEEP: emitting 20Hz-16kHz through music stream…"
        appendLog("[SWEEP] 3s log-sweep started — keep room quiet")
        sweeper.trigger3SecondSweep(micAnalyzer, object : AcousticSweeper.SweepCallback {
            override fun onProgress(percentage: Int) {
                runOnUiThread { ui.statusLabel.text = "SWEEP: $percentage%" }
            }

            override fun onComplete(targetCorrection: ShortArray) {
                sweepRunning = false
                runOnUiThread {
                    if (targetCorrection.all { it.toInt() == 0 }) {
                        ui.statusLabel.text = "SWEEP FAILED: no mic signal captured"
                        appendLog("[SWEEP] failed — check RECORD_AUDIO permission")
                        return@runOnUiThread
                    }
                    npuAgent?.transferCompMb = targetCorrection
                    npuAgent?.transferTag = "H(f) SWEEP-CAL"
                    val db = targetCorrection.joinToString("/") {
                        "%+.1f".format(Locale.US, it / 100f)
                    }
                    ui.statusLabel.text = "SWEEP DONE: room curve measured"
                    ui.advisorResultLabel.text =
                        "Room compensation applied to closed loop: $db dB"
                    appendLog("[SWEEP] measured room response -> comp $db dB")
                    logbook.recordEntry(
                        currentRoute, "Sweep Calibration", targetCorrection,
                        "3s sine sweep through mic RTA"
                    )
                }
            }
        })
    }

    private fun startAndBindEngine() {
        val serviceIntent = Intent(this, EqualizerService::class.java)
        startForegroundService(serviceIntent)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
        micAnalyzer.onMicBandsListener = { bands ->
            // Mic RTA thread ~28Hz — DSP updates off-UI, repaints throttled.
            val micLow = (bands[0] + bands[1]) * 0.5f
            val micMed = bands[2]
            val micHigh = 0.5f * (bands[3] + bands[4])
            val tf = transferEngine.evaluateTransfer(bands)
            npuAgent?.transferCompMb = tf.compensationMb
            npuAgent?.transferTag = tf.summaryTag
            if (micLow + micMed + micHigh > 0.03f) {
                npuAgent?.pushSpectralFrame(micLow, micMed, micHigh)
            }
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastMicUiMs >= 150) {
                lastMicUiMs = now
                runOnUiThread {
                    ui.visualizerView.updateMicBands(bands)
                    if (micLow + micMed + micHigh > 0.03f) {
                        ui.hypnoView.updateSpectralBands(micLow, micMed, micHigh)
                        ui.visualizerView.updateSpectralTriplet(micLow, micMed, micHigh)
                    }
                }
            }
        }
        micAnalyzer.start()
    }

    private fun updateDbStats() {
        ui.dbStatsLabel.text =
            "SQLITE (eq_telemetry.db): ${dbHelper.getTotalRowCount()} snapshots indexed."
    }

    private fun refreshModelStatus() {
        ui.modelStatusLabel.text = lmEngine.getModelManager().statusText()
        ui.modelBtn.text =
            if (lmEngine.getModelManager().isModelPresent()) "Model ready ✓"
            else "Get Gemma model (584 MB)"
    }

    private fun runLmSessionInference() {
        updateDbStats()
        val recentAnalysis = dbHelper.getRecentAnalysis(300_000L)
        if (recentAnalysis.totalSamples < 3) {
            ui.advisorResultLabel.text =
                "INSUFFICIENT TELEMETRY: Play music for a few seconds first."
            return
        }
        ui.advisorResultLabel.text = "Running on-device inference…"
        lmEngine.inferStreaming(
            recentAnalysis, currentRoute,
            onToken = { tok -> runOnUiThread { appendLog(tok.trim()) } },
            onComplete = { resp ->
                runOnUiThread {
                    recommendedState = resp.recommendedState
                    recommendedGains = resp.targetGains
                    ui.advisorResultLabel.text =
                        "[${resp.backendTag}] ${resp.reasoning}"
                    ui.applyRecBtn.visibility = View.VISIBLE
                }
            },
        )
    }

    private fun applyRecommendation() {
        val gains = recommendedGains
        when {
            gains != null -> {
                hasCustomCurve = true
                EqualizerService.profiles[EqualizerService.currentState] = gains.clone()
                ui.visualizerView.setTargetGains(gains)
                eqService?.setTargetGains(gains)
                dbHelper.saveRouteProfile(
                    currentRoute, FloatArray(5) { gains[it].toFloat() }, 0f
                )
                logbook.recordEntry(
                    currentRoute, "Advisor Curve", gains,
                    ui.advisorResultLabel.text.toString()
                )
                appendLog("[APPLY] Advisor gains -> DSP chain")
            }
            recommendedState != -1 ->
                setHardwareState(recommendedState, ui.advisorResultLabel.text.toString())
        }
        ui.applyRecBtn.visibility = View.GONE
    }

    private fun appendLog(line: String) {
        if (line.isBlank()) return
        runOnUiThread {
            synchronized(terminalLines) {
                if (terminalLines.size >= maxLogLines) terminalLines.removeFirst()
                terminalLines.add(line)
                ui.crtLogView.text = ui.renderLog(terminalLines)
            }
            ui.crtScrollView.post { ui.crtScrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun checkAudioPermission() {
        val perms = mutableListOf<String>()
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != 0) {
            perms.add(android.Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != 0
        ) {
            perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        if (perms.isNotEmpty()) {
            requestPermissions(perms.toTypedArray(), 101)
        } else {
            startAndBindEngine()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray,
    ) {
        if (requestCode == 101 &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            startAndBindEngine()
            startAudioTap()
        }
    }

    private fun setHardwareState(state: Int, reason: String = "Manual 4-bit register selection") {
        hasCustomCurve = false
        val safe = state.coerceIn(0, 15)
        val presetFloats = FloatArray(5) { EqualizerService.profiles[safe][it].toFloat() }
        dbHelper.saveRouteProfile(currentRoute, presetFloats, 0f)
        val name = EqualizerService.profileNames.getOrNull(safe) ?: "Preset $safe"
        val gains = EqualizerService.profiles.getOrNull(safe) ?: ShortArray(5)
        logbook.recordEntry(currentRoute, name, gains, reason)
        startService(
            Intent(this, EqualizerService::class.java).apply {
                action = EqualizerService.ACTION_SET_STATE
                putExtra(EqualizerService.EXTRA_STATE, safe)
            }
        )
        renderState(safe)
    }

    private fun renderState(state: Int) {
        val safeState = state.coerceIn(0, 15)
        val binaryStr = Integer.toBinaryString(safeState).padStart(4, '0')
        val hexStr = "0x" + Integer.toHexString(safeState).uppercase(Locale.ROOT)
        val name = EqualizerService.profileNames.getOrNull(safeState) ?: "Preset $safeState"
        ui.titleLabel.text = "$hexStr [$binaryStr] $name"
        for (i in 0 until 4) {
            val bitIndex = 3 - i
            val isSet = (1 shl bitIndex) and safeState != 0
            val btn = ui.bitButtons[i]
            btn.text = "B$bitIndex\n${if (isSet) "1" else "0"}"
            ui.neonButton(btn, EqUiHolders.LIME, filled = isSet)
        }
        if (!isNpuAutoMode) {
            EqualizerService.profiles.getOrNull(safeState)?.let {
                ui.visualizerView.setTargetGains(it)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ui.visualizerView.isPaused = false
        ui.hypnoView.isPaused = false
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == 0) {
            micAnalyzer.start()
            startAudioTap()
        }
        val filter = IntentFilter(EqualizerService.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        ui.visualizerView.isPaused = true
        ui.hypnoView.isPaused = true
        micAnalyzer.stop()
        audioTap.stop()
        unregisterReceiver(receiver)
    }

    private var vizFullscreen: android.app.Dialog? = null

    /** Moves the visualizer into an immersive, screen-on dialog and back. */
    private fun toggleVizFullscreen() {
        vizFullscreen?.let { it.dismiss(); return }
        val v = ui.hypnoView
        val parent = v.parent as? android.view.ViewGroup ?: return
        val idx = parent.indexOfChild(v)
        val lp = v.layoutParams
        parent.removeView(v)
        val dlg = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dlg.setContentView(v, android.view.ViewGroup.LayoutParams(-1, -1))
        dlg.setOnDismissListener {
            (v.parent as? android.view.ViewGroup)?.removeView(v)
            parent.addView(v, idx, lp)
            v.isFullscreen = false
            vizFullscreen = null
        }
        v.isFullscreen = true
        vizFullscreen = dlg
        dlg.show()
        dlg.window?.let { w ->
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= 30) {
                w.insetsController?.let {
                    it.hide(android.view.WindowInsets.Type.systemBars())
                    it.systemBarsBehavior =
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                w.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            }
        }
    }

    override fun onDestroy() {
        vizFullscreen?.dismiss()
        super.onDestroy()
        if (isBound) {
            EqualizerService.onTelemetryListener = null
            unbindService(connection)
            isBound = false
        }
        micAnalyzer.stop()
        npuAgent?.release()
        if (::lmEngine.isInitialized) lmEngine.release()
        if (::dbHelper.isInitialized) dbHelper.close()
    }
}
