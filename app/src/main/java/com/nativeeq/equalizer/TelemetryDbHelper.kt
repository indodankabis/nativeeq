package com.nativeeq.equalizer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.Cursor
import android.database.sqlite.SQLiteOpenHelper
import android.util.JsonWriter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TelemetryDbHelper(context: Context) :
    SQLiteOpenHelper(context, "eq_telemetry.db", null, 17) {

    data class SessionSummary(
        val totalSamples: Int,
        val mudAlerts: Int,
        val harshAlerts: Int,
        val compressionAlerts: Int,
        val avgLow: Float,
        val avgMid: Float,
        val avgHigh: Float,
        val dominantRoute: String,
    )

    /** One labeled (spectral context, correction, accepted?) row from tuning_events. */
    class TrainingTuple(
        val id: Long,
        val timestamp: Long,
        val route: String,
        val sourceTier: String,
        val spectrum: FloatArray,
        val proposedGainsMb: FloatArray,
        val limiterPregainDb: Float,
        val outcome: String,
        val finalGainsMb: FloatArray?,
    ) {
        val kept: Boolean get() = outcome == OUTCOME_KEPT
    }

    data class TuningEventCounts(val pending: Long, val kept: Long, val reverted: Long) {
        val total: Long get() = pending + kept + reverted
    }

    companion object {
        const val OUTCOME_PENDING = "pending"
        const val OUTCOME_KEPT = "kept"
        const val OUTCOME_REVERTED = "reverted"

        fun packSpectrum(spectrum: FloatArray?): ByteArray? {
            if (spectrum == null) return null
            val buf = ByteBuffer.allocate(spectrum.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (v in spectrum) buf.putFloat(v)
            return buf.array()
        }

        fun unpackSpectrum(blob: ByteArray?): FloatArray {
            if (blob == null) return FloatArray(0)
            val buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(blob.size / 4) { buf.getFloat() }
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS snapshots (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            timestamp INTEGER, route TEXT,
            low REAL, med REAL, high REAL,
            g0 REAL, g1 REAL, g2 REAL, g3 REAL, g4 REAL)"""
        )
        createRouteTable(db)
        createTuningTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 16) {
            // Pre-v16 schemas came from the decompiled builds; reset them as before.
            db.execSQL("DROP TABLE IF EXISTS snapshots")
            db.execSQL("DROP TABLE IF EXISTS route_profiles")
            onCreate(db)
            return
        }
        if (oldVersion < 17) createTuningTable(db)
    }

    private fun createTuningTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS tuning_events (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            timestamp INTEGER, route TEXT, source_tier TEXT,
            spectrum BLOB,
            proposed_g0 REAL, proposed_g1 REAL, proposed_g2 REAL,
            proposed_g3 REAL, proposed_g4 REAL,
            limiter_pregain REAL,
            outcome TEXT, resolved_at INTEGER,
            final_g0 REAL, final_g1 REAL, final_g2 REAL, final_g3 REAL, final_g4 REAL)"""
        )
    }

    private fun createRouteTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS route_profiles (
            route_key TEXT PRIMARY KEY,
            b0_mb REAL, b1_mb REAL, b2_mb REAL, b3_mb REAL, b4_mb REAL,
            limiter_pregain REAL, last_used INTEGER)"""
        )
    }

    fun insertSnapshot(route: String, low: Float, med: Float, high: Float, gains: ShortArray) {
        val cv = ContentValues().apply {
            put("timestamp", System.currentTimeMillis())
            put("route", route)
            put("low", low)
            put("med", med)
            put("high", high)
            for (i in 0..4) put("g$i", gains.getOrNull(i)?.toFloat() ?: 0f)
        }
        writableDatabase.insert("snapshots", null, cv)
    }

    fun getTotalRowCount(): Long =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM snapshots", null).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }

    fun clearTelemetry() {
        writableDatabase.execSQL("DELETE FROM snapshots")
    }

    fun generateAsciiHeatmap(): String {
        val sb = StringBuilder("[--- TEMPORAL SPECTRAL HEATMAP ---]\n")
        readableDatabase.rawQuery(
            "SELECT low, med, high FROM snapshots ORDER BY id DESC LIMIT 10", null
        ).use {
            while (it.moveToNext()) {
                val l = (it.getFloat(0) * 10).toInt().coerceIn(0, 10)
                val m = (it.getFloat(1) * 10).toInt().coerceIn(0, 10)
                val h = (it.getFloat(2) * 10).toInt().coerceIn(0, 10)
                sb.append("L:").append("#".repeat(l)).append(" ".repeat(10 - l))
                    .append(" | M:").append("#".repeat(m)).append(" ".repeat(10 - m))
                    .append(" | H:").append("#".repeat(h)).append("\n")
            }
        }
        return if (sb.length <= 35) "[HEATMAP: Insufficient data buffer]" else sb.toString()
    }

    fun getRecentAnalysis(windowMs: Long = 300_000L): SessionSummary {
        val cutoff = System.currentTimeMillis() - windowMs
        readableDatabase.rawQuery(
            "SELECT route, low, med, high FROM snapshots WHERE timestamp >= ? ORDER BY id DESC",
            arrayOf(cutoff.toString())
        ).use {
            var count = 0
            var mud = 0
            var harsh = 0
            var compression = 0
            var sumLow = 0f
            var sumMid = 0f
            var sumHigh = 0f
            var lastRoute = "dev_internal"
            while (it.moveToNext()) {
                if (count == 0) lastRoute = it.getString(0) ?: "dev_internal"
                val low = it.getFloat(1)
                val med = it.getFloat(2)
                val high = it.getFloat(3)
                if (low > 0.58f) mud++
                if (high > 0.4f) harsh++
                if (low > 0.7f) compression++
                sumLow += low; sumMid += med; sumHigh += high
                count++
            }
            return if (count > 0) {
                SessionSummary(
                    count, mud, harsh, compression,
                    sumLow / count, sumMid / count, sumHigh / count, lastRoute
                )
            } else {
                SessionSummary(0, 0, 0, 0, 0f, 0f, 0f, "dev_internal")
            }
        }
    }

    fun saveRouteProfile(routeKey: String, gainsMb: FloatArray, limiterPregain: Float) {
        val cv = ContentValues().apply {
            put("route_key", routeKey)
            for (i in 0..4) put("b${i}_mb", gainsMb[i])
            put("limiter_pregain", limiterPregain)
            put("last_used", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "route_profiles", null, cv, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun getRouteProfile(routeKey: String): FloatArray? =
        readableDatabase.rawQuery(
            "SELECT b0_mb, b1_mb, b2_mb, b3_mb, b4_mb FROM route_profiles WHERE route_key = ?",
            arrayOf(routeKey)
        ).use {
            if (it.moveToFirst()) FloatArray(5) { i -> it.getFloat(i) } else null
        }

    fun insertTuningEvent(
        route: String,
        sourceTier: String,
        spectrum: FloatArray?,
        proposedGainsMb: FloatArray,
        limiterPregainDb: Float,
    ): Long {
        val cv = ContentValues().apply {
            put("timestamp", System.currentTimeMillis())
            put("route", route)
            put("source_tier", sourceTier)
            put("spectrum", packSpectrum(spectrum))
            for (i in 0..4) put("proposed_g$i", proposedGainsMb.getOrElse(i) { 0f })
            put("limiter_pregain", limiterPregainDb)
            put("outcome", OUTCOME_PENDING)
        }
        return writableDatabase.insert("tuning_events", null, cv)
    }

    /** Resolves a still-pending event; returns false if it was already resolved. */
    fun resolveTuningEvent(id: Long, outcome: String, finalGainsMb: FloatArray? = null): Boolean {
        val cv = ContentValues().apply {
            put("outcome", outcome)
            put("resolved_at", System.currentTimeMillis())
            if (finalGainsMb != null) {
                for (i in 0..4) put("final_g$i", finalGainsMb.getOrElse(i) { 0f })
            }
        }
        return writableDatabase.update(
            "tuning_events", cv, "id = ? AND outcome = ?",
            arrayOf(id.toString(), OUTCOME_PENDING)
        ) > 0
    }

    fun getTuningEventCounts(): TuningEventCounts {
        var pending = 0L
        var kept = 0L
        var reverted = 0L
        readableDatabase.rawQuery(
            "SELECT outcome, COUNT(*) FROM tuning_events GROUP BY outcome", null
        ).use {
            while (it.moveToNext()) {
                when (it.getString(0)) {
                    OUTCOME_PENDING -> pending = it.getLong(1)
                    OUTCOME_KEPT -> kept = it.getLong(1)
                    OUTCOME_REVERTED -> reverted = it.getLong(1)
                }
            }
        }
        return TuningEventCounts(pending, kept, reverted)
    }

    private fun queryResolvedTuples(limit: Int?): Cursor =
        readableDatabase.rawQuery(
            "SELECT id, timestamp, route, source_tier, spectrum, " +
                "proposed_g0, proposed_g1, proposed_g2, proposed_g3, proposed_g4, " +
                "limiter_pregain, outcome, " +
                "final_g0, final_g1, final_g2, final_g3, final_g4 " +
                "FROM tuning_events WHERE outcome != ? ORDER BY id DESC" +
                (if (limit != null) " LIMIT $limit" else ""),
            arrayOf(OUTCOME_PENDING)
        )

    private fun readTuple(c: Cursor): TrainingTuple = TrainingTuple(
        id = c.getLong(0),
        timestamp = c.getLong(1),
        route = c.getString(2) ?: "",
        sourceTier = c.getString(3) ?: "",
        spectrum = unpackSpectrum(if (c.isNull(4)) null else c.getBlob(4)),
        proposedGainsMb = FloatArray(5) { c.getFloat(5 + it) },
        limiterPregainDb = c.getFloat(10),
        outcome = c.getString(11) ?: "",
        finalGainsMb = if (c.isNull(12)) null else FloatArray(5) { c.getFloat(12 + it) },
    )

    /** Resolved (kept/reverted) rows, newest first. */
    fun getTrainingTuples(limit: Int): List<TrainingTuple> =
        queryResolvedTuples(limit).use { c ->
            val out = ArrayList<TrainingTuple>()
            while (c.moveToNext()) out.add(readTuple(c))
            out
        }

    /** Streams every resolved tuple to [file] as JSON; returns the tuple count. */
    fun exportTrainingJson(file: File): Int {
        var count = 0
        file.parentFile?.mkdirs()
        JsonWriter(file.bufferedWriter()).use { w ->
            w.setIndent(" ")
            w.beginObject()
            w.name("format").value("nativeeq.tuning_events.v1")
            w.name("exported_at").value(System.currentTimeMillis())
            w.name("eq_bands_hz").beginArray()
            for (hz in intArrayOf(60, 230, 910, 3600, 14000)) w.value(hz.toLong())
            w.endArray()
            w.name("spectrum_band_centers_hz").beginArray()
            for (b in 0 until EqualizerService.SPECTRUM_BANDS) {
                w.value(EqualizerService.spectrumBandCenterHz(b).toDouble())
            }
            w.endArray()
            w.name("tuples").beginArray()
            queryResolvedTuples(null).use { c ->
                while (c.moveToNext()) {
                    val t = readTuple(c)
                    w.beginObject()
                    w.name("id").value(t.id)
                    w.name("timestamp").value(t.timestamp)
                    w.name("route").value(t.route)
                    w.name("source_tier").value(t.sourceTier)
                    w.name("spectrum").beginArray()
                    for (v in t.spectrum) w.value(v.toDouble())
                    w.endArray()
                    w.name("proposed_mb").beginArray()
                    for (v in t.proposedGainsMb) w.value(v.toDouble())
                    w.endArray()
                    w.name("limiter_pregain_db").value(t.limiterPregainDb.toDouble())
                    w.name("outcome").value(t.outcome)
                    w.name("kept").value(t.kept)
                    w.name("final_mb")
                    val fin = t.finalGainsMb
                    if (fin == null) {
                        w.nullValue()
                    } else {
                        w.beginArray()
                        for (v in fin) w.value(v.toDouble())
                        w.endArray()
                    }
                    w.endObject()
                    count++
                }
            }
            w.endArray()
            w.endObject()
        }
        return count
    }
}
