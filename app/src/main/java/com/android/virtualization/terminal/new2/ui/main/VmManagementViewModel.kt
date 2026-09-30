/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal.new2.ui.main

import com.android.virtualization.terminal.AppStrings
import com.android.virtualization.terminal.R
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
        name = runCatching { VmProfiles.displayName(uri).substringBeforeLast('.') }.getOrDefault(AppStrings.get(R.string.plus_custom_image))
        firmware = null; kernel = null; initrd = null; directBoot = false
        error = null; importedBytes = 0
        qcowImport = false; formatReady = archiveImport
        if (!archiveImport) work(AppStrings.get(R.string.plus_detecting_image_format)) {
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
            catch (e: CancellationException) { error = AppStrings.get(R.string.plus_operation_canceled); throw e }
            catch (e: Exception) { error = e.message ?: AppStrings.get(R.string.plus_operation_failed) }
            finally { importing = false }
        }
    }
    fun import() {
        val uri = image ?: return
        if (!formatReady) return
        if (!archiveImport && directBoot && kernel == null) { error = AppStrings.get(R.string.plus_kernel_required); return }
        val chosenArchive = archiveImport
        val convertQcow = qcowImport
        val chosenFirmware = firmware; val chosenName = name
        val chosenKernel = if (directBoot) kernel else null
        val chosenInitrd = if (directBoot) initrd else null; val chosenParams = params
        work(AppStrings.get(R.string.plus_importing_image)) {
            val cloud = if (chosenArchive) null else prepareCloud("pending")
            val result = if (chosenArchive) VmProfiles.importDebianArchive(uri, chosenName) { importedBytes = it }
            else VmProfiles.importImage(uri, chosenFirmware, chosenName, chosenKernel, chosenInitrd, chosenParams,
                allowConversion = convertQcow, cloudInit = cloud, status = { operation = it }) { importedBytes = it }
            image = null; resetCloud(); switchTarget = result
        }
    }
    fun clone(profile: VmProfile) { work(AppStrings.get(R.string.plus_cloning_disk)) { VmProfiles.clone(profile) { importedBytes = it } } }
    fun delete(profile: VmProfile) { deleteTarget = null; work(AppStrings.get(R.string.plus_deleting_vm)) { VmProfiles.delete(profile) } }
    fun rename(profile: VmProfile, value: String) { work(AppStrings.get(R.string.plus_rename)) { withContext(Dispatchers.IO) { VmProfiles.rename(profile, value) }; renameTarget = null } }
    fun edit(profile: VmProfile) {
        work(AppStrings.get(R.string.plus_reading_configuration)) {
            configDraft = withContext(Dispatchers.IO) { VmProfiles.readConfig(profile) }
            disks = withContext(Dispatchers.IO) { if (profile.isManaged) emptyList() else VmProfiles.customDisks(profile) }
            diskMessage = null; resizingDisk = false
            resetCloud()
            screenDraft = profile.screen; jsonMode = false; editTarget = profile
        }
    }
    fun property(key: String, value: String) {
        runCatching {
            val json = JsonParser.parseString(configDraft).asJsonObject
            if (key == "memory_mib" && value.toIntOrNull() != null) json.addProperty(key, value.toInt()) else json.addProperty(key, value)
            configDraft = VmConfigDocument.format(json)
        }.onFailure { error = AppStrings.get(R.string.plus_fix_json_first) }
    }
    private fun resetCloud() {
        cloudEnabled = false; cloudUsername = "droid"; cloudPassword = ""; cloudConfirm = ""
        cloudHostname = ""; cloudKeys = ""; cloudSshPassword = false; cloudExistingHash = ""
    }
    private suspend fun prepareCloud(id: String): CloudInitConfig? {
        if (!cloudEnabled) return null
        require(cloudPassword == cloudConfirm) { AppStrings.get(R.string.plus_passwords_mismatch) }
        val user = cloudUsername.trim(); val password = cloudPassword; val host = cloudHostname.trim()
        val keys = cloudKeys; val ssh = cloudSshPassword; val oldHash = cloudExistingHash
        val config = withContext(Dispatchers.IO) { CloudInit.config(user, password, host, keys, ssh, id, oldHash) }
        cloudExistingHash = config.passwordHash; cloudPassword = ""; cloudConfirm = ""
        return config
    }
    fun openDiskResize() {
        val disk = disks.firstOrNull() ?: return
        diskPath = disk.path; diskGiB = ""; error = null; resizingDisk = true
    }
    fun growDisk() {
        val profile = editTarget ?: return
        val path = diskPath
        val size = runCatching { CustomDiskSize.targetBytes(diskGiB) }.getOrElse { error = it.message; return }
        work(AppStrings.get(R.string.plus_expand_disk)) {
            VmProfiles.growDisk(profile, path, size)
            disks = withContext(Dispatchers.IO) { VmProfiles.customDisks(profile) }
            resizingDisk = false
            diskMessage = AppStrings.get(R.string.plus_disk_expanded , size / CustomDiskSize.GIB)
        }
    }
    fun restore() {
        val profile = editTarget ?: return
        work(AppStrings.get(R.string.plus_restoring_configuration)) { configDraft = withContext(Dispatchers.IO) { VmProfiles.restoreConfig(profile) } }
    }
    fun save() {
        val profile = editTarget ?: return
        val draft = configDraft; val screen = screenDraft
        work(AppStrings.get(R.string.plus_saving_configuration)) {
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
        work(AppStrings.get(R.string.plus_switching_vm)) { VmController.switchTo(target) }
    }
    fun forceStop() {
        val target = stopTarget ?: return
        stopTarget = null
        work(AppStrings.get(R.string.plus_force_stop)) {
            check(VmProfiles.selected.value.id == target.id && VmController.vmState.value == VmState.Running) {
                AppStrings.get(R.string.plus_vm_state_changed)
            }
            VmController.stop()
            withTimeout(30_000) {
                VmController.vmState.first { it != VmState.Stopping }
            }
            check(VmController.vmState.value == VmState.Stopped) { AppStrings.get(R.string.plus_vm_stop_failed) }
        }
    }
}
