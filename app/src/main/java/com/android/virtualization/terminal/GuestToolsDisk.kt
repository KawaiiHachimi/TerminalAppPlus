/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Read-only installation media, appended after the user's custom VM disks. */
internal object GuestToolsDisk {
    const val INSTALL_COMMAND = "sudo mkdir -p /mnt/terminal-plus && " +
        "(mountpoint -q /mnt/terminal-plus || sudo mount -o ro /dev/disk/by-label/PLUS_TOOLS /mnt/terminal-plus) && " +
        "sudo sh /mnt/terminal-plus/install.sh --user \"\$(id -un)\""

    // Called on the VM lifecycle IO worker before starting a custom VM.
    fun prepare(context: Context): File {
        val directory = File(context.filesDir, "guest-tools").apply { mkdirs() }
        val disk = File(directory, "guest-tools.iso")
        val data = context.assets.open("guest-tools.iso").use { it.readBytes() }
        if (!disk.exists() || !disk.readBytes().contentEquals(data)) {
            val temporary = File(directory, "guest-tools.tmp")
            temporary.outputStream().use { it.write(data); it.fd.sync() }
            Files.move(temporary.toPath(), disk.toPath(), REPLACE_EXISTING)
        }
        return disk
    }
}
