package com.nativeeq.equalizer

import android.os.SystemClock
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide hot-path counters for the perf HUD. Writers are lock-free
 * increments; [summary] diffs against the previous call to get rates.
 * Pair with Perfetto: the same paths emit android.os.Trace sections (eq.*).
 */
object PerfStats {
    val jniWrites = AtomicLong()
    val morphFrames = AtomicLong()
    val fftFrames = AtomicLong()
    val fftNanos = AtomicLong()
    val spectrumOut = AtomicLong()
    val playbackEvents = AtomicLong()
    val controlEvents = AtomicLong()
    val agentTicks = AtomicLong()

    @Volatile var lastAttachMicros = -1L
    @Volatile var sessionDps = 0
    @Volatile var controlSessions = 0
    @Volatile var captureOn = false

    private val counters = arrayOf(
        jniWrites, morphFrames, fftFrames, fftNanos, spectrumOut,
        playbackEvents, controlEvents, agentTicks,
    )
    private var prev = LongArray(counters.size)
    private var prevMs = 0L

    fun summary(): String {
        val now = SystemClock.uptimeMillis()
        val cur = LongArray(counters.size) { counters[it].get() }
        val dtSec = if (prevMs == 0L) 0f else (now - prevMs) / 1000f
        val d = LongArray(cur.size) { cur[it] - prev[it] }
        prev = cur
        prevMs = now
        if (dtSec <= 0f) return "PERF: sampling…"
        fun rate(i: Int) = d[i] / dtSec
        val fftMs = if (d[2] > 0) d[3] / d[2] / 1e6f else 0f
        val wakeupsPerMin = (d[1] + d[2] + d[7]) / dtSec * 60f
        val attach = if (lastAttachMicros < 0) "—" else "%.1fms".format(Locale.US, lastAttachMicros / 1000f)
        return String.format(
            Locale.US,
            "PERF jni %.0f/s · morph %.0f fps · fft %.0f/s @ %.3fms · spec %.1f/s\n" +
                "agent %.0f/s · wakeups %.0f/min · capture %s\n" +
                "sessions %d DP (%d via EQ broadcast) · events cb %d ctl %d · attach %s",
            rate(0), rate(1), rate(2), fftMs, rate(4),
            rate(7), wakeupsPerMin, if (captureOn) "on" else "off",
            sessionDps, controlSessions, cur[5], cur[6], attach,
        )
    }
}
