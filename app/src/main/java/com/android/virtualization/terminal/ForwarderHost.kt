/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal

import android.os.ParcelFileDescriptor
import android.system.virtualmachine.VirtualMachine
import android.util.Log
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/** Local TCP -> AVF-owned vsock -> small guest localhost proxy (bundled in cidata). */
object ForwarderHost {
    interface ForwardingCallback { fun onForwardingRequestReceived(guestTcpPort: Int, vsockPort: Int) }
    private val lock = Any()
    private var requested = emptySet<Int>()
    private var running: Session? = null
    private var vm: VirtualMachine? = null
    fun attach(vm: VirtualMachine) = synchronized(lock) { this.vm = vm }

    private class Session(val vm: VirtualMachine) {
        val done = CountDownLatch(1)
        val listeners = mutableMapOf<String, ServerSocket>()
        val sockets = ConcurrentHashMap.newKeySet<Socket>()
        val descriptors = ConcurrentHashMap.newKeySet<ParcelFileDescriptor>()
        @Volatile var stopped = false
        fun stop() {
            stopped = true
            listeners.values.forEach { runCatching { it.close() } }; listeners.clear()
            sockets.forEach { runCatching { it.close() } }
            descriptors.forEach { runCatching { it.close() } }
            done.countDown()
        }
    }

    @JvmStatic fun run(cid: Int, callback: ForwardingCallback?) {
        val session = synchronized(lock) {
            val machine = checkNotNull(vm) { "No VM attached" }
            check(machine.cid == cid) { "Stale forwarding request" }
            Session(machine).also { running?.stop(); running = it; update(it) }
        }
        try { session.done.await() } finally {
            synchronized(lock) { session.stop(); if (running === session) running = null }
        }
    }
    @JvmStatic fun shutdown() = synchronized(lock) { running?.stop(); running = null }
    @JvmStatic fun updateListeningPorts(ports: IntArray?) = synchronized(lock) {
        requested = (ports ?: intArrayOf()).filter { it in 1024..65535 }.toSet()
        running?.let { update(it) }
    }
    private fun update(session: Session) {
        val desired = requested.flatMap { port -> listOf("127.0.0.1:$port", "[::1]:$port") }.toSet()
        (session.listeners.keys - desired).forEach { session.listeners.remove(it)?.close() }
        for (port in requested) for (host in listOf("127.0.0.1", "::1")) {
            val key = if (host == "::1") "[::1]:$port" else "$host:$port"
            if (key in session.listeners) continue
            try {
                val listener = ServerSocket(port, 50, InetAddress.getByName(host))
                session.listeners[key] = listener
                thread(name = "PlusForward-$port", isDaemon = true) {
                    while (!session.stopped && !listener.isClosed) {
                        try {
                            val client = listener.accept()
                            session.sockets.add(client)
                            thread(name = "PlusForwardSession", isDaemon = true) { forward(session, client, port) }
                        } catch (e: Exception) {
                            if (!listener.isClosed) Log.w("ForwarderHost", "Accept failed", e)
                            break
                        }
                    }
                }
            } catch (e: Exception) { Log.w("ForwarderHost", "Cannot bind $key", e) }
        }
    }
    private fun forward(session: Session, client: Socket, port: Int) {
        var guest: ParcelFileDescriptor? = null
        var writeFd: ParcelFileDescriptor? = null
        try {
            if (session.stopped) return
            guest = session.vm.connectVsock(7682)
            session.descriptors.add(guest)
            writeFd = ParcelFileDescriptor.dup(guest.fileDescriptor)
            session.descriptors.add(writeFd)
            if (session.stopped) return
            val input = ParcelFileDescriptor.AutoCloseInputStream(guest)
            val output = ParcelFileDescriptor.AutoCloseOutputStream(writeFd)
            output.write(byteArrayOf((port ushr 8).toByte(), port.toByte()))
            output.flush()
            val upload = thread(name = "PlusForwardUpload", isDaemon = true) {
                try { client.getInputStream().copyTo(output) }
                catch (_: Exception) { }
                finally { runCatching { android.system.Os.shutdown(writeFd.fileDescriptor, android.system.OsConstants.SHUT_WR) } }
            }
            try { input.copyTo(client.getOutputStream()) }
            finally { runCatching { input.close() }; runCatching { client.close() } }
            upload.join()
        } catch (e: Exception) {
            if (!session.stopped) Log.w("ForwarderHost", "Forwarding port $port failed", e)
        } finally {
            runCatching { client.close() }; session.sockets.remove(client)
            guest?.let { runCatching { it.close() }; session.descriptors.remove(it) }
            writeFd?.let { runCatching { it.close() }; session.descriptors.remove(it) }
        }
    }
}
