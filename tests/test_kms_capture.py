import importlib.util
from pathlib import Path
import unittest
spec = importlib.util.spec_from_file_location('kms_capture', Path(__file__).resolve().parents[1] / 'tools/experiments/kms-capture-server.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class ActivePlaneTest(unittest.TestCase):
    def test_skips_stale_disabled_scanout(self):
        state = 'plane[1]: plane-0\n\tcrtc=crtc-0\n\tfb=149\nplane[2]: plane-1\n\tcrtc=crtc-1\n\tfb=177\ncrtc[3]: crtc-0\n\tenable=1\n\tactive=0\ncrtc[4]: crtc-1\n\tenable=1\n\tactive=1\n'
        self.assertEqual(module.active_framebuffer(state), 177)
    def test_no_active_output_is_not_a_stale_frame(self):
        with self.assertRaises(OSError):
            module.active_framebuffer('plane[1]: plane-0\n\tcrtc=(null)\n\tfb=0\n')

if __name__ == '__main__':
    unittest.main()
