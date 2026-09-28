/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal.new2.core

import java.io.File
import java.io.InputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.util.zip.GZIPInputStream

/** Extract an AOSP-style bundle into a fresh staging directory, never into an existing VM. */
internal object DebianImageArchive {
    suspend fun extract(input: InputStream, directory: File, progress: (Long) -> Unit) {
        directory.mkdirs()
        val base = directory.canonicalFile
        val seen = mutableSetOf<String>()
        var total = 0L
        var entries = 0
        TarArchiveInputStream(GZIPInputStream(input.buffered())).use { tar ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = (tar.nextEntry ?: break) as org.apache.commons.compress.archivers.tar.TarArchiveEntry
                require(++entries <= 4096) { "镜像包文件数量过多" }
                val target = File(base, entry.name).canonicalFile
                require(!entry.name.startsWith('/') && (target == base || target.path.startsWith(base.path + File.separator))) { "镜像包包含越界路径" }
                require(entry.isDirectory || (entry.isFile && !entry.isSymbolicLink && !entry.isLink)) { "镜像包不支持链接或特殊文件" }
                require(seen.add(target.path)) { "镜像包包含重复路径" }
                require(tar.canReadEntryData(entry)) { "无法读取镜像包条目：${entry.name}" }
                if (entry.isDirectory) { target.mkdirs(); continue }
                require(target != base) { "无效文件路径" }
                if (target.name == "vm_config.json") require(entry.size <= 256 * 1024) { "配置超过 256 KiB" }
                target.parentFile!!.mkdirs()
                SparseFiles.copyStream(tar, target) { progress(total + it) }
                total += target.length()
            }
        }
        require(File(base, "vm_config.json").isFile && File(base, "root_part").isFile) {
            "请选择包含 vm_config.json、root_part 和内核的 Android Debian 镜像包（images.tar.gz）；单独的 img.gz 请使用磁盘镜像模式"
        }
    }

    fun configuration(directory: File, id: String): String {
        val file = File(directory, "vm_config.json")
        require(file.length() <= 256 * 1024) { "配置超过 256 KiB" }
        val json = com.google.gson.JsonParser.parseString(file.readText()).asJsonObject
        json.remove("platform_version")
        json.addProperty("name", "plus-$id")
        json.addProperty("console_out", true)
        json.addProperty("connect_console", false)
        val parsed = VmConfigDocument.parse(VmConfigDocument.format(json))
        // Imported bundles must be self-contained, including read-only disks and kernels.
        fun checkPath(value: String) {
            require(value.startsWith("\$PAYLOAD_DIR/")) { "镜像包文件路径必须位于 \$PAYLOAD_DIR 内：$value" }
            val base = directory.canonicalFile
            val target = File(value.replace("\$PAYLOAD_DIR", base.path)).canonicalFile
            require(target.path.startsWith(base.path + File.separator)) { "镜像包配置引用越界文件" }
        }
        listOf("kernel", "initrd", "bootloader").forEach { key -> parsed.get(key)?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }?.let(::checkPath) }
        parsed.getAsJsonArray("disks").forEach { item ->
            val disk = item.asJsonObject
            disk.get("image")?.let { checkPath(it.asString) }
            disk.getAsJsonArray("partitions")?.forEach { checkPath(it.asJsonObject.get("path").asString) }
        }
        VmConfigDocument.validateFiles(parsed, directory)
        return VmConfigDocument.format(parsed)
    }
}
