/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal

import android.app.Activity
import android.os.Bundle
import android.system.virtualmachine.VirtualMachine
import android.system.virtualmachine.VirtualMachineConfig
import android.system.virtualmachine.VirtualMachineCustomImageConfig
import android.system.virtualmachine.VirtualMachineManager
import java.io.File
import java.util.concurrent.Executors

/** Debug-only console experiment. Never attaches the user's existing Debian root disk. */
class ConsoleProbeActivity : Activity() {
    private val workers = Executors.newFixedThreadPool(3)
    @Volatile private var vm: VirtualMachine? = null
    @Volatile private var consoleInput: java.io.OutputStream? = null
    private lateinit var terminal: com.termux.view.TerminalView
    private lateinit var session: com.termux.terminal.AvfTerminalSession
    private val writer = Executors.newSingleThreadExecutor()
    private var sharedConsole = false
    private var replayingConsole = false
    private val consoleListener: (ByteArray) -> Unit = { bytes ->
        runOnUiThread { if (!isDestroyed) session.append(bytes) }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        sharedConsole = !intent.getBooleanExtra("lab", false) && !intent.hasExtra("uboot")
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        terminal = com.termux.view.TerminalView(this, null).apply {
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
        val client = ProbeTerminalClient(terminal)
        terminal.setTerminalViewClient(client)
        session = com.termux.terminal.AvfTerminalSession(client) { bytes ->
            if (!replayingConsole) writer.execute { runCatching {
                if (sharedConsole) VmConsole.write(bytes)
                else { consoleInput?.write(bytes); consoleInput?.flush() }
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
        row(listOf(key("ESC", 111), " / " to { session.write("/") }, " - " to { session.write("-") }, key("HOME", 122), key("↑", 19), key("END", 123), key("PGUP", 92)))
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
        if (sharedConsole) {
            replayingConsole = true
            val connected = VmConsole.subscribe(consoleListener)
            replayingConsole = false
            if (!connected) {
                session.append("当前虚拟机未运行，请返回主界面启动后重试。\r\n".toByteArray())
            }
            return
        }
        File(filesDir, "console-probe-error.txt").delete()
        val prefs = getSharedPreferences("console-probe", MODE_PRIVATE)
        if (intent.hasExtra("uboot")) prefs.edit().putBoolean("uboot", intent.getBooleanExtra("uboot", false))
            .putBoolean("disk", intent.getBooleanExtra("disk", false)).commit()
        val bootloaderProbe = prefs.getBoolean("uboot", false)
        val diskProbe = bootloaderProbe && prefs.getBoolean("disk", false)
        File(filesDir, "console-probe-mode.txt").writeText("uboot=$bootloaderProbe disk=$diskProbe")
        workers.execute {
            try {
                val manager = getSystemService(VirtualMachineManager::class.java)!!
                val name = "plus-console-probe-${System.nanoTime()}"
                manager.get(name)?.let {
                    if (it.status != VirtualMachine.STATUS_STOPPED) it.stop()
                    manager.delete(name)
                }
                val image = File(filesDir, "linux")
                val customBuilder = VirtualMachineCustomImageConfig.Builder()
                    .setName(name).setOsName("debian")
                if (bootloaderProbe) {
                    customBuilder.setBootloaderPath(File(filesDir, "console-probe/u-boot.bin").path)
                    if (diskProbe) customBuilder.addDisk(VirtualMachineCustomImageConfig.Disk.RWDisk(
                        File(filesDir, "console-probe/debian.raw").path))
                } else {
                    customBuilder.setKernelPath(GuestKernelCompat.kernelPath(File(image, "vmlinuz").path, false))
                    .setInitrdPath(File(filesDir, "console-probe/initrd.gz").path)
                    .addParam("console=hvc0 earlycon rdinit=/init panic=-1")
                }
                val custom = customBuilder.build()
                val config = VirtualMachineConfig.Builder(this)
                    .setProtectedVm(false).setMemoryBytes(512L * 1024 * 1024)
                    .setCpuTopology(VirtualMachineConfig.CPU_TOPOLOGY_ONE_CPU)
                    .setDebugLevel(VirtualMachineConfig.DEBUG_LEVEL_FULL)
                    .setVmOutputCaptured(true).setVmConsoleInputSupported(true).setConnectVmConsole(false)
                    .setConsoleInputDevice(if (bootloaderProbe) "ttyS0" else "hvc0").setCustomImageConfig(custom).build()
                val machine = manager.create(name, config)
                vm = machine
                val output = machine.consoleOutput
                val input = machine.consoleInput
                consoleInput = input
                val log = File(filesDir, "console-probe.txt")
                workers.execute {
                    runCatching { log.outputStream().use { file ->
                        val buffer = ByteArray(4096)
                        while (true) {
                            val count = output.read(buffer)
                            if (count < 0) break
                            file.write(buffer, 0, count); file.flush()
                            val bytes = buffer.copyOf(count)
                            runOnUiThread { if (!isDestroyed) session.append(bytes) }
                        }
                    } }
                }
                workers.execute { runCatching {
                    machine.logOutput.use { inputLog ->
                        File(filesDir, "console-probe-vmm.txt").outputStream().use { inputLog.copyTo(it) }
                    }
                } }
                machine.run()
                if (diskProbe) return@execute // Never type commands into an unknown login/password prompt.
                if (bootloaderProbe) {
                    repeat(15) { input.write(byteArrayOf(3)); input.flush(); Thread.sleep(100) }
                } else Thread.sleep(12000)
                val command = if (diskProbe) "\necho DEBIAN_UBOOT_OK; uname -a; tty; cat /etc/os-release\n"
                    else if (bootloaderProbe) "\nversion; echo UBOOT_CONSOLE_OK\n"
                    else "\necho DIRECT_CONSOLE_OK; uname -a; tty; echo PROBE_DONE\n"
                input.write(command.toByteArray())
                input.flush()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                File(filesDir, "console-probe-error.txt").writeText(e.stackTraceToString())
                runOnUiThread { if (!isDestroyed) session.append(e.stackTraceToString().toByteArray()) }
            }
        }
    }
    private fun stopProbe() {
        val machine = vm
        vm = null
        runCatching { machine?.let { it.stop(); getSystemService(VirtualMachineManager::class.java)?.delete(it.name) } }
    }
    override fun onDestroy() {
        VmConsole.unsubscribe(consoleListener)
        stopProbe()
        workers.shutdownNow()
        writer.shutdownNow()
        super.onDestroy()
    }
}
