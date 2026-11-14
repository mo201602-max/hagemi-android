package jp.hagemi.android

import kotlin.math.abs
import kotlin.math.hypot

/** Uses normalized preview coordinates and a monotonic clock. */
class AutoCaptureGate {
    data class Target(val x: Double, val y: Double, val area: Double)
    var status: String = "中央のガイドへ近づけてね"
        private set
    private var previous: Target? = null
    private var since = 0L
    private var last = 0L
    fun reset() { previous = null; since = 0; last = 0; status = "中央のガイドへ近づけてね" }
    fun update(target: Target?, now: Long): Boolean {
        if (target == null || hypot(target.x - .5, target.y - .5) > .18) { reset(); return false }
        val old = previous
        if (old == null || now - last > 500 || hypot(target.x - old.x, target.y - old.y) > .075 ||
            abs(target.area - old.area) / old.area.coerceAtLeast(.0001) > .60) since = now
        previous = target; last = now
        val remaining = (1000 - (now - since)).coerceAtLeast(0)
        status = if (remaining > 0) "位置の安定待ち…${(1000 - remaining) / 10}%" else "位置が安定しました"
        return remaining == 0L
    }
    companion object {
        fun interval(distance: Double): Long = (200 + 1600 * distance.coerceIn(0.0, .5)).toLong()
    }
}
