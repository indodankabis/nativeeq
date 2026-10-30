package com.nativeeq.equalizer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class TelemetryDbHelper(context: Context) :
    SQLiteOpenHelper(context, "eq_telemetry.db", null, 16) {

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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS snapshots")
        db.execSQL("DROP TABLE IF EXISTS route_profiles")
        onCreate(db)
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
}
