package com.kai.voicelock

object PhraseMatcher {

    fun normalize(s: String): String =
        s.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    fun matches(heard: String, phrase: String): Boolean {
        val h = normalize(heard)
        val p = normalize(phrase)
        if (p.isEmpty() || h.isEmpty()) return false
        if (h == p) return true

        val ht = h.split(" ").filter { it.isNotEmpty() }
        val pt = p.split(" ").filter { it.isNotEmpty() }

        if (pt.size <= ht.size) {
            for (i in 0..(ht.size - pt.size)) {
                val window = ht.subList(i, i + pt.size).joinToString(" ")
                if (window == p) return true
                if (similarity(window, p) >= 0.80) return true
            }
        }
        return similarity(h, p) >= 0.78
    }

    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val max = maxOf(a.length, b.length).toDouble()
        return 1.0 - levenshtein(a, b) / max
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev
            prev = cur
            cur = tmp
        }
        return prev[b.length]
    }
}
