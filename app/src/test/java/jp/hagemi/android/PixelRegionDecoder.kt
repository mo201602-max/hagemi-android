package jp.hagemi.android

import android.graphics.*
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers

/** Robolectric's default region shadow returns blank pixels and ignores sample size.
 * This test-only adapter supplies real decoded pixels for validating band transforms and persistence.
 * Native Android region-decoder behaviour/performance still requires a device check.
 */
@Implements(BitmapRegionDecoder::class)
class PixelRegionDecoder {
    private var image:Bitmap?=null
    companion object {
        @JvmStatic @Implementation
        fun newInstance(pathName:String,isShareable:Boolean):BitmapRegionDecoder {
            val result=ReflectionHelpers.callConstructor(BitmapRegionDecoder::class.java,ReflectionHelpers.ClassParameter.from(Long::class.javaPrimitiveType!!,0L))
            Shadow.extract<PixelRegionDecoder>(result).image=requireNotNull(BitmapFactory.decodeFile(pathName))
            return result
        }
    }
    @Implementation fun getWidth()=requireNotNull(image).width
    @Implementation fun getHeight()=requireNotNull(image).height
    @Implementation fun decodeRegion(rect:Rect,options:BitmapFactory.Options):Bitmap {
        val source=requireNotNull(image);val sample=options.inSampleSize.coerceAtLeast(1)
        val w=(rect.width()+sample-1)/sample;val h=(rect.height()+sample-1)/sample
        return Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).also {out->
            Canvas(out).drawBitmap(source,rect,Rect(0,0,w,h),Paint(Paint.FILTER_BITMAP_FLAG))
        }
    }
    @Implementation fun recycle(){image?.recycle();image=null}
}
