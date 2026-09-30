package com.android.virtualization.terminal.new2.core

import org.junit.Assert.assertThrows
import org.junit.Test

class RawDiskFormatTest : com.android.virtualization.terminal.LocalizedResourcesTest() {
    private fun disk() = ByteArray(65536).apply { this[510] = 0x55; this[511] = 0xaa.toByte() }
    @Test fun acceptsMbrAndGptProtectiveMbr() {
        RawDiskFormat.validate(disk())
        RawDiskFormat.validate(disk().apply { this[450] = 0xee.toByte(); "EFI PART".toByteArray().copyInto(this, 512) })
    }
    @Test fun rejectsShortAndUnsignedFiles() {
        listOf(ByteArray(0), ByteArray(511), ByteArray(65536)).forEach {
            assertThrows(IllegalArgumentException::class.java) { RawDiskFormat.validate(it) }
        }
    }
    @Test fun rejectsQcowEvenWithMisleadingSignature() {
        val data = disk().apply { byteArrayOf(0x51, 0x46, 0x49, 0xfb.toByte()).copyInto(this) }
        assertThrows(IllegalArgumentException::class.java) { RawDiskFormat.validate(data) }
    }
    @Test fun rejectsCompressedArchives() {
        listOf(byteArrayOf(0x1f, 0x8b.toByte()), byteArrayOf(0xfd.toByte(), 0x37, 0x7a, 0x58, 0x5a), byteArrayOf(0x50, 0x4b)).forEach { magic ->
            assertThrows(IllegalArgumentException::class.java) { RawDiskFormat.validate(disk().apply { magic.copyInto(this) }) }
        }
    }
    @Test fun rejectsHybridIsoWithBootSignature() {
        val data = disk().apply { "CD001".toByteArray().copyInto(this, 32769) }
        assertThrows(IllegalArgumentException::class.java) { RawDiskFormat.validate(data) }
    }
}
