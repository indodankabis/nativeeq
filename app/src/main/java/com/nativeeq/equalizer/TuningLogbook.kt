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
 * `reverted` with the gains the user settled on. A/B bypass pauses the
 * window. The applied timestamp is persisted, so events left pending by an
 * app exit are reconciled on the next start. Gains are recorded on the same
 * 50mB grid the DSP plays. Main thread only.
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
    private var bypassed = false

    private val keepRunnable = Runnable {
        if (pendingId >= 0 && applied && !reverting && !bypassed) {
            resolve(TelemetryDbHelper.OUTCOME_KEPT, EqualizerService.quantizeGains(currentGains()))
        }
    }
    private val revertRunnable = Runnable {
        if (pendingId >= 0) {
            resolve(TelemetryDbHelper.OUTCOME_REVERTED, EqualizerService.quantizeGains(currentGains()))
        }
    }

    init {
        recoverPending()
    }

    /** Resolve events orphaned by a previous exit; adopt one still inside its keep window. */
    private fun recoverPending() {
        val pending = try {
            db.getPendingTuningEvents()
        } catch (_: Exception) {
            return
        }
        val now = System.currentTimeMillis()
        // Only the newest applied event can still have been live; older ones were superseded.
        val live = pending.lastOrNull { it.appliedAt != null }
        val adopt = live?.takeIf { now - it.appliedAt!! < KEEP_WINDOW_MS }
        for (ev in pending) {
            when {
                ev === adopt -> continue
                ev === live -> resolveRow(
                    ev.id, ev.route, ev.sourceTier, TelemetryDbHelper.OUTCOME_KEPT,
                    ev.proposedGainsMb, wasApplied = true
                )
                else -> resolveRow(
                    ev.id, ev.route, ev.sourceTier, TelemetryDbHelper.OUTCOME_REVERTED,
                    null, wasApplied = ev.appliedAt != null
                )
            }
        }
        if (adopt != null) {
            pendingId = adopt.id
            pendingRoute = adopt.route
            pendingTier = adopt.sourceTier
            applied = true
            handler.postDelayed(keepRunnable, KEEP_WINDOW_MS - (now - (adopt.appliedAt ?: now)))
        }
        onResolved()
    }

    fun isPending(id: Long): Boolean = id >= 0 && id == pendingId

    /** Records a proposal; any still-pending one is superseded (reverted -> these gains). */
    fun propose(
        route: String, tier: String, spectrum: FloatArray?, gainsMb: ShortArray, applyNow: Boolean,
    ): Long {
        val q = EqualizerService.quantizeGains(gainsMb)
        if (pendingId >= 0) resolve(TelemetryDbHelper.OUTCOME_REVERTED, q)
        val id = try {
            db.insertTuningEvent(route, tier, spectrum, q, EqualizerService.limiterPreGainFor(q))
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

    /** The proposal's gains are now live in the DSP chain; start (or restart) the keep window. */
    fun markApplied(id: Long) {
        if (!isPending(id)) return
        applied = true
        reverting = false
        try {
            db.markTuningEventApplied(id)
        } catch (_: Exception) {
        }
        handler.removeCallbacks(revertRunnable)
        handler.removeCallbacks(keepRunnable)
        if (!bypassed) handler.postDelayed(keepRunnable, KEEP_WINDOW_MS)
    }

    /** A user-initiated gain change that is not itself a new proposal (e.g. mid-drag). */
    fun onUserGainChange() {
        if (pendingId < 0 || !applied) return
        reverting = true
        handler.removeCallbacks(keepRunnable)
        handler.removeCallbacks(revertRunnable)
        handler.postDelayed(revertRunnable, REVERT_SETTLE_MS)
    }

    /** A/B bypass: listening to flat isn't acceptance. Pause, then a fresh window on re-engage. */
    fun onBypass(isBypassed: Boolean) {
        bypassed = isBypassed
        if (pendingId < 0 || !applied || reverting) return
        if (isBypassed) handler.removeCallbacks(keepRunnable) else markApplied(pendingId)
    }

    private fun resolve(outcome: String, finalGains: FloatArray) {
        val id = pendingId
        pendingId = -1L
        handler.removeCallbacks(keepRunnable)
        handler.removeCallbacks(revertRunnable)
        if (id < 0) return
        resolveRow(id, pendingRoute, pendingTier, outcome, finalGains, applied)
        onResolved()
    }

    private fun resolveRow(
        id: Long, route: String, tier: String, outcome: String,
        finalGains: FloatArray?, wasApplied: Boolean,
    ) {
        val ok = try {
            db.resolveTuningEvent(id, outcome, finalGains)
        } catch (_: Exception) {
            false
        }
        if (ok) {
            val logGains = finalGains?.let { f -> ShortArray(f.size) { f[it].toInt().toShort() } }
                ?: ShortArray(5)
            logbook.recordEntry(
                route, "Outcome: ${outcome.uppercase()} [$tier]", logGains,
                "tuning_events #$id" + if (wasApplied) "" else " (never applied)"
            )
        }
    }

    /** Exit: settle what can be settled now; an applied event in its window stays pending for recovery. */
    fun release() {
        if (pendingId >= 0 && (reverting || !applied)) {
            resolve(TelemetryDbHelper.OUTCOME_REVERTED, EqualizerService.quantizeGains(currentGains()))
        }
        handler.removeCallbacks(keepRunnable)
        handler.removeCallbacks(revertRunnable)
    }
}
