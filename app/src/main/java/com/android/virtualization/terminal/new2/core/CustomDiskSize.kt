/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal.new2.core

import com.android.virtualization.terminal.AppStrings
import com.android.virtualization.terminal.R
import com.google.gson.JsonParser
import java.io.File
import java.io.RandomAccessFile

internal object CustomDiskSize {
    const val GIB = 1024L * 1024 * 1024
    data class Disk(val path: String, val bytes: Long)

    fun disks(config: String, directory: File): List<Disk> {
        val base = directory.canonicalFile
        return JsonParser.parseString(config).asJsonObject.getAsJsonArray("disks")
            .mapNotNull { element ->
                val disk = element.asJsonObject
                if (disk.get("writable")?.asBoolean != true || !disk.has("image")) return@mapNotNull null
                val path = disk.get("image").asString
                val file = File(path.replace("\$PAYLOAD_DIR", base.path)).canonicalFile
                if (!file.path.startsWith(base.path + File.separator) || !file.isFile) return@mapNotNull null
                Disk(path, file.length())
            }.distinctBy { File(it.path.replace("\$PAYLOAD_DIR", base.path)).canonicalPath }
    }

    fun targetBytes(gib: String): Long {
        val value = gib.trim().toLongOrNull()
        require(value != null && value > 0 && value <= Long.MAX_VALUE / GIB) { AppStrings.get(R.string.plus_invalid_disk_capacity) }
        return value * GIB
    }

    // Caller holds the VM lifecycle lock; re-resolve the saved config before mutation.
    fun grow(config: String, directory: File, path: String, bytes: Long) {
        require(disks(config, directory).any { it.path == path }) { AppStrings.get(R.string.plus_resize_disk_changed) }
        val file = File(path.replace("\$PAYLOAD_DIR", directory.canonicalPath)).canonicalFile
        RandomAccessFile(file, "rw").use { disk ->
            require(bytes > disk.length()) { AppStrings.get(R.string.plus_resize_must_grow) }
            require(bytes % 512 == 0L) { AppStrings.get(R.string.plus_size_sector_alignment) }
            val header = ByteArray(minOf(disk.length(), 65536L).toInt())
            disk.readFully(header)
            RawDiskFormat.validate(header, requireBootSector = false)
            disk.setLength(bytes)
            disk.fd.sync()
        }
    }
}
