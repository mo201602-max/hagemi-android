package jp.hagemi.android

import android.graphics.*
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

object HairPhotoAnalysis {
    @Suppress("DEPRECATION")
    fun analyze(file: File, bald: Region, cancelled: AtomicBoolean, progress: (Int, Int) -> Unit): HairReview {
        require(bald.isValid)
        val decoder = requireNotNull(BitmapRegionDecoder.newInstance(file.path, false))
        try {
            val w=decoder.width;val h=decoder.height;val scale=(max(w,h)/3000f).coerceIn(.05f,1f)
            val cx=(bald.left+bald.right)/2*w;val cy=(bald.top+bald.bottom)/2*h
            val rx=(bald.right-bald.left)/2*w;val ry=(bald.bottom-bald.top)/2*h
            val left=floor(cx-rx*1.5).toInt().coerceAtLeast(0);val top=floor(cy-ry*1.5).toInt().coerceAtLeast(0)
            val right=ceil(cx+rx*1.5).toInt().coerceAtMost(w);val bottom=ceil(cy+ry*1.5).toInt().coerceAtMost(h)
            val cores=ArrayList<Rect>()
            for(y in top until bottom step 512)for(x in left until right step 512) {
                val r=Rect(x,y,min(right,x+512),min(bottom,y+512))
                val nearestX=(cx.coerceIn(r.left.toDouble(),r.right.toDouble())-cx)/rx
                val nearestY=(cy.coerceIn(r.top.toDouble(),r.bottom.toDouble())-cy)/ry
                val maxX=max(abs(r.left-cx),abs(r.right-cx))/rx;val maxY=max(abs(r.top-cy),abs(r.bottom-cy))/ry
                if(hypot(nearestX,nearestY)<=1.45 && hypot(maxX,maxY)>=.7) cores.add(r)
            }
            val exposure=exposure(file)
            val results=ArrayList<HairCandidate>();var truncated=false
            for((i,core) in cores.withIndex()) {
                if(cancelled.get())throw CancellationException()
                progress(i+1,cores.size)
                val halo=max(16,(192*scale).roundToInt())
                val bounds=Rect(max(0,core.left-halo),max(0,core.top-halo),min(w,core.right+halo),min(h,core.bottom+halo))
                val bitmap=requireNotNull(decoder.decodeRegion(bounds,BitmapFactory.Options()))
                val gray=ByteArray(bitmap.width*bitmap.height);val band=IntArray(bitmap.width*16)
                val bw=bitmap.width;val bh=bitmap.height
                try {
                    for(y in 0 until bh step 16) {
                        val rows=min(16,bh-y);bitmap.getPixels(band,0,bw,0,y,bw,rows)
                        for(j in 0 until bw*rows)gray[y*bw+j]=(((band[j] and 255)-exposure.first)*exposure.second+14f).roundToInt().coerceIn(0,255).toByte()
                    }
                } finally { bitmap.recycle() }
                val found=HairEndpointDetector.detect(HairEndpointDetector.Tile(gray,bw,bh),scale,110f,{cancelled.get()})
                for(c in found) {
                    val x=c.x+bounds.left;val y=c.y+bounds.top
                    if(x<core.left||x>=core.right||y<core.top||y>=core.bottom)continue
                    if(hypot((x-cx)/rx,(y-cy)/ry) !in .70..1.45)continue
                    val normalized=HairPoint(x/w,y/h)
                    if(results.any { hypot((it.point.x-normalized.x)*w,(it.point.y-normalized.y)*h)<max(2f,6*scale) })continue
                    if(results.size>=HairReviewCodec.MAX_CANDIDATES) {truncated=true;continue}
                    results.add(HairCandidate(normalized,c.path.map { HairPoint((it.first+bounds.left)/w,(it.second+bounds.top)/h) },c.flags))
                }
            }
            return HairReview(bald,results.sortedWith(compareBy<HairCandidate>{it.point.y}.thenBy{it.point.x}),truncated)
        } finally {decoder.recycle()}
    }
    private fun exposure(file:File):Pair<Float,Float> {
        val info=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeFile(file.path,info)
        var sample=1;while(max(info.outWidth,info.outHeight)/sample>512)sample*=2
        val image=requireNotNull(BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply {inSampleSize=sample}))
        val histogram=IntArray(256)
        try {
            val row=IntArray(image.width)
            for(y in 0 until image.height) {image.getPixels(row,0,image.width,0,y,image.width,1);row.forEach {histogram[it and 255]++}}
        } finally {image.recycle()}
        val total=histogram.sum()
        fun percentile(fraction:Float):Int {var n=0;for(i in histogram.indices){n+=histogram[i];if(n>=total*fraction)return i};return 255}
        val low=percentile(.1f);val high=percentile(.9f)
        require(high-low>=20) {"写真の明暗差が不足しています。明るい場所の鮮明な写真で試してください"}
        return low.toFloat() to (104f/(high-low)).coerceIn(.5f,2f)
    }
}
