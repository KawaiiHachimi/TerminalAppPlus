/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal

import android.app.Activity
import android.os.Bundle
import java.util.concurrent.Executors

/** Terminal UI for the current VM. Closing this Activity never stops the VM. */
class ConsoleProbeActivity : Activity() {
    private lateinit var terminal: com.termux.view.TerminalView
    private lateinit var client: ProbeTerminalClient
    private lateinit var session: com.termux.terminal.AvfTerminalSession
    private val writer = Executors.newSingleThreadExecutor()
    private var replayingConsole = false
    private val consoleListener: (ByteArray) -> Unit = { bytes ->
        runOnUiThread { if (!isDestroyed) session.append(bytes) }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        terminal = object : com.termux.view.TerminalView(this, null) {
            override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
                try {
                    return super.onKeyDown(keyCode, event)
                } finally {
                    // Also covers arrows, Tab and IME backspace, which may not
                    // produce a code point or a matching key-up event.
                    if (!android.view.KeyEvent.isModifierKey(keyCode) && !event.isSystem) {
                        client.releaseModifiers()
                    }
                }
            }
        }.apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            setTextSize((12 * resources.displayMetrics.scaledDensity).toInt())
            // Load the actual font file, bypassing vendor replacements of the monospace alias.
            setTypeface(runCatching {
                android.graphics.Typeface.createFromFile("/system/fonts/DroidSansMono.ttf")
            }.getOrElse {
                android.util.Log.w("ConsoleProbe", "Stock monospace font unavailable; using platform fallback", it)
                android.graphics.Typeface.MONOSPACE
            })
        }
        client = ProbeTerminalClient(terminal)
        terminal.setTerminalViewClient(client)
        session = com.termux.terminal.AvfTerminalSession(client) { bytes ->
            if (!replayingConsole) writer.execute { runCatching {
                VmConsole.write(bytes)
            } }
        }
        terminal.attachSession(session)
        val container = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        container.addView(terminal, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        fun key(label: String, code: Int): Pair<String, () -> Unit> = label to {
            terminal.onKeyDown(code, android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
            terminal.onKeyUp(code, android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
        }
        fun row(actions: List<Pair<String, () -> Unit>>) {
            val row = android.widget.LinearLayout(this)
            actions.forEach { (label, action) ->
                row.addView(android.widget.Button(this).apply {
                    text = label
                    textSize = 12f
                    setTextColor(android.graphics.Color.LTGRAY)
                    background = android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(0x55FFFFFF),
                        android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK), null)
                    isAllCaps = false
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(0, 0, 0, 0)
                    isFocusable = false
                    setOnClickListener { action(); terminal.requestFocus() }
                }, android.widget.LinearLayout.LayoutParams(0, (36 * resources.displayMetrics.density).toInt(), 1f))
            }
            container.addView(row)
        }
        row(listOf(key("ESC", 111), " / " to { terminal.inputCodePoint(0, '/'.code, false, false) }, " - " to { terminal.inputCodePoint(0, '-'.code, false, false) }, key("HOME", 122), key("↑", 19), key("END", 123), key("PGUP", 92)))
        row(listOf(
            key("TAB", 61),
            "Ctrl" to { client.control = !client.control; client.onModifiersChanged() },
            "Alt" to { client.alt = !client.alt; client.onModifiersChanged() },
            key("←", 21), key("↓", 20), key("→", 22), key("PGDN", 93),
        ))
        val modifiers = container.getChildAt(2) as android.widget.LinearLayout
        client.onModifiersChanged = {
            (modifiers.getChildAt(1) as android.widget.Button).text = if (client.control) "Ctrl ●" else "Ctrl"
            (modifiers.getChildAt(2) as android.widget.Button).text = if (client.alt) "Alt ●" else "Alt"
        }

        container.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(container)
        terminal.requestFocus()
        replayingConsole = true
        val connected = VmConsole.subscribe(consoleListener)
        replayingConsole = false
        if (!connected) {
            session.append("当前虚拟机未运行，请返回主界面启动后重试。\r\n".toByteArray())
        }
    }
    override fun onDestroy() {
        VmConsole.unsubscribe(consoleListener)
        writer.shutdownNow()
        super.onDestroy()
    }
}
