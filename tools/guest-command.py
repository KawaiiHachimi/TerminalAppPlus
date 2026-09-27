#!/usr/bin/env python3
"""Run a validation command in a fresh ttyd session through an adb-forwarded Plus bridge.
Requires websocket-client. Reads port/token from recent app logcat (or environment).
Only uses the localhost bridge belonging to this app; never exposes it on the LAN.
"""
import json, os, re, subprocess, sys, time
import websocket
adb = os.environ.get('ADB', 'adb')
pid = subprocess.check_output([adb, 'shell', 'pidof', 'com.android.virtualization.terminal.plus'], text=True).strip()
logs = subprocess.check_output([adb, 'logcat', '-d', '--pid='+pid], text=True)
found = re.findall(r'Running\(terminalAddress=TerminalAddress\(ipAddress=localhost, port=(\d+), key=([\w-]+)\)', logs)
port, token = found[-1] if found else (os.environ['PLUS_PORT'], os.environ['PLUS_TOKEN'])
local = subprocess.check_output([adb, 'forward', 'tcp:0', 'tcp:'+port], text=True).strip()
try:
    ws = websocket.create_connection('ws://127.0.0.1:'+local+'/ws', subprotocols=['tty'],
                                    cookie='access_token='+token, timeout=2)
    ws.send(json.dumps({'AuthToken':'', 'columns':120, 'rows':40}))
    time.sleep(0.3)
    ws.send_binary(b'0' + sys.argv[1].encode() + b'\r')
    until = time.monotonic() + float(os.environ.get('PLUS_TIMEOUT', '8'))
    while time.monotonic() < until:
        try:
            msg = ws.recv()
            if isinstance(msg, bytes) and msg[:1] == b'0':
                sys.stdout.write(msg[1:].decode(errors='replace'));sys.stdout.flush()
        except websocket.WebSocketTimeoutException:
            continue
    ws.close()
finally:
    subprocess.run([adb, 'forward', '--remove', 'tcp:'+local], check=True)
