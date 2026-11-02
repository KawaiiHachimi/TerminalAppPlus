/* SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal.new2.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.virtualization.terminal.R
import com.android.virtualization.terminal.appearance.AppearanceRepository
import com.android.virtualization.terminal.appearance.TerminalTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun AppearancePage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = remember { AppearanceRepository.get(context) }
    val appearance by repository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var selecting by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<TerminalTheme?>(null) }
    var busy by remember { mutableStateOf(false) }
    val importError = stringResource(R.string.plus_appearance_import_error)
    val systemDark = isSystemInDarkTheme()
    val importTheme = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val id = repository.importTheme(uri)
                val current = repository.state.value
                repository.select(if (!current.automatic) "fixed" else if (current.isDark(systemDark)) "dark" else "light", id)
            }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { Toast.makeText(context, importError, Toast.LENGTH_LONG).show() }
            finally { busy = false }
        }
    }
    val importFont = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try { repository.importFont(uri) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { Toast.makeText(context, importError, Toast.LENGTH_LONG).show() }
            finally { busy = false }
        }
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.plus_appearance_mode), style = MaterialTheme.typography.titleSmall)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            listOf("system" to R.string.plus_appearance_system, "light" to R.string.plus_appearance_light, "dark" to R.string.plus_appearance_dark).forEachIndexed { index, (key,label) ->
                SegmentedButton(selected = appearance.mode == key, onClick = { repository.setMode(key) },
                    shape = SegmentedButtonDefaults.itemShape(index,3), modifier = Modifier.weight(1f).fillMaxHeight()) { Text(stringResource(label)) }
            }
        }
        HorizontalDivider()
        Text(stringResource(R.string.plus_appearance_colors), style = MaterialTheme.typography.titleSmall)
        ListItem(headlineContent = { Text(stringResource(R.string.plus_appearance_auto)) },
            trailingContent = { Switch(checked = appearance.automatic, onCheckedChange = repository::setAutomatic) },
            modifier = Modifier.clickable { repository.setAutomatic(!appearance.automatic) })
        val slots = if (appearance.automatic) listOf("light" to R.string.plus_appearance_light_theme, "dark" to R.string.plus_appearance_dark_theme)
            else listOf("fixed" to R.string.plus_appearance_colors)
        slots.forEach { (key,label) ->
            val id = when(key) { "light" -> appearance.light; "dark" -> appearance.dark; else -> appearance.fixed }
            val theme = appearance.themes.find { it.id == id }
            OutlinedCard(onClick = { selecting = key }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
                    Text(theme?.let { themeLabel(it) }.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        val active = appearance.theme(isSystemInDarkTheme())
        Surface(color = Color(android.graphics.Color.parseColor(active.background)), shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("user@vm:~$ ls", color = Color(android.graphics.Color.parseColor(active.foreground)), fontFamily = FontFamily.Monospace)
                Row(Modifier.padding(top = 12.dp).fillMaxWidth()) {
                    TerminalTheme.ansi.take(8).forEach { name -> Box(Modifier.weight(1f).height(18.dp).background(Color(android.graphics.Color.parseColor(active.colors.getValue(name))))) }
                }
            }
        }
        OutlinedButton(onClick = { importTheme.launch(arrayOf("*/*")) }, enabled = !busy) { Text(stringResource(R.string.plus_appearance_import_theme)) }
        Text("Alacritty · TOML · ≤128 KiB", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text(stringResource(R.string.plus_appearance_font), style = MaterialTheme.typography.titleSmall)
        Text(appearance.fontName.ifEmpty { stringResource(R.string.plus_appearance_default_font) })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { importFont.launch(arrayOf("*/*")) }, enabled = !busy) { Text(stringResource(R.string.plus_appearance_import_font)) }
            if (appearance.fontId.isNotEmpty()) IconButton(onClick = { scope.launch { busy = true; try { repository.resetFont() } finally { busy = false } } }, enabled = !busy) {
                Icon(Icons.Default.Delete, stringResource(R.string.plus_appearance_reset_font))
            }
        }
        Text("TTF / OTF · ≤15 MB", style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.plus_appearance_font_size) + " · ttyd", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onClick = { repository.setFontSize(appearance.fontSize-1) }, enabled = appearance.fontSize>5) { Icon(Icons.Default.Remove, stringResource(R.string.plus_appearance_font_smaller)) }
            Text("${appearance.fontSize}", modifier = Modifier.padding(horizontal = 12.dp))
            IconButton(onClick = { repository.setFontSize(appearance.fontSize+1) }, enabled = appearance.fontSize<200) { Icon(Icons.Default.Add, stringResource(R.string.plus_appearance_font_larger)) }
        }
        Text("Aa Bb 0123456789 >_", fontFamily = FontFamily(repository.typeface), fontSize = appearance.fontSize.coerceAtMost(32).sp)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    selecting?.let { slot ->
        val selected = when(slot) { "light" -> appearance.light; "dark" -> appearance.dark; else -> appearance.fixed }
        AlertDialog(onDismissRequest = { selecting = null }, title = { Text(stringResource(R.string.plus_appearance_colors)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    appearance.themes.forEach { theme ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { repository.select(slot, theme.id); selecting = null }) {
                            RadioButton(selected = selected == theme.id, onClick = { repository.select(slot, theme.id); selecting = null })
                            Text(themeLabel(theme), modifier = Modifier.weight(1f))
                            if (theme.id.startsWith("import_")) IconButton(onClick = { pendingDelete = theme }) { Icon(Icons.Default.Delete, stringResource(R.string.plus_delete)) }
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { selecting = null }) { Text(stringResource(android.R.string.cancel)) } })
    }
    pendingDelete?.let { theme ->
        AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text(stringResource(R.string.plus_delete)) }, text = { Text(theme.name) },
            confirmButton = { TextButton(onClick = { scope.launch { repository.deleteTheme(theme.id) }; pendingDelete = null }) { Text(stringResource(R.string.plus_delete)) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(android.R.string.cancel)) } })
    }
}

@Composable
private fun themeLabel(theme: TerminalTheme): String = when (theme.id) {
    "standard_dark" -> stringResource(R.string.plus_appearance_default_dark)
    "standard_light" -> stringResource(R.string.plus_appearance_default_light)
    else -> theme.name
}
