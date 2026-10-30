package com.nativeeq.equalizer

import android.content.Context
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
