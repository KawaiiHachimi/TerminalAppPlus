#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Custom Guest runtime helpers. Sourcing has no side effects.
guest_ttyd_binary() {
    existing=$(command -v ttyd || true)
    if [ -n "$existing" ]; then
        ttyd_path=$existing
    else
        case "$(uname -m)" in
            aarch64|arm64) ;;
            *) printf 'Bundled ttyd requires ARM64; install ttyd yourself or use --skip-ttyd.\n' >&2; return 1 ;;
        esac
        ttyd_path=${2:-/usr/local/bin/ttyd}
        install -D -m 755 "$1/ttyd.aarch64" "$ttyd_path" || return 1
        if command -v restorecon >/dev/null; then restorecon "$ttyd_path" || return 1; fi
    fi
    # Only literal absolute paths may be interpolated into ExecStart.
    case "$ttyd_path" in
        /*) ;;
        *) printf 'ttyd must have an absolute executable path.\n' >&2; return 1 ;;
    esac
    case "$ttyd_path" in *[!a-zA-Z0-9_./-]*) return 1 ;; esac
    "$ttyd_path" --version >&2 || return 1
    printf '%s\n' "$ttyd_path"
}
