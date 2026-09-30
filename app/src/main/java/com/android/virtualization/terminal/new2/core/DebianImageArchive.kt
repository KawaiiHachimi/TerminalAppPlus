/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal.new2.core

import com.android.virtualization.terminal.AppStrings
import com.android.virtualization.terminal.R
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
                require(++entries <= 4096) { AppStrings.get(R.string.plus_archive_too_many_files) }
                val target = File(base, entry.name).canonicalFile
                require(!entry.name.startsWith('/') && (target == base || target.path.startsWith(base.path + File.separator))) { AppStrings.get(R.string.plus_archive_path_escape) }
                require(entry.isDirectory || (entry.isFile && !entry.isSymbolicLink && !entry.isLink)) { AppStrings.get(R.string.plus_archive_special_files) }
                require(seen.add(target.path)) { AppStrings.get(R.string.plus_archive_duplicate_path) }
                require(tar.canReadEntryData(entry)) { AppStrings.get(R.string.plus_archive_entry_unreadable , entry.name) }
                if (entry.isDirectory) { target.mkdirs(); continue }
                require(target != base) { AppStrings.get(R.string.plus_invalid_file_path) }
                if (target.name == "vm_config.json") require(entry.size <= 256 * 1024) { AppStrings.get(R.string.plus_config_too_large) }
                target.parentFile!!.mkdirs()
                SparseFiles.copyStream(tar, target) { progress(total + it) }
                total += target.length()
            }
        }
        require(File(base, "vm_config.json").isFile && File(base, "root_part").isFile) {
            AppStrings.get(R.string.plus_android_archive_required)
        }
    }

    fun configuration(directory: File, id: String): String {
        val file = File(directory, "vm_config.json")
        require(file.length() <= 256 * 1024) { AppStrings.get(R.string.plus_config_too_large) }
        val json = com.google.gson.JsonParser.parseString(file.readText()).asJsonObject
        json.remove("platform_version")
        json.addProperty("name", "plus-$id")
        json.addProperty("console_out", true)
        json.addProperty("connect_console", false)
        val parsed = VmConfigDocument.parse(VmConfigDocument.format(json))
        // Imported bundles must be self-contained, including read-only disks and kernels.
        fun checkPath(value: String) {
            require(value.startsWith("\$PAYLOAD_DIR/")) { AppStrings.get(R.string.plus_archive_payload_path , value) }
            val base = directory.canonicalFile
            val target = File(value.replace("\$PAYLOAD_DIR", base.path)).canonicalFile
            require(target.path.startsWith(base.path + File.separator)) { AppStrings.get(R.string.plus_archive_config_path_escape) }
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
