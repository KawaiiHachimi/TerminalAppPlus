/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal

/** One producer, one renderer, one replaceable pending frame. No growing frame backlog. */
internal class LatestFrameQueue {
    data class Frame(val width: Int, val height: Int, val bytes: ByteArray)
    private val lock = java.lang.Object()
    private val buffers = ArrayDeque<ByteArray>()
    private var pending: Frame? = null
    private var closed = false
    @Volatile var dropped = 0
        private set

    fun acquire(length: Int): ByteArray = synchronized(lock) {
        while (buffers.isNotEmpty()) {
            val bytes = buffers.removeFirst()
            if (bytes.size == length) return@synchronized bytes
        }
        ByteArray(length)
    }
    fun offer(frame: Frame) = synchronized(lock) {
        if (!closed) {
            pending?.let { recycleLocked(it.bytes); dropped++ }
            pending = frame
            lock.notifyAll()
        }
    }
    fun take(): Frame? = synchronized(lock) {
        while (pending == null && !closed) lock.wait()
        if (closed) null else pending.also { pending = null }
    }
    fun recycle(bytes: ByteArray) = synchronized(lock) { if (!closed) recycleLocked(bytes) }
    private fun recycleLocked(bytes: ByteArray) {
        if (buffers.size < 3) buffers.addLast(bytes)
    }
    fun close() = synchronized(lock) {
        closed = true
        pending = null
        buffers.clear()
        lock.notifyAll()
    }
}
