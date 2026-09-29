/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal.new2.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.selection.SelectionContainer
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import com.android.virtualization.terminal.GuestToolsDisk
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.style.TextDecoration
import com.android.virtualization.terminal.ImageArchive
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.virtualization.terminal.new2.core.*
import com.android.virtualization.terminal.new2.ui.main.VmManagementViewModel
import com.google.gson.JsonParser

@Composable
fun VmManagementPage(firstSetup: Boolean = false, model: VmManagementViewModel = viewModel()) {
    val profiles by VmProfiles.profiles.collectAsStateWithLifecycle()
    val selected by VmProfiles.selected.collectAsStateWithLifecycle()
    val vmState by VmController.vmState.collectAsStateWithLifecycle()
    val switching by VmController.switching.collectAsStateWithLifecycle()
    val busy = switching || model.importing
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::chooseImage) }
    val firmwarePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) model.firmware = uri }
    val kernelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) model.kernel = uri }
    val initrdPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) model.initrd = uri }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            if (firstSetup) {
                Text("初始设置", Modifier.padding(16.dp), style = MaterialTheme.typography.headlineSmall)
            } else {
                Spacer(Modifier.height(16.dp))
            }
            Text(if (firstSetup) "下载 Android 官方 Debian，或导入已有镜像开始使用。" else "每次运行一台，磁盘独立保存。点击切换，长按可强制停止、重命名、克隆或删除。", Modifier.padding(horizontal = 16.dp))
        }
        items(profiles, key = { it.id }) { profile ->
            Box {
                ListItem(
                    headlineContent = { Text(profile.name) },
                    supportingContent = {
                        val description = when {
                            !VmProfiles.isInstalled(profile) -> "尚未下载 · Android 官方预构建 Debian"
                            profile.isDefault -> "Android Debian"
                            profile.managedDebian -> "导入的镜像包"
                            else -> "自定义镜像"
                        }
                        Text(description + if (VmProfiles.isInstalled(profile) && profile.id == selected.id) " · 当前" else "")
                    },
                    trailingContent = {
                        if (VmProfiles.isInstalled(profile)) TextButton(onClick = { model.edit(profile) }, enabled = !busy) { Text("配置") }
                    },
                    modifier = Modifier.combinedClickable(enabled = !busy,
                        onClick = { if (profile.id != selected.id || !vmState.isAlive) model.switchTarget = profile },
                        onLongClick = { model.menuTarget = profile }),
                )
                DropdownMenu(expanded = model.menuTarget?.id == profile.id, onDismissRequest = { model.menuTarget = null }) {
                    DropdownMenuItem(text = { Text("强制停止") },
                        enabled = !busy && profile.id == selected.id && vmState == VmState.Running,
                        onClick = { model.menuTarget = null; model.stopTarget = profile })
                    DropdownMenuItem(text = { Text("重命名") }, onClick = { model.menuTarget = null; model.name = profile.name; model.renameTarget = profile })
                    DropdownMenuItem(text = { Text("克隆") }, enabled = VmProfiles.isInstalled(profile) && !(profile.id == selected.id && vmState.isAlive), onClick = { model.menuTarget = null; model.clone(profile) })
                    DropdownMenuItem(text = { Text("删除") }, enabled = !(profile.id == selected.id && vmState.isAlive), onClick = { model.menuTarget = null; model.deleteTarget = profile })
                }
            }
            HorizontalDivider()
        }
        item {
            TextButton(onClick = { model.choosingImport = true }, enabled = !busy, modifier = Modifier.padding(8.dp)) { Text("＋ 导入自定义镜像") }
        }
        if (model.importing) item {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
            Text("${model.operation} · ${model.importedBytes / (1024 * 1024)} MiB", Modifier.padding(horizontal = 16.dp))
            TextButton(onClick = model::cancelImport) { Text("取消") }
        }
        model.error?.let { message -> item { Text(message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) } }
    }
    if (model.choosingImport) AlertDialog(
        onDismissRequest = { model.choosingImport = false },
        title = { Text("导入自定义镜像") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedCard(onClick = { model.archiveImport = true; picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Debian 镜像包", style = MaterialTheme.typography.titleMedium)
                        Text("官方格式 images.tar.gz，包含系统磁盘、内核和配置。按包内配置启动。", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                OutlinedCard(onClick = { model.archiveImport = false; picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("IMG / RAW 磁盘镜像", style = MaterialTheme.typography.titleMedium)
                        Text("支持 .img、.raw 及 gzip 压缩磁盘。使用 U-Boot 或自选内核启动。", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { model.choosingImport = false }) { Text("取消") } },
    )
    if (model.image != null) AlertDialog(
        onDismissRequest = model::dismissImport,
        title = { Text(if (model.archiveImport) "导入 Debian 镜像包" else "导入磁盘镜像") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = model.name, onValueChange = { model.name = it }, label = { Text("虚拟机名称") }, enabled = !model.importing, singleLine = true)
                if (model.archiveImport) {
                    Text("自动解压并读取包内 vm_config.json、内核和磁盘，默认进入 ttyd。创建独立虚拟机，保留原始镜像包。")
                } else {
                Row {
                    FilterChip(selected = !model.directBoot, onClick = { model.directBoot = false }, label = { Text("U-Boot") }, enabled = !model.importing)
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = model.directBoot, onClick = { model.directBoot = true }, label = { Text("直接内核") }, enabled = !model.importing)
                }
                if (model.directBoot) {
                    Text("选择 ARM64 Linux 内核、可选 initrd 和启动参数。内核需支持 AVF/crosvm 设备。磁盘可为整盘或文件系统镜像。")
                    TextButton(onClick = { kernelPicker.launch(arrayOf("*/*")) }, enabled = !model.importing) { Text(if (model.kernel == null) "选择内核（必选）" else "已选择内核 · 更换") }
                    TextButton(onClick = { initrdPicker.launch(arrayOf("*/*")) }, enabled = !model.importing) { Text(if (model.initrd == null) "选择 initrd（可选）" else "已选择 initrd · 更换") }
                    if (model.initrd != null) TextButton(onClick = { model.initrd = null }) { Text("不使用 initrd") }
                    OutlinedTextField(value = model.params, onValueChange = { model.params = it }, label = { Text("内核参数") }, enabled = !model.importing)
                } else {
                    Text("需要 ARM64 raw 可启动整盘镜像。默认使用系统 APEX 的 U-Boot，无法读取时使用 APK 内置版本。")
                    TextButton(onClick = { firmwarePicker.launch(arrayOf("*/*")) }, enabled = !model.importing) { Text(if (model.firmware == null) "可选：替换 U-Boot" else "已选择自定义 U-Boot") }
                    if (model.firmware != null) TextButton(onClick = { model.firmware = null }) { Text("恢复内置 U-Boot") }
                }
                Text("创建独立副本，保留源文件。默认 2 GiB、CPU 匹配宿主，可在导入后的配置页修改。")
                }
                if (model.importing) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("${model.importedBytes / (1024 * 1024)} MiB") }
                model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = model::import, enabled = !busy && model.name.isNotBlank() && (model.archiveImport || !model.directBoot || model.kernel != null)) { Text("导入") } },
        dismissButton = { TextButton(onClick = { if (model.importing) model.cancelImport() else model.dismissImport() }) { Text("取消") } },
    )
    model.switchTarget?.let { target ->
        AlertDialog(onDismissRequest = { model.switchTarget = null }, title = { Text(if (VmProfiles.isInstalled(target)) "启动 ${target.name}" else "下载官方 Debian") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (vmState.isAlive) {
                        Text("切换会强制停止当前虚拟机，未保存的工作可能丢失。建议先在系统内正常关机。")
                    }
                    if (!VmProfiles.isInstalled(target)) {
                        Text("下载 Android 官方预构建的 Debian 镜像。")
                        val downloadUrl = remember { ImageArchive.fromInternet().getPath() }
                        Text("下载地址", style = MaterialTheme.typography.labelLarge)
                        Text(buildAnnotatedString {
                            withLink(LinkAnnotation.Url(downloadUrl, TextLinkStyles(
                                style = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)
                            ))) { append(downloadUrl) }
                        }, style = MaterialTheme.typography.bodyMedium)
                        Text("初始用户名和密码均为 droid", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = { TextButton(onClick = model::switch, enabled = !busy) { Text(if (vmState.isAlive) "强制停止并切换" else "继续") } },
            dismissButton = { TextButton(onClick = { model.switchTarget = null }) { Text("取消") } })
    }
    model.renameTarget?.let { target ->
        AlertDialog(onDismissRequest = { model.renameTarget = null }, title = { Text("重命名") },
            text = { OutlinedTextField(value = model.name, onValueChange = { model.name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { model.rename(target, model.name) }, enabled = !busy && model.name.isNotBlank()) { Text("保存") } },
            dismissButton = { TextButton(onClick = { model.renameTarget = null }) { Text("取消") } })
    }
    model.deleteTarget?.let { target ->
        AlertDialog(onDismissRequest = { model.deleteTarget = null }, title = { Text("删除 ${target.name}？") },
            text = { Text("将永久删除这台 VM 的磁盘和配置，不能撤销。外部导入源文件及其他 VM 不受影响。") },
            confirmButton = { TextButton(onClick = { model.delete(target) }, enabled = !busy) { Text("删除") } },
            dismissButton = { TextButton(onClick = { model.deleteTarget = null }) { Text("取消") } })
    }
    model.stopTarget?.let { target ->
        AlertDialog(onDismissRequest = { model.stopTarget = null },
            title = { Text("强制停止 ${target.name}？") },
            text = { Text("将立即停止这台虚拟机，未保存的工作可能丢失。") },
            confirmButton = {
                TextButton(onClick = model::forceStop,
                    enabled = !busy && target.id == selected.id && vmState == VmState.Running) { Text("强制停止") }
            },
            dismissButton = { TextButton(onClick = { model.stopTarget = null }) { Text("取消") } })
    }
    if (model.editTarget != null) VmConfigurationDialog(model)
}

@Composable
private fun VmConfigurationDialog(model: VmManagementViewModel) {
    val context = LocalContext.current
    val vmState by VmController.vmState.collectAsStateWithLifecycle()
    val selected by VmProfiles.selected.collectAsStateWithLifecycle()
    val switching by VmController.switching.collectAsStateWithLifecycle()
    val canResize = !model.importing && !switching && !(selected.id == model.editTarget?.id && vmState.isAlive)
    var useSudo by remember { mutableStateOf(false) }
    val installCommand = GuestToolsDisk.installCommand(useSudo)
    val json = remember(model.configDraft) { runCatching { JsonParser.parseString(model.configDraft).asJsonObject }.getOrNull() }
    AlertDialog(onDismissRequest = { if (!model.importing) model.editTarget = null }, title = { Text("${model.editTarget!!.name} · 配置") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("配置保存后下次启动生效。恢复只改配置草稿，不回滚磁盘。")
                if (model.editTarget?.isManaged == false) {
                    Text("Guest 工具", style = MaterialTheme.typography.titleMedium)
                    Text("在 Debian/Ubuntu 控制台执行下方命令，安装 ttyd 和图形采集服务。首次使用前请重启虚拟机，安装依赖需要联网。")
                    FilterChip(selected = useSudo, onClick = { useSudo = !useSudo }, label = { Text("使用 sudo") })
                    SelectionContainer {
                        Text(installCommand, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    }
                    Button(colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        contentColor = MaterialTheme.colorScheme.secondaryContainer,
                    ), onClick = {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                            ClipData.newPlainText("安装 Guest 工具", installCommand))
                        Toast.makeText(context, "安装命令已复制", Toast.LENGTH_SHORT).show()
                    }) { Text("复制安装命令") }
                    Text("安装后用 terminal-plus-setup 检查状态，追加 --force 重新安装。", style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                }
                Text("启动后打开")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("ttyd" to "ttyd", "console" to "控制台", "display" to "图形").forEach { (key, label) ->
                        FilterChip(selected = model.screenDraft == key, onClick = { model.screenDraft = key }, label = { Text(label) })
                    }
                }
                Row {
                    TextButton(onClick = { model.jsonMode = false }) { Text("资源设置") }
                    TextButton(onClick = { model.jsonMode = true }) { Text("vm_config.json") }
                }
                if (model.jsonMode) {
                    OutlinedTextField(value = model.configDraft, onValueChange = { model.configDraft = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 420.dp), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                } else if (json == null) Text("JSON 语法有误，请切换到 JSON 编辑修复。")
                else {
                    OutlinedTextField(value = json.get("memory_mib")?.takeIf { it.isJsonPrimitive }?.asString ?: "", onValueChange = { model.property("memory_mib", it) }, label = { Text("内存（MiB）") }, singleLine = true)
                    Text("CPU 核心配置")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("one_cpu" to "单核", "match_host" to "匹配宿主").forEach { (key, label) ->
                            FilterChip(selected = json.get("cpu_topology")?.takeIf { it.isJsonPrimitive }?.asString == key, onClick = { model.property("cpu_topology", key) }, label = { Text(label) })
                        }
                    }
                    Text("支持单核或使用宿主 CPU 拓扑。")
                    if (model.editTarget?.isManaged == false) {
                        HorizontalDivider(Modifier.padding(top = 6.dp))
                        FilledTonalButton(onClick = model::openDiskResize, enabled = canResize && model.disks.isNotEmpty()) { Text("扩容磁盘") }
                        Text(if (!canResize) "关闭虚拟机后可扩容磁盘。" else "仅扩大磁盘文件，分区和文件系统需在 Guest 内自行扩容。", style = MaterialTheme.typography.bodySmall)
                        model.diskMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                TextButton(onClick = model::restore, enabled = !model.importing) { Text("恢复上次启动／保存的配置") }
                model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = model::save, enabled = !model.importing) { Text("保存") } },
        dismissButton = { TextButton(onClick = { model.editTarget = null }, enabled = !model.importing) { Text("取消") } })
    if (model.resizingDisk) {
        val disk = model.disks.firstOrNull { it.path == model.diskPath }
        val size = runCatching { CustomDiskSize.targetBytes(model.diskGiB) }.getOrNull()
        AlertDialog(onDismissRequest = { if (!model.importing) model.resizingDisk = false },
            title = { Text("扩容磁盘") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("选择已保存配置中的磁盘。操作立即生效，不随配置页的取消撤销。")
                    model.disks.forEach { item ->
                        FilterChip(selected = item.path == model.diskPath,
                            onClick = { model.diskPath = item.path }, enabled = !model.importing,
                            label = { Text(item.path.removePrefix("\$PAYLOAD_DIR/")) })
                    }
                    disk?.let { Text("当前逻辑容量：${String.format(java.util.Locale.ROOT, "%.2f", it.bytes.toDouble() / CustomDiskSize.GIB)} GiB（${it.bytes} 字节）") }
                    OutlinedTextField(value = model.diskGiB, onValueChange = { model.diskGiB = it },
                        label = { Text("目标容量（GiB）") }, singleLine = true, enabled = !model.importing,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                    Text("只支持扩大，不支持缩小。新增容量按写入占用手机空间；完成后需自行扩展分区和文件系统。")
                    model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = model::growDisk, enabled = canResize && disk != null && size != null && size > disk.bytes) { Text("扩容") } },
            dismissButton = { TextButton(onClick = { model.resizingDisk = false }, enabled = !model.importing) { Text("取消") } })
    }
}
