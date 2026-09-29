/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal.new2.ui.main

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.virtualization.terminal.new2.core.*
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class VmManagementViewModel(app: Application) : AndroidViewModel(app) {
    var image by mutableStateOf<Uri?>(null); private set
    var name by mutableStateOf("")
    var choosingImport by mutableStateOf(false)
    var archiveImport by mutableStateOf(true)
    var qcowImport by mutableStateOf(false); private set
    var formatReady by mutableStateOf(false); private set
    var cloudEnabled by mutableStateOf(false)
    var cloudUsername by mutableStateOf("droid")
    var cloudPassword by mutableStateOf("")
    var cloudConfirm by mutableStateOf("")
    var cloudHostname by mutableStateOf("")
    var cloudKeys by mutableStateOf("")
    var cloudSshPassword by mutableStateOf(false)
    var cloudDialog by mutableStateOf(false)
    var cloudLocked by mutableStateOf(false); private set
    internal var cloudExistingHash by mutableStateOf(""); private set
    var firmware by mutableStateOf<Uri?>(null)
    var directBoot by mutableStateOf(false)
    var kernel by mutableStateOf<Uri?>(null)
    var initrd by mutableStateOf<Uri?>(null)
    var params by mutableStateOf("console=ttyS0 root=/dev/vda1 rw")
    var importing by mutableStateOf(false); private set
    var importedBytes by mutableStateOf(0L); private set
    var operation by mutableStateOf(""); private set
    var error by mutableStateOf<String?>(null)
    var switchTarget by mutableStateOf<VmProfile?>(null)
    var menuTarget by mutableStateOf<VmProfile?>(null)
    var renameTarget by mutableStateOf<VmProfile?>(null)
    var deleteTarget by mutableStateOf<VmProfile?>(null)
    var stopTarget by mutableStateOf<VmProfile?>(null)
    var editTarget by mutableStateOf<VmProfile?>(null)
    var configDraft by mutableStateOf("")
    var screenDraft by mutableStateOf("console")
    var jsonMode by mutableStateOf(false)
    internal var disks by mutableStateOf<List<CustomDiskSize.Disk>>(emptyList()); private set
    var resizingDisk by mutableStateOf(false)
    var diskPath by mutableStateOf("")
    var diskGiB by mutableStateOf("")
    var diskMessage by mutableStateOf<String?>(null); private set
    private var job: Job? = null

    fun chooseImage(uri: Uri) {
        resetCloud()
        choosingImport = false
        image = uri
        name = runCatching { VmProfiles.displayName(uri).substringBeforeLast('.') }.getOrDefault("自定义镜像")
        firmware = null; kernel = null; initrd = null; directBoot = false
        error = null; importedBytes = 0
        qcowImport = false; formatReady = archiveImport
        if (!archiveImport) work("识别镜像格式") {
            qcowImport = VmProfiles.inspectQcow(uri)
            formatReady = true
        }
    }
    fun dismissImport() { if (!importing) { image = null; resetCloud() } }
    fun cancelImport() { job?.cancel() }
    private fun work(label: String, action: suspend () -> Unit) {
        if (importing) return
        importing = true; error = null; operation = label; importedBytes = 0
        job = viewModelScope.launch {
            try { action() }
            catch (e: CancellationException) { error = "操作已取消"; throw e }
            catch (e: Exception) { error = e.message ?: "操作失败" }
            finally { importing = false }
        }
    }
    fun import() {
        val uri = image ?: return
        if (!formatReady) return
        if (!archiveImport && directBoot && kernel == null) { error = "请选择内核"; return }
        val chosenArchive = archiveImport
        val convertQcow = qcowImport
        val chosenFirmware = firmware; val chosenName = name
        val chosenKernel = if (directBoot) kernel else null
        val chosenInitrd = if (directBoot) initrd else null; val chosenParams = params
        work("导入镜像") {
            val cloud = if (chosenArchive) null else prepareCloud("pending")
            val result = if (chosenArchive) VmProfiles.importDebianArchive(uri, chosenName) { importedBytes = it }
            else VmProfiles.importImage(uri, chosenFirmware, chosenName, chosenKernel, chosenInitrd, chosenParams,
                allowConversion = convertQcow, cloudInit = cloud, status = { operation = it }) { importedBytes = it }
            image = null; resetCloud(); switchTarget = result
        }
    }
    fun clone(profile: VmProfile) { work("克隆磁盘") { VmProfiles.clone(profile) { importedBytes = it } } }
    fun delete(profile: VmProfile) { deleteTarget = null; work("删除虚拟机") { VmProfiles.delete(profile) } }
    fun rename(profile: VmProfile, value: String) { work("重命名") { withContext(Dispatchers.IO) { VmProfiles.rename(profile, value) }; renameTarget = null } }
    fun edit(profile: VmProfile) {
        work("读取配置") {
            configDraft = withContext(Dispatchers.IO) { VmProfiles.readConfig(profile) }
            disks = withContext(Dispatchers.IO) { if (profile.isManaged) emptyList() else VmProfiles.customDisks(profile) }
            diskMessage = null; resizingDisk = false
            resetCloud()
            cloudLocked = withContext(Dispatchers.IO) { CloudInit.locked(VmProfiles.directory(profile)) }
            if (!profile.isManaged) withContext(Dispatchers.IO) { VmProfiles.cloudInit(profile) }?.let {
                cloudEnabled = true; cloudUsername = it.username; cloudHostname = it.hostname
                cloudExistingHash = it.passwordHash; cloudKeys = it.publicKeys.joinToString("\n"); cloudSshPassword = it.sshPassword
            }
            screenDraft = profile.screen; jsonMode = false; editTarget = profile
        }
    }
    fun property(key: String, value: String) {
        runCatching {
            val json = JsonParser.parseString(configDraft).asJsonObject
            if (key == "memory_mib" && value.toIntOrNull() != null) json.addProperty(key, value.toInt()) else json.addProperty(key, value)
            configDraft = VmConfigDocument.format(json)
        }.onFailure { error = "请先修复 JSON 语法" }
    }
    private fun resetCloud() {
        cloudEnabled = false; cloudUsername = "droid"; cloudPassword = ""; cloudConfirm = ""
        cloudHostname = ""; cloudKeys = ""; cloudSshPassword = false; cloudExistingHash = ""
        cloudDialog = false; cloudLocked = false
    }
    private suspend fun prepareCloud(id: String): CloudInitConfig? {
        if (!cloudEnabled) return null
        require(cloudPassword == cloudConfirm) { "两次输入的密码不一致" }
        val user = cloudUsername.trim(); val password = cloudPassword; val host = cloudHostname.trim()
        val keys = cloudKeys; val ssh = cloudSshPassword; val oldHash = cloudExistingHash
        val config = withContext(Dispatchers.IO) { CloudInit.config(user, password, host, keys, ssh, id, oldHash) }
        cloudExistingHash = config.passwordHash; cloudPassword = ""; cloudConfirm = ""
        return config
    }
    fun saveCloud() {
        val profile = editTarget ?: return
        work("保存初始配置") {
            VmProfiles.saveCloudInit(profile, prepareCloud(profile.id))
            cloudDialog = false
        }
    }
    fun openDiskResize() {
        val disk = disks.firstOrNull() ?: return
        diskPath = disk.path; diskGiB = ""; error = null; resizingDisk = true
    }
    fun growDisk() {
        val profile = editTarget ?: return
        val path = diskPath
        val size = runCatching { CustomDiskSize.targetBytes(diskGiB) }.getOrElse { error = it.message; return }
        work("扩容磁盘") {
            VmProfiles.growDisk(profile, path, size)
            disks = withContext(Dispatchers.IO) { VmProfiles.customDisks(profile) }
            resizingDisk = false
            diskMessage = "虚拟磁盘已扩容至 ${size / CustomDiskSize.GIB} GiB。进入系统后，请自行扩展分区（如有）和文件系统，才能使用新增空间。"
        }
    }
    fun restore() {
        val profile = editTarget ?: return
        work("恢复配置草稿") { configDraft = withContext(Dispatchers.IO) { VmProfiles.restoreConfig(profile) } }
    }
    fun save() {
        val profile = editTarget ?: return
        val draft = configDraft; val screen = screenDraft
        work("保存配置") {
            withContext(Dispatchers.IO) {
                VmProfiles.saveConfig(profile, draft)
                VmProfiles.setScreen(VmProfiles.profiles.value.first { it.id == profile.id }, screen)
            }
            editTarget = null
        }
    }
    fun switch() {
        val target = switchTarget ?: return
        switchTarget = null
        work("切换虚拟机") { VmController.switchTo(target) }
    }
    fun forceStop() {
        val target = stopTarget ?: return
        stopTarget = null
        work("强制停止") {
            check(VmProfiles.selected.value.id == target.id && VmController.vmState.value == VmState.Running) {
                "虚拟机状态已改变，请重新操作"
            }
            VmController.stop()
            withTimeout(30_000) {
                VmController.vmState.first { it != VmState.Stopping }
            }
            check(VmController.vmState.value == VmState.Stopped) { "虚拟机未能停止，请重试" }
        }
    }
}
