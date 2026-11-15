package jp.hagemi.android

import android.content.Context
import android.graphics.*
import android.view.View

class DetectionOverlay(context: Context) : View(context) {
    private var box: RectF? = null
    private var focusPoint: PointF? = null
    private var focusReady = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * resources.displayMetrics.density }
    fun update(candidate: RectF?) { box = candidate; invalidate() }
    fun focusAt(point: PointF?, ready: Boolean) { focusPoint = point; focusReady = ready; invalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = Color.WHITE
        val cx = width / 2f; val cy = height / 2f
        paint.color = Color.argb(160, 255, 255, 255)
        canvas.drawOval(cx - width * .18f, cy - height * .18f, cx + width * .18f, cy + height * .18f, paint)
        paint.color = Color.WHITE
        canvas.drawLine(cx - 16, cy, cx + 16, cy, paint)
        canvas.drawLine(cx, cy - 16, cx, cy + 16, paint)
        box?.let {
            paint.color = Color.rgb(255, 210, 80)
            canvas.drawRoundRect(it, 12f, 12f, paint)
        }
        focusPoint?.let {
            paint.color = if (focusReady) Color.GREEN else Color.CYAN
            canvas.drawCircle(it.x, it.y, 14 * resources.displayMetrics.density, paint)
        }
    }
}
