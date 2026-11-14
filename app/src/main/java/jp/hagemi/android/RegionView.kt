package jp.hagemi.android

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View

/** Selection coordinates always refer to the photo, excluding letterboxing. */
class RegionView(context: Context, private val bitmap: Bitmap) : View(context) {
    var bald: Region? = null
    var reference: Region? = null
    var selectingReference = false
    var changed: (() -> Unit)? = null
    private val imageRect = RectF()
    private var startX = 0.0
    private var startY = 0.0
    private var drawing = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 3 * resources.displayMetrics.density }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val w = bitmap.width * scale; val h = bitmap.height * scale
        imageRect.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        canvas.drawBitmap(bitmap, null, imageRect, null)
        fun rect(region: Region) = RectF(imageRect.left + region.left.toFloat() * w, imageRect.top + region.top.toFloat() * h,
            imageRect.left + region.right.toFloat() * w, imageRect.top + region.bottom.toFloat() * h)
        paint.style = Paint.Style.STROKE
        paint.color = Color.YELLOW
        bald?.let { canvas.drawOval(rect(it), paint) }
        paint.color = Color.CYAN
        reference?.let { canvas.drawRect(rect(it), paint) }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (imageRect.isEmpty) return false
        fun x() = ((event.x - imageRect.left) / imageRect.width()).toDouble().coerceIn(0.0, 1.0)
        fun y() = ((event.y - imageRect.top) / imageRect.height()).toDouble().coerceIn(0.0, 1.0)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!imageRect.contains(event.x, event.y)) return false
                startX = x(); startY = y(); drawing = true
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                if (!drawing) return false
                val region = Region(minOf(startX, x()), minOf(startY, y()), maxOf(startX, x()), maxOf(startY, y()))
                if (region.isValid) {
                    if (selectingReference) reference = region else bald = region
                    changed?.invoke(); invalidate()
                }
                if (event.actionMasked == MotionEvent.ACTION_UP) { drawing = false; performClick() }
            }
            MotionEvent.ACTION_CANCEL -> { drawing = false }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
