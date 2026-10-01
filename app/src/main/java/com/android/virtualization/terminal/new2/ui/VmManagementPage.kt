/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal.new2.ui

import androidx.compose.ui.res.stringResource
import com.android.virtualization.terminal.R
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
                Text(stringResource(R.string.plus_initial_setup), Modifier.padding(16.dp), style = MaterialTheme.typography.headlineSmall)
            } else {
                Spacer(Modifier.height(16.dp))
            }
            Text(if (firstSetup) stringResource(R.string.plus_initial_setup_summary) else stringResource(R.string.plus_vm_list_summary), Modifier.padding(horizontal = 16.dp))
        }
        items(profiles, key = { it.id }) { profile ->
            Box {
                ListItem(
                    headlineContent = { Text(profile.name) },
                    supportingContent = {
                        val description = when {
                            !VmProfiles.isInstalled(profile) -> stringResource(R.string.plus_official_not_downloaded)
                            profile.isDefault -> "Android Debian"
                            profile.managedDebian -> stringResource(R.string.plus_imported_archive)
                            else -> stringResource(R.string.plus_custom_image)
                        }
                        Text(description + if (VmProfiles.isInstalled(profile) && profile.id == selected.id) stringResource(R.string.plus_current_suffix) else "")
                    },
                    trailingContent = {
                        if (VmProfiles.isInstalled(profile)) TextButton(onClick = { model.edit(profile) }, enabled = !busy) { Text(stringResource(R.string.plus_configure)) }
                    },
                    modifier = Modifier.combinedClickable(enabled = !busy,
                        onClick = { if (profile.id != selected.id || !vmState.isAlive) model.switchTarget = profile },
                        onLongClick = { model.menuTarget = profile }),
                )
                DropdownMenu(expanded = model.menuTarget?.id == profile.id, onDismissRequest = { model.menuTarget = null }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.plus_force_stop)) },
                        enabled = !busy && profile.id == selected.id && vmState == VmState.Running,
                        onClick = { model.menuTarget = null; model.stopTarget = profile })
                    DropdownMenuItem(text = { Text(stringResource(R.string.plus_rename)) }, onClick = { model.menuTarget = null; model.name = profile.name; model.renameTarget = profile })
                    DropdownMenuItem(text = { Text(stringResource(R.string.plus_clone)) }, enabled = VmProfiles.isInstalled(profile) && !(profile.id == selected.id && vmState.isAlive), onClick = { model.menuTarget = null; model.clone(profile) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.plus_delete)) }, enabled = !(profile.id == selected.id && vmState.isAlive), onClick = { model.menuTarget = null; model.deleteTarget = profile })
                }
            }
            HorizontalDivider()
        }
        item {
            TextButton(onClick = { model.choosingImport = true }, enabled = !busy, modifier = Modifier.padding(8.dp)) { Text(stringResource(R.string.plus_import_image_add)) }
        }
        if (model.importing) item {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
            Text("${model.operation} · ${model.importedBytes / (1024 * 1024)} MiB", Modifier.padding(horizontal = 16.dp))
            TextButton(onClick = model::cancelImport) { Text(stringResource(R.string.plus_cancel)) }
        }
        model.error?.let { message -> item { Text(message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) } }
    }
    if (model.choosingImport) AlertDialog(
        onDismissRequest = { model.choosingImport = false },
        title = { Text(stringResource(R.string.plus_import_custom_image)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedCard(onClick = { model.archiveImport = true; picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.plus_debian_archive), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.plus_debian_archive_summary), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                OutlinedCard(onClick = { model.archiveImport = false; picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.plus_disk_image_formats), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.plus_disk_image_summary), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { model.choosingImport = false }) { Text(stringResource(R.string.plus_cancel)) } },
    )
    if (model.image != null) AlertDialog(
        onDismissRequest = model::dismissImport,
        title = { Text(if (model.archiveImport) stringResource(R.string.plus_import_debian_archive) else stringResource(R.string.plus_import_disk_image)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = model.name, onValueChange = { model.name = it }, label = { Text(stringResource(R.string.plus_vm_name)) }, enabled = !model.importing, singleLine = true)
                if (model.archiveImport) {
                    Text(stringResource(R.string.plus_archive_import_details))
                } else {
                if (model.qcowImport) {
                    Text(stringResource(R.string.plus_qcow_conversion_notice), color = MaterialTheme.colorScheme.primary)
                }
                Row {
                    FilterChip(selected = !model.directBoot, onClick = { model.directBoot = false }, label = { Text("U-Boot") }, enabled = !model.importing)
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = model.directBoot, onClick = { model.directBoot = true }, label = { Text(stringResource(R.string.plus_direct_kernel)) }, enabled = !model.importing)
                }
                if (model.directBoot) {
                    Text(stringResource(R.string.plus_kernel_boot_summary))
                    TextButton(onClick = { kernelPicker.launch(arrayOf("*/*")) }, enabled = !model.importing) { Text(if (model.kernel == null) stringResource(R.string.plus_choose_kernel) else stringResource(R.string.plus_change_kernel)) }
                    TextButton(onClick = { initrdPicker.launch(arrayOf("*/*")) }, enabled = !model.importing) { Text(if (model.initrd == null) stringResource(R.string.plus_choose_initrd) else stringResource(R.string.plus_change_initrd)) }
                    if (model.initrd != null) TextButton(onClick = { model.initrd = null }) { Text(stringResource(R.string.plus_remove_initrd)) }
                    OutlinedTextField(value = model.params, onValueChange = { model.params = it }, label = { Text(stringResource(R.string.plus_kernel_arguments)) }, enabled = !model.importing)
                } else {
                    Text(stringResource(R.string.plus_uboot_boot_summary))
                    TextButton(onClick = { firmwarePicker.launch(arrayOf("*/*")) }, enabled = !model.importing) { Text(if (model.firmware == null) stringResource(R.string.plus_replace_uboot) else stringResource(R.string.plus_custom_uboot_selected)) }
                    if (model.firmware != null) TextButton(onClick = { model.firmware = null }) { Text(stringResource(R.string.plus_restore_uboot)) }
                }
                Text(stringResource(R.string.plus_import_defaults))
                CloudInitFields(model)
                }
                if (model.importing) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("${model.operation} · ${model.importedBytes / (1024 * 1024)} MiB") }
                model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = model::import, enabled = !busy && model.formatReady && model.name.isNotBlank() && (model.archiveImport || !model.directBoot || model.kernel != null)) { Text(if (model.qcowImport) stringResource(R.string.plus_convert_import) else stringResource(R.string.plus_import_action)) } },
        dismissButton = { TextButton(onClick = { if (model.importing) model.cancelImport() else model.dismissImport() }) { Text(stringResource(R.string.plus_cancel)) } },
    )
    model.switchTarget?.let { target ->
        AlertDialog(onDismissRequest = { model.switchTarget = null }, title = { Text(if (VmProfiles.isInstalled(target)) stringResource(R.string.plus_start_vm_named , target.name) else stringResource(R.string.plus_download_official_debian)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (vmState.isAlive) {
                        Text(stringResource(R.string.plus_switch_vm_warning))
                    }
                    if (!VmProfiles.isInstalled(target)) {
                        Text(stringResource(R.string.plus_official_debian_description))
                        val downloadUrl = remember { ImageArchive.fromInternet().getPath() }
                        Text(stringResource(R.string.plus_download_address), style = MaterialTheme.typography.labelLarge)
                        Text(buildAnnotatedString {
                            withLink(LinkAnnotation.Url(downloadUrl, TextLinkStyles(
                                style = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)
                            ))) { append(downloadUrl) }
                        }, style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.plus_initial_credentials), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = { TextButton(onClick = model::switch, enabled = !busy) { Text(if (vmState.isAlive) stringResource(R.string.plus_force_stop_switch) else stringResource(R.string.plus_continue_action)) } },
            dismissButton = { TextButton(onClick = { model.switchTarget = null }) { Text(stringResource(R.string.plus_cancel)) } })
    }
    model.renameTarget?.let { target ->
        AlertDialog(onDismissRequest = { model.renameTarget = null }, title = { Text(stringResource(R.string.plus_rename)) },
            text = { OutlinedTextField(value = model.name, onValueChange = { model.name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { model.rename(target, model.name) }, enabled = !busy && model.name.isNotBlank()) { Text(stringResource(R.string.plus_save)) } },
            dismissButton = { TextButton(onClick = { model.renameTarget = null }) { Text(stringResource(R.string.plus_cancel)) } })
    }
    model.deleteTarget?.let { target ->
        AlertDialog(onDismissRequest = { model.deleteTarget = null }, title = { Text(stringResource(R.string.plus_delete_vm_named , target.name)) },
            text = { Text(stringResource(R.string.plus_delete_vm_warning)) },
            confirmButton = { TextButton(onClick = { model.delete(target) }, enabled = !busy) { Text(stringResource(R.string.plus_delete)) } },
            dismissButton = { TextButton(onClick = { model.deleteTarget = null }) { Text(stringResource(R.string.plus_cancel)) } })
    }
    model.stopTarget?.let { target ->
        AlertDialog(onDismissRequest = { model.stopTarget = null },
            title = { Text(stringResource(R.string.plus_force_stop_named , target.name)) },
            text = { Text(stringResource(R.string.plus_force_stop_warning)) },
            confirmButton = {
                TextButton(onClick = model::forceStop,
                    enabled = !busy && target.id == selected.id && vmState == VmState.Running) { Text(stringResource(R.string.plus_force_stop)) }
            },
            dismissButton = { TextButton(onClick = { model.stopTarget = null }) { Text(stringResource(R.string.plus_cancel)) } })
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
    val installLabel = stringResource(R.string.plus_install_guest_tools)
    val copiedMessage = stringResource(R.string.plus_install_command_copied)
    val json = remember(model.configDraft) { runCatching { JsonParser.parseString(model.configDraft).asJsonObject }.getOrNull() }
    AlertDialog(onDismissRequest = { if (!model.importing) model.editTarget = null }, title = { Text(stringResource(R.string.plus_vm_configuration_title , model.editTarget!!.name)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.plus_configuration_summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (model.editTarget?.isManaged == false) {
                    Text(stringResource(R.string.plus_guest_tools), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.plus_guest_tools_summary))
                    FilterChip(selected = useSudo, onClick = { useSudo = !useSudo }, label = { Text(stringResource(R.string.plus_use_sudo)) })
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                        SelectionContainer {
                            Text(installCommand, modifier = Modifier.fillMaxWidth().padding(12.dp),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace,
                                    textDirection = androidx.compose.ui.text.style.TextDirection.Ltr))
                        }
                    }
                    Button(colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        contentColor = MaterialTheme.colorScheme.secondaryContainer,
                    ), onClick = {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                            ClipData.newPlainText(installLabel, installCommand))
                        Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                    }) { Text(stringResource(R.string.plus_copy_install_command)) }
                    Text(stringResource(R.string.plus_guest_tools_repair_hint), style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                }
                Text(stringResource(R.string.plus_startup_screen), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("ttyd" to "ttyd", "console" to stringResource(R.string.plus_console), "display" to stringResource(R.string.plus_graphics)).forEach { (key, label) ->
                        FilterChip(selected = model.screenDraft == key, onClick = { model.screenDraft = key }, label = { Text(label) })
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                VmConfigModeSelector(
                    jsonMode = model.jsonMode,
                    onModeChange = { model.jsonMode = it },
                )
                if (model.jsonMode) {
                    OutlinedTextField(value = model.configDraft, onValueChange = { model.configDraft = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 420.dp), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, textDirection = androidx.compose.ui.text.style.TextDirection.Ltr))
                } else if (json == null) Text(stringResource(R.string.plus_json_syntax_hint))
                else {
                    OutlinedTextField(value = json.get("memory_mib")?.takeIf { it.isJsonPrimitive }?.asString ?: "", onValueChange = { model.property("memory_mib", it) }, label = { Text(stringResource(R.string.plus_memory_mib)) }, singleLine = true)
                    Text(stringResource(R.string.plus_cpu_configuration), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("one_cpu" to stringResource(R.string.plus_single_cpu), "match_host" to stringResource(R.string.plus_host_cpu)).forEach { (key, label) ->
                            FilterChip(selected = json.get("cpu_topology")?.takeIf { it.isJsonPrimitive }?.asString == key, onClick = { model.property("cpu_topology", key) }, label = { Text(label) })
                        }
                    }
                    Text(stringResource(R.string.plus_cpu_hint), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.plus_console_device), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("hvc0", "ttyS0").forEach { device ->
                            FilterChip(selected = json.get("console_input_device")?.takeIf { it.isJsonPrimitive }?.asString == device,
                                onClick = { model.property("console_input_device", device) }, label = { Text(device) })
                        }
                    }
                    Text(stringResource(R.string.plus_console_device_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (model.editTarget?.isManaged == false) {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    FilledTonalButton(onClick = model::openDiskResize, enabled = canResize && model.disks.isNotEmpty()) { Text(stringResource(R.string.plus_expand_disk)) }
                    Text(if (!canResize) stringResource(R.string.plus_stop_before_resize) else stringResource(R.string.plus_resize_hint), style = MaterialTheme.typography.bodySmall)
                    model.diskMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
                TextButton(onClick = model::restore, enabled = !model.importing) { Text(stringResource(R.string.plus_restore_configuration)) }
                model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = model::save, enabled = !model.importing) { Text(stringResource(R.string.plus_save)) } },
        dismissButton = { TextButton(onClick = { model.editTarget = null }, enabled = !model.importing) { Text(stringResource(R.string.plus_cancel)) } })
    if (model.resizingDisk) {
        val disk = model.disks.firstOrNull { it.path == model.diskPath }
        val size = runCatching { CustomDiskSize.targetBytes(model.diskGiB) }.getOrNull()
        AlertDialog(onDismissRequest = { if (!model.importing) model.resizingDisk = false },
            title = { Text(stringResource(R.string.plus_expand_disk)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.plus_resize_selection_hint))
                    model.disks.forEach { item ->
                        FilterChip(selected = item.path == model.diskPath,
                            onClick = { model.diskPath = item.path }, enabled = !model.importing,
                            label = { Text(item.path.removePrefix("\$PAYLOAD_DIR/")) })
                    }
                    disk?.let { Text(stringResource(R.string.plus_current_disk_size , String.format(java.util.Locale.ROOT, "%.2f", it.bytes.toDouble() / CustomDiskSize.GIB), it.bytes)) }
                    OutlinedTextField(value = model.diskGiB, onValueChange = { model.diskGiB = it },
                        label = { Text(stringResource(R.string.plus_target_disk_size)) }, singleLine = true, enabled = !model.importing,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                    Text(stringResource(R.string.plus_resize_warning))
                    model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = model::growDisk, enabled = canResize && disk != null && size != null && size > disk.bytes) { Text(stringResource(R.string.plus_expand_action)) } },
            dismissButton = { TextButton(onClick = { model.resizingDisk = false }, enabled = !model.importing) { Text(stringResource(R.string.plus_cancel)) } })
    }

}

@Composable
private fun CloudInitFields(model: VmManagementViewModel) {
    val enabled = !model.importing
    var showPassword by remember { mutableStateOf(false) }
    HorizontalDivider()
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Checkbox(checked = model.cloudEnabled, onCheckedChange = { model.cloudEnabled = it }, enabled = enabled)
        Text(stringResource(R.string.plus_cloud_init_title))
    }
    Text(stringResource(R.string.plus_cloud_init_summary), style = MaterialTheme.typography.bodySmall)
    if (model.cloudEnabled) {
        OutlinedTextField(value = model.cloudUsername, onValueChange = { model.cloudUsername = it }, label = { Text(stringResource(R.string.plus_username)) }, singleLine = true, enabled = enabled)
        if (model.cloudUsername.trim() == "root") Text(stringResource(R.string.plus_root_account_hint), style = MaterialTheme.typography.bodySmall)
        val transformation = if (showPassword) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation()
        OutlinedTextField(value = model.cloudPassword, onValueChange = { model.cloudPassword = it }, label = { Text(if (model.cloudExistingHash.isEmpty()) stringResource(R.string.plus_password) else stringResource(R.string.plus_new_password)) }, singleLine = true, enabled = enabled, visualTransformation = transformation,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password))
        OutlinedTextField(value = model.cloudConfirm, onValueChange = { model.cloudConfirm = it }, label = { Text(stringResource(R.string.plus_confirm_password)) }, singleLine = true, enabled = enabled, visualTransformation = transformation,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password))
        TextButton(onClick = { showPassword = !showPassword }, enabled = enabled) { Text(if (showPassword) stringResource(R.string.plus_hide_password) else stringResource(R.string.plus_show_password)) }
        OutlinedTextField(value = model.cloudHostname, onValueChange = { model.cloudHostname = it }, label = { Text(stringResource(R.string.plus_hostname_optional)) }, singleLine = true, enabled = enabled)
        OutlinedTextField(value = model.cloudKeys, onValueChange = { model.cloudKeys = it }, label = { Text(stringResource(R.string.plus_ssh_keys_optional)) }, enabled = enabled, minLines = 2)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(checked = model.cloudSshPassword, onCheckedChange = { model.cloudSshPassword = it }, enabled = enabled)
            Text(stringResource(R.string.plus_ssh_password_login))
        }
        Text(stringResource(R.string.plus_cloud_credentials_hint), style = MaterialTheme.typography.bodySmall)
    }
}
