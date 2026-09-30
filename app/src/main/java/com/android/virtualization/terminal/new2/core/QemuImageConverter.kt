/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal.new2.core

import com.android.virtualization.terminal.AppStrings
import com.android.virtualization.terminal.R
import android.content.Context
import java.io.File
import kotlinx.coroutines.*

internal object QemuImageConverter {
    suspend fun convert(context: Context, source: File, destination: File, status: (String) -> Unit) = coroutineScope {
        val executable = File(context.applicationInfo.nativeLibraryDir, "libqemu-img.so")
        check(executable.canExecute()) { AppStrings.get(R.string.plus_converter_unavailable) }
        // Explicit formats and app-owned absolute paths; never pass user text to a shell.
        val process = ProcessBuilder(executable.absolutePath, "convert", "-p", "-f", "qcow2", "-O", "raw",
            "-S", "4k", source.absolutePath, destination.absolutePath)
            .redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = launch(Dispatchers.IO) {
            process.inputStream.bufferedReader().use { stream ->
                val chunk = CharArray(1024)
                while (true) {
                    val count = stream.read(chunk)
                    if (count < 0) break
                    output.append(chunk, 0, count)
                    if (output.length > 8192) output.delete(0, output.length - 8192)
                    Regex("([0-9]+(?:\\.[0-9]+)?)/100%").findAll(output).lastOrNull()?.let {
                        status(AppStrings.get(R.string.plus_qcow_progress , it.groupValues[1]))
                    }
                }
            }
        }
        try {
            while (process.isAlive) {
                ensureActive()
                check(destination.parentFile!!.usableSpace > 64L * 1024 * 1024) { AppStrings.get(R.string.plus_conversion_no_space) }
                delay(150)
            }
            reader.join()
            ensureActive()
            check(process.exitValue() == 0) { AppStrings.get(R.string.plus_qcow_conversion_failed , output.takeLast(1000)) }
        } finally {
            if (process.isAlive) process.destroyForcibly()
            withContext(NonCancellable + Dispatchers.IO) {
                process.waitFor()
                reader.join()
            }
        }
    }
}
