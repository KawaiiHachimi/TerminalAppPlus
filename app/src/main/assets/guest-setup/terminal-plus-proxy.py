#!/usr/bin/python3
# Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0
"""AVF-owned vsock connection -> guest localhost TCP; no host AF_VSOCK permission."""
import socket
import struct
import threading

slots = threading.BoundedSemaphore(64)


def pump(source, destination):
    try:
        while data := source.recv(65536):
            destination.sendall(data)
    except OSError:
        pass
    finally:
        try:
            destination.shutdown(socket.SHUT_WR)
        except OSError:
            pass


def handle(client):
    try:
        with client:
            client.settimeout(10)
            header = b''
            while len(header) < 2:
                data = client.recv(2 - len(header))
                if not data:
                    return
                header += data
            port, = struct.unpack('!H', header)
            if port < 1024:
                return
            with socket.create_connection(('127.0.0.1', port), 10) as target:
                client.settimeout(None)
                target.settimeout(None)
                thread = threading.Thread(target=pump, args=(client, target), daemon=True)
                thread.start()
                pump(target, client)
                thread.join()
    except OSError:
        pass
    finally:
        slots.release()


def main():
    with socket.socket(socket.AF_VSOCK, socket.SOCK_STREAM) as server:
        server.bind((socket.VMADDR_CID_ANY, 7682))
        server.listen(32)
        while True:
            client, peer = server.accept()
            # Only the Android host (CID 2), never other guests.
            if peer[0] != 2 or not slots.acquire(blocking=False):
                client.close()
                continue
            threading.Thread(target=handle, args=(client,), daemon=True).start()


if __name__ == "__main__":
    main()
