import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
HELPER = ROOT / 'tools/guest-runtime.sh'

class GuestRuntimeTest(unittest.TestCase):
    def shell(self, command, env=None):
        return subprocess.run(['/bin/sh', '-c', '. ' + shlex.quote(str(HELPER)) + '; ' + command], text=True, capture_output=True, env=env)

    def executable(self, directory, name, body='exit 0'):
        path = Path(directory) / name
        path.write_text('#!/bin/sh\n' + body + '\n')
        path.chmod(0o755)
        return path

    def test_existing_ttyd_is_reused_without_install(self):
        with tempfile.TemporaryDirectory() as temp:
            ttyd = self.executable(temp, 'ttyd', 'echo ttyd-version')
            result = self.shell('install() { return 99; }; guest_ttyd_binary /missing', dict(os.environ, PATH=temp))
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.strip(), str(ttyd))
            self.assertIn('ttyd-version', result.stderr)

    def test_bundled_ttyd_copy_and_execute(self):
        # Mock install only to avoid depending on GNU install -D on macOS.
        with tempfile.TemporaryDirectory() as temp:
            source = self.executable(temp, 'ttyd.aarch64', 'echo bundled-version')
            target = Path(temp) / 'installed'
            result = self.shell(f'''uname() {{ echo aarch64; }}
install() {{ cp "$4" "$5"; chmod 755 "$5"; }}
guest_ttyd_binary {shlex.quote(temp)} {shlex.quote(str(target))}''')
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(target.read_bytes(), source.read_bytes())
            self.assertEqual(result.stdout.strip(), str(target))

    def test_wrong_arch_and_copy_failure(self):
        result = self.shell('uname() { echo x86_64; }; guest_ttyd_binary /missing', dict(os.environ, PATH='/nonexistent'))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('ARM64', result.stderr)
        result = self.shell('uname() { echo aarch64; }; install() { return 42; }; guest_ttyd_binary /missing')
        self.assertNotEqual(result.returncode, 0)

    def test_broken_existing_ttyd_not_silently_replaced(self):
        with tempfile.TemporaryDirectory() as temp:
            self.executable(temp, 'ttyd', 'exit 126')
            result = self.shell('guest_ttyd_binary /missing', dict(os.environ, PATH=temp))
            self.assertNotEqual(result.returncode, 0)

if __name__ == '__main__':
    unittest.main()
