/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal.new2.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import com.google.gson.Gson
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Metadata is separate from AVF configuration. The original Debian directory is never moved. */
data class VmProfile(val id: String, val name: String) {
    val isDefault: Boolean get() = id == "default"
}

object VmProfiles {
    private lateinit var context: Context
    private val gson = Gson()
    private val _profiles = MutableStateFlow(listOf(VmProfile("default", "默认 Debian")))
    val profiles = _profiles.asStateFlow()
    private val _selected = MutableStateFlow(_profiles.value.first())
    val selected = _selected.asStateFlow()
    private val root get() = File(context.filesDir, "virtual-machines")
    private val preferences get() = context.getSharedPreferences("vm-profiles", Context.MODE_PRIVATE)

    @Synchronized fun initialize(app: Context) {
        if (::context.isInitialized) return
        context = app.applicationContext
        root.mkdirs()
        // Only unfinished imports owned by this importer may be removed after process death.
        root.listFiles()?.filter { it.name.startsWith(".import-") }?.forEach { it.deleteRecursively() }
        refresh()
        _selected.value = _profiles.value.firstOrNull { it.id == preferences.getString("selected", "default") }
            ?: _profiles.value.first()
    }

    private fun refresh() {
        val custom = root.listFiles().orEmpty().filter { !it.name.startsWith(".") }.mapNotNull { dir ->
            runCatching { gson.fromJson(AtomicFile(File(dir, "profile.json")).openRead().bufferedReader().use { it.readText() }, VmProfile::class.java) }
                .getOrNull()?.takeIf { it.id == dir.name && !it.isDefault && !it.name.isNullOrBlank() }
        }.sortedBy { it.name }
        _profiles.value = listOf(VmProfile("default", "默认 Debian")) + custom
    }

    fun directory(profile: VmProfile): File {
        require(!profile.isDefault && profile.id.matches(Regex("[a-f0-9-]{36}")))
        return File(root, profile.id)
    }

    @Synchronized fun select(profile: VmProfile) {
        require(_profiles.value.any { it == profile }) { "虚拟机配置不存在" }
        check(preferences.edit().putString("selected", profile.id).commit()) { "无法保存当前虚拟机" }
        _selected.value = profile
    }

    fun existingBootloader(): File? = File(context.filesDir, "console-probe/u-boot.bin").takeIf { it.isFile && it.length() > 0 }

    fun displayName(uri: Uri): String = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: "自定义镜像"

    suspend fun importImage(uri: Uri, bootloader: Uri?, name: String, progress: (Long) -> Unit): VmProfile = withContext(Dispatchers.IO) {
        require(name.trim().isNotEmpty()) { "请输入虚拟机名称" }
        val profile = VmProfile(UUID.randomUUID().toString(), name.trim().take(80))
        val staging = File(root, ".import-${profile.id}")
        check(staging.mkdir()) { "无法创建导入目录" }
        try {
            val firmware = File(staging, "u-boot.bin")
            if (bootloader != null) {
                context.contentResolver.openInputStream(bootloader)!!.use { input ->
                    firmware.outputStream().use { output ->
                        val bytes = input.readNBytes(16 * 1024 * 1024 + 1)
                        require(bytes.isNotEmpty() && bytes.size <= 16 * 1024 * 1024) { "U-Boot 文件大小无效（上限 16 MiB）" }
                        output.write(bytes)
                    }
                }
            } else {
                checkNotNull(existingBootloader()) { "请先选择适用于 crosvm ARM64 的 U-Boot 文件" }.copyTo(firmware)
            }
            var copied = 0L
            context.contentResolver.openInputStream(uri)!!.use { input ->
                val header = input.readNBytes(64 * 1024)
                RawDiskFormat.validate(header)
                RandomAccessFile(File(staging, "system.raw"), "rw").use { output ->
                    output.write(header)
                    copied += header.size
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        check(staging.usableSpace > 64L * 1024 * 1024) { "存储空间不足，导入已取消" }
                        // Preserve sparse zero regions instead of allocating the image's full logical size.
                        if ((0 until count).all { buffer[it] == 0.toByte() }) output.seek(output.filePointer + count)
                        else output.write(buffer, 0, count)
                        copied += count
                        progress(copied)
                    }
                    require(copied >= 1024 * 1024 && copied % 512 == 0L) { "磁盘至少应为 1 MiB，且大小须为 512 字节的整数倍" }
                    output.setLength(copied)
                    output.fd.sync()
                }
            }
            currentCoroutineContext().ensureActive()
            val metadata = AtomicFile(File(staging, "profile.json"))
            val stream = metadata.startWrite()
            try { stream.write(gson.toJson(profile).toByteArray()); metadata.finishWrite(stream) }
            catch (e: Exception) { metadata.failWrite(stream); throw e }
            check(staging.renameTo(directory(profile))) { "无法完成镜像导入" }
            synchronized(this@VmProfiles) { refresh() }
            profile
        } finally {
            staging.deleteRecursively()
        }
    }
}
