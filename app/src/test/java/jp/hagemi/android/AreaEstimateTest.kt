package jp.hagemi.android
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
class AreaEstimateTest {
    private val bald = Region(.1, .1, .3, .3)
    private val reference = Region(.5, .5, .6, .6)
    @Test fun ellipseAreaAndDensity() {
        assertEquals(.01 * PI, AreaEstimate.fraction(bald), 1e-9)
        assertEquals(20 * PI, AreaEstimate.missingHairs(bald, reference, 20), 1e-8)
    }
    @Test fun matchingZoomLeavesCountUnchanged() {
        val zoomBald = Region(.0, .0, .4, .4)
        val zoomRef = Region(.5, .5, .7, .7)
        assertEquals(AreaEstimate.missingHairs(bald, reference, 20), AreaEstimate.missingHairs(zoomBald, zoomRef, 20), 1e-8)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsOverlap() { AreaEstimate.missingHairs(bald, bald, 20) }
    @Test(expected = IllegalArgumentException::class) fun rejectsZeroReferenceCount() { AreaEstimate.missingHairs(bald, reference, 0) }
    @Test fun rejectsInvalidGeometry() {
        assertFalse(Region(-.1, .1, .5, .5).isValid)
        assertFalse(Region(.5, .5, .1, .1).isValid)
        assertFalse(Region(Double.NaN, .1, .5, .5).isValid)
    }
}
