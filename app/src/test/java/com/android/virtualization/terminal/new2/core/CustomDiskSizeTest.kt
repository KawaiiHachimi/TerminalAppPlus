package com.android.virtualization.terminal.new2.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class CustomDiskSizeTest {
    private val config = """{"disks":[{"image":"${'$'}PAYLOAD_DIR/system.raw","writable":true},{"image":"${'$'}PAYLOAD_DIR/tools.iso","writable":false}]}"""
    @Test fun expandsWithoutChangingExistingBytesAndRefusesShrink() {
        val dir = Files.createTempDirectory("disk-grow").toFile()
        try {
            val file = File(dir, "system.raw")
            val original = ByteArray(1024) { (it % 251).toByte() }
            file.writeBytes(original)
            File(dir, "tools.iso").writeBytes(original)
            assertEquals(1, CustomDiskSize.disks(config, dir).size)
            CustomDiskSize.grow(config, dir, "${'$'}PAYLOAD_DIR/system.raw", 4096)
            assertEquals(4096L, file.length())
            assertArrayEquals(original, file.readBytes().copyOfRange(0, 1024))
            assertTrue(file.readBytes().drop(1024).all { it == 0.toByte() })
            assertThrows(IllegalArgumentException::class.java) { CustomDiskSize.grow(config, dir, "${'$'}PAYLOAD_DIR/system.raw", 512) }
            assertEquals(4096L, file.length())
            assertThrows(IllegalArgumentException::class.java) { CustomDiskSize.grow(config, dir, "${'$'}PAYLOAD_DIR/tools.iso", 4096) }
        } finally { dir.deleteRecursively() }
    }
    @Test fun excludesEscapingSymlinksAndStaleTargets() {
        val dir = Files.createTempDirectory("disk-link").toFile()
        val outside = Files.createTempFile("outside", ".raw").toFile()
        try {
            outside.writeBytes(ByteArray(1024))
            Files.createSymbolicLink(File(dir, "system.raw").toPath(), outside.toPath())
            assertTrue(CustomDiskSize.disks(config, dir).isEmpty())
            assertThrows(IllegalArgumentException::class.java) { CustomDiskSize.grow(config, dir, "${'$'}PAYLOAD_DIR/system.raw", 4096) }
            assertEquals(1024L, outside.length())
        } finally { File(dir, "system.raw").delete(); dir.deleteRecursively(); outside.delete() }
    }
    @Test fun validatesCapacityWithoutOverflow() {
        assertEquals(32 * CustomDiskSize.GIB, CustomDiskSize.targetBytes("32"))
        for (bad in listOf("0", "-1", "1.5", "", "9223372036854775807")) {
            assertThrows(IllegalArgumentException::class.java) { CustomDiskSize.targetBytes(bad) }
        }
    }
    @Test fun refusesContainerFormatsWithoutChangingFile() {
        val dir = Files.createTempDirectory("disk-format").toFile()
        try {
            val file = File(dir, "system.raw")
            val data = ByteArray(1024).apply { byteArrayOf(0x51, 0x46, 0x49, 0xfb.toByte()).copyInto(this) }
            file.writeBytes(data)
            assertThrows(IllegalArgumentException::class.java) { CustomDiskSize.grow(config, dir, "${'$'}PAYLOAD_DIR/system.raw", 4096) }
            assertArrayEquals(data, file.readBytes())
        } finally { dir.deleteRecursively() }
    }
}
