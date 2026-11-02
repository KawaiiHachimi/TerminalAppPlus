package com.android.virtualization.terminal

import java.io.*
import org.junit.Assert.*
import org.junit.Test

class AvcStreamTest {
    private fun input(vararg values: Int): DataInputStream {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out -> values.forEach(out::writeInt) }
        return DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
    }
    @Test fun validatesDimensionsBeforeAllocation() {
        assertEquals(AvcStream.Size(1280, 720), AvcStream.header(input(0x50415631, 1280, 720)))
        for ((w, h) in listOf(4096 to 4096, 1279 to 720, 0 to 720, Int.MAX_VALUE to Int.MAX_VALUE)) {
            assertThrows(IllegalStateException::class.java) { AvcStream.header(input(0x50415631, w, h)) }
        }
        assertThrows(IllegalStateException::class.java) { AvcStream.header(input(0, 1280, 720)) }
    }
    @Test fun rejectsUnboundedAndTruncatedPackets() {
        for (size in listOf(-1, 0, AvcStream.MAX_PACKET + 1)) {
            assertThrows(IllegalStateException::class.java) { AvcStream.packet(input(size)) }
        }
        assertThrows(EOFException::class.java) { AvcStream.packet(input(100)) }
    }
    @Test fun recognizesMixedAnnexBPrefixes() {
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x1f)
        val pps = byteArrayOf(0, 0, 1, 0x68, 0x20)
        val frame = byteArrayOf(0, 0, 1, 0x65, 0x11)
        val sets = AvcStream.parameterSets(sps + pps + frame)
        assertArrayEquals(sps, sets[7]); assertArrayEquals(pps, sets[8])
        assertTrue(AvcStream.parameterSets(frame).isEmpty())
    }
}
