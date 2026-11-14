package jp.hagemi.android

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View

class HairReviewView(context:Context):View(context) {
    var bitmap:Bitmap?=null
    var candidates:List<HairCandidate> = emptyList()
    var selected=0
    var detail=true
    var showMarks=true
    var crop:Rect?=null
    var photoWidth=1
    var photoHeight=1
    var onSelect:((Int)->Unit)?=null
    private val rect=RectF()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val image=bitmap?:return
        val scale=minOf(width.toFloat()/image.width,height.toFloat()/image.height)
        val w=image.width*scale;val h=image.height*scale
        rect.set((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2)
        canvas.drawBitmap(image,null,rect,null)
        if(!showMarks)return
        fun x(p:HairPoint):Float = if(detail&&crop!=null)rect.left+(p.x*photoWidth-crop!!.left)/crop!!.width()*w else rect.left+p.x*w
        fun y(p:HairPoint):Float = if(detail&&crop!=null)rect.top+(p.y*photoHeight-crop!!.top)/crop!!.height()*h else rect.top+p.y*h
        paint.style=Paint.Style.STROKE;paint.strokeWidth=2*resources.displayMetrics.density
        if(detail) {
            val c=candidates.getOrNull(selected)?:return
            canvas.save();canvas.clipRect(rect)
            paint.color=Color.CYAN
            for(i in 1 until c.path.size)canvas.drawLine(x(c.path[i-1]),y(c.path[i-1]),x(c.path[i]),y(c.path[i]),paint)
            paint.color=color(c.verdict)
            canvas.drawCircle(x(c.point),y(c.point),6*resources.displayMetrics.density,paint)
            canvas.restore()
        } else candidates.forEachIndexed {i,c->
            paint.color=color(c.verdict)
            canvas.drawCircle(x(c.point),y(c.point),(if(i==selected)6 else 3)*resources.displayMetrics.density,paint)
        }
    }
    private fun color(v:HairVerdict)=when(v) {
        HairVerdict.CONFIRMED->Color.rgb(30,230,80)
        HairVerdict.HOLD->Color.YELLOW
        HairVerdict.REJECTED->Color.GRAY
        HairVerdict.UNREVIEWED->Color.rgb(255,140,40)
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(detail)return super.onTouchEvent(event)
        if(event.action==MotionEvent.ACTION_UP&&rect.contains(event.x,event.y)) {
            val index=candidates.indices.minByOrNull {i->kotlin.math.hypot(rect.left+candidates[i].point.x*rect.width()-event.x,rect.top+candidates[i].point.y*rect.height()-event.y)}
            if(index!=null)onSelect?.invoke(index)
            performClick();return true
        }
        return true
    }
    override fun performClick():Boolean {super.performClick();return true}
}
