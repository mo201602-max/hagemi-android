package jp.hagemi.android
import org.junit.Assert.*
import org.junit.Test
class RootCandidatesTest {
    private val region = Region(0.0, 0.0, 1.0, 1.0)
    @Test fun countsSeparatedDarkSpots() {
        val gray = IntArray(80 * 80) { 180 }
        for ((cx, cy) in listOf(20 to 20, 50 to 50, 20 to 50))
            for (y in cy..cy + 2) for (x in cx..cx + 2) gray[y * 80 + x] = 40
        val result = RootCandidates.analyze(gray, 80, 80, region)
        assertEquals(3, result.points.size)
        assertTrue(result.visibleSkinFraction > .9)
    }
    @Test fun uniformSkinHasNoCandidates() {
        assertTrue(RootCandidates.analyze(IntArray(6400) { 180 }, 80, 80, region).points.isEmpty())
    }
    @Test fun rejectsLongHairAndEdgeComponents() {
        val gray = IntArray(6400) { 180 }
        for (y in 10..60) gray[y * 80 + 20] = 20
        for (y in 0..3) for (x in 0..3) gray[y * 80 + x] = 20
        assertTrue(RootCandidates.analyze(gray, 80, 80, region).points.isEmpty())
    }
    @Test fun darkOccludedAreaIsUnsuitable() {
        val result = RootCandidates.analyze(IntArray(6400) { 30 }, 80, 80, region)
        assertEquals(0.0, result.visibleSkinFraction, .001)
        assertTrue(result.points.isEmpty())
    }
    @Test fun candidatePositionsReferToFullPhoto() {
        val gray = IntArray(10000) { 180 }
        for (y in 60..62) for (x in 60..62) gray[y * 100 + x] = 20
        val result = RootCandidates.analyze(gray, 100, 100, Region(.5, .5, .9, .9))
        assertEquals(1, result.points.size)
        assertEquals(.615, result.points.first().x, .001)
    }
    @Test fun touchingSpotsAreOneUnresolvedComponent() {
        val gray = IntArray(6400) { 180 }
        for (y in 30..32) for (x in 30..35) gray[y * 80 + x] = 20
        assertEquals(1, RootCandidates.analyze(gray, 80, 80, region).points.size)
    }
    @Test fun doesNotProposeDensityFromUniformOrCoveredSurroundings() {
        for (value in listOf(30, 180)) {
            assertNull(RootCandidates.proposeReference(IntArray(6400) { value }, 80, 80, Region(.4, .4, .6, .6)))
        }
    }
}
