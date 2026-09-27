#!/bin/sh
# Run INSIDE the Linux guest, from a checkout of this repository.
set -eu
if [ "$(id -u)" != 0 ]; then
    exec sudo sh "$0" "$@"
fi
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
payload=${1:-"$script_dir/../app/src/main/assets/guest-setup"}
command -v python3 >/dev/null
command -v systemctl >/dev/null
[ -f "$payload/terminal-plus-capture.py" ]
[ -f "$payload/terminal-plus-capture.service" ]
[ -c /dev/dri/card0 ] || { echo 'No DRM card0 in this guest.' >&2; exit 1; }
[ -r /sys/kernel/debug/dri/0/state ] || {
    echo 'DRM debugfs state is unavailable; mount/enable debugfs first.' >&2
    exit 1
}
install -m 755 "$payload/terminal-plus-capture.py" /usr/local/bin/terminal-plus-capture.py
install -m 644 "$payload/terminal-plus-capture.service" /etc/systemd/system/terminal-plus-capture.service
systemctl stop terminal-plus-kms-probe.service 2>/dev/null || true
systemctl daemon-reload
systemctl enable --now terminal-plus-capture.service
systemctl restart terminal-plus-capture.service
systemctl --no-pager --full status terminal-plus-capture.service
