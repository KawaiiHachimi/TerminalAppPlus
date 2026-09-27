/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal

import android.content.Context
import android.util.Base64

internal object GuestTools {
    /** Only fixed packaged files; installation is never injected into the serial console. */
    fun installScript(context: Context): String {
        val encoded = context.assets.open("guest-setup/guest-tools.tar.gz").use {
            Base64.encodeToString(it.readBytes(), Base64.NO_WRAP)
        }.chunked(76).joinToString("\n")
        return """
set -eu
tmp=${'$'}(mktemp -d)
trap 'rm -rf "${'$'}tmp"' EXIT
base64 -d <<'TERMINAL_PLUS_GUEST_PAYLOAD' | tar -xz -C "${'$'}tmp"
$encoded
TERMINAL_PLUS_GUEST_PAYLOAD
sh "${'$'}tmp/install-guest-tools.sh" "${'$'}tmp"
""".trimIndent()
    }

    fun manualCommand(context: Context): String {
        val script = installScript(context).replace("'", "'\\''")
        return "sh -c '$script'\n"
    }
}
