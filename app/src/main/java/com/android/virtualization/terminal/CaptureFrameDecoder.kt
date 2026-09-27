/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal

import java.util.zip.Inflater

internal class CaptureFrameDecoder : AutoCloseable {
    private val inflater = Inflater()
    private val lz4 = net.jpountz.lz4.LZ4Factory.fastestJavaInstance().safeDecompressor()
    fun decode(source: ByteArray, length: Int, target: ByteArray, codec: Int) {
        when (codec) {
            1 -> inflate(source, length, target)
            2 -> check(lz4.decompress(source, 0, length, target, 0, target.size) == target.size) {
                "LZ4 screen frame has an invalid size"
            }
            else -> error("Unsupported screen codec")
        }
    }
    fun inflate(source: ByteArray, length: Int, target: ByteArray) {
        inflater.reset()
        inflater.setInput(source, 0, length)
        var offset = 0
        while (offset < target.size && !inflater.finished()) {
            val count = inflater.inflate(target, offset, target.size - offset)
            check(count > 0) { "Incomplete compressed screen frame" }
            offset += count
        }
        check(offset == target.size && inflater.finished() && inflater.remaining == 0) {
            "Compressed screen frame has an invalid size"
        }
    }
    override fun close() { inflater.end() }
}
