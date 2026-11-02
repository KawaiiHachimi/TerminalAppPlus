/* SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal.new2.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.virtualization.terminal.R
import com.android.virtualization.terminal.Dracula
import com.android.virtualization.terminal.new2.core.TerminalSession
import com.android.virtualization.terminal.new2.ui.main.TerminalViewModel

val TAB_BAR_HEIGHT = 56.dp

/** QPR3-style tabs: rounded active top corners, concave bottom joins and no underline. */
@Composable
fun TerminalTabBar(
    tabs: List<TerminalSession>, selectedTabId: String?, onTabSelected: (String) -> Unit,
    onTabClosed: (String) -> Unit, onAddTab: () -> Unit,
    settingsOpen: Boolean, settingsSelected: Boolean, onSettingsSelected: () -> Unit, onSettingsClosed: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(TAB_BAR_HEIGHT)) {
        val count = tabs.size + if (settingsOpen) 1 else 0
        val width = ((maxWidth - 44.dp) / count.coerceAtLeast(1)).coerceIn(120.dp, 240.dp)
        val scroll = rememberLazyListState()
        LaunchedEffect(selectedTabId, settingsSelected, tabs.size) {
            val index = if (settingsSelected) tabs.size else tabs.indexOfFirst { it.id == selectedTabId }
            if (index in 0 until count) scroll.animateScrollToItem(index)
        }
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.Bottom) {
            LazyRow(Modifier.weight(1f).fillMaxHeight(), state = scroll, verticalAlignment = Alignment.Bottom) {
                itemsIndexed(tabs, key = { _, tab -> tab.id }) { _, tab ->
                    val model: TerminalViewModel = viewModel(key = tab.id)
                    val title by model.title.collectAsStateWithLifecycle()
                    var confirm by remember { mutableStateOf(false) }
                    BrowserTab(title, selectedTabId == tab.id, width, false, { onTabSelected(tab.id) }, { confirm = true })
                    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
                        title = { Text(stringResource(R.string.terminal_dlg_title_close_tab)) },
                        text = { Text(stringResource(R.string.terminal_dlg_message_close_tab)) },
                        confirmButton = { TextButton(onClick = { model.terminalClose(); onTabClosed(tab.id); confirm = false }) { Text(stringResource(R.string.terminal_dlg_btn_close)) } },
                        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(android.R.string.cancel)) } })
                }
                if (settingsOpen) item(key = "plus-settings-tab") {
                    BrowserTab(stringResource(R.string.settings_title), settingsSelected, width, true, onSettingsSelected, onSettingsClosed)
                }
                item(key = "add-tab") {
                    IconButton(onClick = onAddTab, modifier = Modifier.size(44.dp).padding(bottom = 2.dp)) {
                        Icon(Icons.Default.Add, stringResource(R.string.terminal_hint_btn_add_tab))
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserTab(title: String, selected: Boolean, width: Dp, settings: Boolean, onClick: () -> Unit, onClose: () -> Unit) {
    val bar = MaterialTheme.colorScheme.surfaceContainer
    val active = if (settings) MaterialTheme.colorScheme.surface else Color(android.graphics.Color.parseColor(Dracula.background))
    val content = if (settings) MaterialTheme.colorScheme.onSurface else Color(0xfff8f8f2)
    val color by animateColorAsState(if (selected) active else bar, label = "tabColor")
    Row(Modifier.width(width).height(48.dp), verticalAlignment = Alignment.Bottom) {
        TabJoin(selected, color, bar, false)
        Tab(selected = selected, onClick = onClick,
            selectedContentColor = content, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)).background(color)) {
            Row(Modifier.fillMaxSize().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (settings) { Icon(Icons.Default.Settings, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
                Text(title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Close, stringResource(R.string.terminal_hint_btn_close_tab), Modifier.size(18.dp))
                }
            }
        }
        TabJoin(selected, color, bar, true)
    }
}

@Composable
private fun TabJoin(selected: Boolean, active: Color, bar: Color, right: Boolean) {
    Canvas(Modifier.size(12.dp)) {
        if (selected) {
            drawRect(active)
            drawCircle(bar, radius = size.width, center = Offset(if (right) size.width else 0f, 0f))
        }
    }
}
