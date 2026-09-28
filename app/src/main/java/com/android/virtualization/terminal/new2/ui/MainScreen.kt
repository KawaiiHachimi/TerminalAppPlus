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

import android.app.Activity
import android.content.Intent
import com.android.virtualization.terminal.BuildConfig
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.ui.res.stringResource
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.virtualization.terminal.BetterBugLauncher
import com.android.virtualization.terminal.R
import com.android.virtualization.terminal.new2.core.InstallState
import com.android.virtualization.terminal.new2.core.Installer
import com.android.virtualization.terminal.new2.ui.main.DisplayState
import com.android.virtualization.terminal.new2.ui.main.MainUiState
import com.android.virtualization.terminal.new2.ui.main.MainViewModel

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val installState by Installer.installState.collectAsStateWithLifecycle()
    val showSettings by viewModel.showSettings.collectAsStateWithLifecycle()
    val profile by com.android.virtualization.terminal.new2.core.VmProfiles.selected.collectAsStateWithLifecycle()
    val officialRequested by com.android.virtualization.terminal.new2.core.VmProfiles.officialRequested.collectAsStateWithLifecycle()
    val switching by com.android.virtualization.terminal.new2.core.VmController.switching.collectAsStateWithLifecycle()
    val isFullscreen by viewModel.isFullscreen.collectAsStateWithLifecycle()
    val hasMandatoryPermissions by viewModel.hasMandatoryPermissions.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val activity = context as Activity

    PermissionChecker(viewModel, snackbarHostState)

    LaunchedEffect(uiState) {
        when (val state = uiState) {
            is MainUiState.Ready -> {
                snackbarHostState.currentSnackbarData?.dismiss()
            }
            is MainUiState.Stopped -> {}
            is MainUiState.Error -> {}
            else -> {}
        }
    }

    LaunchedEffect(uiState is MainUiState.Running, profile.id) {
        if (uiState is MainUiState.Running) {
            viewModel.setShowSettings(false)
            when (profile.screen) {
                "console" -> context.startActivity(Intent().setClassName(context.packageName,
                    "com.android.virtualization.terminal.ConsoleProbeActivity"))
                "display" -> if (viewModel.displayState.value != DisplayState.Normal) viewModel.toggleDisplay()
                else -> if (viewModel.displayState.value != DisplayState.Hidden) viewModel.toggleDisplay()
            }
        }
    }
    LaunchedEffect(officialRequested) { if (officialRequested) viewModel.setShowSettings(false) }

    Scaffold(snackbarHost = { SnackbarHost(hostState = snackbarHostState) }) { innerPadding ->
        val padding = if (isFullscreen) PaddingValues(0.dp) else innerPadding

        Box(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                if (!hasMandatoryPermissions) {
                    PermissionScreen(viewModel = viewModel)
                } else if (profile.isDefault && installState !is InstallState.Installed) {
                    if (officialRequested || installState.isStarted()) {
                        Column(Modifier.fillMaxSize()) {
                            androidx.compose.material3.TextButton(onClick = { com.android.virtualization.terminal.new2.core.VmProfiles.requestOfficial(false) }, enabled = !installState.isStarted()) { androidx.compose.material3.Text("更改系统来源") }
                            Box(Modifier.weight(1f)) { InstallScreen(snackbarHostState = snackbarHostState) }
                        }
                    } else if (installState is InstallState.Checking) BootingScreen()
                    else VmManagementPage(firstSetup = true)
                } else
                    when (val state = uiState) {
                        is MainUiState.Ready -> {
                            // VM will soon be booting
                        }
                        is MainUiState.Stopped -> {
                            if (switching) BootingScreen() else VmStoppedScreen(viewModel)
                        }
                        is MainUiState.Error -> VmStoppedScreen(viewModel, (state.handler as? MainUiState.ErrorHandler.ReportBug)?.error?.message)
                        is MainUiState.Booting -> BootingScreen()
                        is MainUiState.Running -> RunningScreen(state, viewModel)
                        is MainUiState.Stopping -> BootingScreen() // TODO: show the shutdown screen
                        else -> {}
                    }
            }

            AnimatedVisibility(
                visible = showSettings,
                enter = slideInHorizontally(initialOffsetX = { it }),
                exit = slideOutHorizontally(targetOffsetX = { it }),
            ) {
                SettingsScreen(onBack = { viewModel.setShowSettings(false) })
            }
        }
    }
}

@Composable
fun SplashScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun BootingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun RunningScreen(state: MainUiState.Running, viewModel: MainViewModel) {
    val context = LocalContext.current
    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    val selectedTabId by viewModel.selectedTabId.collectAsStateWithLifecycle()
    val displayState by viewModel.displayState.collectAsStateWithLifecycle()
    val isFullscreen by viewModel.isFullscreen.collectAsStateWithLifecycle()

    Column {
        if (!isFullscreen) {
            androidx.compose.material3.Surface(
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface),
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        TerminalTabBar(
                            tabs = tabs,
                            selectedTabId =
                                if (displayState == DisplayState.Normal) null else selectedTabId,
                            onTabSelected = { viewModel.selectTab(it) },
                            onTabClosed = { viewModel.closeTab(it) },
                            onAddTab = { viewModel.addTab() },
                        )
                    }
                    run {
                        IconButton(onClick = {
                            context.startActivity(Intent().setClassName(context.packageName,
                                "com.android.virtualization.terminal.ConsoleProbeActivity"))
                        }) {
                            Icon(Icons.Default.Terminal, contentDescription = stringResource(R.string.plus_direct_console))
                        }
                    }
                    DisplayController(viewModel = viewModel)
                    IconButton(
                        onClick = {
                            viewModel.setShowSettings(true)
                            viewModel.setIsImeVisible(false)
                        }
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            }
        }
        if (displayState == DisplayState.Normal) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Wrap with key(isFullscreen) to force recreation of DisplayScreen (and its
                // internal SurfaceView and DisplayProvider) when toggling fullscreen.
                // This ensures that the VM display is correctly updated to the new
                // layout dimensions.
                key(isFullscreen) { DisplayScreen(viewModel = viewModel) }
            }
        } else {
            val connection = state.terminalConnection
            if (tabs.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Text(stringResource(R.string.plus_terminal_no_sessions),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (connection is com.android.virtualization.terminal.new2.core.TerminalConnection.Endpoint) {
                TerminalScreen(connection.address, selectedTabId, viewModel)
            } else {
                TerminalServiceNotice(onRetry = { com.android.virtualization.terminal.new2.core.VmController.retryTerminalConnection() })
            }
        }
    }
}

private suspend fun handleError(
    activity: Activity,
    snackbarHostState: SnackbarHostState,
    handler: MainUiState.ErrorHandler,
) {
    val (messageId, actionLabel, action) =
        when (handler) {
            is MainUiState.ErrorHandler.ReportBug ->
                Triple(
                    R.string.error_title,
                    activity.getString(R.string.error_btn_report_bug),
                    {
                        val error = handler.error
                        val exception = error as? Exception ?: Exception(error)
                        BetterBugLauncher.launchBetterBugActivity(activity, exception)
                    },
                )
        }

    val result =
        snackbarHostState.showSnackbar(
            message = activity.getString(messageId),
            actionLabel = actionLabel,
            duration = SnackbarDuration.Indefinite,
        )
    if (result == SnackbarResult.ActionPerformed) {
        action()
    }
}

@Composable
private fun VmStoppedScreen(viewModel: MainViewModel, error: String? = null) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        androidx.compose.material3.Text(error ?: "虚拟机已停止")
        androidx.compose.material3.TextButton(onClick = { viewModel.startVm() }) {
            androidx.compose.material3.Text(if (error == null) "启动虚拟机" else "重试启动")
        }
        androidx.compose.material3.TextButton(onClick = { viewModel.setShowSettings(true) }) {
            androidx.compose.material3.Text("虚拟机与设置")
        }
    }
}
