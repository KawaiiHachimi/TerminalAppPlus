/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal.new2.core

import com.android.virtualization.terminal.AppStrings
import com.android.virtualization.terminal.R
import android.system.Os
import android.system.ErrnoException
import android.system.OsConstants
import java.io.File
import java.io.RandomAccessFile
import java.io.InputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal object SparseFiles {
    suspend fun copyStream(input: InputStream, target: File, progress: (Long) -> Unit) {
        RandomAccessFile(target, "rw").use { output ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                check(target.parentFile!!.usableSpace > 64L * 1024 * 1024) { AppStrings.get(R.string.plus_not_enough_storage) }
                if ((0 until count).all { buffer[it] == 0.toByte() }) output.seek(output.filePointer + count)
                else output.write(buffer, 0, count)
                progress(output.filePointer)
            }
            output.setLength(output.filePointer)
            output.fd.sync()
        }
    }
    suspend fun copyFile(source: File, target: File, progress: (Long) -> Unit) {
        RandomAccessFile(source, "r").use { input ->
            RandomAccessFile(target, "rw").use { output ->
                val size = input.length()
                var offset = 0L
                val buffer = ByteArray(1024 * 1024)
                while (offset < size) {
                    currentCoroutineContext().ensureActive()
                    val data = try { Os.lseek(input.fd, offset, 3 /* SEEK_DATA */) } catch (e: ErrnoException) {
                        if (e.errno == OsConstants.ENXIO) break
                        if (e.errno == OsConstants.EINVAL) offset else throw e
                    }
                    val end = try { Os.lseek(input.fd, data, 4 /* SEEK_HOLE */).coerceAtMost(size) } catch (e: ErrnoException) {
                        if (e.errno == OsConstants.EINVAL) size else throw e
                    }
                    input.seek(data); output.seek(data)
                    while (input.filePointer < end) {
                        currentCoroutineContext().ensureActive()
                        check(target.parentFile!!.usableSpace > 64L * 1024 * 1024) { AppStrings.get(R.string.plus_not_enough_storage) }
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), end - input.filePointer).toInt())
                        check(count > 0) { AppStrings.get(R.string.plus_source_disk_incomplete) }
                        if ((0 until count).all { buffer[it] == 0.toByte() }) output.seek(output.filePointer + count)
                        else output.write(buffer, 0, count)
                        progress(input.filePointer)
                    }
                    offset = end
                }
                output.setLength(size); output.fd.sync(); progress(size)
            }
        }
    }
}
