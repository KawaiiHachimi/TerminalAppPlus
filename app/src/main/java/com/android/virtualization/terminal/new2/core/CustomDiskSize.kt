/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal.new2.core

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
        require(value != null && value > 0 && value <= Long.MAX_VALUE / GIB) { "请输入有效的整数容量（GiB）" }
        return value * GIB
    }

    // Caller holds the VM lifecycle lock; re-resolve the saved config before mutation.
    fun grow(config: String, directory: File, path: String, bytes: Long) {
        require(disks(config, directory).any { it.path == path }) { "目标磁盘已改变或不支持扩容" }
        val file = File(path.replace("\$PAYLOAD_DIR", directory.canonicalPath)).canonicalFile
        RandomAccessFile(file, "rw").use { disk ->
            require(bytes > disk.length()) { "目标容量必须大于当前容量，不支持缩小磁盘" }
            require(bytes % 512 == 0L) { "容量必须按 512 字节对齐" }
            val header = ByteArray(minOf(disk.length(), 65536L).toInt())
            disk.readFully(header)
            RawDiskFormat.validate(header, requireBootSector = false)
            disk.setLength(bytes)
            disk.fd.sync()
        }
    }
}
