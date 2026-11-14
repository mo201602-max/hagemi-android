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
        for(orientation in 1..8) {
            val source=File(context.cacheDir,"source-$orientation.jpg");val output=File(context.cacheDir,"gray-$orientation.jpg")
            val bitmap=Bitmap.createBitmap(96,80,Bitmap.Config.ARGB_8888)
            for(y in 0 until 80)for(x in 0 until 96)bitmap.setPixel(x,y,when {
                x<48&&y<40->Color.RED
                x>=48&&y<40->Color.GREEN
                x<48->Color.BLUE
                else->Color.WHITE
            })
            source.outputStream().use{bitmap.compress(Bitmap.CompressFormat.JPEG,100,it)};bitmap.recycle()
            ExifInterface(source).apply{setAttribute(ExifInterface.TAG_ORIENTATION,orientation.toString());setAttribute(ExifInterface.TAG_GPS_LATITUDE,"1/1,0/1,0/1");setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF,"N");saveAttributes()}
            val original=BitmapFactory.decodeFile(source.path)
            PhotoProcessor.saveGrayscale(source,output)
            val actual=BitmapFactory.decodeFile(output.path)
            assertEquals(if(orientation>=5)80 else 96,actual.width)
            assertEquals(if(orientation>=5)96 else 80,actual.height)
            for((x,y) in listOf(12 to 12,80 to 12,12 to 67,80 to 67,56 to 62,56 to 65)) {
                val q=original.getPixel(x,y)
                val expected=(.213*Color.red(q)+.715*Color.green(q)+.072*Color.blue(q)).toInt()
                val target=when(orientation) {
                    2->95-x to y;3->95-x to 79-y;4->x to 79-y;5->y to x
                    6->79-y to x;7->79-y to 95-x;8->y to 95-x;else->x to y
                }
                val pixel=actual.getPixel(target.first,target.second)
                assertEquals("orientation $orientation at $x,$y",expected.toDouble(),Color.red(pixel).toDouble(),5.0)
                assertEquals(Color.red(pixel).toDouble(),Color.green(pixel).toDouble(),2.0)
                assertEquals(Color.red(pixel).toDouble(),Color.blue(pixel).toDouble(),2.0)
            }
            assertNull(ExifInterface(output).getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            actual.recycle();original.recycle()
        }
    }
    @Test fun sampledOddDimensionsDoNotLeaveBlackBandAtBottom() {
        val context=RuntimeEnvironment.getApplication()
        val source=File(context.cacheDir,"odd.jpg");val output=File(context.cacheDir,"odd-gray.jpg")
        val original=Bitmap.createBitmap(301,257,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.WHITE)}
        source.outputStream().use{original.compress(Bitmap.CompressFormat.JPEG,100,it)};original.recycle()
        PhotoProcessor.saveGrayscale(source,output,20_000)
        val result=BitmapFactory.decodeFile(output.path)
        assertEquals(151,result.width);assertEquals(129,result.height)
        assertTrue(Color.red(result.getPixel(150,128))>240);result.recycle()
    }
}
