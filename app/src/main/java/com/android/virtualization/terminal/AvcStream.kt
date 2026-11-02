/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal

import java.io.DataInputStream

/** PAV1 experimental stream. Bounds are checked before allocating codec input. */
internal object AvcStream {
    const val MAX_PACKET = 4 * 1024 * 1024
    data class Size(val width: Int, val height: Int)
    fun header(input: DataInputStream): Size {
        check(input.readInt() == 0x50415631) { "Unsupported AVC stream" }
        val w = input.readInt(); val h = input.readInt()
        check(w in 2..4096 && h in 2..4096 && w % 2 == 0 && h % 2 == 0 && w.toLong() * h * 4 <= 32 * 1024 * 1024)
        return Size(w, h)
    }
    fun packet(input: DataInputStream): ByteArray {
        val length = input.readInt()
        check(length in 1..MAX_PACKET) { "Invalid AVC access unit length" }
        return ByteArray(length).also(input::readFully)
    }
    fun parameterSets(data: ByteArray): Map<Int, ByteArray> {
        val starts = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i + 3 < data.size) {
            val prefix = when {
                data[i] == 0.toByte() && data[i+1] == 0.toByte() && data[i+2] == 1.toByte() -> 3
                i + 4 < data.size && data[i] == 0.toByte() && data[i+1] == 0.toByte() && data[i+2] == 0.toByte() && data[i+3] == 1.toByte() -> 4
                else -> 0
            }
            if (prefix > 0) { starts.add(i to prefix); i += prefix } else i++
        }
        return starts.mapIndexedNotNull { n, (start, prefix) ->
            val type = data[start + prefix].toInt() and 31
            if (type !in 7..8) null else type to data.copyOfRange(start, starts.getOrNull(n+1)?.first ?: data.size)
        }.toMap()
    }
}
