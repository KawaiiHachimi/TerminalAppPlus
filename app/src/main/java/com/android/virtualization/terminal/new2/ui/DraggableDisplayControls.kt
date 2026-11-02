/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal.new2.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.virtualization.terminal.R
import com.android.virtualization.terminal.new2.ui.main.MainViewModel
import com.android.virtualization.terminal.new2.ui.main.SettingsViewModel
import kotlin.math.roundToInt

/** A normalized position keeps the control reachable after rotation, IME and toolbar size changes. */
@Composable
internal fun DraggableDisplayControls(
    viewModel: MainViewModel,
    settingsViewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val initialPosition = remember { settingsViewModel.displayControlsPosition() }
    var x by rememberSaveable { mutableFloatStateOf(initialPosition.first) }
    var y by rememberSaveable { mutableFloatStateOf(initialPosition.second) }
    var bounds by remember { mutableStateOf(IntSize.Zero) }
    var controlSize by remember { mutableStateOf(IntSize.Zero) }
    var dragging by remember { mutableStateOf(false) }
    val isFullscreen by viewModel.isFullscreen.collectAsStateWithLifecycle()

    fun finishDrag() {
        dragging = false
        settingsViewModel.setDisplayControlsPosition(x, y)
    }

    Box(modifier.windowInsetsPadding(WindowInsets.displayCutout).onSizeChanged { bounds = it }) {
        Box(
            Modifier
                .offset {
                    IntOffset(
                        (x * (bounds.width - controlSize.width).coerceAtLeast(0)).roundToInt(),
                        (y * (bounds.height - controlSize.height).coerceAtLeast(0)).roundToInt(),
                    )
                }
                .onSizeChanged { controlSize = it }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = { finishDrag() },
                        onDragCancel = { finishDrag() },
                    ) { change, delta ->
                        // Consuming movement cancels the nested button click when this is a drag.
                        change.consume()
                        val travelX = (bounds.width - controlSize.width).coerceAtLeast(0)
                        val travelY = (bounds.height - controlSize.height).coerceAtLeast(0)
                        if (travelX > 0) x = (x + delta.x / travelX).coerceIn(0f, 1f)
                        if (travelY > 0) y = (y + delta.y / travelY).coerceIn(0f, 1f)
                    }
                }
        ) {
            if (isFullscreen) {
                DisplayController(viewModel, isDragging = dragging)
            } else {
                IconButton(onClick = { viewModel.setIsFullscreen(true) }) {
                    Icon(
                        Icons.Default.Fullscreen,
                        contentDescription = stringResource(R.string.fullscreen_enter),
                        tint = Color.White.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}
