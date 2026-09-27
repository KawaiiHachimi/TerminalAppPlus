/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructTimeval
import android.system.virtualmachine.VirtualMachine
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Sideband metadata only; never writes a shell command into the serial input stream. */
internal class ConsoleResizeClient(
    private val vm: VirtualMachine,
    private val device: String,
    private val status: (Int, Int, Boolean) -> Unit,
) : AutoCloseable {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var pending: ScheduledFuture<*>? = null
    @Volatile private var generation = 0L
    @Volatile private var closed = false
    @Volatile private var connection: ParcelFileDescriptor? = null

    @Synchronized fun update(columns: Int, rows: Int) {
        if (closed) return
        val ticket = ++generation
        pending?.cancel(false)
        status(columns, rows, false)
        pending = executor.schedule({ send(ticket, columns, rows, 0) }, 200, TimeUnit.MILLISECONDS)
    }

    private fun send(ticket: Long, columns: Int, rows: Int, attempt: Int) {
        if (closed || generation != ticket || vm.status != VirtualMachine.STATUS_RUNNING) return
        val ok = runCatching {
            val index = when (device) { "hvc0" -> 0; "ttyS0" -> 1; else -> error("Unsupported console") }
            vm.connectVsock(7684).use { fd ->
                connection = fd
                if (closed || generation != ticket) return@use false
                Os.setsockoptTimeval(fd.fileDescriptor, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO, StructTimeval.fromMillis(1500))
                Os.setsockoptTimeval(fd.fileDescriptor, OsConstants.SOL_SOCKET, OsConstants.SO_SNDTIMEO, StructTimeval.fromMillis(1500))
                val packet = ByteBuffer.allocate(9).put(byteArrayOf(84, 80, 82, 49))
                    .put(index.toByte()).putShort(rows.toShort()).putShort(columns.toShort()).array()
                FileOutputStream(fd.fileDescriptor).write(packet)
                FileInputStream(fd.fileDescriptor).read() == 0
            }
        }.getOrDefault(false)
        connection = null
        synchronized(this) {
            if (closed || generation != ticket) return
            status(columns, rows, ok)
            // Retain the latest dimensions while the guest boots or installs its helper.
            if (!ok) pending = executor.schedule({ send(ticket, columns, rows, attempt + 1) },
                if (attempt < 5) 2L else 10L, TimeUnit.SECONDS)
        }
    }

    @Synchronized override fun close() {
        closed = true
        ++generation
        pending?.cancel(false)
        runCatching { connection?.close() }
        executor.shutdownNow()
    }
}
