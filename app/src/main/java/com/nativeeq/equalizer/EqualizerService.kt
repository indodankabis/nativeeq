package com.nativeeq.equalizer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Virtualizer
import android.media.audiofx.Visualizer
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import java.io.BufferedReader
import java.io.InputStreamReader
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
            val limiterDiff = targetLimiterPreGainDb - currentLimiterPreGainDb
            if (abs(limiterDiff) > LIMITER_GRID_DB) {
                currentLimiterPreGainDb += morphAlpha * limiterDiff
                activeDelta = true
            } else {
                currentLimiterPreGainDb = targetLimiterPreGainDb
            }
            val wrote = applyHardwareState()
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

    private val dumpsysReconcile = Runnable { reconcileSessions(readDumpsysSessions()) }

    init {
        initDspEffects()
        startAudioCapture()
    }

    override fun onCreate() {
        super.onCreate()
        registerSessionTracking()
    }

    private fun hasPendingMorph(): Boolean {
        for (i in 0 until bandCount) {
            if (targetGainsMb[i] != currentGainsMb[i]) return true
        }
        return targetLimiterPreGainDb != currentLimiterPreGainDb
    }

    /** Writes only the params whose quantized value changed. Returns true if any JNI write happened. */
    private fun applyGainsToDp(dp: DynamicsProcessing): Boolean {
        val cache = lastWritten.getOrPut(dp) { ShortArray(bandCount + 1) { Short.MIN_VALUE } }
        var wrote = false
        for (i in 0 until bandCount) {
            val mb = currentGainsMb[i].roundToInt().coerceIn(-9999, 9999).toShort()
            if (mb == cache[i]) continue
            for (ch in 0..1) {
                val band = dp.getPreEqBandByChannelIndex(ch, i)
                band.gain = mb / 100f
                dp.setPreEqBandByChannelIndex(ch, i, band)
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
            }
            cache[bandCount] = postGainCdb
            wrote = true
        }
        return wrote
    }

    private fun initDspEffects() {
        if (dynamicsProcessing != null) return
        try {
            val config = DynamicsProcessing.Config.Builder(
                0, 2, true, bandCount, true, bandCount, false, 0, true
            ).build()
            for (b in 0 until bandCount) {
                config.setPreEqBandAllChannelsTo(b, DynamicsProcessing.EqBand(true, bandFreqs[b], 0f))
            }
            dspConfig = config
            val dp = DynamicsProcessing(0, 0, config)
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

    /**
     * Per-session attach, event-driven: AudioPlaybackCallback fires on every
     * player start/pause/stop and we reconcile the session -> DP map.
     */
    private fun registerSessionTracking() {
        if (playbackCallbackRegistered) return
        val am = getSystemService(AudioManager::class.java) ?: return
        try {
            am.registerAudioPlaybackCallback(playbackCallback, morphHandler)
            playbackCallbackRegistered = true
            val initial = am.activePlaybackConfigurations
            morphHandler.post { onPlaybackConfigs(initial) }
        } catch (_: Exception) {
        }
    }

    private fun onPlaybackConfigs(configs: List<AudioPlaybackConfiguration>) {
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
        reconcileSessions(live)
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

    @Synchronized
    private fun reconcileSessions(live: Set<Int>) {
        val map = sessionDps ?: return
        val config = dspConfig ?: return
        for (session in live) {
            if (map.containsKey(session)) continue
            try {
                val dp = DynamicsProcessing(0, session, config)
                dp.enabled = true
                map[session] = dp
                applyGainsToDp(dp)
            } catch (_: Exception) {
            }
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
