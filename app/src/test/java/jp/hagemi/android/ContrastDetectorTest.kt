package jp.hagemi.android

import org.junit.Assert.*
import org.junit.Test

class ContrastDetectorTest {
    private val width = 120
    private val height = 90
    private fun scene(background: Int = 35, foreground: Int = 190, edge: Boolean = false): IntArray =
        IntArray(width * height) { i ->
            val x = i % width; val y = i / width
            val cx = if (edge) 0 else 60
            if ((x - cx) * (x - cx) + (y - 45) * (y - 45) <= 15 * 15) foreground else background
        }
    @Test fun findsEnclosedBrightPatch() {
        val result = ContrastDetector().detect(scene(), width, height)
        assertNotNull(result)
        assertEquals(45, result!!.left)
        assertEquals(76, result.right)
    }
    @Test fun rejectsUniformFrames() {
        for (value in listOf(0, 128, 255)) assertNull(ContrastDetector().detect(IntArray(width * height) { value }, width, height))
    }
    @Test fun rejectsLowContrast() { assertNull(ContrastDetector().detect(scene(100, 115), width, height)) }
    @Test fun rejectsBrightRegionOpenToFrameEdge() { assertNull(ContrastDetector().detect(scene(edge = true), width, height)) }
    @Test fun rejectsThinParting() {
        val pixels = IntArray(width * height) { if (it % width in 57..62 && it / width in 15..75) 190 else 35 }
        assertNull(ContrastDetector().detect(pixels, width, height))
    }
    @Test fun toleratesDarkHairNoise() {
        val pixels = scene().mapIndexed { index, value -> value + index % 9 - 4 }.toIntArray()
        assertNotNull(ContrastDetector().detect(pixels, width, height))
    }
}
