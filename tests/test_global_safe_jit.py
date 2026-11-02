"""Global preload transactions must preserve unrelated configuration and remain reversible."""
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('globaljit', os.environ.get('PLUS_SAFE_JIT_TEST_SOURCE', str(Path(__file__).resolve().parents[1] / 'guest/root_files/usr/local/bin/terminal-plus-safe-jit.py')))
jit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(jit)


class GlobalPreloadTest(unittest.TestCase):
    def test_remove_preserves_other_entries_and_comments(self):
        with tempfile.TemporaryDirectory() as d:
            path, lib = Path(d)/'preload', Path(d)/'lib.so'
            text = '# vendor\n/vendor/lib.so\n' + str(lib) + '\n# keep\n'
            path.write_text(text)
            with patch.object(jit.os, 'geteuid', return_value=0):
                result = jit.configure_global(False, path, lib)
                jit.configure_global(False, path, lib)
            self.assertFalse(result['enabled'])
            self.assertEqual(path.read_text(), '# vendor\n/vendor/lib.so\n# keep\n')

    def test_refuses_unmanaged_line_and_symlink(self):
        with tempfile.TemporaryDirectory() as d:
            path, lib = Path(d)/'preload', Path(d)/'lib.so'
            path.write_text('/vendor/lib.so:' + str(lib) + '\n')
            with patch.object(jit.os, 'geteuid', return_value=0):
                with self.assertRaisesRegex(RuntimeError, 'non-managed'):
                    jit.configure_global(False, path, lib)
                link = Path(d)/'link'; link.symlink_to(path)
                with self.assertRaisesRegex(RuntimeError, 'symlink'):
                    jit.configure_global(False, link, lib)
            self.assertIn('/vendor/lib.so:', path.read_text())

    def test_unaffected_guest_does_not_compile_or_change_configuration(self):
        with tempfile.TemporaryDirectory() as d:
            path, lib = Path(d)/'preload', Path(d)/'lib.so'
            path.write_text('/vendor/lib.so\n')
            with patch.object(jit.os, 'geteuid', return_value=0), patch.object(jit.platform, 'system', return_value='Linux'), patch.object(jit.platform, 'libc_ver', return_value=('glibc','2.41')), patch.object(jit, 'affected', return_value=False), patch.object(jit, 'preload') as build:
                with self.assertRaisesRegex(RuntimeError, 'No affected'):
                    jit.configure_global(True, path, lib)
                build.assert_not_called()
            self.assertEqual(path.read_text(), '/vendor/lib.so\n')

    def test_requires_root_and_rejects_other_libc(self):
        with tempfile.TemporaryDirectory() as d:
            path, lib = Path(d)/'preload', Path(d)/'lib.so'
            with patch.object(jit.os, 'geteuid', return_value=1000):
                with self.assertRaisesRegex(RuntimeError, 'requires root'):
                    jit.configure_global(False, path, lib)
            with patch.object(jit.os, 'geteuid', return_value=0), patch.object(jit.platform, 'system', return_value='Linux'), patch.object(jit.platform, 'libc_ver', return_value=('musl','1.2')):
                with self.assertRaisesRegex(RuntimeError, 'glibc'):
                    jit.configure_global(True, path, lib)
            self.assertFalse(path.exists())

    def test_native_candidate_failure_does_not_activate_or_replace_old_library(self):
        # Run against a private root-owned Linux directory only; never use /etc/ld.so.preload.
        if os.geteuid() != 0 or jit.platform.system() != 'Linux':
            self.skipTest('Needs an isolated root-owned Linux directory')
        with tempfile.TemporaryDirectory(dir='/root') as d:
            path, lib = Path(d)/'preload', Path(d)/'jit'/'llvm-generic.so'
            lib.parent.mkdir(); lib.write_bytes(b'old library')
            path.write_text('/vendor/lib.so\n')
            def candidate(directory):
                out = directory/'llvm-generic.so'; out.write_bytes(b'invalid ELF')
                out.with_suffix('.c').write_text(jit.PRELOAD_SOURCE)
                return out
            with patch.object(jit, 'affected', return_value=True), patch.object(jit, 'preload', side_effect=candidate):
                with self.assertRaises(Exception): jit.configure_global(True, path, lib)
            self.assertEqual(lib.read_bytes(), b'old library')
            self.assertEqual(path.read_text(), '/vendor/lib.so\n')

    def test_detection_restores_bypass_environment(self):
        with patch.dict(os.environ, {'TERMINAL_PLUS_SAFE_JIT_BYPASS': 'keep'}):
            with patch.object(jit, '_affected', side_effect=RuntimeError('probe failure')):
                with self.assertRaises(RuntimeError): jit.affected()
            self.assertEqual(os.environ['TERMINAL_PLUS_SAFE_JIT_BYPASS'], 'keep')

    def test_native_enable_idempotence_and_disable(self):
        if os.geteuid() != 0 or jit.platform.system() != 'Linux' or not jit.shutil.which('gcc'):
            self.skipTest('Needs an isolated root-owned Linux directory and C compiler')
        with tempfile.TemporaryDirectory(dir='/root') as d:
            path, lib = Path(d)/'preload', Path(d)/'jit'/'llvm-generic.so'
            path.write_text('# vendor\n/vendor/lib.so\n')
            with patch.object(jit, 'affected', return_value=True):
                self.assertTrue(jit.configure_global(True, path, lib)['enabled'])
                first=path.read_text()
                self.assertTrue(jit.configure_global(True, path, lib)['enabled'])
            self.assertEqual(path.read_text(), first)
            self.assertEqual(first.count(str(lib)), 1)
            self.assertFalse(jit.configure_global(False, path, lib)['enabled'])
            self.assertEqual(path.read_text(), '# vendor\n/vendor/lib.so\n')

    def test_heterogeneous_cpus_are_checked_and_affinity_restored(self):
        import ctypes
        from unittest.mock import MagicMock
        names=[ctypes.create_string_buffer(b'cortex-a720'), ctypes.create_string_buffer(b'cortex-x925')]
        llvm=MagicMock()
        llvm.LLVMGetHostCPUName.side_effect=[ctypes.addressof(name) for name in names]
        with patch.object(jit.ctypes, 'CDLL', return_value=llvm), patch.object(jit.ctypes.util, 'find_library', return_value='LLVM'), patch.object(jit.os, 'sched_getaffinity', return_value={0,1}, create=True), patch.object(jit.os, 'sched_setaffinity', create=True) as affinity:
            self.assertTrue(jit.affected('aarch64',0))
        self.assertEqual([call.args for call in affinity.call_args_list],[(0,{0}),(0,{1}),(0,{0,1})])
        self.assertEqual(llvm.LLVMDisposeMessage.call_count,2)
