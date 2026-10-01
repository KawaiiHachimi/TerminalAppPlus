/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal.new2.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.android.virtualization.terminal.R

@Composable
internal fun VmConfigModeSelector(jsonMode: Boolean, onModeChange: (Boolean) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        listOf(R.string.plus_resource_settings, R.string.plus_json_editor).forEachIndexed { index, label ->
            SegmentedButton(
                selected = jsonMode == (index == 1),
                onClick = { onModeChange(index == 1) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                Text(stringResource(label), textAlign = TextAlign.Center)
            }
        }
    }
}
