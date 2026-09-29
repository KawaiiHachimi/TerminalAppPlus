#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Debian/Ubuntu + systemd bootstrap, executed explicitly inside the guest.
set -eu
fail() { printf '%s\n' "$*" >&2; exit 1; }
terminal_user=${SUDO_USER:-$(id -un)}
install_ttyd=1
force=0
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cache=/usr/local/share/terminal-plus-tools
# Updates and the installed entry point preserve the selected shell user/mode.
if [ -f "$cache/user" ] && [ -f "$cache/ttyd" ]; then
    terminal_user=$(cat "$cache/user")
    install_ttyd=$(cat "$cache/ttyd")
fi
while [ "$#" -gt 0 ]; do
    case "$1" in
        --user) [ "$#" -ge 2 ] || fail 'Missing --user value'; terminal_user=$2; shift 2 ;;
        --skip-ttyd) install_ttyd=0; shift ;;
        --force) force=1; shift ;;
        *) fail 'Usage: sh install.sh [--user USER] [--skip-ttyd] [--force]' ;;
    esac
done
[ "$(id -u)" = 0 ] || fail 'Run this installer with sudo or as root.'
command -v systemctl >/dev/null || fail 'This installer requires systemd.'
[ -d /run/systemd/system ] || fail 'systemd must be running.'
payloads='install.sh install-guest-tools.sh terminal-plus-guest.service terminal-plus-guest.py terminal-plus-capture.py terminal-plus-proxy.py'
for file in $payloads; do [ -r "$script_dir/$file" ] || fail "Missing payload: $file"; done
version=$(cd "$script_dir" && sha256sum $payloads | sha256sum | cut -d' ' -f1)
signature="$version:$terminal_user:$install_ttyd"
services='terminal-plus-guest.service'
[ "$install_ttyd" = 0 ] || services="$services terminal-plus-ttyd.service terminal-plus-ttyd-vsock.service"
if [ "$force" = 0 ] && [ -f "$cache/installed" ] && [ "$(cat "$cache/installed")" = "$signature" ]; then
    printf '%s\n' 'Already installed (same version). Service status:'
    healthy=1
    for service in $services; do
        printf '%s: ' "$service"
        systemctl is-active "$service" || healthy=0
    done
    printf '%s\n' 'To reinstall: terminal-plus-setup --force (ordinary users: sudo).'
    [ "$healthy" = 1 ] || exit 1
    exit 0
fi
if [ "$install_ttyd" = 1 ]; then
    case "$terminal_user" in ''|*[!a-zA-Z0-9_-]*|-*) fail 'Specify an existing user with --user USER.' ;; esac
    id -u "$terminal_user" >/dev/null || fail 'User does not exist.'
    home_dir=$(getent passwd "$terminal_user" | cut -d: -f6)
    case "$home_dir" in /*) ;; *) fail 'Invalid user home directory.' ;; esac
    # Restrict generated systemd paths to a safe literal subset.
    case "$home_dir" in *[!a-zA-Z0-9_./-]*) fail 'Unsupported characters in home directory.' ;; esac
    [ -d "$home_dir" ] || fail 'User home directory does not exist.'
    for service in ttyd.service ttyd_uds.service ttyd_vsock_bridge.service; do
        if systemctl is-active --quiet "$service"; then
            fail 'Existing ttyd service detected. Use --skip-ttyd to preserve it.'
        fi
    done
fi
command -v apt-get >/dev/null || fail 'Automatic dependency installation currently supports Debian/Ubuntu only.'
packages='python3 liblz4-1'
[ "$install_ttyd" = 0 ] || packages="$packages ttyd socat"
missing=0
new_ttyd=0
for package in $packages; do
    if [ "$(dpkg-query -W -f='${Status}' "$package" 2>/dev/null || true)" != 'install ok installed' ]; then
        missing=1
        [ "$package" != ttyd ] || new_ttyd=1
    fi
done
if [ "$missing" = 1 ]; then
    apt-get update
    apt-get install -y $packages
fi
# Debian starts its packaged ttyd unit on installation, using the same TCP port.
# Only replace that newly installed default, never a pre-existing user service.
if [ "$new_ttyd" = 1 ]; then
    systemctl disable --now ttyd.service
fi
# Drivers may be built in; inability to load a module alone is not a failure.
modprobe vmw_vsock_virtio_transport 2>/dev/null || true
python3 - <<'PY'
import socket
s = socket.socket(socket.AF_VSOCK, socket.SOCK_STREAM)
s.close()
PY
if [ "$install_ttyd" = 1 ]; then
    [ -x /usr/bin/ttyd ] && [ -x /usr/bin/socat ] || fail 'Expected /usr/bin/ttyd and /usr/bin/socat.'
    # Refuse occupied ports unless they belong to our already-running units.
    if ! systemctl is-active --quiet terminal-plus-ttyd.service; then
        python3 - <<'PY'
import socket
with socket.socket() as s:
    s.bind(('127.0.0.1', 7681))
PY
    fi
    if ! systemctl is-active --quiet terminal-plus-ttyd-vsock.service; then
        python3 - <<'PY'
import socket
with socket.socket(socket.AF_VSOCK, socket.SOCK_STREAM) as s:
    s.bind((socket.VMADDR_CID_ANY, 7681))
PY
    fi
    cat > /etc/systemd/system/terminal-plus-ttyd.service <<UNIT
[Unit]
Description=Terminal Plus ttyd
After=local-fs.target

[Service]
User=$terminal_user
WorkingDirectory=$home_dir
Environment=HOME=$home_dir
Environment=TERM=xterm-256color
ExecStart=/usr/bin/ttyd -i 127.0.0.1 -p 7681 -W /bin/bash -l
Restart=on-failure
RestartSec=2

[Install]
WantedBy=multi-user.target
UNIT
    cat > /etc/systemd/system/terminal-plus-ttyd-vsock.service <<UNIT
[Unit]
Description=Terminal Plus ttyd vsock bridge
Wants=terminal-plus-ttyd.service
After=terminal-plus-ttyd.service

[Service]
User=$terminal_user
ExecStart=/usr/bin/socat VSOCK-LISTEN:7681,fork,reuseaddr TCP:127.0.0.1:7681
Restart=on-failure
RestartSec=2

[Install]
WantedBy=multi-user.target
UNIT
    systemctl daemon-reload
    systemctl enable terminal-plus-ttyd.service terminal-plus-ttyd-vsock.service
    systemctl restart terminal-plus-ttyd.service terminal-plus-ttyd-vsock.service
fi
# systemd mounts debugfs before the capture supervisor starts, including after reboot.
mkdir -p /etc/systemd/system/terminal-plus-guest.service.d
cat > /etc/systemd/system/terminal-plus-guest.service.d/debugfs.conf <<'UNIT'
[Unit]
Wants=sys-kernel-debug.mount
After=sys-kernel-debug.mount
UNIT
sh "$script_dir/install-guest-tools.sh" "$script_dir"
systemctl restart terminal-plus-guest.service
for service in $services; do systemctl is-active --quiet "$service"; done
# Keep a local repair kit; no mounted ISO is needed to check or reinstall later.
mkdir -p "$cache" /usr/local/bin
for file in $payloads; do
    if ! cmp -s "$script_dir/$file" "$cache/$file"; then
        install -m 644 "$script_dir/$file" "$cache/$file"
    fi
done
printf '%s\n' "$terminal_user" > "$cache/user"
printf '%s\n' "$install_ttyd" > "$cache/ttyd"
cat > /usr/local/bin/terminal-plus-setup <<'SH'
#!/bin/sh
set -eu
if [ "$(id -u)" != 0 ]; then exec sudo sh "$0" "$@"; fi
exec sh /usr/local/share/terminal-plus-tools/install.sh "$@"
SH
chmod 755 /usr/local/bin/terminal-plus-setup
printf '%s\n' "$signature" > "$cache/installed"
if [ ! -e /dev/dri/card0 ]; then
    printf '%s\n' 'Installed. No /dev/dri/card0: graphics needs virtio-gpu and an active KMS output.'
else
    printf '%s\n' 'Installed. Open ttyd / virtual display in the app. Capture also needs a supported active KMS output.'
fi
