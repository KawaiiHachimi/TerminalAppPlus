import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest

HELPER = Path(__file__).resolve().parents[1] / 'guest-packages.sh'

class GuestPackagesTest(unittest.TestCase):
    def shell(self, command, env=None):
        return subprocess.run(['sh', '-c', '. ' + shlex.quote(str(HELPER)) + '; ' + command], text=True, capture_output=True, env=env)

    def test_families_and_derivatives(self):
        for name, like, expected in [('debian', '', 'deb'), ('ubuntu', 'debian', 'deb'), ('fedora', '', 'rpm'), ('almalinux', 'rhel centos fedora', 'rpm'), ('rocky', 'rhel', 'rpm'), ('custom', 'ubuntu debian', 'deb')]:
            result = self.shell('guest_package_family ' + shlex.quote(name) + ' ' + shlex.quote(like))
            self.assertEqual(result.returncode, 0)
            self.assertEqual(result.stdout.strip(), expected)
        self.assertNotEqual(self.shell('guest_package_family arch ""').returncode, 0)

    def test_package_names_and_skip_ttyd(self):
        self.assertEqual(self.shell('guest_packages deb 1').stdout.strip(), 'python3 liblz4-1 ttyd socat')
        self.assertEqual(self.shell('guest_packages rpm 1').stdout.strip(), 'python3 lz4-libs ttyd socat')
        self.assertEqual(self.shell('guest_packages rpm 0').stdout.strip(), 'python3 lz4-libs')

    def test_dnf_and_apt_arguments(self):
        for family, tool in [('rpm', 'dnf'), ('deb', 'apt-get')]:
            with tempfile.TemporaryDirectory() as temp:
                path = Path(temp) / tool
                path.write_text('#!/bin/sh\nprintf "%s\\n" "$*"\n')
                path.chmod(0o755)
                env = dict(os.environ, PATH=temp + ':' + os.environ['PATH'])
                result = self.shell('guest_install_packages ' + family + ' python3 ttyd', env)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout.splitlines(), ['install -y python3 ttyd'] if family == 'rpm' else ['update', 'install -y python3 ttyd'])

    def test_install_failure_propagates(self):
        result = self.shell('dnf() { return 42; }; guest_install_packages rpm ttyd')
        self.assertEqual(result.returncode, 42)

if __name__ == '__main__':
    unittest.main()
