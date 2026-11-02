/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** Guest Binder RPCs may wait indefinitely. Keep them off UI/lifecycle locks and do not queue more. */
internal class ClipboardSync(private val executor: Executor) {
    private val busy = AtomicBoolean()
    fun submit(action: () -> Unit) {
        if (!busy.compareAndSet(false, true)) return
        try {
            executor.execute { try { action() } finally { busy.set(false) } }
        } catch (error: Exception) {
            busy.set(false)
            throw error
        }
    }
}
