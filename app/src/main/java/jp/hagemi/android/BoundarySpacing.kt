package jp.hagemi.android

import kotlin.math.*

data class MeshEstimate(val spacing: Double, val points: Int) {
    val isValid get() = spacing.isFinite() && spacing > 0 && spacing <= 1 && points >= 12
    fun amount(bald: Region): Double { require(isValid); return 2 * AreaEstimate.fraction(bald) / (sqrt(3.0) * spacing * spacing) }
}

object BoundarySpacing {
    data class Result(val points: List<RootCandidates.Point>, val distances: List<Double>, val bins: List<Int>,
                      val binWidth: Double, val spacingPx: Double?, val reason: String?, val width: Int, val height: Int) {
        fun estimate(): MeshEstimate? = spacingPx?.let { MeshEstimate(it / sqrt(width.toDouble() * height), points.size) }
    }
    fun analyze(points: List<RootCandidates.Point>, bald: Region, width: Int, height: Int): Result {
        require(bald.isValid && width > 0 && height > 0)
        val cx = (bald.left + bald.right) / 2; val cy = (bald.top + bald.bottom) / 2
        val rx = (bald.right - bald.left) / 2; val ry = (bald.bottom - bald.top) / 2
        val selected = mutableListOf<RootCandidates.Point>()
        val sectors = mutableSetOf<Int>()
        for (p in points.take(500)) {
            val x = (p.x - cx) / rx; val y = (p.y - cy) / ry
            if (hypot(x, y) !in .80..1.45) continue
            if (selected.any { hypot((p.x - it.x) * width, (p.y - it.y) * height) < 2.5 }) continue
            selected.add(p)
            sectors.add(((atan2(y, x) + PI) / (2 * PI) * 8).toInt().coerceIn(0, 7))
        }
        val distances = selected.map { p -> selected.asSequence().filter { it !== p }
            .map { hypot((it.x - p.x) * width, (it.y - p.y) * height) }.minOrNull() ?: 0.0 }.filter { it >= 2.5 }.sorted()
        val median = distances.getOrNull(distances.size / 2) ?: 0.0
        val step = max(1.0, median * .20)
        val bins = IntArray(16)
        distances.forEach { bins[(it / step).toInt().coerceIn(0, 15)]++ }
        val mode = (0..14).maxByOrNull { bins[it] } ?: 0
        val modal = distances.filter { (it / step).toInt() == mode }
        var reason: String? = when {
            points.size >= 500 -> "候補数が上限です。誤検出や比較範囲を確認してください"
            selected.size < 12 -> "境界帯の候補が不足しています（12個以上が必要）"
            sectors.size < 3 -> "候補が境界の一部に偏っています。広く検出できる写真で試してください"
            modal.size < max(4, distances.size / 5) -> "距離分布の山が不明瞭です"
            else -> null
        }
        if (distances.size >= 4 && distances[distances.size * 3 / 4] / distances[distances.size / 4].coerceAtLeast(.001) > 2.5)
            reason = "間隔のばらつきが大きいため換算を見送ります"
        val spacing = if (reason == null) modal[modal.size / 2] else null
        return Result(selected, distances, bins.toList(), step, spacing, reason, width, height)
    }
}
