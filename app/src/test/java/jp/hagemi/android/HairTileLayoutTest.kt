package jp.hagemi.android

import org.junit.Assert.*
import org.junit.Test

class HairTileLayoutTest {
    @Test fun biggerNativeImagesKeepAnalysisBuffersBounded() {
        for(edge in listOf(750,1500,3000,4032,6000,8192,16384)) {
            val layout=HairPhotoAnalysis.tileLayout(edge,edge*3/4)
            assertTrue(layout.coreEdge>0)
            assertTrue(layout.coreEdge+2*layout.halo<=1000)
        }
        assertTrue(HairPhotoAnalysis.tileLayout(4032,3024).scale>1f)
        assertEquals(2f,HairPhotoAnalysis.tileLayout(8192,6144).scale,0f)
    }
}
