/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal

import com.android.virtualization.terminal.AppStrings
import com.android.virtualization.terminal.R
import android.system.virtualmachine.VirtualMachine
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** Logger owns the only console reader; attached UIs receive ordered copies. */
internal object VmConsole {
    private var owner: VirtualMachine? = null
    private var input: OutputStream? = null
    private var history = ByteArrayOutputStream()
    private val listeners = mutableSetOf<(ByteArray) -> Unit>()

    @Synchronized fun begin(vm: VirtualMachine) {
        input?.let { runCatching { it.close() } }
        owner = vm
        input = vm.consoleInput
        history = ByteArrayOutputStream()
        listeners.clear()
    }
    @Synchronized fun publish(vm: VirtualMachine, bytes: ByteArray) {
        if (owner !== vm) return
        history.write(bytes)
        if (history.size() > 256 * 1024) {
            val tail = history.toByteArray().takeLast(128 * 1024).toByteArray()
            history = ByteArrayOutputStream().apply { write(tail) }
        }
        listeners.forEach { it(bytes) }
    }
    @Synchronized fun subscribe(listener: (ByteArray) -> Unit): Boolean {
        if (owner == null || input == null) return false
        listener(history.toByteArray())
        listeners.add(listener)
        return true
    }
    @Synchronized fun unsubscribe(listener: (ByteArray) -> Unit) { listeners.remove(listener) }
    fun write(bytes: ByteArray) {
        val stream = synchronized(this) { checkNotNull(input) { "VM console is not connected" } }
        synchronized(stream) { stream.write(bytes); stream.flush() }
    }
    @Synchronized fun end(vm: VirtualMachine) {
        if (owner !== vm) return
        input?.let { runCatching { it.close() } }
        input = null
        owner = null
        listeners.forEach { it(AppStrings.get(R.string.plus_vm_stopped_notice).toByteArray()) }
        listeners.clear()
    }
}
