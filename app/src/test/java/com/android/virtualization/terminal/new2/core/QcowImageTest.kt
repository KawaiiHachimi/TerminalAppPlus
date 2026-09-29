package com.android.virtualization.terminal.new2.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream
import org.junit.Assert.*
import org.junit.Test

class QcowImageTest {
    private fun header(version: Int = 3) = ByteArray(104).also {
        ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0, 0x514649fb); putInt(4, version); putLong(24, 8L * 1024 * 1024)
        }
    }
    @Test fun detectsContentIncludingGzipWithoutFilename() {
        val bytes = header()
        assertTrue(QcowImage.isQcow(bytes))
        assertFalse(QcowImage.isQcow(ByteArray(104)))
        assertEquals(8L * 1024 * 1024, QcowImage.validate(header(2)))
        val zipped = ByteArrayOutputStream().also { GZIPOutputStream(it).use { out -> out.write(bytes) } }.toByteArray()
        assertArrayEquals(bytes, QcowImage.decoded(ByteArrayInputStream(zipped)).readBytes())
    }
    @Test fun rejectsBackingEncryptionExternalDataAndCorruption() {
        for ((offset, value) in listOf(8 to 100L, 72 to 4L, 72 to 2L, 72 to 32L)) {
            val bytes = header().also { ByteBuffer.wrap(it).putLong(offset, value) }
            assertThrows(IllegalArgumentException::class.java) { QcowImage.validate(bytes) }
        }
        assertThrows(IllegalArgumentException::class.java) { QcowImage.validate(header().also { ByteBuffer.wrap(it).putInt(32, 1) }) }
        assertThrows(IllegalArgumentException::class.java) { QcowImage.validate(header().copyOf(80)) }
        assertThrows(IllegalArgumentException::class.java) { QcowImage.validate(header(1)) }
    }
    @Test fun supportsCompressionAndExtendedL2() {
        assertEquals(8L * 1024 * 1024, QcowImage.validate(header().also { ByteBuffer.wrap(it).putLong(72, 24L) }))
    }
}
