#!/bin/sh
# Run inside the guest; the optional argument is the directory containing payloads.
set -eu
if [ "$(id -u)" != 0 ]; then exec sudo sh "$0" "$@"; fi
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
payload=${1:-"$script_dir/../app/src/main/assets/guest-setup"}
command -v python3 >/dev/null
command -v systemctl >/dev/null
for name in guest capture proxy; do
    test -f "$payload/terminal-plus-$name.py"
done
test -f "$payload/terminal-plus-guest.service"
changed=0
for name in guest capture proxy; do
    if ! cmp -s "$payload/terminal-plus-$name.py" "/usr/local/bin/terminal-plus-$name.py"; then
        install -m 755 "$payload/terminal-plus-$name.py" "/usr/local/bin/terminal-plus-$name.py"
        changed=1
    fi
done
if ! cmp -s "$payload/terminal-plus-guest.service" /etc/systemd/system/terminal-plus-guest.service; then
    install -m 644 "$payload/terminal-plus-guest.service" /etc/systemd/system/terminal-plus-guest.service
    changed=1
fi
# Migrate only the old Plus units; never touch distro services, users or ttyd.
for name in capture console-resize proxy kms-probe; do
    systemctl disable --now "terminal-plus-$name.service" 2>/dev/null || true
done
rm -f /usr/local/bin/terminal-plus-console-resize.py
systemctl daemon-reload
systemctl enable --now terminal-plus-guest.service
if [ "$changed" = 1 ]; then systemctl restart terminal-plus-guest.service; fi
systemctl is-active --quiet terminal-plus-guest.service
printf 'Terminal Plus guest service is active.\n'
