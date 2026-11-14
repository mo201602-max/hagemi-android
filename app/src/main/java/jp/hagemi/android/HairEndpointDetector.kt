package jp.hagemi.android

import java.util.concurrent.CancellationException
import kotlin.math.*

/** Bounded tile analysis. Outputs review candidates, never automatically confirmed roots. */
object HairEndpointDetector {
    data class Tile(val gray: ByteArray, val width: Int, val height: Int)
    data class LocalCandidate(val x: Float, val y: Float, val path: List<Pair<Float, Float>>, val flags: List<String>)
    private fun checkCancelled(cancelled: () -> Boolean) { if (cancelled()) throw CancellationException() }

    fun detect(tile: Tile, scale: Float, level: Float, cancelled: () -> Boolean = { false }): List<LocalCandidate> {
        val w = tile.width; val h = tile.height
        require(w > 0 && h > 0 && w <= 1000 && h <= 1000 && tile.gray.size == w * h)
        require(scale in .05f..2f && level.isFinite())
        checkCancelled(cancelled)
        val smooth = gaussian(tile.gray, w, h, max(.6f, 1.2f * scale))
        val mean = box(smooth, w, h, max(2, (20 * scale).roundToInt()))
        val queue = IntArray(w * h)
        val candidates = ArrayList<LocalCandidate>()
        val neighbourBuffer = IntArray(8)
        val maxTrace = max(12, (100 * scale).roundToInt())
        for (contrast in floatArrayOf(8f, 12f, 16f)) {
            checkCancelled(cancelled)
            val mask = BooleanArray(w * h) { mean[it] > level - 25 && smooth[it] < mean[it] - contrast }
            removeSpecks(mask, w, h, max(2, (20 * scale * scale).roundToInt()), queue)
            val active = IntArray(mask.count { it }); var at = 0
            mask.indices.forEach { if (mask[it]) active[at++] = it }
            val skeleton = thin(mask, w, h, active, queue, cancelled)
            for (index in active) {
                if (!skeleton[index]) continue
                val x = index % w; val y = index / w
                val margin = max(4, (50 * scale).roundToInt())
                if (x < margin || y < margin || x >= w - margin || y >= h - margin) continue
                if (neighbours(skeleton, w, h, index, neighbourBuffer) != 1) continue
                val trace = ArrayList<Int>(maxTrace + 2); trace.add(index)
                var length = 0f; var previous = -1; var current = index
                while (length < maxTrace && trace.size < HairReviewCodec.MAX_PATH_POINTS) {
                    val count = neighbours(skeleton, w, h, current, neighbourBuffer)
                    if (count > 2) break
                    var next = -1; var choices = 0
                    for (j in 0 until count) if (neighbourBuffer[j] != previous) { next = neighbourBuffer[j]; choices++ }
                    if (choices != 1 || trace.contains(next)) break
                    length += hypot((next % w - current % w).toFloat(), (next / w - current / w).toFloat())
                    trace.add(next); previous = current; current = next
                }
                if (length < max(6f, 20 * scale)) continue
                checkCancelled(cancelled)
                val visible = visibilitySupport(smooth, w, h, x, y, level, scale)
                if (visible < .5f) continue
                val positions = FloatArray(trace.size)
                for (i in 1 until trace.size) positions[i] = positions[i - 1] + hypot((trace[i] % w - trace[i - 1] % w).toFloat(), (trace[i] / w - trace[i - 1] / w).toFloat())
                val body = ArrayList<Float>(); var flankVisible = 0; var n = 0
                val endDistance = min(60 * scale, length)
                var distance = max(1f, 4 * scale)
                while (distance <= endDistance) {
                    val i = positions.indexOfFirst { it >= distance }.coerceAtLeast(1)
                    val before = trace[max(0, i - 2)]; val after = trace[min(trace.lastIndex, i + 2)]
                    var dx = (after % w - before % w).toFloat(); var dy = (after / w - before / w).toFloat()
                    val norm = hypot(dx, dy).coerceAtLeast(1f); dx /= norm; dy /= norm
                    val px = (trace[i] % w).toFloat(); val py = (trace[i] / w).toFloat()
                    val left = median(floatArrayOf(10f, 16f, 24f).map { sample(smooth,w,h,px - dy * it * scale,py + dx * it * scale) })
                    val right = median(floatArrayOf(10f, 16f, 24f).map { sample(smooth,w,h,px + dy * it * scale,py - dx * it * scale) })
                    body.add((left + right) / 2 - smooth[trace[i]])
                    if (max(left, right) >= level) flankVisible++
                    n++; distance += max(1f, 4 * scale)
                }
                if (n < 3 || median(body) < 15 || flankVisible.toFloat() / n < .5f) continue
                val flags = ArrayList<String>()
                if (length < 40 * scale) flags.add("short")
                if (visible < .7f) flags.add("scalp_unclear")
                val base = trace[min(trace.lastIndex, max(2, (12 * scale).roundToInt()))]
                var dx = (base % w - x).toFloat(); var dy = (base / w - y).toFloat()
                val norm = hypot(dx,dy).coerceAtLeast(1f); dx /= norm; dy /= norm
                var outwardEvidence = 0
                for (j in 1..7) {
                    val px = x - dx * j * 4 * scale; val py = y - dy * j * 4 * scale
                    val background = max(sample(smooth,w,h,px-dy*12*scale,py+dx*12*scale),sample(smooth,w,h,px+dy*12*scale,py-dx*12*scale))
                    if (background - sample(smooth,w,h,px,py) > max(5f, median(body) * .35f)) outwardEvidence++
                }
                if (outwardEvidence >= 4) flags.add("overlap")
                // Width is only a review cue; changes in threshold can make real emergence points look tapered.
                fun widths(from: Float, to: Float): List<Float> = trace.indices.filter { positions[it] in (from * scale)..(to * scale) }.map { i ->
                    val p = trace[i]; var width = 1f
                    for (sign in intArrayOf(-1,1)) {
                        var step = 1
                        while (step <= max(2,(12*scale).roundToInt())) {
                            val xx = (p % w - dy * step * sign).roundToInt(); val yy = (p / w + dx * step * sign).roundToInt()
                            if (xx !in 0 until w || yy !in 0 until h || !mask[yy*w+xx]) break
                            width++; step++
                        }
                    }
                    width
                }
                val near = widths(4f,12f); val far = widths(22f,38f)
                if (near.isEmpty() || far.isEmpty()) flags.add("width_unknown")
                else if (median(near) / median(far) < .65f) flags.add("taper")
                if (candidates.none { hypot(it.x-x,it.y-y) < max(2f, 6*scale) })
                    candidates.add(LocalCandidate(x.toFloat(),y.toFloat(),trace.map { (it%w).toFloat() to (it/w).toFloat() },flags))
            }
        }
        return candidates
    }

    internal fun gaussian(input: ByteArray,w: Int,h: Int,sigma: Float): FloatArray {
        val radius=(4*sigma+.5f).toInt().coerceAtLeast(1);val kernel=FloatArray(radius*2+1) { exp(-((it-radius)*(it-radius))/(2*sigma*sigma)) }
        val sum=kernel.sum();kernel.indices.forEach { kernel[it]/=sum }
        val temp=FloatArray(input.size);val result=FloatArray(input.size)
        for(y in 0 until h)for(x in 0 until w) {
            var v=0f;for(k in -radius..radius)v+=(input[y*w+(x+k).coerceIn(0,w-1)].toInt() and 255)*kernel[k+radius]
            temp[y*w+x]=v
        }
        for(y in 0 until h)for(x in 0 until w) {
            var v=0f;for(k in -radius..radius)v+=temp[(y+k).coerceIn(0,h-1)*w+x]*kernel[k+radius]
            result[y*w+x]=v
        }
        return result
    }
    private fun box(input: FloatArray,w: Int,h: Int,r: Int): FloatArray {
        val temp=FloatArray(input.size);val result=FloatArray(input.size)
        for(y in 0 until h) {
            var sum=0f;for(k in -r..r)sum+=input[y*w+k.coerceIn(0,w-1)]
            for(x in 0 until w) { temp[y*w+x]=sum/(2*r+1);sum+=input[y*w+(x+r+1).coerceIn(0,w-1)]-input[y*w+(x-r).coerceIn(0,w-1)] }
        }
        for(x in 0 until w) {
            var sum=0f;for(k in -r..r)sum+=temp[k.coerceIn(0,h-1)*w+x]
            for(y in 0 until h) { result[y*w+x]=sum/(2*r+1);sum+=temp[(y+r+1).coerceIn(0,h-1)*w+x]-temp[(y-r).coerceIn(0,h-1)*w+x] }
        }
        return result
    }
    private fun removeSpecks(mask: BooleanArray,w: Int,h: Int,minimum: Int,queue: IntArray) {
        val seen=BooleanArray(mask.size)
        for(start in mask.indices) {
            if(!mask[start]||seen[start])continue
            var head=0;var tail=1;queue[0]=start;seen[start]=true
            while(head<tail) {
                val p=queue[head++];val x=p%w;val y=p/w
                for(dy in -1..1)for(dx in -1..1) {
                    val xx=x+dx;val yy=y+dy;if(xx !in 0 until w||yy !in 0 until h)continue
                    val next=yy*w+xx;if(mask[next]&&!seen[next]) { seen[next]=true;queue[tail++]=next }
                }
            }
            if(tail<minimum)for(i in 0 until tail)mask[queue[i]]=false
        }
    }
    internal fun thin(mask: BooleanArray,w: Int,h: Int,active: IntArray,queue: IntArray,cancelled:()->Boolean):BooleanArray {
        val sk=mask.copyOf()
        repeat(100) {
            checkCancelled(cancelled);var total=0
            for(phase in 0..1) {
                var removed=0
                for(p in active) {
                    if(!sk[p])continue
                    val x=p%w;val y=p/w;if(x==0||y==0||x==w-1||y==h-1)continue
                    val a=sk[p-w];val b=sk[p-w+1];val c=sk[p+1];val d=sk[p+w+1]
                    val e=sk[p+w];val f=sk[p+w-1];val g=sk[p-1];val j=sk[p-w-1]
                    val count=(if(a)1 else 0)+(if(b)1 else 0)+(if(c)1 else 0)+(if(d)1 else 0)+(if(e)1 else 0)+(if(f)1 else 0)+(if(g)1 else 0)+(if(j)1 else 0)
                    if(count !in 2..6)continue
                    val transitions=(if(!a&&b)1 else 0)+(if(!b&&c)1 else 0)+(if(!c&&d)1 else 0)+(if(!d&&e)1 else 0)+(if(!e&&f)1 else 0)+(if(!f&&g)1 else 0)+(if(!g&&j)1 else 0)+(if(!j&&a)1 else 0)
                    if(transitions!=1)continue
                    val preserve=if(phase==0)(a&&c&&e)||(c&&e&&g) else (a&&c&&g)||(a&&e&&g)
                    if(!preserve)queue[removed++]=p
                }
                for(i in 0 until removed)sk[queue[i]]=false
                total+=removed
            }
            if(total==0)return sk
        }
        return sk
    }
    internal fun neighbours(sk: BooleanArray,w: Int,h: Int,p: Int,out: IntArray):Int {
        val x=p%w;val y=p/w;var n=0
        for(dy in -1..1)for(dx in -1..1) {
            if(dx==0&&dy==0)continue
            val xx=x+dx;val yy=y+dy;if(xx !in 0 until w||yy !in 0 until h||!sk[yy*w+xx])continue
            if(dx!=0&&dy!=0&&(sk[y*w+xx]||sk[yy*w+x]))continue
            out[n++]=yy*w+xx
        }
        return n
    }
    internal fun visibilitySupport(input: FloatArray,w: Int,h: Int,x: Int,y: Int,level: Float,scale: Float):Float {
        var support=1f
        for(r in intArrayOf(max(3,(32*scale).roundToInt()),max(4,(48*scale).roundToInt()))) {
            if(x-r<0||y-r<0||x+r>=w||y+r>=h)return 0f
            val side=2*r+1;val visited=BooleanArray(side*side);val queue=IntArray(side*side)
            var disk=0;var largest=0
            for(yy in -r..r)for(xx in -r..r) {
                if(xx*xx+yy*yy>r*r)continue
                disk++;val start=(yy+r)*side+xx+r
                if(visited[start]||input[(y+yy)*w+x+xx]<level)continue
                var head=0;var tail=1;queue[0]=start;visited[start]=true
                while(head<tail) {
                    val p=queue[head++];val px=p%side;val py=p/side
                    for(k in 0..3) {
                        val nx=px+if(k==0)1 else if(k==1)-1 else 0;val ny=py+if(k==2)1 else if(k==3)-1 else 0
                        if(nx !in 0 until side||ny !in 0 until side||(nx-r)*(nx-r)+(ny-r)*(ny-r)>r*r)continue
                        val next=ny*side+nx
                        if(!visited[next]&&input[(y+ny-r)*w+x+nx-r]>=level) { visited[next]=true;queue[tail++]=next }
                    }
                }
                largest=max(largest,tail)
            }
            support=min(support,largest.toFloat()/disk)
        }
        return support
    }
    private fun median(values: List<Float>):Float { if(values.isEmpty())return 0f;val sorted=values.sorted();return sorted[sorted.size/2] }
    private fun sample(a:FloatArray,w:Int,h:Int,x:Float,y:Float):Float {
        val xx=x.coerceIn(0f,(w-1).toFloat());val yy=y.coerceIn(0f,(h-1).toFloat())
        val x0=xx.toInt();val y0=yy.toInt();val x1=min(w-1,x0+1);val y1=min(h-1,y0+1);val fx=xx-x0;val fy=yy-y0
        return (a[y0*w+x0]*(1-fx)+a[y0*w+x1]*fx)*(1-fy)+(a[y1*w+x0]*(1-fx)+a[y1*w+x1]*fx)*fy
    }
}
