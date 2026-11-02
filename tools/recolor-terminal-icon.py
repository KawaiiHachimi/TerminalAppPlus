#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Recolor only green pixels in extracted QPR3 launcher PNGs. Requires Pillow.
Usage: python3 tools/recolor-terminal-icon.py /path/to/apktool/res
"""
import argparse
import colorsys
from pathlib import Path
from PIL import Image


def recolor(image):
    original = image.convert('RGBA')
    pixels = []
    for r, g, b, a in original.getdata():
        h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
        if a and s > .2 and .20 <= h < .50:
            # Taper at green/cyan boundaries to retain the original gradient.
            amount = min(1., (h - .20) / .08, (.50 - h) / .04)
            r, g, b = [round(x * 255) for x in colorsys.hsv_to_rgb(h + (.61 - h) * amount, s, v)]
        pixels.append((r, g, b, a))
    output = Image.new('RGBA', original.size)
    output.putdata(pixels)
    assert output.getchannel('A').tobytes() == original.getchannel('A').tobytes()
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parents[1] / 'app/src/main/res')
    args = parser.parse_args()
    for source in sorted(args.source.glob('mipmap-*/ic_launcher*.png')):
        target = args.output / source.parent.name / source.name
        if source.resolve() == target.resolve():
            raise ValueError('Use original extracted PNGs as input, not the output directory')
        target.parent.mkdir(parents=True, exist_ok=True)
        with Image.open(source) as original:
            recolor(original).save(target, optimize=True)
        target.with_suffix('.webp').unlink(missing_ok=True)
        print(target)


if __name__ == '__main__':
    main()
