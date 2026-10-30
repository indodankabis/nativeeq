package com.nativeeq.equalizer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Virtualizer
import android.media.audiofx.Visualizer
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.abs
import kotlin.math.hypot

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
        var onTelemetryListener: ((Float, Float, Float) -> Unit)? = null

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
    var bassBoost: BassBoost? = null
        private set
    var virtualizer: Virtualizer? = null
        private set
    private var visualizer: Visualizer? = null

    val bandCount = 5
    val currentGainsMb = FloatArray(bandCount)
    val targetGainsMb = FloatArray(bandCount)
    var currentLimiterPreGainDb = 0f
        private set
    var targetLimiterPreGainDb = 0f
        private set

    private var isMorphing = false
    private val morphAlpha = 0.22f
    private val frameIntervalMs = 16L
    // DSP work runs on its own thread — the morph loop does JNI writes into
    // audio effects every 16ms and session sync shells out to `dumpsys audio`;
    // neither belongs on the main looper.
    private val dspThread = HandlerThread("eq_dsp").apply { start() }
    private val morphHandler = Handler(dspThread.looper)
    private var sessionSyncPending = false
    private var isForegroundStarted = false

    private val dbHelper by lazy { TelemetryDbHelper(this) }
    private val binder = LocalBinder()

    private val bandFreqs = floatArrayOf(60f, 230f, 910f, 3600f, 14000f)

    private val morphRunnable = object : Runnable {
        override fun run() {
            var activeDelta = false
            for (i in 0 until bandCount) {
                val diff = targetGainsMb[i] - currentGainsMb[i]
                if (abs(diff) > 1f) {
                    currentGainsMb[i] += morphAlpha * diff
                    activeDelta = true
                } else {
                    currentGainsMb[i] = targetGainsMb[i]
                }
            }
            val limiterDiff = targetLimiterPreGainDb - currentLimiterPreGainDb
            if (abs(limiterDiff) > 0.05f) {
                currentLimiterPreGainDb += morphAlpha * limiterDiff
                activeDelta = true
            } else {
                currentLimiterPreGainDb = targetLimiterPreGainDb
            }
            applyHardwareState()
            if (activeDelta) {
                morphHandler.postDelayed(this, frameIntervalMs)
            } else {
                isMorphing = false
            }
        }
    }

    init {
        initDspEffects()
        startAudioCapture()
    }

    private fun applyGainsToDp(dp: DynamicsProcessing) {
        for (i in 0 until bandCount) {
            for (ch in 0..1) {
                val band = dp.getPreEqBandByChannelIndex(ch, i)
                band.gain = currentGainsMb[i] / 100f
                dp.setPreEqBandByChannelIndex(ch, i, band)
            }
        }
        for (ch in 0..1) {
            val limiter = dp.getLimiterByChannelIndex(ch)
            limiter.linkGroup = 0
            limiter.attackTime = 1f
            limiter.releaseTime = 60f
            limiter.ratio = 10f
            limiter.threshold = -1f
            limiter.postGain = currentLimiterPreGainDb
            dp.setLimiterByChannelIndex(ch, limiter)
        }
    }

    private fun initDspEffects() {
        if (dynamicsProcessing != null) return
        try {
            val config = DynamicsProcessing.Config.Builder(
                0, 2, true, bandCount, true, bandCount, false, 0, true
            ).build()
            dspConfig = config
            val dp = DynamicsProcessing(0, 0, config)
            dp.enabled = true
            dynamicsProcessing = dp
            for (ch in 0..1) {
                for (b in 0 until bandCount) {
                    val band = dp.getPreEqBandByChannelIndex(ch, b)
                    band.cutoffFrequency = bandFreqs[b]
                    dp.setPreEqBandByChannelIndex(ch, b, band)
                }
            }
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
     * Per-session attach: mirror the global DP onto every live audio session
     * found in `dumpsys audio` (needs the DUMP development permission).
     * Runs on the DSP thread, debounced — dumpsys is a fork+exec+parse and
     * startService arrives per UI action.
     */
    private fun scheduleSessionSync() {
        if (sessionSyncPending) return
        sessionSyncPending = true
        morphHandler.postDelayed({
            sessionSyncPending = false
            syncSessionEffects()
        }, 1500)
    }

    private fun syncSessionEffects() {
        val map = sessionDps ?: return
        val config = dspConfig ?: return
        val live = HashSet<Int>()
        try {
            val reader = BufferedReader(
                InputStreamReader(Runtime.getRuntime().exec("dumpsys audio").inputStream)
            )
            while (true) {
                val line = reader.readLine() ?: break
                val idx = line.indexOf("session:")
                if (idx < 0) continue
                val start = idx + 8
                var end = start
                while (end < line.length && line[end].isDigit()) end++
                if (end == start) continue
                val session = line.substring(start, end).toIntOrNull() ?: continue
                if (session == 0) continue
                live.add(session)
                if (!map.containsKey(session)) {
                    try {
                        val dp = DynamicsProcessing(0, session, config)
                        dp.enabled = true
                        map[session] = dp
                    } catch (_: Exception) {
                    }
                }
            }
            reader.close()
        } catch (_: Exception) {
        }
        val it = map.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (!live.contains(entry.key)) {
                try {
                    entry.value.release()
                } catch (_: Exception) {
                }
                it.remove()
            }
        }
    }

    @Synchronized
    private fun applyHardwareState() {
        val dp = dynamicsProcessing ?: return
        try {
            applyGainsToDp(dp)
        } catch (_: Exception) {
        }
        sessionDps?.values?.forEach {
            try {
                applyGainsToDp(it)
            } catch (_: Exception) {
            }
        }
    }

    private fun startAudioCapture() {
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
                        var lowSum = 0f
                        var medSum = 0f
                        var highSum = 0f
                        val n = fft.size / 2
                        for (i in 1 until n) {
                            val mag = hypot(
                                fft[i * 2].toFloat(), fft[i * 2 + 1].toFloat()
                            )
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
                    }
                },
                Visualizer.getMaxCaptureRate() / 2, false, true
            )
            v.enabled = true
            visualizer = v
        } catch (_: Exception) {
        }
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
        scheduleSessionSync()
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
        System.arraycopy(targets, 0, targetGainsMb, 0, bandCount)
        var maxBoostDb = 0f
        for (mb in targetGainsMb) {
            val db = mb / 100f * 1.75f
            if (db > maxBoostDb) maxBoostDb = db
        }
        targetLimiterPreGainDb = -0.45f * maxBoostDb
        if (immediate) {
            System.arraycopy(targetGainsMb, 0, currentGainsMb, 0, bandCount)
            currentLimiterPreGainDb = targetLimiterPreGainDb
            applyHardwareState()
            return
        }
        if (!isMorphing) {
            isMorphing = true
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
            morphHandler.removeCallbacks(morphRunnable)
            dspThread.quitSafely()
            visualizer?.release()
            dynamicsProcessing?.release()
            bassBoost?.release()
            virtualizer?.release()
            sessionDps?.values?.forEach { it.release() }
        } catch (_: Exception) {
        }
    }
}
