package jp.hagemi.android
import android.content.Context
import android.graphics.*
import android.view.View
class SpacingHistogram(context: Context) : View(context) {
    var result: BoundarySpacing.Result? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val data = result ?: return
        val top = 8f; val bottom = height - 28f
        val max = data.bins.maxOrNull()?.coerceAtLeast(1) ?: 1
        val step = width / 16f
        paint.color = Color.DKGRAY
        data.bins.forEachIndexed { i, n ->
            paint.color = if (data.spacingPx?.let { (it / data.binWidth).toInt() == i } == true) Color.rgb(0, 140, 180) else Color.DKGRAY
            canvas.drawRect(i * step + 2, bottom - (bottom - top) * n / max, (i + 1) * step - 2, bottom, paint)
        }
        paint.color = Color.DKGRAY
        paint.textSize = 11 * resources.displayMetrics.scaledDensity
        canvas.drawText("0 → 近傍距離（解析画像px） 右端は上限以上", 0f, height - 5f, paint)
    }
}
