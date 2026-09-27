/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal.new2.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.virtualization.terminal.new2.core.VmController
import com.android.virtualization.terminal.new2.core.VmProfiles
import com.android.virtualization.terminal.new2.ui.main.VmManagementViewModel

/** Both entry points share the import flow; the laboratory exposes only U-Boot import. */
@Composable
fun VmManagementPage(laboratoryOnly: Boolean = false, model: VmManagementViewModel = viewModel()) {
    val profiles by VmProfiles.profiles.collectAsStateWithLifecycle()
    val selected by VmProfiles.selected.collectAsStateWithLifecycle()
    val vmState by VmController.vmState.collectAsStateWithLifecycle()
    val switching by VmController.switching.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::chooseImage) }
    val firmwarePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) model.firmware = uri }
    LazyColumn(Modifier.fillMaxSize()) {
        if (!laboratoryOnly) {
            item { Text("一次运行一台虚拟机。各自的磁盘独立保存，切换不会删除原系统。", Modifier.padding(16.dp)) }
            items(profiles, key = { it.id }) { profile ->
                ListItem(
                    headlineContent = { Text(profile.name) },
                    supportingContent = { Text(if (profile.isDefault) "Android 预构建 Debian · 原磁盘保留" else "U-Boot · ARM64 raw 磁盘 · 2 GiB 内存") },
                    trailingContent = { Text(if (profile == selected) "当前" else "切换") },
                    modifier = Modifier.clickable(enabled = !switching && !model.importing && (profile != selected || !vmState.isAlive)) { model.switchTarget = profile },
                )
                HorizontalDivider()
            }
        }
        item {
            ListItem(
                headlineContent = { Text("自定义 U-Boot 启动镜像") },
                supportingContent = { Text("选择已解压的 ARM64 raw 磁盘，导入为独立虚拟机") },
                modifier = Modifier.clickable(enabled = !switching && !model.importing) { picker.launch(arrayOf("*/*")) },
            )
        }
        if (switching) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
        model.error?.let { message -> item { Text(message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) } }
    }
    if (model.image != null) {
        AlertDialog(
            onDismissRequest = model::dismissImport,
            title = { Text("导入 U-Boot 镜像") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = model.name, onValueChange = { model.name = it }, label = { Text("虚拟机名称") }, enabled = !model.importing, singleLine = true)
                    Text("需要 ARM64 可启动磁盘。导入会创建独立副本，保留源文件。首次启动使用 2 GiB 内存与匹配宿主的 CPU 配置。")
                    Text(if (model.firmware != null) "已选择 U-Boot 文件" else if (VmProfiles.existingBootloader() != null) "使用本机已有的 crosvm U-Boot" else "尚无 U-Boot，请选择适用于 crosvm ARM64 的 U-Boot 文件")
                    TextButton(onClick = { firmwarePicker.launch(arrayOf("*/*")) }, enabled = !model.importing) { Text("选择 U-Boot 文件") }
                    if (model.importing) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("已导入 ${model.importedBytes / (1024 * 1024)} MiB")
                    }
                    model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = model::import, enabled = !model.importing && model.name.isNotBlank() && (model.firmware != null || VmProfiles.existingBootloader() != null)) { Text("导入") } },
            dismissButton = { TextButton(onClick = { if (model.importing) model.cancelImport() else model.dismissImport() }) { Text(if (model.importing) "取消导入" else "取消") } },
        )
    }
    model.switchTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { model.switchTarget = null },
            title = { Text("启动 ${target.name}") },
            text = { Text(if (vmState.isAlive) "当前虚拟机正在运行。切换会强制停止它，未保存的工作可能丢失；建议先在系统内正常关机。磁盘文件会保留。" else "使用这台虚拟机的磁盘与启动配置。原虚拟机的磁盘不会被覆盖。") },
            confirmButton = { TextButton(onClick = model::switch, enabled = !switching) { Text(if (vmState.isAlive) "强制停止并切换" else "启动") } },
            dismissButton = { TextButton(onClick = { model.switchTarget = null }) { Text("取消") } },
        )
    }
}
