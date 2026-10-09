package com.android.virtualization.terminal

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class GuestKernelSymbolsTest {
    @Test fun rejectsInvalidImages() {
        for (data in listOf(ByteArray(0), ByteArray(128))) {
            assertThrows(IllegalArgumentException::class.java) { GuestKernelSymbols.patch(data) }
        }
    }
    private fun fixture(): ByteArray {
        val data = ByteArray(32768)
        fun put32(p: Int, v: Int) { repeat(4) { data[p + it] = (v ushr (it * 8)).toByte() } }
        fun align(p: Int) = (p + 7) and -8
        put32(56, 0x644d5241)
        put32(512, 0xd503233f.toInt())
        val names = listOf("T_text", "T_stext", "T_etext", "T_sinittext", "T_einittext",
            "Tpkvm_init_hyp_services", "Dkallsyms_names", "Dkallsyms_token_table", "Dkallsyms_offsets") +
            (9 until 256).map { "Tsymbol_$it" }
        put32(1024, names.size)
        var p = 1032
        for (s in names) { data[p++] = s.length.toByte(); for (c in s) data[p++] = c.code.toByte() }
        val markers = align(p)
        val table = align(markers + 4)
        p = table
        val indexes = IntArray(256)
        for (i in 0..255) {
            indexes[i] = p - table
            if (i in 32..126) data[p++] = i.toByte()
            data[p++] = 0
        }
        val index = align(p)
        for (i in 0..255) { data[index + i * 2] = indexes[i].toByte(); data[index + i * 2 + 1] = (indexes[i] shr 8).toByte() }
        val offsets = index + 512
        val addresses = listOf(0, 64, 768, 768, 1024, 512, 1032, table, offsets)
        for (i in names.indices) put32(offsets + i * 4, addresses.getOrElse(i) { 600 })
        return data
    }
    @Test fun resolvesSymbolAndIsIdempotent() {
        val data = fixture()
        val original = data.copyOf()
        assertEquals(512, GuestKernelSymbols.patch(data))
        assertEquals(512, GuestKernelSymbols.patch(data))
        assertTrue(data.indices.all { it in 512..515 || data[it] == original[it] })
    }
    @Test fun rejectsDamagedTablesAndUnexpectedInstructions() {
        for (position in listOf(512, 1025, 1032)) {
            val data = fixture()
            data[position] = 0
            val before = data.copyOf()
            assertThrows(IllegalStateException::class.java) { GuestKernelSymbols.patch(data) }
            assertArrayEquals(before, data)
        }
    }
    @Test fun preservesBtiLandingPad() {
        val data = fixture()
        byteArrayOf(0x5f, 0x24, 0x03, 0xd5.toByte()).copyInto(data, 512)
        byteArrayOf(0x3f, 0x23, 0x03, 0xd5.toByte()).copyInto(data, 516)
        assertEquals(516, GuestKernelSymbols.patch(data))
        assertEquals(0x5f, data[512].toInt())
    }
    @Test fun localKernelFixtures() {
        val paths = System.getenv("PLUS_KERNEL_FIXTURES") ?: return
        for (path in paths.split(':')) {
            val data = File(path).readBytes()
            val before = data.copyOf()
            val offset = GuestKernelSymbols.patch(data)
            println("Kernel fixture $path: patch at 0x${offset.toString(16)}")
            assertTrue(before.indices.all { it in offset until offset + 4 || before[it] == data[it] })
            assertEquals(offset, GuestKernelSymbols.patch(data))
            data[offset] = 0
            assertThrows(IllegalStateException::class.java) { GuestKernelSymbols.patch(data) }
        }
    }
}
