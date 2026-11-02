/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal

/** Request pixels from layout bounds, never from a codec-owned Surface buffer. */
internal object CaptureDisplaySize {
    fun requested(width: Int, height: Int, scale: Float, h264: Boolean): Pair<Int, Int> {
        require(width > 0 && height > 0 && scale.isFinite() && scale > 0)
        fun pixels(value: Int): Int {
            val n = (value * scale).toInt().coerceAtLeast(1)
            return if (h264) (n and -2).coerceAtLeast(2) else n
        }
        return pixels(width) to pixels(height)
    }
}
