package jp.hagemi.android
import org.junit.Assert.*
import org.junit.Test
class AutoCaptureGateTest {
    private val center = AutoCaptureGate.Target(.5, .5, .1)
    @Test fun waitsForStability() {
        val gate = AutoCaptureGate()
        for (t in 0L..800L step 200) assertFalse(gate.update(center, t))
        assertTrue(gate.update(center, 1000))
    }
    @Test fun lossResetsCountdown() {
        val gate = AutoCaptureGate()
        for (t in 0L..1600L step 200) gate.update(center, t)
        assertFalse(gate.update(null, 1800))
        assertFalse(gate.update(center, 2000))
    }
    @Test fun staleFramesResetCountdown() {
        val gate = AutoCaptureGate()
        gate.update(center, 0)
        assertFalse(gate.update(center, 2000))
    }
    @Test fun movementAndSizeChangesResetCountdown() {
        for (target in listOf(center.copy(x = .66), center.copy(area = .3), center.copy(x = .8))) {
            val gate = AutoCaptureGate()
            for (t in 0L..1600L step 200) gate.update(center, t)
            assertFalse(gate.update(target, 1800))
        }
    }
    @Test fun toleratesScalpBoxJitter() {
        val gate = AutoCaptureGate()
        for (t in 0L..800L step 200) {
            val target = center.copy(x = if (t % 400 == 0L) .53 else .57, area = if (t % 400 == 0L) .10 else .14)
            assertFalse(gate.update(target, t))
        }
        assertTrue(gate.update(center.copy(x = .54, area = .12), 1000))
    }
    @Test fun acceptsWiderCenterGuide() {
        val gate = AutoCaptureGate()
        val target = center.copy(x = .65)
        for (t in 0L..800L step 200) gate.update(target, t)
        assertTrue(gate.update(target, 1000))
    }
    @Test fun soundGetsFasterNearCenter() { assertTrue(AutoCaptureGate.interval(.01) < AutoCaptureGate.interval(.4)) }
}
