package jp.hagemi.android
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test
class BoundarySpacingTest {
    private val bald = Region(.3, .3, .7, .7)
    private fun ring() = (0 until 32).map { i -> val a = i * 2 * PI / 32; RootCandidates.Point(.5 + .2 * cos(a), .5 + .2 * sin(a)) }
    @Test fun findsRingSpacing() {
        val result = BoundarySpacing.analyze(ring(), bald, 500, 500)
        assertNotNull(result.spacingPx)
        assertEquals(2 * 100 * sin(PI / 32), result.spacingPx!!, .01)
    }
    @Test fun imageScalingPreservesAmount() {
        val first = BoundarySpacing.analyze(ring(), bald, 500, 500).estimate()!!
        val second = BoundarySpacing.analyze(ring(), bald, 1000, 1000).estimate()!!
        assertEquals(first.amount(bald), second.amount(bald), 1e-8)
    }
    @Test fun excludesInteriorAndFarOutsidePoints() {
        val result = BoundarySpacing.analyze(ring() + listOf(RootCandidates.Point(.5, .5), RootCandidates.Point(.05, .05)), bald, 500, 500)
        assertEquals(32, result.points.size)
    }
    @Test fun sparsePointsDoNotProduceEstimate() { assertNull(BoundarySpacing.analyze(ring().take(5), bald, 500, 500).estimate()) }
    @Test fun oneSidedPointsDoNotProduceEstimate() { assertNull(BoundarySpacing.analyze((0 until 16).map { val a = it * .04; RootCandidates.Point(.5 + .2 * cos(a), .5 + .2 * sin(a)) }, bald, 500, 500).estimate()) }
    @Test fun triangularFormulaCountsVerticesRatherThanFaces() {
        val mesh = MeshEstimate(.02, 20)
        assertEquals(2 * AreaEstimate.fraction(bald) / (sqrt(3.0) * .0004), mesh.amount(bald), 1e-9)
    }
}
