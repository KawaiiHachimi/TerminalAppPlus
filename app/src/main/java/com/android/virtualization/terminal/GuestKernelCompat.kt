/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal

import android.os.Build
import android.util.Log
import java.io.File
import java.security.MessageDigest

/** Compatibility for the tested MT6991 GenieZone firmware, for NON-protected guests only. */
internal object GuestKernelCompat {
    private const val STOCK_SHA256 = "1008c4aa740113c3bccd1aa72376bcd23602a2f94aa170d51617b7089c647343"
    private const val PATCHED_SHA256 = "e8250ecd77b159e9013e3816ce8dece12e58fc0758d3fbb24277c640e67cf717"
    private const val OFFSET = 0xea8f7c
    private val original = byteArrayOf(0x3f, 0x23, 0x03, 0xd5.toByte()) // paciasp
    private val replacement = byteArrayOf(0xc0.toByte(), 0x03, 0x5f, 0xd6.toByte()) // ret
    val required: Boolean get() = Build.HARDWARE == "mt6991" && File("/dev/gzvm").exists()

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    @Synchronized fun kernelPath(path: String?, protectedVm: Boolean): String? {
        if (!required || protectedVm || path == null) return path
        val source = File(path)
        val data = source.readBytes()
        val digest = hash(data)
        if (digest == PATCHED_SHA256) return path
        check(digest == STOCK_SHA256) {
            "This MT6991 GenieZone device requires a verified guest-kernel compatibility fix. " +
                "Unknown kernel SHA-256: $digest. Original image was left unchanged."
        }
        check(data.copyOfRange(OFFSET, OFFSET + 4).contentEquals(original))
        replacement.copyInto(data, OFFSET)
        check(hash(data) == PATCHED_SHA256)
        val target = File(source.parentFile, "vmlinuz-terminal-plus")
        if (!target.exists() || hash(target.readBytes()) != PATCHED_SHA256) {
            val tmp = File(source.parentFile, "vmlinuz-terminal-plus.tmp")
            tmp.writeBytes(data)
            java.nio.file.Files.move(tmp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        Log.i("GuestKernelCompat", "Using verified non-protected GenieZone guest compatibility kernel")
        return target.absolutePath
    }
}
