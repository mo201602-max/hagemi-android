package jp.hagemi.android

import android.graphics.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], shadows=[PixelRegionDecoder::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HighResolutionAnalysisTest {
    @Test fun nativePngReachesTheEndpointDetectorWithoutWholePhotoAnalysisBuffers() {
        val context=RuntimeEnvironment.getApplication()
        val source=File(context.cacheDir,"large-shaft.png");val output=File(context.cacheDir,"large-shaft-gray.png")
        val w=4032;val h=3024
        val image=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.rgb(180,180,180))}
        val canvas=Canvas(image);val paint=Paint().apply{color=Color.rgb(30,30,30)}
        canvas.drawRect(0f,0f,1000f,h.toFloat(),paint)
        canvas.drawRect(1910f,1495f,2090f,1505f,paint)
        source.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
        PhotoProcessor.saveGrayscale(source,output)
        PixelRegionDecoder.largestRegionPixels=0
        val review=HairPhotoAnalysis.analyze(output,Region(1900.0/w,1420.0/h,2100.0/w,1580.0/h),AtomicBoolean()){_,_->}
        assertTrue("Left shaft endpoint",review.candidates.any{hypot(it.point.x*w-1915f,it.point.y*h-1500f)<12})
        assertTrue("Right shaft endpoint",review.candidates.any{hypot(it.point.x*w-2085f,it.point.y*h-1500f)<12})
        assertTrue(review.candidates.all{it.verdict==HairVerdict.UNREVIEWED})
        assertTrue(PixelRegionDecoder.largestRegionPixels<=1_000_000)
    }
}
