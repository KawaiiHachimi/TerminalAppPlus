/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal.new2.ui.main

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.virtualization.terminal.new2.core.VmController
import com.android.virtualization.terminal.new2.core.VmProfile
import com.android.virtualization.terminal.new2.core.VmProfiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class VmManagementViewModel(app: Application) : AndroidViewModel(app) {
    var image by mutableStateOf<Uri?>(null); private set
    var name by mutableStateOf("")
    var firmware by mutableStateOf<Uri?>(null)
    var importing by mutableStateOf(false); private set
    var importedBytes by mutableStateOf(0L); private set
    var error by mutableStateOf<String?>(null)
    var switchTarget by mutableStateOf<VmProfile?>(null)
    private var importJob: Job? = null

    fun chooseImage(uri: Uri) {
        image = uri
        name = runCatching { VmProfiles.displayName(uri).substringBeforeLast('.') }.getOrDefault("自定义镜像")
        firmware = null
        error = null
        importedBytes = 0
    }

    fun dismissImport() { if (!importing) image = null }
    fun cancelImport() { importJob?.cancel() }

    fun import() {
        val uri = image ?: return
        if (importing) return
        importing = true
        error = null
        val chosenFirmware = firmware
        val chosenName = name
        importJob = viewModelScope.launch {
            try {
                val result = VmProfiles.importImage(uri, chosenFirmware, chosenName) { importedBytes = it }
                image = null
                switchTarget = result
            } catch (e: CancellationException) {
                error = "导入已取消，原虚拟机未改动"
                throw e
            } catch (e: Exception) {
                error = e.message ?: "导入失败"
            } finally { importing = false }
        }
    }

    fun switch() {
        val target = switchTarget ?: return
        switchTarget = null
        viewModelScope.launch {
            try { VmController.switchTo(target) }
            catch (e: Exception) { error = e.message ?: "切换失败" }
        }
    }
}
