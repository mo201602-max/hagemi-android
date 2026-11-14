package jp.hagemi.android
import org.junit.Assert.*
import org.junit.Test
class AutoCaptureGateTest {
    private val center = AutoCaptureGate.Target(.5, .5, .1)
    @Test fun waitsForStability() {
        val gate = AutoCaptureGate()
        for (t in 0L..1400L step 200) assertFalse(gate.update(center, t))
        assertTrue(gate.update(center, 1600))
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
        for (target in listOf(center.copy(x = .57), center.copy(area = .2), center.copy(x = .8))) {
            val gate = AutoCaptureGate()
            for (t in 0L..1600L step 200) gate.update(center, t)
            assertFalse(gate.update(target, 1800))
        }
    }
    @Test fun soundGetsFasterNearCenter() { assertTrue(AutoCaptureGate.interval(.01) < AutoCaptureGate.interval(.4)) }
}
