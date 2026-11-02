import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
def load(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod

capture = load('capture', 'tools/experiments/kms-capture-server.py')
jit = load('safejit', 'guest/root_files/usr/local/bin/terminal-plus-safe-jit.py')

class AvcTest(unittest.TestCase):
    def test_split_boundaries_preserves_headers_and_reference_frames(self):
        aud = b'\x00\x00\x00\x01\x09\xf0'
        frames = [aud + b'\x00\x00\x01\x67sps\x00\x00\x01\x68pps', aud + b'picture1', aud + b'picture2']
        wire = b''.join(frames)
        for chunk in range(1, 15):
            parser = capture.AvcAccessUnits(); result = []
            for n in range(0, len(wire), chunk): result.extend(parser.push(wire[n:n+chunk]))
            self.assertEqual(result, frames[:-1])
            self.assertEqual(bytes(parser.pending), frames[-1])
    def test_bounds_and_encoder_arguments(self):
        with self.assertRaises(OSError): capture.AvcAccessUnits().push(b'x' * (capture.MAX_AVC_PACKET + 1))
        for w, h in [(0, 720), (4097, 720), (4096, 4096)]:
            with self.assertRaises(OSError): capture.encoder_command(w, h, 30)
        self.assertEqual(capture.encoded_size(1281, 719), (1282, 720))
        args = capture.encoder_command(1281, 719, 30)
        self.assertIn('pad=ceil(iw/2)*2:ceil(ih/2)*2', args)
        self.assertIn('libx264', args)
        self.assertIn('aud=1:repeat-headers=1:bframes=0', args)

class SafeJitTest(unittest.TestCase):
    def test_only_x925_without_sve(self):
        self.assertTrue(jit.affected('aarch64', 0, 'cortex-x925'))
        self.assertFalse(jit.affected('aarch64', 1 << 22, 'cortex-x925'))
        self.assertFalse(jit.affected('aarch64', 0, 'generic'))
        self.assertFalse(jit.affected('x86_64', 0, 'cortex-x925'))
    def test_retains_kernel_features_and_old_cache(self):
        with tempfile.TemporaryDirectory() as directory:
            env = {'KEEP': 'yes', 'MESA_SHADER_CACHE_DIR': '/old-cache', 'LD_PRELOAD': '/existing.so'}
            from unittest.mock import patch
            with patch.object(jit, 'preload', return_value=Path(directory) / 'llvm-generic.so'):
                result = jit.environment(env, 'Features : fp asimd aes\nCPU part : 0xd85\n', directory)
            self.assertEqual(env['MESA_SHADER_CACHE_DIR'], '/old-cache')
            info = Path(result['LLVM_CPUINFO']).read_text()
            self.assertIn('Features : fp asimd aes', info)
            self.assertNotIn('CPU part', info)
            self.assertEqual(result['KEEP'], 'yes')
            self.assertTrue(result['LD_PRELOAD'].endswith(':/existing.so'))
            with patch.object(jit, 'preload', return_value=Path(directory) / 'llvm-generic.so'):
                self.assertEqual(jit.environment(env, 'Features : fp asimd aes\n', directory), result)
    def test_missing_compiler_does_not_claim_a_workaround(self):
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as directory, patch.object(jit.shutil, 'which', return_value=None):
            with self.assertRaisesRegex(RuntimeError, 'C compiler'):
                jit.preload(Path(directory))
    def test_probe_does_not_compile(self):
        from unittest.mock import patch
        import contextlib, io, json
        output = io.StringIO()
        with patch.object(jit.sys, 'argv', ['safe-jit', '--probe']), patch.object(jit, 'affected', return_value=True), patch.object(jit, 'preload') as compiler, contextlib.redirect_stdout(output):
            jit.main()
            compiler.assert_not_called()
        self.assertTrue(json.loads(output.getvalue())['required'])
    def test_no_feature_guessing(self):
        with self.assertRaises(RuntimeError): jit.environment({}, 'unknown')

class AvcIntegrationTest(unittest.TestCase):
    def _roundtrip(self, width, height):
        import os
        import socket
        import struct
        import subprocess
        import threading
        from unittest.mock import patch
        ffmpeg = os.environ['PLUS_FFMPEG']
        server, client = socket.socketpair()
        server.settimeout(2); client.settimeout(5)
        frame = bytearray([10, 80, 200, 255] * (width * height))
        sample = (struct.pack('!III', width, height, len(frame)), frame)
        original_command = capture.encoder_command
        def command(*args):
            value = original_command(*args); value[0] = ffmpeg; return value
        errors = []
        def run():
            try: capture.stream_avc(server, 30)
            except (OSError, BrokenPipeError) as error: errors.append(str(error))
            finally: server.close()
        def exact(n):
            result = bytearray()
            while len(result) < n:
                part = client.recv(n-len(result))
                if not part: raise EOFError('stream ended')
                result.extend(part)
            return bytes(result)
        with patch.object(capture, 'capture', return_value=sample), patch.object(capture, 'encoder_command', side_effect=command):
            worker = threading.Thread(target=run)
            worker.start()
            try:
                self.assertEqual(struct.unpack('!4sII', exact(12)), (b'PAV1', *capture.encoded_size(width, height)))
                encoded = bytearray()
                for _ in range(5):
                    length, = struct.unpack('!I', exact(4))
                    self.assertTrue(0 < length <= capture.MAX_AVC_PACKET)
                    encoded.extend(exact(length))
            finally:
                client.close(); worker.join(timeout=5)
            self.assertFalse(worker.is_alive(), 'encoder survived a disconnected viewer')
        decoded = subprocess.run([ffmpeg, '-v', 'error', '-f', 'h264', '-i', 'pipe:0', '-f', 'rawvideo', '-pix_fmt', 'rgb24', 'pipe:1'],
                                 input=encoded, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5, check=True).stdout
        self.assertEqual(len(decoded), 5 * capture.encoded_size(width, height)[0] * capture.encoded_size(width, height)[1] * 3)
        self.assertLess(abs(decoded[0] - 200), 12)
        self.assertLess(abs(decoded[1] - 80), 12)
        self.assertLess(abs(decoded[2] - 10), 12)

    @unittest.skipUnless(__import__('os').environ.get('PLUS_FFMPEG'), 'Set PLUS_FFMPEG for real encoder/decoder verification')
    def test_real_encoder_stream_decode_and_disconnect_cleanup(self):
        for size in [(64, 48), (65, 49)]:
            with self.subTest(size=size): self._roundtrip(*size)

    @unittest.skipUnless(__import__('os').environ.get('PLUS_FFMPEG'), 'Set PLUS_FFMPEG for real encoder/decoder verification')
    def test_modeset_ends_old_stream_and_reaps_encoder(self):
        import os, socket, struct, threading
        from unittest.mock import patch
        server, client = socket.socketpair()
        server.settimeout(2); client.settimeout(5)
        calls = 0
        def frame(*_, **__):
            nonlocal calls
            calls += 1
            w, h = (64, 48) if calls <= 12 else (96, 64)
            pixels = bytearray([10, 80, 200, 255] * (w*h))
            return struct.pack('!III', w, h, len(pixels)), pixels
        original = capture.encoder_command
        def command(*args):
            value = original(*args); value[0] = os.environ['PLUS_FFMPEG']; return value
        errors = []
        def run():
            try: capture.stream_avc(server, 30)
            except OSError as error: errors.append(str(error))
            finally: server.close()
        with patch.object(capture, 'capture', side_effect=frame), patch.object(capture, 'encoder_command', side_effect=command):
            worker = threading.Thread(target=run); worker.start()
            try:
                received = bytearray()
                while True:
                    data = client.recv(65536)
                    if not data: break
                    received.extend(data)
            finally:
                client.close(); worker.join(timeout=5)
            self.assertFalse(worker.is_alive())
            self.assertTrue(any('resized' in e for e in errors), errors)
            self.assertEqual(struct.unpack('!4sII', received[:12]), (b'PAV1', 64, 48))
            self.assertGreater(len(received), 12)
