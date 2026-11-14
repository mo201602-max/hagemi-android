package jp.hagemi.android

import android.content.Context
import android.graphics.*
import android.view.View

class DetectionOverlay(context: Context) : View(context) {
    private var box: RectF? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * resources.displayMetrics.density }
    fun update(candidate: RectF?) { box = candidate; invalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = Color.WHITE
        val cx = width / 2f; val cy = height / 2f
        canvas.drawLine(cx - 16, cy, cx + 16, cy, paint)
        canvas.drawLine(cx, cy - 16, cx, cy + 16, paint)
        box?.let {
            paint.color = Color.rgb(255, 210, 80)
            canvas.drawRoundRect(it, 12f, 12f, paint)
        }
    }
}
