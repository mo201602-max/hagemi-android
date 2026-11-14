package jp.hagemi.android

import kotlin.math.*

/** Dark compact components on visible skin, not confirmed follicles. All buffers are bounded by caller resolution. */
object RootCandidates {
    data class Point(val x: Double, val y: Double)
    data class Result(val region: Region, val width: Int, val height: Int, val binary: BooleanArray,
                      val points: List<Point>, val visibleSkinFraction: Double)
    fun analyze(gray: IntArray, width: Int, height: Int, region: Region): Result {
        require(region.isValid && gray.size == width * height)
        val left = (region.left * width).toInt().coerceIn(0, width - 1)
        val top = (region.top * height).toInt().coerceIn(0, height - 1)
        val right = ceil(region.right * width).toInt().coerceIn(left + 1, width)
        val bottom = ceil(region.bottom * height).toInt().coerceIn(top + 1, height)
        val w = right - left; val h = bottom - top
        val patch = IntArray(w * h) { gray[(top + it / w) * width + left + it % w] }
        val integral = IntArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var sum = 0
            for (x in 0 until w) {
                sum += patch[y * w + x]
                integral[(y + 1) * (w + 1) + x + 1] = integral[y * (w + 1) + x + 1] + sum
            }
        }
        val mask = BooleanArray(w * h)
        var skin = 0
        for (y in 0 until h) for (x in 0 until w) {
            val l = max(0, x - 7); val r = min(w, x + 8)
            val t = max(0, y - 7); val b = min(h, y + 8)
            val mean = (integral[b * (w + 1) + r] - integral[t * (w + 1) + r] - integral[b * (w + 1) + l] + integral[t * (w + 1) + l]).toDouble() / ((r - l) * (b - t))
            val value = patch[y * w + x]
            if (value >= 95 && value >= mean - 8) skin++
            mask[y * w + x] = mean >= 75 && value < mean - 14
        }
        val seen = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        val points = mutableListOf<Point>()
        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            var head = 0; var tail = 1; queue[0] = start; seen[start] = true
            var minX = w; var maxX = 0; var minY = h; var maxY = 0
            var sumX = 0L; var sumY = 0L
            while (head < tail) {
                val index = queue[head++]; val x = index % w; val y = index / w
                minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
                sumX += x; sumY += y
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx; val ny = y + dy
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val next = ny * w + nx
                    if (mask[next] && !seen[next]) { seen[next] = true; queue[tail++] = next }
                }
            }
            val bw = maxX - minX + 1; val bh = maxY - minY + 1
            val aspect = max(bw, bh).toDouble() / min(bw, bh)
            if (tail in 2..80 && aspect <= 3.0 && tail.toDouble() / (bw * bh) >= .3 &&
                minX > 0 && minY > 0 && maxX < w - 1 && maxY < h - 1 && points.size < 500) {
                points.add(Point((left + sumX.toDouble() / tail + .5) / width, (top + sumY.toDouble() / tail + .5) / height))
            }
        }
        return Result(Region(left.toDouble() / width, top.toDouble() / height, right.toDouble() / width, bottom.toDouble() / height),
            w, h, mask, points, skin.toDouble() / patch.size)
    }

    fun proposeReference(gray: IntArray, width: Int, height: Int, bald: Region): Result? {
        val bw = ((bald.right - bald.left) * .4).coerceIn(.06, .16)
        val bh = ((bald.bottom - bald.top) * .4).coerceIn(.06, .16)
        val cx = (bald.left + bald.right) / 2; val cy = (bald.top + bald.bottom) / 2
        val xs = listOf(bald.left - bw - .01, cx - bw / 2, bald.right + .01)
        val ys = listOf(bald.top - bh - .01, cy - bh / 2, bald.bottom + .01)
        val regions = xs.flatMap { x -> ys.map { y -> Region(x, y, x + bw, y + bh) } }
        return regions.filter { it.isValid && !AreaEstimate.overlaps(it, bald) }
            .map { analyze(gray, width, height, it) }
            .filter { it.visibleSkinFraction >= .5 && it.points.size in 1..499 && !AreaEstimate.overlaps(it.region, bald) }
            .maxByOrNull { it.visibleSkinFraction }
    }
}
