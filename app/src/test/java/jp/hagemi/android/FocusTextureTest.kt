package jp.hagemi.android

import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.*

class FocusTextureTest {
    private val w=640;private val h=480
    private fun measure(image:IntArray)=FocusTexture.measure(w,h,FocusTexture.Point(.5f,.5f)){x,y->image[y*w+x]}
    private fun stripes()=IntArray(w*h){i->if((i%w)%12<3)35 else 185}
    private fun blur(input:IntArray):IntArray {
        var image=input
        repeat(3) {
            val horizontal=IntArray(image.size){i->val x=i%w;val y=i/w;(-4..4).sumOf{image[y*w+(x+it).coerceIn(0,w-1)]}/9}
            image=IntArray(image.size){i->val x=i%w;val y=i/w;(-4..4).sumOf{horizontal[(y+it).coerceIn(0,h-1)*w+x]}/9}
        }
        return image
    }
    @Test fun sharpShaftsCarryMoreDetailThanTheirBlurredVersion() {
        val source=stripes();val sharp=measure(source);val soft=measure(blur(source))
        assertTrue("sharp=$sharp",sharp.clear)
        assertFalse("soft=$soft",soft.acceptable)
        assertTrue(sharp.score>soft.score)
    }
    @Test fun uniformBrightSkinDoesNotProveFocus() {
        assertFalse(measure(IntArray(w*h){190}).acceptable)
    }
    @Test fun sensorGrainOnAFlatSurfaceDoesNotProveFocus() {
        val random=Random(42)
        assertFalse(measure(IntArray(w*h){150+random.nextInt(25)-12}).acceptable)
    }
    @Test fun choosesTexturedHairBoundaryAndADifferentRetryLocation() {
        val cx=320f;val cy=240f;val radius=80f
        val image=IntArray(w*h){i->val x=i%w;val y=i/w
            if(hypot(x-cx,y-cy)<radius)185 else if(x<280&&((x+y)%9<4))185 else 35}
        val region=Region(240.0/w,160.0/h,400.0/w,320.0/h)
        val found=FocusTexture.boundaryOptions(w,h,region){x,y->image[y*w+x]}
        assertEquals(2,found.size)
        assertTrue("first=${found.first()}",found.first().point.x<.5f)
        assertTrue(found.first().detail.clear)
        assertTrue(hypot((found[0].point.x-found[1].point.x)/.125f,(found[0].point.y-found[1].point.y)/(80f/h))>=1f)
        assertTrue(found.all{hypot((it.point.x-.5f)/.125f,(it.point.y-.5f)/(80f/h))>=.8f})
    }
    @Test fun imageEdgesAndSmallFramesRemainWithinBounds() {
        val detail=FocusTexture.measure(5,5,FocusTexture.Point(0f,1f)){x,y->assertTrue(x in 0..4&&y in 0..4);180}
        assertFalse(detail.acceptable)
    }
}
