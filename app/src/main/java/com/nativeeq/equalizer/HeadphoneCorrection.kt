package com.nativeeq.equalizer

import android.content.res.AssetManager
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AutoEq (github.com/jaakkopasanen/AutoEq, MIT) headphone corrections, baked by
 * tools/build_autoeq.py into per-band dB gains for the DynamicsProcessing
 * postEq stage. Curves are normalized so no band boosts above 0 dB.
 */
class AutoEqCatalog private constructor(
    val bandEdgesHz: FloatArray,
    private val names: Array<String>,
    private val types: ByteArray,
    private val gains: ByteArray,
) {
    class Entry(val name: String, val type: String, val gainsDb: FloatArray)
    class Match(val entry: Entry, val score: Float)

    val bands: Int get() = bandEdgesHz.size - 1
    val size: Int get() = names.size

    private val tokenCache: Array<Set<String>> = Array(names.size) { tokens(names[it]) }
    private val modelKeys: Array<String> = Array(names.size) { modelKey(names[it]) }

    fun entry(i: Int): Entry = Entry(
        names[i],
        when (types[i].toInt()) { 1 -> "in-ear"; 2 -> "earbud"; else -> "over-ear" },
        FloatArray(bands) { gains[i * bands + it] / 4f },
    )

    fun find(exactName: String): Entry? {
        val i = names.indexOfFirst { it.equals(exactName, ignoreCase = true) }
        return if (i >= 0) entry(i) else null
    }

    /**
     * Fraction of the catalog name's tokens present in [query], minus 0.1 per
     * unmatched query token ("Buds Pro 2" must not settle for "Buds"), plus a bonus
     * when the brand-less model string appears verbatim (BT names usually drop
     * the brand: "WH-1000XM4" -> "Sony WH-1000XM4"). Shorter names win ties so
     * the base model beats "(ANC off)"-style variants.
     */
    fun search(query: String, limit: Int = 5): List<Match> {
        val q = tokens(query)
        if (q.isEmpty()) return emptyList()
        val qCompact = compact(query)
        val scored = ArrayList<Pair<Int, Float>>()
        for (i in names.indices) {
            val c = tokenCache[i]
            if (c.isEmpty()) continue
            var hit = 0
            for (t in c) if (t in q) hit++
            if (hit == 0) continue
            var score = hit.toFloat() / c.size - (q.size - hit) * 0.1f
            val mk = modelKeys[i]
            if (mk.length >= 4 && qCompact.contains(mk)) score += 0.3f
            score -= names[i].length * 0.001f
            scored.add(i to score)
        }
        scored.sortByDescending { it.second }
        return scored.take(limit).map { Match(entry(it.first), it.second) }
    }

    fun bestMatch(deviceName: String, minScore: Float = AUTO_MATCH_MIN_SCORE): Match? =
        search(deviceName, 1).firstOrNull()?.takeIf { it.score >= minScore }

    companion object {
        const val ASSET = "autoeq_posteq.bin"
        const val AUTO_MATCH_MIN_SCORE = 0.6f

        fun load(assets: AssetManager): AutoEqCatalog? = try {
            val bytes = assets.open(ASSET).use { it.readBytes() }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { buf.get(it) }
            require(String(magic, Charsets.US_ASCII) == "AEQ1")
            val count = buf.short.toInt() and 0xffff
            val bands = buf.get().toInt() and 0xff
            val edges = FloatArray(bands + 1) { buf.float }
            val names = Array(count) { "" }
            val types = ByteArray(count)
            val gains = ByteArray(count * bands)
            for (i in 0 until count) {
                val len = buf.get().toInt() and 0xff
                types[i] = buf.get()
                val nb = ByteArray(len).also { buf.get(it) }
                names[i] = String(nb, Charsets.UTF_8)
                buf.get(gains, i * bands, bands)
            }
            AutoEqCatalog(edges, names, types, gains)
        } catch (_: Exception) {
            null
        }

        private fun tokens(s: String): Set<String> =
            s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()

        private fun compact(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]"), "")

        private fun modelKey(name: String): String {
            val base = name.substringBefore('(').trim()
            val sp = base.indexOf(' ')
            return compact(if (sp > 0) base.substring(sp + 1) else base)
        }
    }
}
