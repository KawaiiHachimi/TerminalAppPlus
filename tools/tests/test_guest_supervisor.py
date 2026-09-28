import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest


class SupervisorTest(unittest.TestCase):
    def test_failed_worker_does_not_stop_peer_and_shutdown_reaps_children(self):
        source = Path(__file__).resolve().parents[2] / 'guest/root_files/usr/local/bin/terminal-plus-guest.py'
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            # Exercise supervision without requiring root or a distro proxy user.
            (root / source.name).write_text(source.read_text().replace(
                'MODULES = ("capture", "proxy")', 'MODULES = ("failing", "worker")'))
            marker = root / 'worker.pid'
            (root / 'terminal-plus-worker.py').write_text(
                f'import os,time\nopen({str(marker)!r}, "w").write(str(os.getpid()))\nwhile True: time.sleep(1)\n')
            (root / 'terminal-plus-failing.py').write_text('raise SystemExit(1)\n')
            process = subprocess.Popen([sys.executable, str(root / source.name)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            child = None
            try:
                deadline = time.monotonic() + 5
                while not marker.exists() and time.monotonic() < deadline:
                    time.sleep(.02)
                self.assertTrue(marker.exists(), 'peer worker did not start')
                child = int(marker.read_text())
                time.sleep(3.2)
                self.assertIsNone(process.poll(), 'supervisor died with worker')
                os.kill(child, 0)  # Peer survives repeated worker failures.
            finally:
                process.terminate()
                output, _ = process.communicate(timeout=10)
            self.assertGreaterEqual(output.count(b'failing started'), 2)
            if child is not None:
                with self.assertRaises(ProcessLookupError):
                    os.kill(child, 0)


if __name__ == '__main__':
    unittest.main()
