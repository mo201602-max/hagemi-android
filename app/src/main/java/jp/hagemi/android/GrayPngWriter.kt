package jp.hagemi.android

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/** Streams an 8-bit grayscale PNG using one scanline and a bounded IDAT buffer. */
internal class GrayPngWriter(output: OutputStream, private val width: Int, private val height: Int) : Closeable {
    private val sink = DataOutputStream(BufferedOutputStream(output, 64 * 1024))
    private val crc = CRC32()
    private val line = ByteArray(width + 1)
    private val deflater = Deflater(Deflater.BEST_SPEED)
    private var rows = 0
    private val idat = object : OutputStream() {
        private val buffer = ByteArray(64 * 1024)
        private var used = 0
        override fun write(value: Int) {
            buffer[used++] = value.toByte()
            if (used == buffer.size) flush()
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            var at = offset; var remaining = length
            while (remaining > 0) {
                val size = minOf(remaining, buffer.size - used)
                bytes.copyInto(buffer, used, at, at + size)
                used += size; at += size; remaining -= size
                if (used == buffer.size) flush()
            }
        }
        override fun flush() { if (used > 0) { chunk("IDAT", buffer, used); used = 0 } }
    }
    private val compressed: DeflaterOutputStream
    init {
        require(width > 0 && height > 0)
        sink.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        val header = ByteBuffer.allocate(13).putInt(width).putInt(height)
            .put(8.toByte()).put(0.toByte()).put(0.toByte()).put(0.toByte()).put(0.toByte()).array()
        chunk("IHDR", header, header.size)
        compressed = DeflaterOutputStream(idat, deflater, 64 * 1024)
    }
    fun writeRow(grayscaleArgb: IntArray) {
        require(rows < height && grayscaleArgb.size >= width)
        line[0] = 1 // PNG Sub filter; values are exactly reversible, including wraparound.
        var previous = 0
        for (x in 0 until width) {
            val value = grayscaleArgb[x] and 255
            line[x + 1] = (value - previous).toByte()
            previous = value
        }
        compressed.write(line)
        rows++
    }
    private fun chunk(type: String, bytes: ByteArray, length: Int) {
        val name = type.toByteArray(Charsets.US_ASCII)
        sink.writeInt(length); sink.write(name); sink.write(bytes, 0, length)
        crc.reset(); crc.update(name); crc.update(bytes, 0, length)
        sink.writeInt(crc.value.toInt())
    }
    override fun close() {
        try {
            check(rows == height) { "PNGの行数が一致しません" }
            compressed.finish(); idat.flush()
            chunk("IEND", ByteArray(0), 0); sink.flush()
        } finally { deflater.end() }
    }
}
