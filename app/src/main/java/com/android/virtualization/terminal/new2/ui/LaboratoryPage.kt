/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal.new2.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.android.virtualization.terminal.BuildConfig
import com.android.virtualization.terminal.R

/** Experimental activities stay in debug builds and do not replace the managed VM. */
@Composable
fun LaboratoryPage() {
    if (!BuildConfig.DEBUG) return
    val context = LocalContext.current
    fun launch(activity: String, uboot: Boolean? = null) {
        val intent = Intent().setClassName(context.packageName, "com.android.virtualization.terminal.$activity")
        if (uboot != null) intent.putExtra("lab", true).putExtra("uboot", uboot).putExtra("disk", uboot)
        context.startActivity(intent)
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.plus_lab_console)) },
                supportingContent = { Text(stringResource(R.string.plus_lab_console_hint)) },
                modifier = Modifier.clickable { launch("ConsoleProbeActivity", false) },
            )
            HorizontalDivider()
        }
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.plus_lab_uboot)) },
                supportingContent = { Text(stringResource(R.string.plus_lab_uboot_hint)) },
                modifier = Modifier.clickable { launch("ConsoleProbeActivity", true) },
            )
            HorizontalDivider()
        }
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.plus_lab_display)) },
                supportingContent = { Text(stringResource(R.string.plus_lab_display_hint)) },
                modifier = Modifier.clickable { launch("VmScreenProbeActivity") },
            )
        }
    }
}
