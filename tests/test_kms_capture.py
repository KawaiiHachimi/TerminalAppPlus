import importlib.util
from pathlib import Path
import unittest
spec = importlib.util.spec_from_file_location('kms_capture', Path(__file__).resolve().parents[1] / 'tools/experiments/kms-capture-server.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class PackagedCaptureTest(unittest.TestCase):
    def test_install_and_cloud_init_payloads_match_capture_source(self):
        root = Path(__file__).resolve().parents[1]
        expected = (root / 'tools/experiments/kms-capture-server.py').read_bytes()
        self.assertEqual((root / 'app/src/main/assets/guest-setup/terminal-plus-capture.py').read_bytes(), expected)
        self.assertEqual((root / 'guest/root_files/usr/local/bin/terminal-plus-capture.py').read_bytes(), expected)
        unit = (root / 'guest/root_files/etc/systemd/system/terminal-plus-guest.service').read_text()
        self.assertIn('WantedBy=multi-user.target', unit)
        self.assertNotIn('RuntimeMaxSec', unit)

class ActivePlaneTest(unittest.TestCase):
    def test_skips_stale_disabled_scanout(self):
        state = 'plane[1]: plane-0\n\tcrtc=crtc-0\n\tfb=149\nplane[2]: plane-1\n\tcrtc=crtc-1\n\tfb=177\ncrtc[3]: crtc-0\n\tenable=1\n\tactive=0\ncrtc[4]: crtc-1\n\tenable=1\n\tactive=1\n'
        self.assertEqual(module.active_framebuffer(state), 177)
    def test_no_active_output_is_not_a_stale_frame(self):
        with self.assertRaises(OSError):
            module.active_framebuffer('plane[1]: plane-0\n\tcrtc=(null)\n\tfb=0\n')

if __name__ == '__main__':
    unittest.main()

class CursorCompositionTest(unittest.TestCase):
    def test_premultiplied_cursor_alpha(self):
        frame = bytearray([0, 0, 255, 255])
        module.compose_cursor(frame, 1, 1, bytes([128, 0, 0, 128]), 1, 1, 0, 0)
        self.assertEqual(frame, bytearray([128, 0, 127, 255]))

    def test_cursor_clipped_at_negative_position(self):
        frame = bytearray([0, 0, 0, 255]*4)
        cursor = bytes([255, 0, 0, 255, 0, 255, 0, 255])
        module.compose_cursor(frame, 2, 2, cursor, 2, 1, -1, 1)
        self.assertEqual(frame[8:12], bytearray([0, 255, 0, 255]))
        self.assertEqual(frame[12:16], bytearray([0, 0, 0, 255]))

    def test_active_cursor_plane_coordinates(self):
        state = ('plane[1]: primary\n crtc=crtc-0\n fb=50\n normalized-zpos=0\n'
                 'plane[2]: cursor\n crtc=crtc-0\n fb=51\n crtc-pos=64x64-2+20\n normalized-zpos=1\n'
                 'crtc[3]: crtc-0\n enable=1\n active=1\n')
        self.assertEqual(module.active_planes(state)[1]['rect'], (64, 64, -2, 20))

class FramePacingTest(unittest.TestCase):
    def test_capture_time_is_part_of_frame_budget(self):
        self.assertAlmostEqual(module.frame_delay(1/30, 0.020), 1/30-0.020)
        self.assertEqual(module.frame_delay(1/30, 0.050), 0)
    def test_reuse_is_bounded_by_role_not_resolution_history(self):
        buffers = {}
        first = module.reusable_buffer(buffers, 'primary', 16)
        self.assertIs(first, module.reusable_buffer(buffers, 'primary', 16))
        self.assertEqual(len(module.reusable_buffer(buffers, 'primary', 32)), 32)
        self.assertEqual(len(buffers), 1)

class AdaptiveCompressionTest(unittest.TestCase):
    def test_flat_desktop_compresses_losslessly(self):
        import zlib
        pixels = bytearray([20, 40, 60, 255]*4096)
        codec, payload = module.encode_frame(pixels)
        self.assertEqual(codec, 1)
        self.assertEqual(zlib.decompress(payload), pixels)
    def test_high_entropy_uses_raw_without_full_copy(self):
        import random
        pixels = bytearray(random.Random(0).randbytes(32768))
        codec, payload = module.encode_frame(pixels)
        self.assertEqual(codec, 0)
        self.assertEqual(payload, pixels)
        self.assertIs(payload.obj, pixels)

class NativeLz4Test(unittest.TestCase):
    def test_native_block_roundtrip_when_available(self):
        import ctypes
        encoder = module.Lz4Encoder()
        if encoder.library is None:
            self.skipTest('Native LZ4 is optional on the build host')
        pixels = bytearray([20, 40, 60, 255]*4096)
        codec, payload = encoder.encode(pixels)
        self.assertEqual(codec, 2)
        decoder = encoder.library.LZ4_decompress_safe
        decoder.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_int, ctypes.c_int]
        decoder.restype = ctypes.c_int
        output = ctypes.create_string_buffer(len(pixels))
        compressed = bytes(payload)
        size = decoder(compressed, output, len(compressed), len(pixels))
        self.assertEqual(size, len(pixels))
        self.assertEqual(output.raw, pixels)
