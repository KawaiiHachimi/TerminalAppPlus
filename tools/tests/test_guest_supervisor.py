import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import unittest


class SupervisorTest(unittest.TestCase):
    def test_failed_capture_does_not_stop_console_and_shutdown_reaps_children(self):
        source = Path(__file__).resolve().parents[2] / 'guest/root_files/usr/local/bin/terminal-plus-guest.py'
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            shutil.copyfile(source, root / source.name)
            marker = root / 'console.pid'
            (root / 'terminal-plus-console-resize.py').write_text(
                f'import os,time\nopen({str(marker)!r}, "w").write(str(os.getpid()))\nwhile True: time.sleep(1)\n')
            (root / 'terminal-plus-capture.py').write_text('raise SystemExit(1)\n')
            (root / 'terminal-plus-proxy.py').write_text('raise SystemExit(1)\n')
            process = subprocess.Popen([sys.executable, str(root / source.name)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            child = None
            try:
                deadline = time.monotonic() + 5
                while not marker.exists() and time.monotonic() < deadline:
                    time.sleep(.02)
                self.assertTrue(marker.exists(), 'console worker did not start')
                child = int(marker.read_text())
                time.sleep(3.2)
                self.assertIsNone(process.poll(), 'supervisor died with capture')
                os.kill(child, 0)  # Console survives repeated capture failures.
            finally:
                process.terminate()
                output, _ = process.communicate(timeout=10)
            self.assertGreaterEqual(output.count(b'capture started'), 2)
            if child is not None:
                with self.assertRaises(ProcessLookupError):
                    os.kill(child, 0)


if __name__ == '__main__':
    unittest.main()
