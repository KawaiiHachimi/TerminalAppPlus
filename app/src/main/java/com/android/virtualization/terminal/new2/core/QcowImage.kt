/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal.new2.core

import com.android.virtualization.terminal.AppStrings
import com.android.virtualization.terminal.R
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
        require(isQcow(header) && header.size >= 72) { AppStrings.get(R.string.plus_qcow_header_incomplete) }
        val data = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val version = data.getInt(4)
        require(version == 2 || version == 3) { AppStrings.get(R.string.plus_qcow_version_unsupported) }
        require(data.getLong(8) == 0L && data.getInt(16) == 0) { AppStrings.get(R.string.plus_qcow_backing_file) }
        require(data.getInt(32) == 0) { AppStrings.get(R.string.plus_qcow_encrypted) }
        if (version == 3) {
            require(header.size >= 104) { AppStrings.get(R.string.plus_qcow_v3_header_incomplete) }
            val features = data.getLong(72)
            require(features and 4L == 0L) { AppStrings.get(R.string.plus_qcow_external_data) }
            require(features and 2L == 0L) { AppStrings.get(R.string.plus_qcow_corrupt) }
            require(features and 25L.inv() == 0L) { AppStrings.get(R.string.plus_qcow_features) }
        }
        val size = data.getLong(24)
        require(size >= 1024 * 1024 && size % 512 == 0L) { AppStrings.get(R.string.plus_qcow_invalid_size) }
        return size
    }
}
