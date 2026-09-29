/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal.new2.core

import android.content.Context
import java.io.File
import kotlinx.coroutines.*

internal object QemuImageConverter {
    suspend fun convert(context: Context, source: File, destination: File, status: (String) -> Unit) = coroutineScope {
        val executable = File(context.applicationInfo.nativeLibraryDir, "libqemu-img.so")
        check(executable.canExecute()) { "此设备缺少可用的 ARM64 镜像转换工具" }
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
                        status("转换 qcow2：${it.groupValues[1]}%")
                    }
                }
            }
        }
        try {
            while (process.isAlive) {
                ensureActive()
                check(destination.parentFile!!.usableSpace > 64L * 1024 * 1024) { "存储空间不足，转换已取消" }
                delay(150)
            }
            reader.join()
            ensureActive()
            check(process.exitValue() == 0) { "qcow2 转换失败：${output.takeLast(1000)}" }
        } finally {
            if (process.isAlive) process.destroyForcibly()
            withContext(NonCancellable + Dispatchers.IO) {
                process.waitFor()
                reader.join()
            }
        }
    }
}
