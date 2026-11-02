package com.nativeeq.equalizer

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TuningLogbook(private val context: Context) {
    private val logFile = File(context.filesDir, "tuning_logbook.txt")
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun recordEntry(route: String, presetName: String, gainsMb: ShortArray, reason: String) {
        try {
            val stamp = fmt.format(Date())
            val dbs = gainsMb.joinToString("/") { "%+.1fdB".format(Locale.US, it / 100f) }
            val entry = "[$stamp] ROUTE:$route | $presetName ($dbs)\n  -> NOTE: $reason\n"
            logFile.appendText(entry)
            val lines = logFile.readLines()
            if (lines.size > 80) {
                logFile.writeText(lines.takeLast(80).joinToString("\n") + "\n")
            }
        } catch (_: Exception) {
        }
    }

    fun readRecentEntries(maxLines: Int = 14): String = try {
        if (!logFile.exists()) {
            "LOGBOOK EMPTY: Switch a preset or run LITERT INFER to log tuning notes."
        } else {
            "=== SOUND TUNING LOGBOOK (60/230/910/3.6k/14k) ===\n" +
                logFile.readLines().takeLast(maxLines).joinToString("\n")
        }
    } catch (_: Exception) {
        "Logbook read error."
    }
}

/**
 * Turns tuning proposals into labeled tuning_events rows. A proposal starts
 * `pending`; once applied, holding it for [KEEP_WINDOW_MS] marks it `kept`.
 * A user gain change inside that window (or a newer proposal) marks it
 * `reverted` with the gains the user settled on. Main thread only.
 */
class TuningOutcomeTracker(
    private val db: TelemetryDbHelper,
    private val logbook: TuningLogbook,
    private val currentGains: () -> ShortArray,
    private val onResolved: () -> Unit,
) {
    companion object {
        const val KEEP_WINDOW_MS = 30_000L
        const val REVERT_SETTLE_MS = 2_000L

        fun tierForBackendTag(tag: String): String = when (tag.uppercase()) {
            "LITERT-LM" -> "litertlm"
            "GEMINI-NANO" -> "nano"
            "TFLITE-ADVISOR" -> "tflite"
            "HEURISTIC" -> "heuristic"
            else -> tag.lowercase()
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pendingId = -1L
    private var pendingRoute = ""
    private var pendingTier = ""
    private var applied = false
    private var reverting = false

    private val keepRunnable = Runnable {
        if (pendingId >= 0 && applied && !reverting) resolve(TelemetryDbHelper.OUTCOME_KEPT, currentGains())
    }
    private val revertRunnable = Runnable {
        if (pendingId >= 0) resolve(TelemetryDbHelper.OUTCOME_REVERTED, currentGains())
    }

    /** Records a proposal; any still-pending one is superseded (reverted -> these gains). */
    fun propose(
        route: String, tier: String, spectrum: FloatArray?, gainsMb: ShortArray, applyNow: Boolean,
    ): Long {
        if (pendingId >= 0) resolve(TelemetryDbHelper.OUTCOME_REVERTED, gainsMb)
        val floats = FloatArray(gainsMb.size) { gainsMb[it].toFloat() }
        val id = try {
            db.insertTuningEvent(
                route, tier, spectrum, floats, EqualizerService.limiterPreGainFor(floats)
            )
        } catch (_: Exception) {
            -1L
        }
        pendingId = id
        pendingRoute = route
        pendingTier = tier
        applied = false
        reverting = false
        if (applyNow) markApplied(id)
        onResolved()
        return id
    }

    /** The proposal's gains are now live in the DSP chain; start the keep window. */
    fun markApplied(id: Long) {
        if (id < 0 || id != pendingId) return
        applied = true
        reverting = false
        handler.removeCallbacks(revertRunnable)
        handler.removeCallbacks(keepRunnable)
        handler.postDelayed(keepRunnable, KEEP_WINDOW_MS)
    }

    /** A user-initiated gain change that is not itself a new proposal (e.g. mid-drag). */
    fun onUserGainChange() {
        if (pendingId < 0 || !applied) return
        reverting = true
        handler.removeCallbacks(keepRunnable)
        handler.removeCallbacks(revertRunnable)
        handler.postDelayed(revertRunnable, REVERT_SETTLE_MS)
    }

    private fun resolve(outcome: String, finalGains: ShortArray) {
        val id = pendingId
        pendingId = -1L
        handler.removeCallbacks(keepRunnable)
        handler.removeCallbacks(revertRunnable)
        if (id < 0) return
        val ok = try {
            db.resolveTuningEvent(id, outcome, FloatArray(finalGains.size) { finalGains[it].toFloat() })
        } catch (_: Exception) {
            false
        }
        if (ok) {
            logbook.recordEntry(
                pendingRoute, "Outcome: ${outcome.uppercase()} [$pendingTier]", finalGains,
                "tuning_events #$id" + if (applied) "" else " (never applied)"
            )
        }
        onResolved()
    }

    fun release() {
        handler.removeCallbacks(keepRunnable)
        handler.removeCallbacks(revertRunnable)
    }
}
