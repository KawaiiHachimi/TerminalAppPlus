/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal.new2.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.virtualization.terminal.BuildConfig
import com.android.virtualization.terminal.R

@Composable
fun TerminalServiceNotice(onRetry: () -> Unit, emptySession: Boolean = false) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(stringResource(if (emptySession) R.string.plus_terminal_no_sessions else R.string.plus_terminal_unavailable))
        if (BuildConfig.DEBUG) {
            TextButton(onClick = {
                context.startActivity(Intent().setClassName(context.packageName,
                    "com.android.virtualization.terminal.ConsoleProbeActivity"))
            }) { Text(stringResource(R.string.plus_direct_console)) }
        }
        TextButton(onClick = onRetry) {
            Text(stringResource(if (emptySession) R.string.plus_terminal_new_session else R.string.plus_terminal_retry))
        }
    }
}
