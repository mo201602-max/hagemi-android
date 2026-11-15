package jp.hagemi.android

import android.graphics.*
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], shadows=[PixelRegionDecoder::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoProcessorTest {
    @Test fun bandsStayGrayscaleAndAllExifOrientationsAreUpright() {
        val context=RuntimeEnvironment.getApplication()
        val w=173;val h=205
        for(orientation in 1..8) {
            val source=File(context.cacheDir,"source-$orientation.jpg");val output=File(context.cacheDir,"gray-$orientation.png")
            val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
            for(y in 0 until h)for(x in 0 until w)bitmap.setPixel(x,y,when {
                x<w/2&&y<h/2->Color.RED
                x>=w/2&&y<h/2->Color.GREEN
                x<w/2->Color.BLUE
                else->Color.WHITE
            })
            source.outputStream().use{bitmap.compress(Bitmap.CompressFormat.JPEG,100,it)};bitmap.recycle()
            ExifInterface(source).apply{setAttribute(ExifInterface.TAG_ORIENTATION,orientation.toString());setAttribute(ExifInterface.TAG_GPS_LATITUDE,"1/1,0/1,0/1");setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF,"N");saveAttributes()}
            val original=BitmapFactory.decodeFile(source.path)
            val resolution=PhotoProcessor.saveGrayscale(source,output)
            val actual=BitmapFactory.decodeFile(output.path)
            assertEquals(if(orientation>=5)h else w,actual.width)
            assertEquals(if(orientation>=5)w else h,actual.height)
            assertEquals(actual.width,resolution.sourceWidth);assertEquals(actual.height,resolution.sourceHeight);assertFalse(resolution.reduced)
            for((x,y) in listOf(20 to 20,150 to 20,20 to 180,150 to 180,40 to 127,40 to 128,127 to 40,128 to 40)) {
                val q=original.getPixel(x,y)
                val expected=(.213*Color.red(q)+.715*Color.green(q)+.072*Color.blue(q)).toInt()
                val target=when(orientation) {
                    2->w-1-x to y;3->w-1-x to h-1-y;4->x to h-1-y;5->y to x
                    6->h-1-y to x;7->h-1-y to w-1-x;8->y to w-1-x;else->x to y
                }
                val pixel=actual.getPixel(target.first,target.second)
                assertEquals("orientation $orientation at $x,$y",expected.toDouble(),Color.red(pixel).toDouble(),2.0)
                assertEquals(Color.red(pixel),Color.green(pixel));assertEquals(Color.red(pixel),Color.blue(pixel))
            }
            assertNull(ExifInterface(output).getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            actual.recycle();original.recycle()
        }
    }
    @Test fun smallFallbackBudgetDoesNotShrinkSupportedImagesOrLoseTheLastOddRow() {
        val context=RuntimeEnvironment.getApplication()
        val source=File(context.cacheDir,"odd.jpg");val output=File(context.cacheDir,"odd-gray.png")
        val original=Bitmap.createBitmap(301,257,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.WHITE)}
        source.outputStream().use{original.compress(Bitmap.CompressFormat.JPEG,100,it)};original.recycle()
        PhotoProcessor.saveGrayscale(source,output,20_000)
        val result=BitmapFactory.decodeFile(output.path)
        assertEquals(301,result.width);assertEquals(257,result.height)
        assertEquals(255,Color.red(result.getPixel(300,256)));result.recycle()
    }
    @Test fun twelveMegapixelsRemainNativeWithBoundedRegionRequestsAndReadableMetadata() {
        val context=RuntimeEnvironment.getApplication()
        val source=File(context.cacheDir,"native-photo.png")
        val bitmap=Bitmap.createBitmap(4032,3024,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.WHITE)}
        Canvas(bitmap).drawRect(3500f,2500f,3501f,2600f,Paint().apply{color=Color.BLACK})
        source.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        PixelRegionDecoder.largestRegionPixels=0
        val store=RecordStore(context);val saved=store.save(source,"高解像度のメモ")
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(saved.photo.path,bounds)
        assertEquals(4032,bounds.outWidth);assertEquals(3024,bounds.outHeight)
        assertTrue(PixelRegionDecoder.largestRegionPixels <= PhotoProcessor.MAX_BAND_PIXELS)
        assertEquals("png",saved.photo.extension)
        val result=BitmapFactory.decodeFile(saved.photo.path)
        assertEquals(Color.BLACK,result.getPixel(3500,2550));assertEquals(Color.WHITE,result.getPixel(3499,2550));result.recycle()
        val reopened=store.list().single{it.id==saved.id}
        assertEquals(saved.resolution,reopened.resolution);assertFalse(reopened.resolution!!.reduced)
        assertEquals("高解像度のメモ",reopened.note)
        store.saveMeasurement(reopened,Measurement(Region(.2,.2,.8,.8),null,null))
        store.saveHairReview(reopened,HairReview(Region(.2,.2,.8,.8),emptyList()))
        assertEquals(saved.resolution,store.list().single{it.id==saved.id}.resolution)
        store.delete(reopened);assertFalse(saved.photo.exists())
    }
    @Test fun unsupportedRegionFormatUsesAnExplicitlyReportedFallback() {
        val context=RuntimeEnvironment.getApplication()
        val source=File(context.cacheDir,"fallback.jpg");val output=File(context.cacheDir,"fallback.png")
        val bitmap=Bitmap.createBitmap(301,257,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.WHITE)}
        source.outputStream().use{bitmap.compress(Bitmap.CompressFormat.JPEG,100,it)};bitmap.recycle()
        PixelRegionDecoder.rejectRegions=true
        val dimensions=try {PhotoProcessor.saveGrayscale(source,output,20_000)} finally {PixelRegionDecoder.rejectRegions=false}
        assertTrue(dimensions.reduced);assertEquals(301,dimensions.sourceWidth);assertEquals(257,dimensions.sourceHeight)
        assertTrue(dimensions.width*dimensions.height<=20_000)
        val result=BitmapFactory.decodeFile(output.path)
        assertEquals(dimensions.width,result.width);assertEquals(dimensions.height,result.height)
        assertEquals(255,Color.red(result.getPixel(result.width-1,result.height-1)));result.recycle()
    }
}
