package jp.hagemi.android

/** Bright, enclosed regions are candidates, not a diagnosis or a measurement. */
class ContrastDetector {
    data class Candidate(val left: Int, val top: Int, val right: Int, val bottom: Int)

    fun detect(pixels: IntArray, width: Int, height: Int): Candidate? {
        require(width > 0 && height > 0 && pixels.size == width * height)
        val histogram = IntArray(256)
        pixels.forEach { histogram[it.coerceIn(0, 255)]++ }
        val total = pixels.size
        val sum = pixels.sum().toDouble()
        var count = 0
        var partial = 0.0
        var bestVariance = 0.0
        var threshold = 0
        for (level in 0..254) {
            count += histogram[level]
            partial += level * histogram[level]
            if (count == 0 || count == total) continue
            val difference = partial / count - (sum - partial) / (total - count)
            val variance = count.toDouble() * (total - count) * difference * difference
            if (variance > bestVariance) { bestVariance = variance; threshold = level }
        }
        if (bestVariance == 0.0) return null
        val darkCount = histogram.take(threshold + 1).sum()
        val darkMean = (0..threshold).sumOf { it.toDouble() * histogram[it] } / darkCount
        val brightMean = (sum - darkMean * darkCount) / (total - darkCount)
        if (brightMean - darkMean < 24) return null
        // Move the split into the gap so sensor noise in dark hair is not included.
        threshold = ((darkMean + brightMean) / 2).toInt()
        val visited = BooleanArray(total)
        val queue = IntArray(total)
        var best: Candidate? = null
        var bestScore = 0.0
        for (start in pixels.indices) {
            if (visited[start] || pixels[start] <= threshold) continue
            var head = 0
            var tail = 1
            queue[0] = start; visited[start] = true
            var left = width; var top = height; var right = 0; var bottom = 0
            while (head < tail) {
                val point = queue[head++]
                val x = point % width; val y = point / width
                left = minOf(left, x); right = maxOf(right, x)
                top = minOf(top, y); bottom = maxOf(bottom, y)
                fun add(next: Int) {
                    if (!visited[next] && pixels[next] > threshold) {
                        visited[next] = true; queue[tail++] = next
                    }
                }
                if (x > 0) add(point - 1)
                if (x + 1 < width) add(point + 1)
                if (y > 0) add(point - width)
                if (y + 1 < height) add(point + width)
            }
            val area = (right - left + 1) * (bottom - top + 1)
            if (left < 3 || top < 3 || right >= width - 3 || bottom >= height - 3) continue
            if (tail < total * 0.008 || tail > total * 0.45 || tail.toDouble() / area < 0.35) continue
            val aspect = (right - left + 1).toDouble() / (bottom - top + 1)
            if (aspect !in 0.3..3.3) continue
            var ring = 0; var dark = 0
            for (y in maxOf(0, top - 4)..minOf(height - 1, bottom + 4)) {
                for (x in maxOf(0, left - 4)..minOf(width - 1, right + 4)) {
                    if (x in left..right && y in top..bottom) continue
                    ring++
                    if (pixels[y * width + x] <= threshold) dark++
                }
            }
            val enclosure = dark.toDouble() / ring.coerceAtLeast(1)
            if (enclosure < 0.65) continue
            val score = tail * enclosure
            if (score > bestScore) {
                bestScore = score; best = Candidate(left, top, right + 1, bottom + 1)
            }
        }
        return best
    }
}
