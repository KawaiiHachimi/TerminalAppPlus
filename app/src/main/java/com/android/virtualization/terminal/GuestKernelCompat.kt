/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal

import android.util.Log
import java.io.File

/** Compatibility for the GenieZone backend, for NON-protected guests only. */
internal object GuestKernelCompat {
    val required: Boolean get() = File("/dev/gzvm").exists()

    @Synchronized fun kernelPath(path: String?, protectedVm: Boolean): String? {
        if (!required || protectedVm || path == null) return path
        val source = File(path)
        val data = source.readBytes()
        val offset = GuestKernelSymbols.patch(data)
        val target = File(source.parentFile, "vmlinuz-terminal-plus")
        if (!target.exists() || !target.readBytes().contentEquals(data)) {
            val tmp = File(source.parentFile, "vmlinuz-terminal-plus.tmp")
            tmp.writeBytes(data)
            java.nio.file.Files.move(tmp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        Log.i("GuestKernelCompat", "Using non-protected GenieZone compatibility kernel at symbol offset 0x${offset.toString(16)}")
        return target.absolutePath
    }
}
