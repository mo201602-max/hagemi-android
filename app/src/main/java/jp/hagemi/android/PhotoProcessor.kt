package jp.hagemi.android

import android.graphics.*
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.ceil

/** One destination bitmap and a decoded band, rather than multiple full-resolution bitmaps. */
object PhotoProcessor {
    fun saveGrayscale(source: File, destination: File, maxPixels: Int = 9_000_000) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "画像を読み込めませんでした" }
        var sample = 1
        while (ceil(bounds.outWidth.toDouble() / sample) * ceil(bounds.outHeight.toDouble() / sample) > maxPixels ||
            maxOf(bounds.outWidth, bounds.outHeight) / sample > 3000) sample *= 2
        val orientation = ExifInterface(source).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
        @Suppress("DEPRECATION")
        val decoder = runCatching { BitmapRegionDecoder.newInstance(source.path, false) }.getOrNull()
        if (decoder == null) {
            // Unsupported region formats use a deliberately smaller fallback.
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1500) sample *= 2
            val image = requireNotNull(BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample }))
            try {
                val output = orientedTarget(image.width, image.height, orientation)
                try {
                    Canvas(output).apply { concat(orientationMatrix(image.width, image.height, orientation)); drawBitmap(image, 0f, 0f, paint) }
                    destination.outputStream().use { check(output.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
                } finally { output.recycle() }
            } finally { image.recycle() }
            return
        }
        try {
            val w = ceil(bounds.outWidth.toDouble() / sample).toInt(); val h = ceil(bounds.outHeight.toDouble() / sample).toInt()
            val output = orientedTarget(w, h, orientation)
            try {
                val canvas = Canvas(output).apply { concat(orientationMatrix(w, h, orientation)) }
                val band = 64 * sample
                for (top in 0 until bounds.outHeight step band) {
                    val bottom = minOf(bounds.outHeight, top + band)
                    val stripe = requireNotNull(decoder.decodeRegion(Rect(0, top, bounds.outWidth, bottom), BitmapFactory.Options().apply { inSampleSize = sample }))
                    try { canvas.drawBitmap(stripe, 0f, top.toFloat() / sample, paint) } finally { stripe.recycle() }
                }
                destination.outputStream().use { check(output.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            } finally { output.recycle() }
        } finally { decoder.recycle() }
    }
    private fun orientedTarget(w: Int, h: Int, orientation: Int): Bitmap {
        val swap = orientation in 5..8
        return Bitmap.createBitmap(if (swap) h else w, if (swap) w else h, Bitmap.Config.ARGB_8888)
    }
    private fun orientationMatrix(w: Int, h: Int, orientation: Int): Matrix {
        // Map three corners so all eight EXIF orientations have a positive destination origin.
        val source = floatArrayOf(0f, 0f, w.toFloat(), 0f, 0f, h.toFloat())
        val wf = w.toFloat(); val hf = h.toFloat()
        val target = when (orientation) {
            2 -> floatArrayOf(wf, 0f, 0f, 0f, wf, hf)
            3 -> floatArrayOf(wf, hf, 0f, hf, wf, 0f)
            4 -> floatArrayOf(0f, hf, wf, hf, 0f, 0f)
            5 -> floatArrayOf(0f, 0f, 0f, wf, hf, 0f)
            6 -> floatArrayOf(hf, 0f, hf, wf, 0f, 0f)
            7 -> floatArrayOf(hf, wf, hf, 0f, 0f, wf)
            8 -> floatArrayOf(0f, wf, 0f, 0f, hf, wf)
            else -> source
        }
        return Matrix().apply { check(setPolyToPoly(source, 0, target, 0, 3)) }
    }
}
