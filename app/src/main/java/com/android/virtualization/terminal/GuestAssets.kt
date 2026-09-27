/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal object GuestAssets {
    /** Migrate this port's original cidata without treating the user's root disk as obsolete. */
    fun updateKnownCidata(context: Context) {
        val dir = File(context.filesDir, "linux")
        val id = File(dir, InstalledImage.CIDATA_BUILD_ID_FILENAME)
        if (!File(dir, "completed").exists() || !id.exists()) return
        val old = id.readText().trim()
        val next = context.assets.open(InstalledImage.CIDATA_BUILD_ID_FILENAME)
            .bufferedReader().use { it.readText() }
        if (old !in setOf("15101902", "15101902-plus1") || next.trim() != "15101902-plus-display1") return
        val temporary = File(dir, "cidata-plus.tmp")
        context.assets.open("cidata.iso").use { input -> temporary.outputStream().use { input.copyTo(it) } }
        Files.move(temporary.toPath(), File(dir, "cidata.iso").toPath(), REPLACE_EXISTING)
        id.writeText(next)
    }
}
