package com.android.virtualization.terminal

import org.junit.Assert.*
import org.junit.Test
import java.util.zip.Deflater

class CaptureFrameDecoderTest {
    private fun compressed(bytes: ByteArray): ByteArray {
        val encoder = Deflater(1)
        return try {
            encoder.setInput(bytes); encoder.finish()
            val output = ByteArray(bytes.size + 100)
            output.copyOf(encoder.deflate(output))
        } finally { encoder.end() }
    }
    @Test fun decodesAndReusesInflaterForNextFrame() {
        CaptureFrameDecoder().use { decoder ->
            for (value in listOf(1, 2)) {
                val pixels = ByteArray(4096) { value.toByte() }
                val wire = compressed(pixels)
                val output = ByteArray(pixels.size)
                decoder.inflate(wire, wire.size, output)
                assertArrayEquals(pixels, output)
            }
        }
    }
    @Test(expected = IllegalStateException::class) fun rejectsTruncatedFrames() {
        val wire = compressed(ByteArray(4096))
        CaptureFrameDecoder().use { it.inflate(wire, wire.size - 2, ByteArray(4096)) }
    }
    @Test(expected = IllegalStateException::class) fun rejectsOutputBeyondAnnouncedSize() {
        val wire = compressed(ByteArray(4096))
        CaptureFrameDecoder().use { it.inflate(wire, wire.size, ByteArray(2048)) }
    }
    @Test fun decodesLz4Block() {
        val pixels = ByteArray(4096) { (it % 16).toByte() }
        val compressor = net.jpountz.lz4.LZ4Factory.safeInstance().fastCompressor()
        val wire = compressor.compress(pixels)
        val output = ByteArray(pixels.size)
        CaptureFrameDecoder().use { it.decode(wire, wire.size, output, 2) }
        assertArrayEquals(pixels, output)
    }
    @Test(expected = RuntimeException::class) fun rejectsTruncatedLz4() {
        val wire = byteArrayOf(0x50, 1, 2)
        CaptureFrameDecoder().use { it.decode(wire, wire.size, ByteArray(5), 2) }
    }
}
