import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest

HELPER = Path(__file__).resolve().parents[1] / 'guest-packages.sh'

class GuestPackagesTest(unittest.TestCase):
    def shell(self, command, env=None):
        return subprocess.run(['/bin/sh', '-c', '. ' + shlex.quote(str(HELPER)) + '; ' + command], text=True, capture_output=True, env=env)

    def test_detect_capabilities_without_os_release(self):
        for commands, expected in [
            (['apt-get', 'dpkg-query'], 'deb'),
            (['rpm', 'dnf'], 'rpm'), (['rpm', 'yum'], 'rpm'),
            (['apt-get'], None), (['rpm'], None), (['dnf'], None), ([], None),
            (['apt-get', 'dpkg-query', 'rpm', 'dnf'], 'deb'),
        ]:
            with self.subTest(commands=commands), tempfile.TemporaryDirectory() as temp:
                for name in commands:
                    path = Path(temp) / name
                    path.write_text('#!/bin/sh\nexit 0\n')
                    path.chmod(0o755)
                result = self.shell('guest_package_family', dict(os.environ, PATH=temp))
                self.assertEqual(result.returncode, 0 if expected else 1)
                self.assertEqual(result.stdout.strip(), expected or '')

    def test_package_names_and_skip_ttyd(self):
        for family, lz4 in [('deb', 'liblz4-1'), ('rpm', 'lz4-libs')]:
            self.assertEqual(self.shell(f'guest_packages {family} 1').stdout.strip(), f'python3 {lz4} socat')
            self.assertEqual(self.shell(f'guest_packages {family} 0').stdout.strip(), f'python3 {lz4}')

    def test_package_manager_arguments(self):
        for family, tool in [('rpm', 'dnf'), ('rpm', 'yum'), ('deb', 'apt-get')]:
            with tempfile.TemporaryDirectory() as temp:
                path = Path(temp) / tool
                path.write_text('#!/bin/sh\nprintf "%s\\n" "$*"\n')
                path.chmod(0o755)
                result = self.shell(f'guest_install_packages {family} python3 socat', dict(os.environ, PATH=temp))
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout.splitlines(), ['install -y python3 socat'] if family == 'rpm' else ['update', 'install -y python3 socat'])

    def test_install_failure_propagates(self):
        self.assertEqual(self.shell('dnf() { return 42; }; guest_install_packages rpm socat').returncode, 42)
        result = self.shell('apt-get() { [ "$1" != update ]; }; guest_install_packages deb socat')
        self.assertNotEqual(result.returncode, 0)

if __name__ == '__main__':
    unittest.main()
