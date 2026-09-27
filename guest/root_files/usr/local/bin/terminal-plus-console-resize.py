#!/usr/bin/env python3
"""Apply host terminal dimensions to a fixed guest serial TTY. No command execution."""
import fcntl
import os
import socket
import struct
import termios

PORT = 7684
DEVICES = ("/dev/hvc0", "/dev/ttyS0")
PACKET = struct.Struct("!4sBHH")  # magic, device index, rows, columns


def decode(data):
    magic, device, rows, columns = PACKET.unpack(data)
    if magic != b"TPR1" or device >= len(DEVICES) or not (1 <= rows <= 1000 and 1 <= columns <= 1000):
        raise ValueError("invalid terminal resize")
    return DEVICES[device], rows, columns


def resize_fd(fd, rows, columns):
    # Linux delivers SIGWINCH to this TTY's foreground process group when size changes.
    fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", rows, columns, 0, 0))


def serve():
    with socket.socket(socket.AF_VSOCK, socket.SOCK_STREAM) as server:
        server.bind((socket.VMADDR_CID_ANY, PORT))
        server.listen(4)
        while True:
            connection, peer = server.accept()
            with connection:
                if peer[0] != 2:  # Host only; never expose a TCP listener.
                    continue
                connection.settimeout(2)
                try:
                    data = b""
                    while len(data) < PACKET.size:
                        part = connection.recv(PACKET.size - len(data))
                        if not part:
                            raise ValueError("short packet")
                        data += part
                    path, rows, columns = decode(data)
                    fd = os.open(path, os.O_RDWR | os.O_NOCTTY | os.O_NONBLOCK)
                    try:
                        resize_fd(fd, rows, columns)
                    finally:
                        os.close(fd)
                    connection.sendall(b"\x00")
                except (ValueError, OSError, struct.error):
                    try:
                        connection.sendall(b"\x01")
                    except OSError:
                        pass


if __name__ == "__main__":
    serve()
