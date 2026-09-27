#!/usr/bin/env python3
"""Opt-in guest-side repair for AOSP's shell display bootstrap versus a display manager.
Run with guest sudo. Backs up the profile, does not change users/passwords, and
stops only stock transient /usr/bin/sleep 1d placeholders (not real sessions).
"""
import fcntl
import os
from pathlib import Path
import subprocess
import time


def output(*args):
    return subprocess.check_output(args, text=True).strip()


def properties(session):
    return dict(line.split('=', 1) for line in output(
        'loginctl', 'show-session', session,
        '-p', 'Type', '-p', 'Class', '-p', 'Leader', '-p', 'VTNr', '-p', 'Active'
    ).splitlines())


def main():
    if os.geteuid() != 0:
        raise SystemExit('Run inside the guest with sudo.')
    profile = Path('/etc/profile.d/activate_display.sh')
    text = profile.read_text()
    marker = '# Terminal Plus: an existing display manager owns the seat.\n'
    if marker not in text:
        expected = '# Only a user account in an interactive shell can run this script.'
        if expected not in text or 'source /usr/local/bin/enable_display' not in text:
            raise SystemExit('Unknown display profile; left unchanged.')
        backup = profile.with_name(profile.name + '.terminal-plus-backup')
        if backup.exists():
            raise SystemExit('Backup already exists; inspect before changing the profile.')
        backup.write_bytes(profile.read_bytes())
        guard = (marker + 'if systemctl is-active --quiet display-manager.service || '
                 'systemctl is-enabled --quiet display-manager.service; then\n'
                 '  return 0\nfi\n\n')
        profile.write_text(text.replace(expected, guard + expected, 1))
    units = output('systemctl', 'list-units', '--type=service', '--all', '--plain',
                   '--no-legend', 'run-*.service')
    for line in units.splitlines():
        unit = line.split()[0]
        if not unit.startswith('run-p'):
            continue
        pid = output('systemctl', 'show', unit, '--property=MainPID', '--value')
        try:
            command = Path('/proc/' + pid + '/cmdline').read_bytes()
        except FileNotFoundError:
            continue
        settings = output('systemctl', 'show', unit, '-p', 'PAMName', '-p', 'TTYPath', '-p', 'Environment')
        if (command == b'/usr/bin/sleep\x001d\x00' and 'PAMName=login' in settings
                and 'TTYPath=/dev/tty1' in settings and 'XDG_SESSION_TYPE=wayland' in settings):
            subprocess.run(['systemctl', 'stop', unit], check=True)
    candidates = []
    for line in output('loginctl', 'list-sessions', '--no-legend', '--no-pager').splitlines():
        session = line.split()[0]
        props = properties(session)
        if props.get('Type') in ('wayland', 'x11') and int(props.get('VTNr', '0')) > 0:
            candidates.append((session, props))
    active = [entry for entry in candidates if entry[1].get('Active') == 'yes']
    if active:
        print('Graphical session already active:', active[0][0])
        return
    if len(candidates) != 1:
        raise SystemExit('No unambiguous graphical session; select/activate one manually.')
    session, props = candidates[0]
    target = int(props['VTNr'])
    subprocess.run(['loginctl', 'activate', session], check=True)
    if properties(session).get('Active') != 'yes':
        # logind may retain an inactive same-VT session until a real VT transition.
        fd = os.open('/dev/tty0', os.O_RDWR | os.O_CLOEXEC)
        try:
            fcntl.ioctl(fd, 0x5606, 2 if target != 2 else 1)  # VT_ACTIVATE
            time.sleep(0.5)
            fcntl.ioctl(fd, 0x5606, target)
        finally:
            os.close(fd)
        time.sleep(1)
    if properties(session).get('Active') != 'yes':
        raise SystemExit('Graphical session is still inactive; do not treat frame delivery as live updates.')
    print('Activated graphical session:', session)


if __name__ == '__main__':
    main()
