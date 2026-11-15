package jp.hagemi.android

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.InflaterInputStream

class GrayPngWriterTest {
    @Test fun grayscalePngHasValidChunksAndLosslesslyRoundTripsAcrossIdatBoundaries() {
        val w=401;val h=301;val original=ByteArray(w*h).apply{Random(42).nextBytes(this)}
        val output=ByteArrayOutputStream()
        GrayPngWriter(output,w,h).use{png->for(y in 0 until h)png.writeRow(IntArray(w){x->original[y*w+x].toInt() and 255})}
        val input=DataInputStream(ByteArrayInputStream(output.toByteArray()))
        val signature=ByteArray(8).also{input.readFully(it)}
        assertArrayEquals(byteArrayOf(137.toByte(),80,78,71,13,10,26,10),signature)
        val compressed=ByteArrayOutputStream();var idats=0;var ended=false
        while(input.available()>0) {
            val length=input.readInt();val name=ByteArray(4).also{input.readFully(it)};val bytes=ByteArray(length).also{input.readFully(it)}
            val crc=CRC32().apply{update(name);update(bytes)}
            assertEquals(crc.value.toInt(),input.readInt())
            when(String(name,Charsets.US_ASCII)) {
                "IHDR"->{val header=DataInputStream(ByteArrayInputStream(bytes));assertEquals(w,header.readInt());assertEquals(h,header.readInt());assertEquals(8,header.readUnsignedByte());assertEquals(0,header.readUnsignedByte())}
                "IDAT"->{assertTrue(length<=64*1024);compressed.write(bytes);idats++}
                "IEND"->{assertEquals(0,length);ended=true}
                else->fail("Unexpected chunk")
            }
        }
        assertTrue(ended);assertTrue(idats>=2)
        val raw=InflaterInputStream(ByteArrayInputStream(compressed.toByteArray())).use{it.readBytes()}
        assertEquals((w+1)*h,raw.size)
        for(y in 0 until h) {
            assertEquals(1,raw[y*(w+1)].toInt());var previous=0
            for(x in 0 until w){previous=(previous+raw[y*(w+1)+x+1]) and 255;assertEquals(original[y*w+x].toInt() and 255,previous)}
        }
    }
    @Test(expected=IllegalStateException::class) fun incompleteImagesCannotFinishSuccessfully() {
        GrayPngWriter(ByteArrayOutputStream(),10,10).use{it.writeRow(IntArray(10))}
    }
}
