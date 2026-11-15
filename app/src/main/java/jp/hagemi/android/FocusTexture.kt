package jp.hagemi.android

import kotlin.math.*

/** Heuristic detail evidence from the low-resolution Y plane, not a high-resolution focus guarantee. */
object FocusTexture {
    data class Point(val x: Float, val y: Float)
    data class Detail(val contrast: Float, val laplacianRms: Float, val edgeFraction: Float) {
        val acceptable: Boolean get() = contrast >= 24f && laplacianRms >= 3f && edgeFraction >= .04f
        val clear: Boolean get() = contrast >= 28f && laplacianRms >= 5.5f && edgeFraction >= .06f
        val score: Float get() = if (contrast < 24f) 0f else laplacianRms * sqrt(edgeFraction) * min(contrast, 100f)
    }
    data class Option(val point: Point, val detail: Detail)

    fun measure(width: Int, height: Int, point: Point, pixel: (Int, Int) -> Int): Detail {
        require(width >= 5 && height >= 5 && point.x.isFinite() && point.y.isFinite())
        val radius = (14f * max(width, height) / 640).roundToInt().coerceIn(5, 16)
        val cx = (point.x * width).roundToInt().coerceIn(2, width - 3)
        val cy = (point.y * height).roundToInt().coerceIn(2, height - 3)
        val histogram = IntArray(256)
        val left = max(1, cx - radius); val right = min(width - 2, cx + radius)
        val top = max(1, cy - radius); val bottom = min(height - 2, cy + radius)
        val stride = right - left + 3
        // Suppress fine sensor grain before assessing edges. Noise alone must not prove focus.
        val smooth = IntArray(stride * (bottom - top + 3)) { i ->
            val x = left - 1 + i % stride; val y = top - 1 + i / stride
            var sum = 0
            for (dy in -1..1) for (dx in -1..1) sum += pixel((x + dx).coerceIn(0, width - 1), (y + dy).coerceIn(0, height - 1))
            (sum + 4) / 9
        }
        var n = 0; var lapSquare = 0.0; var edges = 0
        for (y in top..bottom) {
            for (x in left..right) {
                val i = (y - top + 1) * stride + x - left + 1
                val c = smooth[i]; val l = smooth[i - 1]; val r = smooth[i + 1]
                val t = smooth[i - stride]; val b = smooth[i + stride]
                histogram[c.coerceIn(0, 255)]++
                val lap = l + r + t + b - 4 * c
                lapSquare += lap.toDouble() * lap
                if (hypot((r - l) / 2f, (b - t) / 2f) >= 8f) edges++
                n++
            }
        }
        fun percentile(f: Float): Int { var count = 0; for (i in histogram.indices) { count += histogram[i]; if (count >= n * f) return i }; return 255 }
        return Detail((percentile(.9f) - percentile(.1f)).toFloat(), sqrt(lapSquare / n).toFloat(), edges.toFloat() / n)
    }

    /** Search the scalp/hair boundary rather than the featureless center of the patch. */
    fun boundaryOptions(width: Int, height: Int, region: Region?, pixel: (Int, Int) -> Int): List<Option> {
        val box = region?.takeIf { it.isValid } ?: Region(.25, .25, .75, .75)
        val cx = ((box.left + box.right) / 2).toFloat(); val cy = ((box.top + box.bottom) / 2).toFloat()
        val rx = ((box.right - box.left) / 2).toFloat(); val ry = ((box.bottom - box.top) / 2).toFloat()
        val all = ArrayList<Option>()
        for (i in 0 until 16) {
            val angle = i * PI / 8
            for (radius in floatArrayOf(.85f, 1f, 1.15f)) {
                val point = Point((cx + cos(angle).toFloat() * rx * radius).coerceIn(.03f, .97f),
                    (cy + sin(angle).toFloat() * ry * radius).coerceIn(.03f, .97f))
                all.add(Option(point, measure(width, height, point, pixel)))
            }
        }
        val sorted = all.sortedByDescending { it.detail.score }
        val first = sorted.first()
        // The retry uses a different part of the boundary, not a second region at another depth.
        val second = sorted.firstOrNull { hypot((it.point.x - first.point.x) / rx.coerceAtLeast(.01f),
            (it.point.y - first.point.y) / ry.coerceAtLeast(.01f)) >= 1f }
        return listOfNotNull(first, second)
    }
}
