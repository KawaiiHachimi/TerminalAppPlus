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

    val terminalStyle = remember { com.android.virtualization.terminal.FixedTerminalStyle.get(ttydView.context) }
    val styleState by terminalStyle.state.collectAsStateWithLifecycle()
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    LaunchedEffect(ttydView, styleState, configuration.uiMode) { ttydView.applyTerminalStyle(styleState) }

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
