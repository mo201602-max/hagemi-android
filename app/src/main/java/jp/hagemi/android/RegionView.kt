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
    var analysis: RootCandidates.Result? = null
    var binaryPreview: Bitmap? = null
    var showBinary = false
    var spacingResult: BoundarySpacing.Result? = null
    var meshEstimate: MeshEstimate? = null
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
        analysis?.let { result ->
            if (showBinary) binaryPreview?.let { canvas.drawBitmap(it, null, rect(result.region), null) }
            paint.style = Paint.Style.STROKE
            paint.color = Color.MAGENTA
            result.points.forEach { point ->
                canvas.drawCircle(imageRect.left + point.x.toFloat() * w, imageRect.top + point.y.toFloat() * h,
                    4 * resources.displayMetrics.density, paint)
            }
        }
        val mesh = meshEstimate
        val area = bald
        if (mesh != null && area != null && mesh.amount(area) <= 1500) {
            val bounds = rect(area)
            val pitch = (mesh.spacing * kotlin.math.sqrt(bitmap.width.toDouble() * bitmap.height) * scale).toFloat()
            if (pitch >= 1f) {
                canvas.save()
                canvas.clipPath(Path().apply { addOval(bounds, Path.Direction.CW) })
                paint.color = Color.argb(160, 0, 210, 255)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = resources.displayMetrics.density
                val dy = pitch * .8660254f
                val rows = (bounds.height() / dy).toInt() + 2
                val cols = (bounds.width() / pitch).toInt() + 3
                for (row in -1..rows) for (col in -1..cols) {
                    val x = bounds.left + col * pitch + if (row % 2 == 0) 0f else pitch / 2
                    val y = bounds.top + row * dy
                    canvas.drawLine(x, y, x + pitch, y, paint)
                    canvas.drawLine(x, y, x + pitch / 2, y + dy, paint)
                    canvas.drawLine(x, y, x - pitch / 2, y + dy, paint)
                }
                canvas.restore()
            }
        }
        paint.strokeWidth = 3 * resources.displayMetrics.density
        paint.color = Color.GREEN; paint.style = Paint.Style.STROKE
        spacingResult?.points?.forEach { point ->
            canvas.drawCircle(imageRect.left + point.x.toFloat() * w, imageRect.top + point.y.toFloat() * h,
                4 * resources.displayMetrics.density, paint)
        }
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
