/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal.new2.core

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

internal object QcowImage {
    fun decoded(input: InputStream): InputStream {
        val source = input.buffered()
        source.mark(2)
        val gzip = source.read() == 0x1f && source.read() == 0x8b
        source.reset()
        return if (gzip) GZIPInputStream(source) else source
    }
    fun isQcow(header: ByteArray): Boolean = header.size >= 4 &&
        header[0] == 0x51.toByte() && header[1] == 0x46.toByte() && header[2] == 0x49.toByte() && header[3] == 0xfb.toByte()

    fun validate(header: ByteArray): Long {
        require(isQcow(header) && header.size >= 72) { "qcow2 文件头不完整" }
        val data = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val version = data.getInt(4)
        require(version == 2 || version == 3) { "仅支持 qcow2 v2/v3" }
        require(data.getLong(8) == 0L && data.getInt(16) == 0) { "此镜像依赖 backing file，请先在电脑上合并为独立镜像" }
        require(data.getInt(32) == 0) { "暂不支持加密 qcow2 镜像" }
        if (version == 3) {
            require(header.size >= 104) { "qcow2 v3 文件头不完整" }
            val features = data.getLong(72)
            require(features and 4L == 0L) { "暂不支持使用外部数据文件的 qcow2 镜像" }
            require(features and 2L == 0L) { "qcow2 镜像已标记损坏，请先修复" }
            require(features and 25L.inv() == 0L) { "qcow2 包含不支持的格式特性" }
        }
        val size = data.getLong(24)
        require(size >= 1024 * 1024 && size % 512 == 0L) { "qcow2 虚拟磁盘容量无效" }
        return size
    }
}
