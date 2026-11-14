package jp.hagemi.android
import org.junit.Assert.*
import org.junit.Test

class HairReviewCodecTest {
    private fun example()=HairReview(Region(.2,.2,.8,.8),HairVerdict.entries.mapIndexed{i,v->
        HairCandidate(HairPoint(.3f+i*.1f,.4f),listOf(HairPoint(.3f+i*.1f,.4f),HairPoint(.4f,.5f)),listOf("short"),v)
    },true)
    @Test fun roundTripPreservesAllUserDecisionsAndCoordinates() {
        val source=example();val actual=HairReviewCodec.decode(HairReviewCodec.encode(source))
        assertEquals(source,actual);assertEquals(1,actual.summary().confirmed);assertEquals(1,actual.summary().hold);assertEquals(1,actual.summary().rejected)
        assertTrue(actual.truncated)
    }
    @Test fun newCandidatesStayUnreviewed() {
        val c=HairCandidate(HairPoint(.5f,.5f),emptyList(),emptyList())
        assertEquals(HairVerdict.UNREVIEWED,c.verdict)
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsOutOfPhotoCoordinates() {
        HairReviewCodec.encode(HairReview(Region(.1,.1,.9,.9),listOf(HairCandidate(HairPoint(2f,.5f),emptyList(),emptyList()))))
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsNonFiniteCoordinates() {
        HairReviewCodec.encode(HairReview(Region(.1,.1,.9,.9),listOf(HairCandidate(HairPoint(Float.NaN,.5f),emptyList(),emptyList()))))
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsUnboundedCandidateArray() {
        HairReviewCodec.encode(HairReview(Region(.1,.1,.9,.9),List(401){HairCandidate(HairPoint(.5f,.5f),emptyList(),emptyList())}))
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsUnboundedPath() {
        HairReviewCodec.encode(HairReview(Region(.1,.1,.9,.9),listOf(HairCandidate(HairPoint(.5f,.5f),List(121){HairPoint(.5f,.5f)},emptyList()))))
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsUnknownSchemaInsteadOfReinterpretingData() {
        HairReviewCodec.decode(HairReviewCodec.encode(example()).replace("\"version\":1","\"version\":99"))
    }
}
