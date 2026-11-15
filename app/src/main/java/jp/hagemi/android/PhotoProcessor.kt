package jp.hagemi.android

import android.graphics.*
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.ceil

/** Dimensions are upright, including EXIF rotation. Old records may have no resolution metadata. */
data class PhotoResolution(val sourceWidth: Int, val sourceHeight: Int, val width: Int, val height: Int) {
    val reduced: Boolean get() = width != sourceWidth || height != sourceHeight
    val text: String get() = "保存：$width × $height px" + if (reduced) "（元：$sourceWidth × $sourceHeight px）" else ""
}

/** Region-supported images preserve every pixel. Only unsupported formats use a smaller fallback. */
object PhotoProcessor {
    internal const val MAX_BAND_PIXELS = 256 * 1024
    @Suppress("DEPRECATION")
    fun saveGrayscale(source: File, destination: File, fallbackMaxPixels: Int = 4_000_000): PhotoResolution {
        require(fallbackMaxPixels > 0)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "画像を読み込めませんでした" }
        val orientation = ExifInterface(source).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val swap = orientation in 5..8
        val sourceW = if (swap) bounds.outHeight else bounds.outWidth
        val sourceH = if (swap) bounds.outWidth else bounds.outHeight
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
        val decoder = runCatching { BitmapRegionDecoder.newInstance(source.path, false) }.getOrNull()
        var fallback: Bitmap? = null
        try {
            val w: Int; val h: Int
            if (decoder != null) {
                w = decoder.width; h = decoder.height
            } else {
                // HEIC and other unsupported region formats keep a bounded full decode.
                var sample = 1
                while (ceil(bounds.outWidth.toDouble() / sample) * ceil(bounds.outHeight.toDouble() / sample) > fallbackMaxPixels ||
                    maxOf(bounds.outWidth, bounds.outHeight) / sample > 2000) sample *= 2
                fallback = requireNotNull(BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample }))
                w = fallback.width; h = fallback.height
            }
            val outputW = if (swap) h else w; val outputH = if (swap) w else h
            val matrix = orientationMatrix(w, h, orientation)
            val inverse = Matrix().also { check(matrix.invert(it)) }
            val bandRows = (MAX_BAND_PIXELS / outputW).coerceIn(1, 128)
            destination.outputStream().use { stream ->
                GrayPngWriter(stream, outputW, outputH).use { png ->
                    val row = IntArray(outputW)
                    for (top in 0 until outputH step bandRows) {
                        val count = minOf(bandRows, outputH - top)
                        val outputRect = RectF(0f, top.toFloat(), outputW.toFloat(), (top + count).toFloat())
                        inverse.mapRect(outputRect)
                        val sourceRect = Rect().apply { outputRect.round(this); intersect(0, 0, w, h) }
                        val stripe = decoder?.let { requireNotNull(it.decodeRegion(sourceRect, BitmapFactory.Options())) }
                        val band = Bitmap.createBitmap(outputW, count, Bitmap.Config.ARGB_8888)
                        try {
                            val canvas = Canvas(band)
                            canvas.translate(0f, -top.toFloat()); canvas.concat(matrix)
                            if (stripe != null) canvas.drawBitmap(stripe, sourceRect.left.toFloat(), sourceRect.top.toFloat(), paint)
                            else canvas.drawBitmap(requireNotNull(fallback), 0f, 0f, paint)
                            for (y in 0 until count) {
                                band.getPixels(row, 0, outputW, 0, y, outputW, 1)
                                png.writeRow(row)
                            }
                        } finally { band.recycle(); stripe?.recycle() }
                    }
                }
            }
            return PhotoResolution(sourceW, sourceH, outputW, outputH)
        } finally { decoder?.recycle(); fallback?.recycle() }
    }
    private fun orientationMatrix(w: Int, h: Int, orientation: Int): Matrix {
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
