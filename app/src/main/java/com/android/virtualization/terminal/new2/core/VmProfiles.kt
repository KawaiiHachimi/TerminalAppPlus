/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal.new2.core

import com.android.virtualization.terminal.AppStrings
import com.android.virtualization.terminal.R
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import com.android.virtualization.terminal.InstalledImage
import com.android.virtualization.terminal.GuestKernelCompat
import com.android.virtualization.terminal.new2.ui.main.SettingsViewModel
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.File
import java.io.ByteArrayInputStream
import java.io.SequenceInputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class VmProfile(val id: String, val name: String, val managedDebian: Boolean = false,
    val startupScreen: String? = null, val revision: Long = 0) {
    val isDefault: Boolean get() = id == "default"
    val isManaged: Boolean get() = isDefault || managedDebian
    val screen: String get() = startupScreen ?: if (isManaged) "ttyd" else "console"
}

object VmProfiles {
    private lateinit var context: Context
    private val gson = Gson()
    private val _profiles = MutableStateFlow(listOf(VmProfile("default", AppStrings.get(R.string.plus_default_debian))))
    val profiles = _profiles.asStateFlow()
    private val _selected = MutableStateFlow(_profiles.value.first())
    val selected = _selected.asStateFlow()
    private val _officialRequested = MutableStateFlow(false)
    val officialRequested = _officialRequested.asStateFlow()
    fun requestOfficial(value: Boolean = true) { _officialRequested.value = value }
    private val root get() = File(context.filesDir, "virtual-machines")
    private val preferences get() = context.getSharedPreferences("vm-profiles", Context.MODE_PRIVATE)

    @Synchronized fun initialize(app: Context) {
        if (::context.isInitialized) return
        context = app.applicationContext
        root.mkdirs()
        root.listFiles()?.filter { it.name.startsWith(".import-") }?.forEach { it.deleteRecursively() }
        refresh()
        _selected.value = _profiles.value.firstOrNull { it.id == preferences.getString("selected", "default") } ?: _profiles.value.first()
    }
    @Synchronized private fun refresh() {
        val stored = root.listFiles().orEmpty().filter { !it.name.startsWith(".") }.mapNotNull { dir ->
            runCatching { gson.fromJson(AtomicFile(File(dir, "profile.json")).openRead().bufferedReader().use { it.readText() }, VmProfile::class.java) }
                .getOrNull()?.takeIf { it.id == dir.name && !it.name.isNullOrBlank() }
        }
        _profiles.value = listOf(stored.firstOrNull { it.isDefault } ?: VmProfile("default", AppStrings.get(R.string.plus_default_debian))) + stored.filter { !it.isDefault }.sortedBy { it.name }
        _profiles.value.firstOrNull { it.id == _selected.value.id }?.let { _selected.value = it }
    }
    fun directory(profile: VmProfile): File {
        require(profile.isDefault || profile.id.matches(Regex("[a-f0-9-]{36}")))
        return File(root, profile.id)
    }
    fun payloadDirectory(profile: VmProfile): File = when {
        profile.isDefault -> InstalledImage.getDefault(context).installDir.toFile()
        profile.isManaged -> File(directory(profile), "payload")
        else -> directory(profile)
    }
    fun isInstalled(profile: VmProfile): Boolean = if (profile.isDefault) InstalledImage.getDefault(context).isInstalled() else directory(profile).isDirectory
    @Synchronized fun select(profile: VmProfile) {
        val current = _profiles.value.firstOrNull { it.id == profile.id } ?: error(AppStrings.get(R.string.plus_vm_config_missing))
        check(preferences.edit().putString("selected", current.id).commit()) { AppStrings.get(R.string.plus_selected_vm_save_failed) }
        _selected.value = current
    }
    private fun atomicWrite(file: File, text: String) {
        file.parentFile!!.mkdirs()
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(text.toByteArray()); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }
    private fun store(profile: VmProfile) {
        atomicWrite(File(directory(profile), "profile.json"), gson.toJson(profile)); refresh()
    }
    @Synchronized fun rename(profile: VmProfile, name: String) {
        require(name.trim().isNotEmpty()) { AppStrings.get(R.string.plus_name_required) }
        store(profile.copy(name = name.trim().take(80)))
    }
    @Synchronized fun setScreen(profile: VmProfile, screen: String) {
        require(screen in listOf("ttyd", "console", "display"))
        store(profile.copy(startupScreen = screen))
    }
    fun defaultBootloader(target: File) {
        val system = File("/apex/com.android.virt/etc/u-boot.bin")
        if (system.isFile && system.canRead()) system.copyTo(target, overwrite = true)
        else context.assets.open("bootloader/u-boot.bin").use { input -> target.outputStream().use { input.copyTo(it) } }
    }
    fun displayName(uri: Uri): String = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: AppStrings.get(R.string.plus_custom_image)

    @Synchronized fun readConfig(profile: VmProfile): String {
        val file = File(directory(profile), "vm_config.json")
        if (file.exists()) return file.readText()
        val text = if (profile.isManaged) {
            val original = JsonParser.parseString(File(payloadDirectory(profile), "vm_config.json").readText()).asJsonObject
            original.remove("platform_version")
            original.addProperty("connect_console", false)
            original.addProperty("console_out", true)
            if (GuestKernelCompat.required) original.addProperty("console_input_device", "hvc0")
            if (profile.isDefault) original.addProperty("memory_mib", context.getSharedPreferences(SettingsViewModel.PREFS_NAME, Context.MODE_PRIVATE).getInt(SettingsViewModel.KEY_MEMORY_MIB, SettingsViewModel.DEFAULT_MEMORY_MIB))
            original.addProperty("name", if (profile.isDefault) "debian" else "plus-${profile.id}")
            VmConfigDocument.format(original)
        } else defaultConfig(profile.id, false, "")
        atomicWrite(file, text)
        return text
    }
    @Synchronized fun saveConfig(profile: VmProfile, text: String) {
        val json = VmConfigDocument.parse(text)
        val expectedName = if (profile.isDefault) "debian" else "plus-${profile.id}"
        require(json.get("name").asString == expectedName) { AppStrings.get(R.string.plus_internal_vm_name) }
        VmConfigDocument.validateFiles(json, payloadDirectory(profile))
        val maxMemory = (context.getSystemService(android.app.ActivityManager::class.java).let { manager -> android.app.ActivityManager.MemoryInfo().also(manager::getMemoryInfo).totalMem } / (1024 * 1024)).toInt()
        require(json.get("memory_mib").asInt <= maxMemory) { AppStrings.get(R.string.plus_memory_host_limit , maxMemory) }
        val file = File(directory(profile), "vm_config.json")
        if (file.exists()) atomicWrite(File(directory(profile), "vm_config.previous.json"), file.readText())
        atomicWrite(file, VmConfigDocument.format(json))
        store(profile.copy(revision = profile.revision + 1))
    }
    fun restoreConfig(profile: VmProfile): String {
        val dir = directory(profile)
        val file = File(dir, "vm_config.last-good.json").takeIf { it.isFile } ?: File(dir, "vm_config.previous.json")
        require(file.isFile) { AppStrings.get(R.string.plus_no_configuration_backup) }
        return file.readText() // Draft only: user validates and saves explicitly.
    }
    fun markStarted(profile: VmProfile, text: String) { atomicWrite(File(directory(profile), "vm_config.last-good.json"), text) }

    private fun defaultConfig(id: String, direct: Boolean, params: String, initrd: Boolean = false): String {
        val json = JsonParser.parseString("""{"name":"plus-$id","protected":false,"cpu_topology":"match_host","memory_mib":2048,"debuggable":true,"console_out":true,"connect_console":false,"console_input_device":"ttyS0","network":true,"gpu":{"backend":"2d"},"disks":[{"image":"${'$'}PAYLOAD_DIR/system.raw","writable":true}]}""").asJsonObject
        json.addProperty(if (direct) "kernel" else "bootloader", "\$PAYLOAD_DIR/" + if (direct) "vmlinuz" else "u-boot.bin")
        if (direct) { json.addProperty("params", params); if (initrd) json.addProperty("initrd", "\$PAYLOAD_DIR/initrd.img") }
        return VmConfigDocument.format(json)
    }
    private suspend fun smallFile(uri: Uri, target: File, max: Int) {
        currentCoroutineContext().ensureActive()
        context.contentResolver.openInputStream(uri)!!.use { input ->
            val bytes = input.readNBytes(max + 1)
            require(bytes.isNotEmpty() && bytes.size <= max) { AppStrings.get(R.string.plus_invalid_file_size , target.name) }
            target.writeBytes(bytes)
        }
    }
    suspend fun importDebianArchive(uri: Uri, name: String, progress: (Long) -> Unit): VmProfile = withContext(Dispatchers.IO) {
        require(name.trim().isNotEmpty()) { AppStrings.get(R.string.plus_vm_name_required) }
        val profile = VmProfile(UUID.randomUUID().toString(), name.trim().take(80), managedDebian = true)
        val staging = File(root, ".import-${profile.id}")
        check(staging.mkdir()) { AppStrings.get(R.string.plus_import_directory_failed) }
        try {
            val payload = File(staging, "payload")
            context.contentResolver.openInputStream(uri)!!.use { DebianImageArchive.extract(it, payload, progress) }
            if (!File(payload, "cidata.iso").exists()) {
                for (asset in listOf("cidata.iso", InstalledImage.CIDATA_BUILD_ID_FILENAME)) {
                    context.assets.open(asset).use { input -> File(payload, asset).outputStream().use { input.copyTo(it) } }
                }
            }
            val config = DebianImageArchive.configuration(payload, profile.id)
            File(payload, InstalledImage.MARKER_FILENAME).writeText("")
            atomicWrite(File(staging, "vm_config.json"), config)
            atomicWrite(File(staging, "profile.json"), gson.toJson(profile))
            currentCoroutineContext().ensureActive()
            check(staging.renameTo(directory(profile))) { AppStrings.get(R.string.plus_image_import_failed) }
            refresh(); profile
        } finally { staging.deleteRecursively() }
    }
    internal suspend fun inspectQcow(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)!!.use { source ->
            QcowImage.decoded(source).use { input ->
                val header = input.readNBytes(104)
                QcowImage.isQcow(header).also { if (it) QcowImage.validate(header) }
            }
        }
    }
    internal suspend fun importImage(uri: Uri, bootloader: Uri?, name: String, kernel: Uri? = null, initrd: Uri? = null, params: String = "console=ttyS0 root=/dev/vda1 rw", allowConversion: Boolean = false, cloudInit: CloudInitConfig? = null, status: (String) -> Unit = {}, progress: (Long) -> Unit): VmProfile = withContext(Dispatchers.IO) {
        require(name.trim().isNotEmpty()) { AppStrings.get(R.string.plus_vm_name_required) }
        val profile = VmProfile(UUID.randomUUID().toString(), name.trim().take(80))
        val staging = File(root, ".import-${profile.id}")
        check(staging.mkdir()) { AppStrings.get(R.string.plus_import_directory_failed) }
        try {
            if (kernel != null) {
                smallFile(kernel, File(staging, "vmlinuz"), 128 * 1024 * 1024)
                if (initrd != null) smallFile(initrd, File(staging, "initrd.img"), 256 * 1024 * 1024)
            } else if (bootloader != null) smallFile(bootloader, File(staging, "u-boot.bin"), 16 * 1024 * 1024)
            else defaultBootloader(File(staging, "u-boot.bin"))
            val disk = File(staging, "system.raw")
            var convert = false
            var convertedSize = 0L
            val qcow = File(staging, "source.qcow2")
            context.contentResolver.openInputStream(uri)!!.use { source ->
                QcowImage.decoded(source).use { input ->
                val header = input.readNBytes(64 * 1024)
                convert = QcowImage.isQcow(header)
                if (convert) {
                    require(allowConversion) { AppStrings.get(R.string.plus_qcow_confirmation_required) }
                    convertedSize = QcowImage.validate(header)
                    status(AppStrings.get(R.string.plus_copying_qcow))
                } else RawDiskFormat.validate(header, requireBootSector = kernel == null)
                SparseFiles.copyStream(SequenceInputStream(ByteArrayInputStream(header), input), if (convert) qcow else disk, progress)
                }
            }
            if (convert) {
                status(AppStrings.get(R.string.plus_converting_qcow))
                QemuImageConverter.convert(context, qcow, disk, status)
                check(disk.length() == convertedSize) { AppStrings.get(R.string.plus_qcow_size_mismatch) }
                disk.inputStream().use { RawDiskFormat.validate(it.readNBytes(65536), requireBootSector = kernel == null) }
                check(qcow.delete()) { AppStrings.get(R.string.plus_conversion_cleanup_failed) }
                status(AppStrings.get(R.string.plus_conversion_finishing))
            }
            require(disk.length() >= 1024 * 1024 && disk.length() % 512 == 0L) { AppStrings.get(R.string.plus_raw_size_invalid) }
            CloudInit.save(context, staging, cloudInit?.copy(instanceId = "terminal-plus-${profile.id}"))
            atomicWrite(File(staging, "vm_config.json"), defaultConfig(profile.id, kernel != null, params, initrd != null))
            atomicWrite(File(staging, "profile.json"), gson.toJson(profile))
            currentCoroutineContext().ensureActive()
            check(staging.renameTo(directory(profile))) { AppStrings.get(R.string.plus_image_import_failed) }
            refresh(); profile
        } finally { staging.deleteRecursively() }
    }
    internal fun customDisks(profile: VmProfile): List<CustomDiskSize.Disk> {
        require(!profile.isManaged) { AppStrings.get(R.string.plus_managed_disk_size) }
        return CustomDiskSize.disks(readConfig(profile), payloadDirectory(profile))
    }
    internal suspend fun growDisk(profile: VmProfile, path: String, bytes: Long) = withContext(Dispatchers.IO) {
        require(!profile.isManaged) { AppStrings.get(R.string.plus_managed_disk_size) }
        VmController.withStoppedProfile(profile) {
            CustomDiskSize.grow(readConfig(profile), payloadDirectory(profile), path, bytes)
        }
    }

    suspend fun clone(profile: VmProfile, progress: (Long) -> Unit): VmProfile = withContext(Dispatchers.IO) {
        VmController.withStoppedProfile(profile) {
            require(isInstalled(profile)) { AppStrings.get(R.string.plus_system_not_installed) }
            val copy = profile.copy(id = UUID.randomUUID().toString(), name = profile.name + AppStrings.get(R.string.plus_copy_suffix), managedDebian = profile.isManaged, revision = 0)
            val stage = File(root, ".import-${copy.id}").also { check(it.mkdir()) }
            try {
                val source = payloadDirectory(profile)
                val destination = if (copy.isManaged) File(stage, "payload").also { it.mkdir() } else stage
                var copied = 0L
                for (file in source.walkTopDown()) {
                    currentCoroutineContext().ensureActive()
                    val relative = file.relativeTo(source)
                    if (!copy.isManaged && relative.path in listOf("profile.json", "vm_config.json", "vm_config.last-good.json", "vm_config.previous.json")) continue
                    val target = File(destination, relative.path)
                    if (file.isDirectory) target.mkdirs() else {
                        SparseFiles.copyFile(file, target) { progress(copied + it) }
                        copied += file.length()
                    }
                }
                val config = JsonParser.parseString(readConfig(profile).replace(source.absolutePath, "\$PAYLOAD_DIR")).asJsonObject
                if (!copy.isManaged) {
                    if (CloudInit.locked(source)) File(stage, "cloud-init.booted").writeText("")
                    else CloudInit.read(context, source)?.let { CloudInit.save(context, stage, it.copy(instanceId = "terminal-plus-${copy.id}")) }
                }
                config.addProperty("name", "plus-${copy.id}")
                atomicWrite(File(stage, "vm_config.json"), VmConfigDocument.format(config))
                atomicWrite(File(stage, "profile.json"), gson.toJson(copy))
                check(stage.renameTo(directory(copy))) { AppStrings.get(R.string.plus_clone_failed) }
                refresh(); copy
            } finally { stage.deleteRecursively() }
        }
    }
    suspend fun delete(profile: VmProfile) = withContext(Dispatchers.IO) {
        VmController.withStoppedProfile(profile) {
            if (profile.isDefault) {
                Installer.uninstall(false)
                check(!InstalledImage.getDefault(context).isInstalled()) { AppStrings.get(R.string.plus_default_delete_failed) }
            }
            check(!directory(profile).exists() || directory(profile).deleteRecursively()) { AppStrings.get(R.string.plus_files_delete_failed) }
            refresh()
            if (_selected.value.id == profile.id) select(_profiles.value.firstOrNull { !it.isDefault } ?: _profiles.value.first())
        }
    }
}
