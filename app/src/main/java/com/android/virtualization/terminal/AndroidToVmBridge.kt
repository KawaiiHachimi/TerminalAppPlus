/*
 * Copyright 2025 The Android Open Source Project
 * Copyright 2026 Terminal Plus contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.virtualization.terminal

import android.os.ParcelFileDescriptor
import android.system.virtualmachine.VirtualMachine
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Authenticated localhost HTTP/WebSocket bridge to the stock Debian ttyd service. */
class AndroidToVmBridge(
    private val vm: VirtualMachine,
    private val vmPort: Int = 7681,
    val secretKey: String = UUID.randomUUID().toString(),
) {
    private var listener: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val descriptors = ConcurrentHashMap.newKeySet<ParcelFileDescriptor>()

    @Synchronized fun start(): Int? {
        if (running.get()) return listener?.localPort
        return try {
            val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            listener = server
            running.set(true)
            thread(name = "PlusTtydListener", isDaemon = true) {
                while (running.get()) {
                    try {
                        val client = server.accept()
                        clients.add(client)
                        thread(name = "PlusTtydClient", isDaemon = true) { handle(client) }
                    } catch (e: Exception) {
                        if (running.get()) Log.w(TAG, "Accept failed", e)
                        break
                    }
                }
            }
            Log.i(TAG, "Listening on 127.0.0.1:${server.localPort}")
            server.localPort
        } catch (e: Exception) { Log.e(TAG, "Cannot start bridge", e); null }
    }

    @Synchronized fun stop() {
        running.set(false)
        runCatching { listener?.close() }
        listener = null
        clients.forEach { runCatching { it.close() } }
        descriptors.forEach { runCatching { it.close() } }
    }

    private fun connect(): ParcelFileDescriptor? {
        // AVF opens the socket on our behalf; untrusted_app does not need direct vsock access.
        repeat(600) {
            if (!running.get()) return null
            try { return vm.connectVsock(vmPort.toLong()) }
            catch (e: Exception) {
                if (it == 599) Log.e(TAG, "Guest ttyd did not become ready", e)
                Thread.sleep(100)
            }
        }
        return null
    }

    private fun handle(client: Socket) {
        var guest: ParcelFileDescriptor? = null
        var outputFd: ParcelFileDescriptor? = null
        try {
            client.soTimeout = 15_000
            val input = client.getInputStream()
            val output = client.getOutputStream()
            val header = ByteArrayOutputStream()
            var tail = 0
            while (header.size() < 32_768) {
                val b = input.read()
                if (b < 0) return
                header.write(b)
                tail = (tail shl 8) or b
                if (tail == 0x0d0a0d0a) break
            }
            val bytes = header.toByteArray()
            val authorized = tail == 0x0d0a0d0a && bytes.toString(Charsets.ISO_8859_1)
                .split("\r\n").filter { it.startsWith("Cookie:", ignoreCase = true) }
                .flatMap { it.substringAfter(':').split(';') }
                .any { it.trim() == "access_token=$secretKey" }
            if (!authorized) {
                output.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }
            client.soTimeout = 0
            guest = connect()
            if (guest == null) {
                output.write("HTTP/1.1 504 Gateway Timeout\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }
            descriptors.add(guest)
            outputFd = ParcelFileDescriptor.dup(guest.fileDescriptor)
            descriptors.add(outputFd)
            if (!running.get()) return
            val guestInput = ParcelFileDescriptor.AutoCloseInputStream(guest)
            val guestOutput = ParcelFileDescriptor.AutoCloseOutputStream(outputFd)
            guestOutput.write(bytes)
            guestOutput.flush()
            val upload = thread(name = "PlusTtydUpload", isDaemon = true) {
                try { input.copyTo(guestOutput) }
                catch (_: Exception) { }
                finally { runCatching { guestOutput.close() }; runCatching { client.close() } }
            }
            try { guestInput.copyTo(output) }
            finally { runCatching { guestInput.close() }; runCatching { client.close() } }
            upload.join()
        } catch (e: Exception) {
            if (running.get()) Log.d(TAG, "Connection closed: ${e.message}")
        } finally {
            runCatching { client.close() }; clients.remove(client)
            guest?.let { runCatching { it.close() }; descriptors.remove(it) }
            outputFd?.let { runCatching { it.close() }; descriptors.remove(it) }
        }
    }
    companion object { private const val TAG = "AndroidToVmBridge" }
}
