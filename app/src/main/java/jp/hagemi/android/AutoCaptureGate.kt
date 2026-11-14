package jp.hagemi.android

import kotlin.math.abs
import kotlin.math.hypot

/** Uses normalized preview coordinates and a monotonic clock. */
class AutoCaptureGate {
    data class Target(val x: Double, val y: Double, val area: Double)
    private var previous: Target? = null
    private var since = 0L
    private var last = 0L
    fun reset() { previous = null; since = 0; last = 0 }
    fun update(target: Target?, now: Long): Boolean {
        if (target == null || hypot(target.x - .5, target.y - .5) > .10) { reset(); return false }
        val old = previous
        if (old == null || now - last > 500 || hypot(target.x - old.x, target.y - old.y) > .025 ||
            abs(target.area - old.area) / old.area.coerceAtLeast(.0001) > .20) since = now
        previous = target; last = now
        return now - since >= 1600
    }
    companion object {
        fun interval(distance: Double): Long = (200 + 1600 * distance.coerceIn(0.0, .5)).toLong()
    }
}
