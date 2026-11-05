package com.nativeeq.equalizer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Virtualizer
import android.media.audiofx.Visualizer
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.os.Trace
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

class EqualizerService : Service() {

    companion object {
        const val ACTION_SET_STATE = "com.nativeeq.equalizer.ACTION_SET_STATE"
        const val ACTION_SET_BASS = "com.nativeeq.equalizer.ACTION_SET_BASS"
        const val ACTION_SET_VIRT = "com.nativeeq.equalizer.ACTION_SET_VIRT"
        const val ACTION_APPLY_NPU_CORRECTION =
            "com.nativeeq.equalizer.ACTION_APPLY_NPU_CORRECTION"
        const val ACTION_STATE_CHANGED = "com.nativeeq.equalizer.ACTION_STATE_CHANGED"
        const val EXTRA_STATE = "com.nativeeq.equalizer.EXTRA_STATE"
        const val EXTRA_ROUTE_KEY = "com.nativeeq.equalizer.EXTRA_ROUTE_KEY"
        const val EXTRA_BASS_STRENGTH = "com.nativeeq.equalizer.EXTRA_BASS_STRENGTH"
        const val EXTRA_VIRT_STRENGTH = "com.nativeeq.equalizer.EXTRA_VIRT_STRENGTH"
        const val EXTRA_NPU_GAINS = "com.nativeeq.equalizer.EXTRA_NPU_GAINS"
        const val EXTRA_NPU_MSG = "com.nativeeq.equalizer.EXTRA_NPU_MSG"
        private const val NOTIF_CHANNEL_ID = "eq_dsp_engine"
        private const val NOTIF_ID = 0xE4

        var currentState = 9
            private set

        fun setCurrentState(state: Int) {
            currentState = state
        }

        var bassBoostStrength: Short = 0
        var virtualizerStrength: Short = 0
        /** UI-facing L/M/H triplet, delivered every FFT frame. */
        var onTelemetryListener: ((Float, Float, Float) -> Unit)? = null
        /** Agent-facing log-spaced spectrum (SPECTRUM_BANDS levels in 0..1), ~4Hz. */
        var onSpectrumListener: ((FloatArray) -> Unit)? = null

        const val SPECTRUM_BANDS = 24
        const val SPECTRUM_MIN_HZ = 50f
        const val SPECTRUM_MAX_HZ = 16000f
        private const val SPECTRUM_INTERVAL_MS = 250L
        // 10*log10(1 + max byte-FFT power 128^2 * 2)
        private val SPECTRUM_DB_FULL_SCALE = 10f * log10(1f + 32768f)

        const val GAIN_GRID_MB = 50f
        const val LIMITER_GRID_DB = 0.1f

        /** Headphone-correction stage: DP postEq, log-spaced 20Hz-20kHz (matches tools/build_autoeq.py). */
        const val POST_EQ_BANDS = 32
        private const val POST_EQ_MIN_HZ = 20f
        private const val POST_EQ_MAX_HZ = 20000f
        const val POST_EQ_GRID_DB = 0.25f
        const val ROUTE_INTERNAL = "dev_internal"
        private const val HEADPHONE_OFF = "__off__"

        fun postEqEdgeHz(edge: Int): Float =
            POST_EQ_MIN_HZ * (POST_EQ_MAX_HZ / POST_EQ_MIN_HZ).pow(edge.toFloat() / POST_EQ_BANDS)

        /** Output route key: "bt:<name>", "usb:<name>", "wired" or [ROUTE_INTERNAL]. */
        @Volatile var currentRouteKey = ROUTE_INTERNAL
            private set
        /** Active headphone correction, e.g. "Sony WH-1000XM4 (auto)", or null. */
        @Volatile var headphoneLabel: String? = null
            private set

        private var instanceRef = WeakReference<EqualizerService>(null)
        // Senders of EQ control broadcasts can't be verified (by protocol design), so
        // bound how many DPs a misbehaving app can make us hold; oldest drops first.
        private const val MAX_CONTROL_SESSIONS = 16
        private val controlSessions = LinkedHashSet<Int>()

        /** From [SessionControlReceiver] or the service's runtime receiver. */
        fun onControlSession(session: Int, open: Boolean) {
            val changed = synchronized(controlSessions) {
                val c = if (open) controlSessions.add(session) else controlSessions.remove(session)
                while (controlSessions.size > MAX_CONTROL_SESSIONS) {
                    controlSessions.remove(controlSessions.first())
                }
                PerfStats.controlSessions = controlSessions.size
                c
            }
            if (!changed) return
            PerfStats.controlEvents.incrementAndGet()
            instanceRef.get()?.requestReconcile(SystemClock.elapsedRealtimeNanos())
        }

        private fun controlSessionSnapshot(): Set<Int> =
            synchronized(controlSessions) { HashSet(controlSessions) }

        // AudioDeviceInfo types newer than minSdk 26 (28, 31, 31).
        private const val TYPE_HEARING_AID = 23
        private const val TYPE_BLE_HEADSET = 26
        private const val TYPE_BLE_SPEAKER = 27

        // AudioPlaybackConfiguration.PLAYER_STATE_* (@SystemApi constants)
        private const val PLAYER_STATE_RELEASED = 0
        private const val PLAYER_STATE_STOPPED = 4

        fun spectrumBandEdgeHz(edge: Int): Float =
            SPECTRUM_MIN_HZ * (SPECTRUM_MAX_HZ / SPECTRUM_MIN_HZ)
                .pow(edge.toFloat() / SPECTRUM_BANDS)

        fun spectrumBandCenterHz(band: Int): Float =
            sqrt(spectrumBandEdgeHz(band) * spectrumBandEdgeHz(band + 1))

        /** Snap a gain to the 50mB grid the DSP actually plays. */
        fun quantizeGainMb(mb: Float): Float = (mb / GAIN_GRID_MB).roundToInt() * GAIN_GRID_MB

        fun quantizeGains(gainsMb: ShortArray): FloatArray =
            FloatArray(gainsMb.size) { quantizeGainMb(gainsMb[it].toFloat()) }

        fun limiterPreGainFor(gainsMb: FloatArray): Float {
            var maxBoostDb = 0f
            for (mb in gainsMb) {
                val db = mb / 100f * 1.75f
                if (db > maxBoostDb) maxBoostDb = db
            }
            return (-0.45f * maxBoostDb / LIMITER_GRID_DB).roundToInt() * LIMITER_GRID_DB
        }

        fun limiterPreGainFor(gainsMb: ShortArray): Float =
            limiterPreGainFor(FloatArray(gainsMb.size) { gainsMb[it].toFloat() })

        val profileNames = arrayOf(
            "Flat", "Deep Bass", "Sub Punch", "Warm Lows", "Mid Presence",
            "V-Shape", "Scooped Warm", "Aggressive V", "High Clarity",
            "Vocal Forward", "Bright Crisp", "Air Shine", "Mid Focus",
            "Presence Solo", "Balanced Bright", "Master Final"
        )
        val profiles: Array<ShortArray> = arrayOf(
            shortArrayOf(0, 0, 0, 0, 0),
            shortArrayOf(300, 150, 0, 0, 0),
            shortArrayOf(450, 200, -50, 0, 50),
            shortArrayOf(250, 200, 50, 0, -100),
            shortArrayOf(0, 50, 250, 100, 0),
            shortArrayOf(350, 100, -150, 150, 300),
            shortArrayOf(300, 200, -100, 50, 150),
            shortArrayOf(500, 150, -250, 200, 400),
            shortArrayOf(-100, 0, 100, 250, 400),
            shortArrayOf(-150, 0, 300, 250, 100),
            shortArrayOf(-50, 0, 150, 350, 450),
            shortArrayOf(0, 0, 50, 200, 500),
            shortArrayOf(-100, 100, 350, 150, -50),
            shortArrayOf(-50, 50, 400, 300, 100),
            shortArrayOf(150, 50, 100, 200, 300),
            shortArrayOf(200, 100, 50, 150, 250),
        )
    }

    var dynamicsProcessing: DynamicsProcessing? = null
        private set
    private var dspConfig: DynamicsProcessing.Config? = null
    private var sessionDps: HashMap<Int, DynamicsProcessing>? = null
    // Per-DP snapshot of what was last pushed through JNI: [0..bandCount) preEq
    // gains in mB, [bandCount] limiter postGain in centi-dB.
    private val lastWritten = HashMap<DynamicsProcessing, ShortArray>()
    var bassBoost: BassBoost? = null
        private set
    var virtualizer: Virtualizer? = null
        private set
    private var visualizer: Visualizer? = null
    private val captureLock = Any()
    private var captureWanted = false

    val bandCount = 5
    val currentGainsMb = FloatArray(bandCount)
    val targetGainsMb = FloatArray(bandCount)
    var currentLimiterPreGainDb = 0f
        private set
    var targetLimiterPreGainDb = 0f
        private set

    private val isMorphing = AtomicBoolean(false)
    private val morphAlpha = 0.22f
    private val frameIntervalMs = 16L
    // DSP work runs on its own thread — the morph loop does JNI writes into
    // audio effects and session reconciliation runs off playback callbacks;
    // neither belongs on the main looper.
    private val dspThread = HandlerThread("eq_dsp").apply { start() }
    private val morphHandler = Handler(dspThread.looper)
    private var isForegroundStarted = false
    private var playbackCallbackRegistered = false
    private var deviceCallbackRegistered = false
    private var controlReceiverRegistered = false
    // Last session set seen via the playback callback / DUMP fallback.
    @Volatile private var playbackLive: Set<Int> = emptySet()
    private var postEqInUse = false
    val currentPostEqDb = FloatArray(POST_EQ_BANDS)
    private val targetPostEqDb = FloatArray(POST_EQ_BANDS)
    private var lastRouteProductName: String? = null
    val autoEq: AutoEqCatalog? by lazy { AutoEqCatalog.load(assets) }
    private val headphonePrefs by lazy { getSharedPreferences("headphone_correction", Context.MODE_PRIVATE) }

    private val dbHelper by lazy { TelemetryDbHelper(this) }
    private val binder = LocalBinder()

    private val bandFreqs = floatArrayOf(60f, 230f, 910f, 3600f, 14000f)

    // FFT -> log-band map, rebuilt only when capture size / sample rate change.
    private var spectrumBinLo = IntArray(SPECTRUM_BANDS)
    private var spectrumBinHi = IntArray(SPECTRUM_BANDS)
    private var spectrumMapKey = 0L
    private var lastSpectrumMs = 0L

    private val morphRunnable = object : Runnable {
        override fun run() {
            val frameStart = SystemClock.uptimeMillis()
            Trace.beginSection("eq.morph")
            PerfStats.morphFrames.incrementAndGet()
            var activeDelta = false
            for (i in 0 until bandCount) {
                val diff = targetGainsMb[i] - currentGainsMb[i]
                if (abs(diff) > GAIN_GRID_MB) {
                    currentGainsMb[i] += morphAlpha * diff
                    activeDelta = true
                } else {
                    currentGainsMb[i] = targetGainsMb[i]
                }
            }
            if (postEqInUse) {
                for (b in 0 until POST_EQ_BANDS) {
                    val diff = targetPostEqDb[b] - currentPostEqDb[b]
                    if (abs(diff) > POST_EQ_GRID_DB) {
                        currentPostEqDb[b] += morphAlpha * diff
                        activeDelta = true
                    } else {
                        currentPostEqDb[b] = targetPostEqDb[b]
                    }
                }
            }
            val limiterDiff = targetLimiterPreGainDb - currentLimiterPreGainDb
            if (abs(limiterDiff) > LIMITER_GRID_DB) {
                currentLimiterPreGainDb += morphAlpha * limiterDiff
                activeDelta = true
            } else {
                currentLimiterPreGainDb = targetLimiterPreGainDb
            }
            val wrote = applyHardwareState()
            Trace.endSection()
            if (wrote || activeDelta) {
                // Self-clocking: hold a 16ms cadence, but if the JNI writes
                // themselves overran the frame, yield at least that long.
                val cost = SystemClock.uptimeMillis() - frameStart
                val delay = if (cost < frameIntervalMs) frameIntervalMs - cost else cost
                morphHandler.postDelayed(this, delay)
            } else {
                isMorphing.set(false)
                // A target may have landed between the last check and the flag drop.
                if (hasPendingMorph() && isMorphing.compareAndSet(false, true)) {
                    morphHandler.post(this)
                }
            }
        }
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            onPlaybackConfigs(configs ?: emptyList())
        }
    }

    private val dumpsysReconcile = Runnable {
        playbackLive = readDumpsysSessions()
        reconcileSessions(SystemClock.elapsedRealtimeNanos())
    }

    private val controlReceiver = SessionControlReceiver()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = refreshRoute()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = refreshRoute()
    }

    init {
        initDspEffects()
        startAudioCapture()
    }

    override fun onCreate() {
        super.onCreate()
        instanceRef = WeakReference(this)
        registerSessionTracking()
    }

    fun requestReconcile(eventNanos: Long) {
        morphHandler.post { reconcileSessions(eventNanos) }
    }

    private fun hasPendingMorph(): Boolean {
        for (i in 0 until bandCount) {
            if (targetGainsMb[i] != currentGainsMb[i]) return true
        }
        if (postEqInUse) {
            for (b in 0 until POST_EQ_BANDS) if (targetPostEqDb[b] != currentPostEqDb[b]) return true
        }
        return targetLimiterPreGainDb != currentLimiterPreGainDb
    }

    /** Writes only the params whose quantized value changed. Returns true if any JNI write happened. */
    private fun applyGainsToDp(dp: DynamicsProcessing): Boolean {
        // postEq slots start at the config's 0dB so a fresh DP with correction off costs no writes.
        val cache = lastWritten.getOrPut(dp) {
            ShortArray(bandCount + 1 + POST_EQ_BANDS) { if (it > bandCount) 0 else Short.MIN_VALUE }
        }
        var wrote = false
        for (i in 0 until bandCount) {
            val mb = currentGainsMb[i].roundToInt().coerceIn(-9999, 9999).toShort()
            if (mb == cache[i]) continue
            for (ch in 0..1) {
                val band = dp.getPreEqBandByChannelIndex(ch, i)
                band.gain = mb / 100f
                dp.setPreEqBandByChannelIndex(ch, i, band)
                PerfStats.jniWrites.incrementAndGet()
            }
            cache[i] = mb
            wrote = true
        }
        val postGainCdb = (currentLimiterPreGainDb * 100f).roundToInt().coerceIn(-9999, 9999).toShort()
        if (postGainCdb != cache[bandCount]) {
            for (ch in 0..1) {
                val limiter = dp.getLimiterByChannelIndex(ch)
                limiter.linkGroup = 0
                limiter.attackTime = 1f
                limiter.releaseTime = 60f
                limiter.ratio = 10f
                limiter.threshold = -1f
                limiter.postGain = postGainCdb / 100f
                dp.setLimiterByChannelIndex(ch, limiter)
                PerfStats.jniWrites.incrementAndGet()
            }
            cache[bandCount] = postGainCdb
            wrote = true
        }
        if (postEqInUse) {
            for (b in 0 until POST_EQ_BANDS) {
                val slot = bandCount + 1 + b
                val cdb = (currentPostEqDb[b] * 100f).roundToInt().coerceIn(-9999, 9999).toShort()
                if (cdb == cache[slot]) continue
                for (ch in 0..1) {
                    val band = dp.getPostEqBandByChannelIndex(ch, b)
                    band.gain = cdb / 100f
                    dp.setPostEqBandByChannelIndex(ch, b, band)
                    PerfStats.jniWrites.incrementAndGet()
                }
                cache[slot] = cdb
                wrote = true
            }
        }
        return wrote
    }

    private fun initDspEffects() {
        if (dynamicsProcessing != null) return
        try {
            // Prefer the chain with the headphone-correction postEq stage; fall
            // back to the original preEq-only layout if the device rejects it.
            var config: DynamicsProcessing.Config
            val dp = try {
                config = buildDspConfig(withPostEq = true)
                DynamicsProcessing(0, 0, config).also { postEqInUse = true }
            } catch (_: Exception) {
                config = buildDspConfig(withPostEq = false)
                DynamicsProcessing(0, 0, config)
            }
            dspConfig = config
            dp.enabled = true
            dynamicsProcessing = dp
            sessionDps = HashMap()
            val bb = BassBoost(0, 0)
            bb.enabled = true
            bb.setStrength(bassBoostStrength)
            bassBoost = bb
            val v = Virtualizer(0, 0)
            v.enabled = true
            v.setStrength(virtualizerStrength)
            virtualizer = v
        } catch (_: Exception) {
        }
    }

    private fun buildDspConfig(withPostEq: Boolean): DynamicsProcessing.Config {
        val config = DynamicsProcessing.Config.Builder(
            0, 2, true, bandCount, true, bandCount,
            withPostEq, if (withPostEq) POST_EQ_BANDS else 0, true
        ).build()
        for (b in 0 until bandCount) {
            config.setPreEqBandAllChannelsTo(b, DynamicsProcessing.EqBand(true, bandFreqs[b], 0f))
        }
        if (withPostEq) {
            for (b in 0 until POST_EQ_BANDS) {
                config.setPostEqBandAllChannelsTo(
                    b, DynamicsProcessing.EqBand(true, postEqEdgeHz(b + 1), 0f)
                )
            }
        }
        return config
    }

    /**
     * Per-session attach, event-driven: AudioPlaybackCallback fires on every
     * player start/pause/stop and we reconcile the session -> DP map.
     */
    private fun registerSessionTracking() {
        val am = getSystemService(AudioManager::class.java) ?: return
        if (!controlReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
            }
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(controlReceiver, filter, null, morphHandler, Context.RECEIVER_EXPORTED)
                } else {
                    registerReceiver(controlReceiver, filter, null, morphHandler)
                }
                controlReceiverRegistered = true
            } catch (_: Exception) {
            }
        }
        if (!deviceCallbackRegistered) {
            try {
                am.registerAudioDeviceCallback(deviceCallback, morphHandler)
                deviceCallbackRegistered = true
            } catch (_: Exception) {
            }
        }
        if (playbackCallbackRegistered) return
        try {
            am.registerAudioPlaybackCallback(playbackCallback, morphHandler)
            playbackCallbackRegistered = true
            val initial = am.activePlaybackConfigurations
            morphHandler.post { onPlaybackConfigs(initial) }
        } catch (_: Exception) {
        }
    }

    private fun onPlaybackConfigs(configs: List<AudioPlaybackConfiguration>) {
        val eventNanos = SystemClock.elapsedRealtimeNanos()
        PerfStats.playbackEvents.incrementAndGet()
        val live = HashSet<Int>()
        var anonymized = 0
        for (cfg in configs) {
            val state = PlaybackConfigReflect.playerState(cfg)
            if (state == PLAYER_STATE_STOPPED || state == PLAYER_STATE_RELEASED) continue
            val session = PlaybackConfigReflect.sessionId(cfg)
            if (session == 0) {
                anonymized++
                continue
            }
            live.add(session)
        }
        // Without MODIFY_AUDIO_ROUTING (privileged-only) the framework hands us
        // anonymizedCopy()s whose sessionId is always 0. If the user granted
        // DUMP, read the real ids once for this event instead of polling.
        if (live.isEmpty() && anonymized > 0 && hasDumpPermission()) {
            morphHandler.removeCallbacks(dumpsysReconcile)
            morphHandler.post(dumpsysReconcile)
            return
        }
        playbackLive = live
        reconcileSessions(eventNanos)
    }

    private fun hasDumpPermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.DUMP) ==
            PackageManager.PERMISSION_GRANTED

    private fun readDumpsysSessions(): Set<Int> {
        val live = HashSet<Int>()
        try {
            BufferedReader(
                InputStreamReader(Runtime.getRuntime().exec("dumpsys audio").inputStream)
            ).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val idx = line.indexOf("session:")
                    if (idx < 0) continue
                    val start = idx + 8
                    var end = start
                    while (end < line.length && line[end].isDigit()) end++
                    if (end == start) continue
                    val session = line.substring(start, end).toIntOrNull() ?: continue
                    if (session != 0) live.add(session)
                }
            }
        } catch (_: Exception) {
        }
        return live
    }

    /** Live sessions = playback callback/DUMP view ∪ sessions announced via EQ control broadcasts. */
    @Synchronized
    private fun reconcileSessions(eventNanos: Long) {
        val map = sessionDps ?: return
        val config = dspConfig ?: return
        Trace.beginSection("eq.reconcile")
        val live = HashSet(playbackLive)
        live.addAll(controlSessionSnapshot())
        var attached = false
        for (session in live) {
            if (map.containsKey(session)) continue
            try {
                val dp = DynamicsProcessing(0, session, config)
                dp.enabled = true
                map[session] = dp
                applyGainsToDp(dp)
                attached = true
            } catch (_: Exception) {
            }
        }
        if (attached) {
            PerfStats.lastAttachMicros = (SystemClock.elapsedRealtimeNanos() - eventNanos) / 1000
        }
        val it = map.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (!live.contains(entry.key)) {
                lastWritten.remove(entry.value)
                try {
                    entry.value.release()
                } catch (_: Exception) {
                }
                it.remove()
            }
        }
        PerfStats.sessionDps = map.size
        Trace.endSection()
    }

    @Synchronized
    private fun applyHardwareState(): Boolean {
        val dp = dynamicsProcessing ?: return false
        var wrote = false
        try {
            wrote = applyGainsToDp(dp)
        } catch (_: Exception) {
        }
        sessionDps?.values?.forEach {
            try {
                if (applyGainsToDp(it)) wrote = true
            } catch (_: Exception) {
            }
        }
        return wrote
    }

    /**
     * The Visualizer only runs while someone consumes it: the UI is on screen
     * or the closed-loop agent is driving the EQ.
     */
    fun setCaptureDemand(uiVisible: Boolean, agentActive: Boolean) {
        synchronized(captureLock) {
            captureWanted = uiVisible || agentActive
            PerfStats.captureOn = captureWanted
            val v = visualizer ?: return
            try {
                if (v.enabled != captureWanted) v.enabled = captureWanted
            } catch (_: Exception) {
            }
        }
    }

    private fun startAudioCapture() {
        synchronized(captureLock) {
            if (visualizer != null) return
            try {
                val v = Visualizer(0)
                v.captureSize = Visualizer.getCaptureSizeRange()[1]
                v.setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(
                            v: Visualizer?, waveform: ByteArray?, samplingRate: Int,
                        ) {
                        }

                        override fun onFftDataCapture(
                            v: Visualizer?, fft: ByteArray?, samplingRate: Int,
                        ) {
                            if (fft == null) return
                            onFftFrame(fft, samplingRate)
                        }
                    },
                    Visualizer.getMaxCaptureRate() / 2, false, true
                )
                v.enabled = captureWanted
                visualizer = v
            } catch (_: Exception) {
            }
        }
    }

    private fun onFftFrame(fft: ByteArray, samplingRateMilliHz: Int) {
        val t0 = System.nanoTime()
        Trace.beginSection("eq.fft")
        try {
            processFftFrame(fft, samplingRateMilliHz)
        } finally {
            Trace.endSection()
            PerfStats.fftNanos.addAndGet(System.nanoTime() - t0)
            PerfStats.fftFrames.incrementAndGet()
        }
    }

    private fun processFftFrame(fft: ByteArray, samplingRateMilliHz: Int) {
        var lowSum = 0f
        var medSum = 0f
        var highSum = 0f
        val n = fft.size / 2
        for (i in 1 until n) {
            val re = fft[i * 2].toFloat()
            val im = fft[i * 2 + 1].toFloat()
            val mag = sqrt(re * re + im * im)
            when {
                i < 8 -> lowSum += mag
                i < 33 -> medSum += mag
                else -> highSum += mag
            }
        }
        val low = (lowSum / 1024f).coerceIn(0f, 1f)
        val med = (medSum / 3072f).coerceIn(0f, 1f)
        val high = (highSum / ((n - 32) * 128f)).coerceIn(0f, 1f)
        onTelemetryListener?.invoke(low, med, high)

        val spectrumSink = onSpectrumListener ?: return
        val now = SystemClock.uptimeMillis()
        if (now - lastSpectrumMs < SPECTRUM_INTERVAL_MS) return
        lastSpectrumMs = now
        PerfStats.spectrumOut.incrementAndGet()
        spectrumSink(computeLogSpectrum(fft, samplingRateMilliHz))
    }

    /** Mean power per log-spaced band, as dB scaled to 0..1. One log10 per band, no per-bin sqrt. */
    private fun computeLogSpectrum(fft: ByteArray, samplingRateMilliHz: Int): FloatArray {
        val n = fft.size / 2
        val key = (fft.size.toLong() shl 32) or (samplingRateMilliHz.toLong() and 0xffffffffL)
        if (key != spectrumMapKey) {
            val binHz = samplingRateMilliHz / 1000f / fft.size
            for (b in 0 until SPECTRUM_BANDS) {
                var lo = (spectrumBandEdgeHz(b) / binHz).toInt() + 1
                var hi = (spectrumBandEdgeHz(b + 1) / binHz).toInt()
                lo = lo.coerceIn(1, n - 1)
                hi = hi.coerceIn(1, n - 1)
                if (hi < lo) {
                    // Band narrower than one bin: use the bin nearest its center.
                    val c = (spectrumBandCenterHz(b) / binHz).roundToInt().coerceIn(1, n - 1)
                    lo = c
                    hi = c
                }
                spectrumBinLo[b] = lo
                spectrumBinHi[b] = hi
            }
            spectrumMapKey = key
        }
        val out = FloatArray(SPECTRUM_BANDS)
        for (b in 0 until SPECTRUM_BANDS) {
            var power = 0f
            val lo = spectrumBinLo[b]
            val hi = spectrumBinHi[b]
            for (i in lo..hi) {
                val re = fft[i * 2].toFloat()
                val im = fft[i * 2 + 1].toFloat()
                power += re * re + im * im
            }
            val mean = power / (hi - lo + 1)
            out[b] = (10f * log10(1f + mean) / SPECTRUM_DB_FULL_SCALE).coerceIn(0f, 1f)
        }
        return out
    }

    private fun promoteToForeground() {
        if (isForegroundStarted) return
        isForegroundStarted = true
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                NOTIF_CHANNEL_ID, "EQ DSP Engine",
                NotificationManager.IMPORTANCE_MIN
            ).apply { setShowBadge(false) }
        )
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = Notification.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("Equalizer active")
            .setContentText("DSP engine processing audio")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): EqualizerService {
            startAudioCapture()
            return this@EqualizerService
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        initDspEffects()
        startAudioCapture()
        registerSessionTracking()
        when (intent?.action) {
            ACTION_SET_BASS -> {
                val strength = intent.getShortExtra(EXTRA_BASS_STRENGTH, 0)
                bassBoostStrength = strength
                try {
                    bassBoost?.setStrength(strength)
                } catch (_: Exception) {
                }
            }
            ACTION_SET_VIRT -> {
                val strength = intent.getShortExtra(EXTRA_VIRT_STRENGTH, 0)
                virtualizerStrength = strength
                try {
                    virtualizer?.setStrength(strength)
                } catch (_: Exception) {
                }
            }
            ACTION_APPLY_NPU_CORRECTION -> {
                val floatGains = intent.getFloatArrayExtra(EXTRA_NPU_GAINS)
                val shortGains = intent.getShortArrayExtra(EXTRA_NPU_GAINS)
                when {
                    floatGains != null -> setTargetGains(floatGains)
                    shortGains != null -> setTargetGains(shortGains)
                }
            }
            ACTION_SET_STATE -> {
                val state = intent.getIntExtra(EXTRA_STATE, currentState)
                currentState = state
                if (state in 0..15) setTargetGains(profiles[state])
            }
        }
        return START_STICKY
    }

    fun setTargetGains(targets: FloatArray, immediate: Boolean = false) {
        if (targets.size != bandCount) return
        for (i in 0 until bandCount) {
            targetGainsMb[i] = quantizeGainMb(targets[i])
        }
        targetLimiterPreGainDb = limiterPreGainFor(targetGainsMb)
        if (immediate) {
            System.arraycopy(targetGainsMb, 0, currentGainsMb, 0, bandCount)
            currentLimiterPreGainDb = targetLimiterPreGainDb
            applyHardwareState()
            return
        }
        if (isMorphing.compareAndSet(false, true)) {
            morphHandler.post(morphRunnable)
        }
    }

    fun setTargetGains(targets: ShortArray, immediate: Boolean = false) {
        setTargetGains(FloatArray(targets.size) { targets[it].toFloat() }, immediate)
    }

    /** Morph the postEq stage to [gainsDb] (AutoEq bands), or back to flat when null. */
    fun setHeadphoneCorrection(gainsDb: FloatArray?) {
        if (!postEqInUse) return
        for (b in 0 until POST_EQ_BANDS) {
            val g = gainsDb?.getOrNull(b) ?: 0f
            targetPostEqDb[b] = (g / POST_EQ_GRID_DB).roundToInt() * POST_EQ_GRID_DB
        }
        if (isMorphing.compareAndSet(false, true)) morphHandler.post(morphRunnable)
    }

    val headphoneCorrectionAvailable: Boolean get() = postEqInUse && autoEq != null

    /** Pin a catalog model (or null = off) for the current route; persists per route. */
    fun setHeadphoneModel(name: String?) {
        headphonePrefs.edit().putString("model:$currentRouteKey", name ?: HEADPHONE_OFF).apply()
        morphHandler.post { applyHeadphoneForRoute() }
    }

    /** Forget the pinned model for the current route and go back to auto-matching. */
    fun clearHeadphoneOverride() {
        headphonePrefs.edit().remove("model:$currentRouteKey").apply()
        morphHandler.post { applyHeadphoneForRoute() }
    }

    private fun applyHeadphoneForRoute() {
        val catalog = autoEq
        val override = headphonePrefs.getString("model:$currentRouteKey", null)
        val product = lastRouteProductName
        var label: String? = null
        val entry = when {
            catalog == null || override == HEADPHONE_OFF -> null
            override != null -> catalog.find(override)?.also { label = "${it.name} (manual)" }
            product != null && !currentRouteKey.startsWith("wired") && currentRouteKey != ROUTE_INTERNAL ->
                catalog.bestMatch(product)?.entry?.also { label = "${it.name} (auto)" }
            else -> null
        }
        headphoneLabel = label
        setHeadphoneCorrection(entry?.gainsDb)
    }

    private fun refreshRoute() {
        val am = getSystemService(AudioManager::class.java) ?: return
        val outs = try {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        } catch (_: Exception) {
            return
        }
        val best = outs.maxByOrNull { routePriority(it.type) }
        val prio = best?.let { routePriority(it.type) } ?: 0
        val name = best?.productName?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val key = when (prio) {
            3 -> "bt:${name ?: "unknown"}"
            2 -> "usb:${name ?: "unknown"}"
            1 -> "wired"
            else -> ROUTE_INTERNAL
        }
        if (key == currentRouteKey && name == lastRouteProductName) return
        currentRouteKey = key
        lastRouteProductName = if (prio >= 2) name else null
        applyHeadphoneForRoute()
        sendBroadcast(
            Intent(ACTION_STATE_CHANGED).setPackage(packageName)
                .putExtra(EXTRA_STATE, currentState)
                .putExtra(EXTRA_ROUTE_KEY, key)
        )
    }

    private fun routePriority(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        TYPE_HEARING_AID, TYPE_BLE_HEADSET, TYPE_BLE_SPEAKER -> 3
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> 2
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> 1
        else -> 0
    }

    fun saveCustomRouteCurve(routeKey: String, gains: ShortArray) {
        dbHelper.saveRouteProfile(
            routeKey, FloatArray(gains.size) { gains[it].toFloat() },
            currentLimiterPreGainDb
        )
    }

    fun loadCustomRouteCurve(routeKey: String): ShortArray? {
        val floats = dbHelper.getRouteProfile(routeKey) ?: return null
        setTargetGains(floats)
        return ShortArray(floats.size) { floats[it].toInt().toShort() }
    }

    @Synchronized
    fun updateMbcEnvelope(attackMs: Float, releaseMs: Float, ratio: Float, thresholdDb: Float) {
        val dp = dynamicsProcessing ?: return
        for (ch in 0..1) {
            try {
                for (b in 0 until bandCount) {
                    val mbc = dp.getMbcBandByChannelIndex(ch, b)
                    mbc.isEnabled = true
                    mbc.attackTime = attackMs
                    mbc.releaseTime = releaseMs
                    mbc.ratio = ratio
                    mbc.threshold = thresholdDb
                    dp.setMbcBandByChannelIndex(ch, b, mbc)
                }
            } catch (_: Exception) {
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (playbackCallbackRegistered) {
                getSystemService(AudioManager::class.java)
                    ?.unregisterAudioPlaybackCallback(playbackCallback)
                playbackCallbackRegistered = false
            }
            if (deviceCallbackRegistered) {
                getSystemService(AudioManager::class.java)
                    ?.unregisterAudioDeviceCallback(deviceCallback)
                deviceCallbackRegistered = false
            }
            if (controlReceiverRegistered) {
                unregisterReceiver(controlReceiver)
                controlReceiverRegistered = false
            }
            if (instanceRef.get() === this) instanceRef = WeakReference(null)
            // CLOSE broadcasts are missed while no service is registered; don't
            // resurrect possibly-dead sessions in the next instance.
            synchronized(controlSessions) {
                controlSessions.clear()
                PerfStats.controlSessions = 0
            }
            morphHandler.removeCallbacks(morphRunnable)
            morphHandler.removeCallbacks(dumpsysReconcile)
            dspThread.quitSafely()
            visualizer?.release()
            dynamicsProcessing?.release()
            bassBoost?.release()
            virtualizer?.release()
            sessionDps?.values?.forEach { it.release() }
            sessionDps?.clear()
            lastWritten.clear()
        } catch (_: Exception) {
        }
    }
}

/** AudioPlaybackConfiguration.getPlayerState()/getSessionId() are @SystemApi (sdk-listed, reflectable). */
private object PlaybackConfigReflect {
    private val getPlayerState = runCatching {
        AudioPlaybackConfiguration::class.java.getMethod("getPlayerState")
    }.getOrNull()
    private val getSessionId = runCatching {
        AudioPlaybackConfiguration::class.java.getMethod("getSessionId")
    }.getOrNull()

    fun playerState(cfg: AudioPlaybackConfiguration): Int =
        runCatching { getPlayerState?.invoke(cfg) as? Int }.getOrNull() ?: -1

    fun sessionId(cfg: AudioPlaybackConfiguration): Int =
        runCatching { getSessionId?.invoke(cfg) as? Int }.getOrNull() ?: 0
}
