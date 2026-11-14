package jp.hagemi.android
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class HairEndpointDetectorTest {
    private val w=256;private val h=256
    private fun image(kind:String):ByteArray=ByteArray(w*h){i->val x=i%w;val y=i/w
        (if(kind=="line"&&x in 65..190&&y in 124..132 || kind=="cross"&&(x in 55..200&&y in 124..132 || y in 55..200&&x in 124..132) || kind=="dot"&&x in 126..130&&y in 126..130)40 else 180).toByte()
    }
    @Test fun uniformSurfaceDoesNotCreateHairCandidates() {
        assertTrue(HairEndpointDetector.detect(HairEndpointDetector.Tile(image("blank"),w,h),1f,110f).isEmpty())
    }
    @Test fun tracesTwoEndsOfAnIsolatedShaft() {
        val found=HairEndpointDetector.detect(HairEndpointDetector.Tile(image("line"),w,h),1f,110f)
        assertEquals(2,found.size)
        assertTrue(found.all{it.path.size>20})
    }
    @Test fun crossingCenterIsNotAnEndpoint() {
        val found=HairEndpointDetector.detect(HairEndpointDetector.Tile(image("cross"),w,h),1f,110f)
        assertEquals(4,found.size)
        assertTrue(found.none{kotlin.math.hypot(it.x-128,it.y-128)<20})
    }
    @Test fun isolatedDotIsNotATracedShaft() {
        assertTrue(HairEndpointDetector.detect(HairEndpointDetector.Tile(image("dot"),w,h),1f,110f).isEmpty())
    }
    @Test fun darkSurfaceFailsVisibilitySupport() {
        assertEquals(0f,HairEndpointDetector.visibilitySupport(FloatArray(w*h){50f},w,h,128,128,110f,1f),.001f)
    }
    @Test fun brightSurfaceSupportsReviewButDoesNotProveARoot() {
        assertEquals(1f,HairEndpointDetector.visibilitySupport(FloatArray(w*h){180f},w,h,128,128,110f,1f),.001f)
    }
    @Test(expected=CancellationException::class) fun cancelledAnalysisStops() {
        HairEndpointDetector.detect(HairEndpointDetector.Tile(image("line"),w,h),1f,110f,{true})
    }
    @Test(expected=IllegalArgumentException::class) fun tileSizeIsBounded() {
        HairEndpointDetector.detect(HairEndpointDetector.Tile(ByteArray(1001),1001,1),1f,110f)
    }
}
