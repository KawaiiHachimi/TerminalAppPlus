/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.virtualization.terminal.new2.core

data class TerminalAddress(val ipAddress: String, val port: Int, val key: String? = null)

sealed interface VmState {
    data object Ready : VmState

    data object Starting : VmState

    data object Running : VmState

    data object Rebooting : VmState

    data object Stopping : VmState

    data object Stopped : VmState

    data class Error(val cause: Throwable) : VmState

    val isAlive: Boolean
        get() = this is Starting || this is Running || this is Stopping || this is Rebooting
}

/** A local endpoint is not proof that ttyd exists in the guest. WebView tracks session readiness. */
sealed interface TerminalConnection {
    data object Disconnected : TerminalConnection
    data object Connecting : TerminalConnection
    data class Endpoint(val address: TerminalAddress) : TerminalConnection
    data class Unavailable(val reason: String) : TerminalConnection
}
