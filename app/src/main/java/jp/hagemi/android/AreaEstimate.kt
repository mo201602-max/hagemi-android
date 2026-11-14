package jp.hagemi.android

import kotlin.math.PI
import kotlin.math.roundToInt

data class Region(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val rectangleArea: Double get() = (right - left) * (bottom - top)
    val isValid: Boolean get() = left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
        left >= 0 && top >= 0 && right <= 1 && bottom <= 1 && right - left >= .01 && bottom - top >= .01
}

object AreaEstimate {
    fun fraction(bald: Region): Double { require(bald.isValid); return bald.rectangleArea * PI / 4 }
    fun missingHairs(bald: Region, reference: Region, count: Int): Double {
        require(bald.isValid && reference.isValid && count in 1..10000)
        require(!overlaps(bald, reference)) { "比較範囲は脱毛範囲の外に置いてください" }
        return fraction(bald) / reference.rectangleArea * count
    }
    fun overlaps(a: Region, b: Region) = a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top
    fun rounded(value: Double): Int = value.roundToInt()
}

data class Measurement(val bald: Region, val reference: Region?, val count: Int?, val candidateCount: Boolean = false) {
    val estimate: Double? get() = if (reference != null && count != null) AreaEstimate.missingHairs(bald, reference, count) else null
}
