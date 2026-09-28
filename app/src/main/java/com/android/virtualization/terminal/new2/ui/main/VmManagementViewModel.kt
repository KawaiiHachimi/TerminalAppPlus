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

class VmManagementViewModel(app: Application) : AndroidViewModel(app) {
    var image by mutableStateOf<Uri?>(null); private set
    var name by mutableStateOf("")
    var choosingImport by mutableStateOf(false)
    var archiveImport by mutableStateOf(true)
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
    var editTarget by mutableStateOf<VmProfile?>(null)
    var configDraft by mutableStateOf("")
    var screenDraft by mutableStateOf("console")
    var jsonMode by mutableStateOf(false)
    private var job: Job? = null

    fun chooseImage(uri: Uri) {
        choosingImport = false
        image = uri
        name = runCatching { VmProfiles.displayName(uri).substringBeforeLast('.') }.getOrDefault("自定义镜像")
        firmware = null; kernel = null; initrd = null; directBoot = false
        error = null; importedBytes = 0
    }
    fun dismissImport() { if (!importing) image = null }
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
        if (!archiveImport && directBoot && kernel == null) { error = "请选择内核"; return }
        val chosenArchive = archiveImport
        val chosenFirmware = firmware; val chosenName = name
        val chosenKernel = if (directBoot) kernel else null
        val chosenInitrd = if (directBoot) initrd else null; val chosenParams = params
        work("导入镜像") {
            val result = if (chosenArchive) VmProfiles.importDebianArchive(uri, chosenName) { importedBytes = it }
            else VmProfiles.importImage(uri, chosenFirmware, chosenName, chosenKernel, chosenInitrd, chosenParams) { importedBytes = it }
            image = null; switchTarget = result
        }
    }
    fun clone(profile: VmProfile) { work("克隆磁盘") { VmProfiles.clone(profile) { importedBytes = it } } }
    fun delete(profile: VmProfile) { deleteTarget = null; work("删除虚拟机") { VmProfiles.delete(profile) } }
    fun rename(profile: VmProfile, value: String) { work("重命名") { withContext(Dispatchers.IO) { VmProfiles.rename(profile, value) }; renameTarget = null } }
    fun edit(profile: VmProfile) {
        work("读取配置") {
            configDraft = withContext(Dispatchers.IO) { VmProfiles.readConfig(profile) }
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
}
