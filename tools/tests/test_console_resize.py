import importlib.util
from pathlib import Path
import fcntl
import os
import pty
import struct
import signal
import select
import termios
import unittest

script = Path(__file__).resolve().parents[2] / 'guest/root_files/usr/local/bin/terminal-plus-console-resize.py'
spec = importlib.util.spec_from_file_location('console_resize', script)
resize = importlib.util.module_from_spec(spec)
spec.loader.exec_module(resize)


class ResizeTest(unittest.TestCase):
    def test_wire_rows_and_columns_are_not_transposed(self):
        self.assertEqual(resize.decode(struct.pack('!4sBHH', b'TPR1', 0, 90, 60)), ('/dev/hvc0', 90, 60))
        self.assertEqual(resize.decode(struct.pack('!4sBHH', b'TPR1', 1, 42, 120)), ('/dev/ttyS0', 42, 120))

    def test_rejects_unknown_devices_and_bad_dimensions(self):
        for magic, device, rows, cols in [(b'xxxx', 0, 90, 60), (b'TPR1', 2, 90, 60), (b'TPR1', 0, 0, 60), (b'TPR1', 0, 90, 1001)]:
            with self.assertRaises(ValueError):
                resize.decode(struct.pack('!4sBHH', magic, device, rows, cols))

    def test_resize_notifies_foreground_process(self):
        master, slave = pty.openpty()
        read_fd, write_fd = os.pipe()
        pid = os.fork()
        if pid == 0:
            try:
                os.close(read_fd)
                os.setsid()
                fcntl.ioctl(slave, termios.TIOCSCTTY, 0)
                signal.signal(signal.SIGWINCH, lambda *_: os.write(write_fd, b'W'))
                os.write(write_fd, b'R')
                signal.pause()
            finally:
                os._exit(0)
        os.close(write_fd)
        try:
            self.assertTrue(select.select([read_fd], [], [], 3)[0], 'child did not acquire TTY')
            self.assertEqual(os.read(read_fd, 1), b'R')
            resize.resize_fd(slave, 90, 60)
            self.assertTrue(select.select([read_fd], [], [], 3)[0], 'SIGWINCH missing')
            self.assertEqual(os.read(read_fd, 1), b'W')
        finally:
            try:
                os.kill(pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            os.waitpid(pid, 0)
            os.close(read_fd)
            os.close(master)
            os.close(slave)

    def test_real_tty_window_size_changes(self):
        master, slave = pty.openpty()
        try:
            for rows, cols in [(90, 60), (45, 120), (24, 80)]:
                resize.resize_fd(slave, rows, cols)
                actual = struct.unpack('HHHH', fcntl.ioctl(slave, termios.TIOCGWINSZ, bytes(8)))
                self.assertEqual(actual, (rows, cols, 0, 0))
        finally:
            os.close(master)
            os.close(slave)


if __name__ == '__main__':
    unittest.main()
