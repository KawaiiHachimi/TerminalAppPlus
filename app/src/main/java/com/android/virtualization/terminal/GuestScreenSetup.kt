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
    private const val READY = "TERMINAL_PLUS_CAPTURE_READY_v1"
    private val client = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS).build()

    fun ensure(context: Context, port: Int, token: String, current: () -> Boolean) {
        val id = java.io.File(context.filesDir, "linux/cidata.build_id").readText().trim()
        if (id != "15101902-plus-display1") return
        fun payload(name: String) = context.assets.open("guest-setup/$name").use {
            Base64.encodeToString(it.readBytes(), Base64.NO_WRAP)
        }
        val script = """
            set -eu
            test -d /etc/systemd/system
            command -v python3 >/dev/null
            temp=${'$'}(mktemp -d)
            trap 'rm -rf "${'$'}temp"' EXIT
            printf '%s' '${payload("terminal-plus-capture.py")}' | base64 -d > "${'$'}temp/capture.py"
            printf '%s' '${payload("terminal-plus-capture.service")}' | base64 -d > "${'$'}temp/capture.service"
            changed=0
            if ! cmp -s "${'$'}temp/capture.py" /usr/local/bin/terminal-plus-capture.py; then
                install -m 755 "${'$'}temp/capture.py" /usr/local/bin/terminal-plus-capture.py
                changed=1
            fi
            if ! cmp -s "${'$'}temp/capture.service" /etc/systemd/system/terminal-plus-capture.service; then
                install -m 644 "${'$'}temp/capture.service" /etc/systemd/system/terminal-plus-capture.service
                changed=1
            fi
            systemctl stop terminal-plus-kms-probe.service 2>/dev/null || true
            systemctl daemon-reload
            systemctl enable --now terminal-plus-capture.service
            if [ "${'$'}changed" = 1 ]; then systemctl restart terminal-plus-capture.service; fi
            systemctl is-active --quiet terminal-plus-capture.service
            printf '\n$READY\n'
        """.trimIndent()
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
            if (success.get()) { Log.i("GuestScreenSetup", "Persistent capture service is ready"); return }
            if (current()) Thread.sleep(1000)
        }
        Log.w("GuestScreenSetup", "Capture setup did not complete; guest sudo or systemd may be unavailable")
    }
}
