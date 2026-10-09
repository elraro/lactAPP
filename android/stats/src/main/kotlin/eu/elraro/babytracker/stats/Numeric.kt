package eu.elraro.babytracker.stats

import kotlin.math.floor
import kotlin.math.sqrt

// Integer arithmetic throughout, so that this engine and the Go engine agree exactly
// (docs/STATISTICS.md §2). The only floating-point computation is Spearman's rho, done
// with the same operations in the same order on both sides.

/** Mean of non-negative values, rounded half up. [xs] must not be empty. */
internal fun meanHalfUp(xs: List<Int>): Int {
    val sum = xs.sumOf { it.toLong() }
    val n = xs.size
    return ((2 * sum + n) / (2 * n)).toInt()
}

/**
 * The [pct]-th percentile (0–100) of [sorted], with linear interpolation between closest
 * ranks ("type 7"), rounded half up. [sorted] must not be empty.
 */
internal fun percentile(
    sorted: List<Int>,
    pct: Int,
): Int {
    val pos = (sorted.size - 1) * pct
    val lo = pos / PERCENT
    val frac = pos % PERCENT
    if (frac == 0) return sorted[lo]
    val diff = (sorted[lo + 1] - sorted[lo]).toLong()
    return sorted[lo] + ((2 * diff * frac + PERCENT) / (2 * PERCENT)).toInt()
}

internal fun median(xs: List<Int>): Int = percentile(xs.sorted(), MEDIAN)

/** Summary of [xs]; every statistic is null below [minSample]. */
internal fun distribution(
    xs: List<Int>,
    minSample: Int,
): Distribution {
    if (xs.size < minSample || xs.isEmpty()) return Distribution(sampleSize = xs.size)
    val s = xs.sorted()
    return Distribution(
        median = percentile(s, MEDIAN),
        p25 = percentile(s, QUARTILE_1),
        p75 = percentile(s, QUARTILE_3),
        min = s.first(),
        max = s.last(),
        mean = meanHalfUp(s),
        sampleSize = s.size,
    )
}

internal fun meanValue(
    xs: List<Int>,
    minSample: Int,
): Value = Value(if (xs.size >= minSample && xs.isNotEmpty()) meanHalfUp(xs) else null, xs.size)

/** 1-based ranks; tied values get the average of their positions. */
internal fun ranks(xs: List<Int>): DoubleArray {
    val order = xs.indices.sortedBy { xs[it] } // stable, like the Go engine
    val out = DoubleArray(xs.size)
    var i = 0
    while (i < order.size) {
        var j = i
        while (j + 1 < order.size && xs[order[j + 1]] == xs[order[i]]) j++
        val rank = (i + j).toDouble() / 2 + 1
        for (k in i..j) out[order[k]] = rank
        i = j + 1
    }
    return out
}

/**
 * Spearman's rank correlation times 1000, rounded half away from zero; null when either
 * variable is constant.
 */
internal fun spearmanX1000(
    xs: List<Int>,
    ys: List<Int>,
): Int? {
    val rx = ranks(xs)
    val ry = ranks(ys)
    val n = xs.size.toDouble()
    var sx = 0.0
    var sy = 0.0
    for (i in rx.indices) {
        sx += rx[i]
        sy += ry[i]
    }
    val mx = sx / n
    val my = sy / n
    var cov = 0.0
    var vx = 0.0
    var vy = 0.0
    for (i in rx.indices) {
        val dx = rx[i] - mx
        val dy = ry[i] - my
        cov += dx * dy
        vx += dx * dx
        vy += dy * dy
    }
    if (vx == 0.0 || vy == 0.0) return null
    val rho = cov / sqrt(vx * vy)
    return if (rho < 0) -floor(-rho * PER_MILLE + HALF).toInt() else floor(rho * PER_MILLE + HALF).toInt()
}

private const val PERCENT = 100
private const val MEDIAN = 50
private const val QUARTILE_1 = 25
private const val QUARTILE_3 = 75
private const val PER_MILLE = 1000
private const val HALF = 0.5
