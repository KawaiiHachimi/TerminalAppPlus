/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal

/** Reads the compressed kallsyms layout emitted by Linux 6.12 for an ARM64 Image.
 * Deliberately excludes ELF/compressed kernels and absolute-percpu address encoding.
 */
internal object GuestKernelSymbols {
    private const val TARGET = "pkvm_init_hyp_services"
    private const val RET = 0xd65f03c0.toInt()
    private const val PACIASP = 0xd503233f.toInt()
    private const val BTIC = 0xd503245f.toInt()
    private fun ByteArray.u8(p: Int) = this[p].toInt() and 255
    private fun ByteArray.u16(p: Int) = u8(p) or (u8(p + 1) shl 8)
    private fun ByteArray.i32(p: Int) = u16(p) or (u16(p + 2) shl 16)
    private fun align(p: Int) = (p + 7) and -8

    fun patchOffset(data: ByteArray): Int {
        require(data.size >= 64 && data.i32(56) == 0x644d5241) { "Expected an uncompressed ARM64 Image" }
        require(data.i32(24) and 1 == 0) { "Big-endian kernel is unsupported" }
        val matches = mutableSetOf<Int>()
        // Literal digit tokens occur in their ASCII slots in the kallsyms dictionary.
        for (p in 64 until data.size - 20) {
            if (data.u8(p) != 48 || (0..9).any { data.u8(p + it * 2) != 48 + it || data.u8(p + it * 2 + 1) != 0 }) continue
            val result = runCatching { locate(data, p) }.getOrNull()
            if (result != null) matches.add(result)
        }
        check(matches.size == 1) { "Cannot uniquely locate $TARGET in supported kallsyms; original kernel unchanged" }
        val offset = matches.single()
        // Preserve a BTI landing pad when present; never skip a PAC instruction after execution.
        val entry = if (data.i32(offset) == BTIC) offset + 4 else offset
        check(data.i32(entry) == PACIASP || data.i32(entry) == RET) {
            "Unsupported $TARGET entry instruction; original kernel unchanged"
        }
        return entry
    }

    private fun locate(data: ByteArray, digits: Int): Int? {
        var table = digits
        repeat(48) {
            check(table >= 2 && data.u8(table - 1) == 0)
            table -= 2
            while (table >= 0 && data.u8(table) != 0) table--
            table++
        }
        val starts = IntArray(256)
        val tokens = Array(256) { "" }
        var end = table
        for (i in 0..255) {
            starts[i] = end - table
            val start = end
            while (end < data.size && data.u8(end) != 0) {
                check(data.u8(end) in 32..126 && end - start < 512)
                end++
            }
            check(end < data.size)
            tokens[i] = String(data, start, end - start, Charsets.US_ASCII)
            end++
        }
        val index = align(end)
        check(index + 512 < data.size && (0..255).all { data.u16(index + it * 2) == starts[it] })
        val offsets = index + 512
        // Count precedes names. Markers precede the dictionary and validate every 256 names.
        for (countAt in ((table - 16 * 1024 * 1024).coerceAtLeast(64) + 7 and -8) until table step 8) {
            val count = data.i32(countAt)
            if (count !in 256..1_000_000 || offsets.toLong() + count.toLong() * 4 + 8 > data.size) continue
            val markerBytes = ((count + 255) / 256) * 4
            val markers = table - align(markerBytes)
            val names = countAt + 8
            if (markers <= names || data.i32(markers) != 0 || markers - names < count * 2 || markers - names > count * 128) continue
            val found = runCatching {
                var pos = names
                val symbols = mutableMapOf<String, Pair<Char, Long>>()
                val wanted = setOf(TARGET, "_text", "_stext", "_etext", "_sinittext", "_einittext", "kallsyms_names", "kallsyms_token_table", "kallsyms_offsets")
                repeat(count) { n ->
                    if (n % 256 == 0) check(data.i32(markers + n / 256 * 4) == pos - names)
                    check(pos < markers)
                    var length = data.u8(pos++)
                    if (length and 128 != 0) {
                        check(pos < markers && data.u8(pos) < 128)
                        length = (length and 127) or (data.u8(pos++) shl 7)
                    }
                    check(length > 0 && pos + length <= markers)
                    val name = StringBuilder()
                    repeat(length) { name.append(tokens[data.u8(pos++)]); check(name.length <= 512) }
                    check(name.length > 1)
                    val key = name.substring(1)
                    if (key in wanted) {
                        check(key !in symbols)
                        symbols[key] = name[0] to (data.i32(offsets + n * 4).toLong() and 0xffffffffL)
                    }
                }
                check(align(pos) == markers)
                fun address(s: String) = checkNotNull(symbols[s]).second
                val base = address("_text")
                // Self-references prove the mapping from symbol addresses to file offsets.
                check(address("kallsyms_names") - base == names.toLong())
                check(address("kallsyms_token_table") - base == table.toLong())
                check(address("kallsyms_offsets") - base == offsets.toLong())
                val target = address(TARGET) - base
                check(symbols[TARGET]!!.first in "tT")
                check(listOf("_stext" to "_etext", "_sinittext" to "_einittext").any { (start, end) ->
                    target >= address(start) - base && target + 8 <= address(end) - base
                })
                check(target in 64L..(data.size - 8).toLong() && target % 4 == 0L)
                target.toInt()
            }.getOrNull()
            if (found != null) return found
        }
        return null
    }

    fun patch(data: ByteArray): Int {
        val offset = patchOffset(data)
        repeat(4) { data[offset + it] = (RET ushr (it * 8)).toByte() }
        return offset
    }
}
