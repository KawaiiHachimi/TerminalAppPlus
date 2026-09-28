/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.virtualization.terminal.new2.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.virtualization.terminal.R
import com.android.virtualization.terminal.new2.core.TerminalAddress
import com.android.virtualization.terminal.new2.core.TerminalSession
import com.android.virtualization.terminal.new2.ui.main.MainViewModel
import com.android.virtualization.terminal.new2.ui.main.TerminalUiState
import com.android.virtualization.terminal.new2.ui.main.TerminalViewModel

val TAB_BAR_HEIGHT = 50.dp

@Composable
fun TerminalTabBar(
    tabs: List<TerminalSession>,
    selectedTabId: String?,
    onTabSelected: (String) -> Unit,
    onTabClosed: (String) -> Unit,
    onAddTab: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth()
                .height(TAB_BAR_HEIGHT)
                .background(MaterialTheme.colorScheme.surface),
    ) {
        if (tabs.isEmpty()) {
            IconButton(onClick = onAddTab) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.terminal_hint_btn_add_tab))
            }
        } else key(tabs.size) {
            val selectedTabIndex = tabs.indexOfFirst { it.id == selectedTabId }.coerceAtLeast(0)
            SecondaryScrollableTabRow(
                selectedTabIndex = selectedTabIndex,
                modifier = Modifier.fillMaxWidth(),
                edgePadding = 0.dp,
                containerColor = Color.Transparent,
                indicator = {
                    if (selectedTabId != null) {
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(selectedTabIndex)
                        )
                    }
                },
                divider = {},
            ) {
                tabs.forEachIndexed { index, tab ->
                    val tabViewModel: TerminalViewModel = viewModel(key = tab.id)
                    val isSelected = tab.id == selectedTabId
                    val isNextSelected =
                        if (index < tabs.lastIndex) {
                            tabs[index + 1].id == selectedTabId
                        } else {
                            false
                        }
                    TerminalTab(
                        tab = tab,
                        selected = isSelected,
                        showSeparator = !isSelected && !isNextSelected,
                        onTabSelected = { onTabSelected(tab.id) },
                        onTabClosed = { onTabClosed(tab.id) },
                        tabViewModel = tabViewModel,
                    )
                    if (index == tabs.lastIndex) {
                        Box(modifier = Modifier.padding(horizontal = 6.dp)) {
                            IconButton(onClick = onAddTab) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription =
                                        stringResource(R.string.terminal_hint_btn_add_tab),
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalTab(
    tab: TerminalSession,
    selected: Boolean,
    showSeparator: Boolean,
    onTabSelected: () -> Unit,
    onTabClosed: () -> Unit,
    tabViewModel: TerminalViewModel,
) {
    var showCloseDialog by remember { mutableStateOf(false) }
    val title by tabViewModel.title.collectAsStateWithLifecycle()
    val separatorColor = MaterialTheme.colorScheme.outlineVariant

    if (showCloseDialog) {
        AlertDialog(
            onDismissRequest = { showCloseDialog = false },
            title = { Text(stringResource(R.string.terminal_dlg_title_close_tab)) },
            text = { Text(stringResource(R.string.terminal_dlg_message_close_tab)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        tabViewModel.terminalClose()
                        onTabClosed()
                        showCloseDialog = false
                    }
                ) {
                    Text(stringResource(R.string.terminal_dlg_btn_close))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCloseDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    Tab(
        selected = selected,
        onClick = onTabSelected,
        modifier =
            Modifier.width(150.dp).drawWithContent {
                drawContent()
                if (showSeparator) {
                    drawLine(
                        color = separatorColor,
                        start = Offset(x = size.width, y = 12.dp.toPx()),
                        end = Offset(x = size.width, y = size.height - 12.dp.toPx()),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier.fillMaxHeight()
                    .background(
                        if (selected) MaterialTheme.colorScheme.surfaceVariant
                        else Color.Transparent
                    )
                    .padding(horizontal = 8.dp, vertical = 12.dp),
        ) {
            Text(
                text = title,
                maxLines = 1,
                modifier =
                    Modifier.weight(1f)
                        .graphicsLayer { alpha = 0.99f }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush =
                                    Brush.horizontalGradient(
                                        0.8f to Color.Black,
                                        1f to Color.Transparent,
                                    ),
                                blendMode = BlendMode.DstIn,
                            )
                        },
            )
            IconButton(
                onClick = { showCloseDialog = true },
                modifier = Modifier.size(24.dp).padding(start = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.terminal_hint_btn_close_tab),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalScreen(terminalAddress: TerminalAddress, tabId: String, mainViewModel: MainViewModel) {
    val terminalViewModel: TerminalViewModel = viewModel(key = tabId)
    val terminalUiState by terminalViewModel.uiState.collectAsStateWithLifecycle()
    var retryGeneration by remember(terminalAddress, tabId) { androidx.compose.runtime.mutableIntStateOf(0) }
    var connectionTimedOut by remember(terminalAddress, tabId, retryGeneration) { mutableStateOf(false) }
    val ttydView =
        remember(terminalAddress, tabId, retryGeneration) {
            terminalViewModel.getOrCreateTtydView(tabId, terminalAddress)
        }

    LaunchedEffect(ttydView, terminalUiState, retryGeneration) {
        connectionTimedOut = false
        if (terminalUiState is TerminalUiState.Connecting || terminalUiState is TerminalUiState.Initializing) {
            kotlinx.coroutines.delay(60_000)
            connectionTimedOut = true
        }
    }
    val retryConnection: () -> Unit = {
        terminalViewModel.terminalClose()
        retryGeneration++
    }

    val storedImeVisibility by mainViewModel.isImeVisible.collectAsStateWithLifecycle()
    val isWindowImeVisible = WindowInsets.isImeVisible
    // isFocused is used to track whether this tab is currently active and focused.
    // This is necessary to gate IME visibility synchronization; only the focused tab
    // should update the global IME state to avoid infinite loops and race conditions
    // during tab switching.
    var isFocused by remember { mutableStateOf(false) }

    // 1. Sync ViewModel state to UI (Show/Hide Keyboard)
    LaunchedEffect(tabId, storedImeVisibility, terminalUiState) {
        if (terminalUiState is TerminalUiState.Ready) {
            ttydView.post {
                if (storedImeVisibility) {
                    ttydView.showSoftInput()
                } else {
                    ttydView.hideSoftInput()
                }
            }
        }
    }

    ImeAwareContainer(
        isFocused = isFocused,
        onImeVisibilityChanged = { visible ->
            if (storedImeVisibility != visible) {
                mainViewModel.setIsImeVisible(visible)
            }
        },
        onKeyAction = { key, action ->
            if (key == ExtraKey.CTRL) {
                if (action == KeyEvent.ACTION_DOWN) {
                    ttydView.mapCtrlKey()
                    ttydView.enableCtrlKey()
                }
            } else {
                // Many terminal emulators send esc for alt for historical reason. We should
                // do the same.
                val code = if (key == ExtraKey.ALT) KeyEvent.KEYCODE_ESCAPE else key.keyCode
                code?.let { ttydView.dispatchKeyEvent(KeyEvent(action, it)) }
            }
        },
    ) { stablePadding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(bottom = stablePadding),
            contentAlignment = Alignment.Center,
        ) {
            // Attach while loading so xterm has a viewport and WebView callbacks can run.
            key(tabId, ttydView) {
                DisposableEffect(ttydView) {
                    ttydView.onResume()
                    onDispose { ttydView.onPause() }
                }
                AndroidView(
                    modifier = Modifier.fillMaxSize().clipToBounds(),
                    factory = {
                        ttydView.apply {
                            setOnFocusChangeListener { _, hasFocus ->
                                isFocused = hasFocus
                                if (!hasFocus) disableCtrlKey()
                            }
                        }
                    },
                )
            }
            if (terminalUiState !is TerminalUiState.Ready) Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center,
            ) {
                if (connectionTimedOut || terminalUiState is TerminalUiState.Disconnected) {
                    TerminalServiceNotice(onRetry = retryConnection)
                } else when (terminalUiState) {
                    is TerminalUiState.Ready -> Unit
                    is TerminalUiState.Connecting -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(text = stringResource(R.string.terminal_message_connecting))
                        }
                    }
                    is TerminalUiState.Disconnected -> {
                        Text(text = stringResource(R.string.terminal_message_disconnected))
                    }
                    else -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(text = stringResource(R.string.terminal_message_initializing))
                        }
                    }
                }
            }
        }
    }
}
