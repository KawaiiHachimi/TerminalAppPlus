import importlib.util
from pathlib import Path
import socket
import struct
import threading
import unittest

spec = importlib.util.spec_from_file_location('proxy', Path(__file__).resolve().parents[1] / 'guest/root_files/usr/local/bin/terminal-plus-proxy.py')
proxy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(proxy)

class GuestProxyTest(unittest.TestCase):
    def connect(self):
        client, server = socket.socketpair()
        client.settimeout(3)
        proxy.slots.acquire()
        worker = threading.Thread(target=proxy.handle, args=(server,), daemon=True)
        worker.start()
        return client, worker

    def test_fragmented_header_large_payload_and_half_close(self):
        with socket.socket() as target:
            target.bind(('127.0.0.1', 0)); target.listen(1)
            port = target.getsockname()[1]
            payload = b'VM terminal proxy test\n' * 10000
            def serve():
                with target.accept()[0] as connection:
                    data = bytearray()
                    while chunk := connection.recv(8192): data.extend(chunk)
                    connection.sendall(bytes(data)[::-1])
            server_thread = threading.Thread(target=serve, daemon=True); server_thread.start()
            client, worker = self.connect()
            with client:
                header = struct.pack('!H', port)
                client.sendall(header[:1]); client.sendall(header[1:])
                client.sendall(payload); client.shutdown(socket.SHUT_WR)
                received = bytearray()
                while chunk := client.recv(8192): received.extend(chunk)
                self.assertEqual(received, payload[::-1])
            worker.join(3); server_thread.join(3)
            self.assertFalse(worker.is_alive())

    def test_privileged_port_rejected(self):
        client, worker = self.connect()
        with client:
            client.sendall(struct.pack('!H', 22))
            self.assertEqual(client.recv(1), b'')
        worker.join(3); self.assertFalse(worker.is_alive())

    def test_incomplete_header_disconnect(self):
        client, worker = self.connect()
        client.sendall(b'\x10'); client.close()
        worker.join(3); self.assertFalse(worker.is_alive())

if __name__ == '__main__': unittest.main()
