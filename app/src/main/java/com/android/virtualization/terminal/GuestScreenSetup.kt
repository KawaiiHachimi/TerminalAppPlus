/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal

import android.content.Context
import android.util.Base64
import android.util.Log
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Idempotent upgrade for the app-managed Debian guest; never used by custom-image labs. */
internal object GuestScreenSetup {
    private const val READY = "TERMINAL_PLUS_GUEST_READY_v2"
    private val client = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS).build()

    fun ensure(context: Context, port: Int, token: String, current: () -> Boolean) {
        val id = java.io.File(context.filesDir, "linux/cidata.build_id").readText().trim()
        if (id != "15101902-plus-display1") return
        val script = GuestTools.installScript(context) + "\nprintf '\\n$READY\\n'\n"
        val encoded = Base64.encodeToString(script.toByteArray(), Base64.NO_WRAP)
        // Fixed packaged installer, no guest-controlled command text. Separate ttyd session.
        val command = "printf '%s' '$encoded' | base64 -d | sudo -n sh\r"
        repeat(12) {
            if (!current()) return
            val done = CountDownLatch(1)
            val success = AtomicBoolean()
            val sent = AtomicBoolean()
            val tail = StringBuilder()
            val request = Request.Builder().url("ws://127.0.0.1:$port/ws")
                .header("Cookie", "access_token=$token").header("Sec-WebSocket-Protocol", "tty").build()
            val socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"AuthToken":"","columns":120,"rows":40}""")
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (bytes.size == 0 || bytes[0] != '0'.code.toByte()) return
                    synchronized(tail) {
                        tail.append(bytes.substring(1).utf8())
                        // Wait until readline is ready; sending a long payload during shell init
                        // can overflow a canonical-mode PTY input line.
                        val promptReady = tail.contains("\u001b[?2004h") || tail.endsWith("$ ") || tail.endsWith("# ")
                        if (promptReady && sent.compareAndSet(false, true) && current()) {
                            webSocket.send((byteArrayOf('0'.code.toByte()) + command.toByteArray()).toByteString())
                        }
                        if (tail.contains(READY)) { success.set(true); done.countDown() }
                        if (tail.length > 4096) tail.delete(0, tail.length - 2048)
                    }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { done.countDown() }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { done.countDown() }
            })
            try { done.await(8, TimeUnit.SECONDS) } finally { socket.cancel() }
            if (success.get()) { Log.i("GuestScreenSetup", "Unified guest service is ready"); return }
            if (current()) Thread.sleep(1000)
        }
        Log.w("GuestScreenSetup", "Guest tools setup did not complete; guest sudo or systemd may be unavailable")
    }
}
