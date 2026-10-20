"""実機検証用のガウシアンスプラッティング (.splat) を作る。

test-image.png をサンプリングしたガウス分布の板を生成する。
正面から見たときに test-image.png と同じ色の配置 (赤・緑・青・黄の 4 区画と中央の円) になるため、
verify.py のスクリーンショット自動判定 (analyze_screenshot) をそのまま通過できる。

    python tools/verify/make_test_splat.py
"""

import math
import struct
from pathlib import Path
from PIL import Image

HERE = Path(__file__).resolve().parent
IMAGE_PATH = HERE / "test-image.png"
OUTPUT_PATH = HERE / "test-splat.splat"

WIDTH = 1.6
HEIGHT = 1.0
COLS = 80
ROWS = 50


def main():
    image = Image.open(IMAGE_PATH).convert("RGBA")
    img_w, img_h = image.size

    binary = bytearray()
    dx = WIDTH / COLS
    dy = HEIGHT / ROWS
    radius = max(dx, dy) * 0.75  # わずかに重ねて隙間を埋める

    for row in range(ROWS):
        # .splat は +Y が下なので、上が -HEIGHT/2、下が +HEIGHT/2
        y = -HEIGHT / 2 + (row + 0.5) * dy
        img_y = int((row + 0.5) / ROWS * img_h)
        for col in range(COLS):
            x = -WIDTH / 2 + (col + 0.5) * dx
            img_x = int((col + 0.5) / COLS * img_w)
            z = 0.0

            r, g, b, a = image.getpixel((img_x, img_y))
            # 完全に透明な部分はスキップ
            if a < 10:
                continue

            # pos (x, y, z)
            pos_bytes = struct.pack("<3f", x, y, z)
            # scale (sx, sy, sz)
            scale_bytes = struct.pack("<3f", radius, radius, 0.005)
            # color & opacity (r, g, b, a)
            color_bytes = struct.pack("4B", r, g, b, a)
            # rotation (w, x, y, z: 単位四元数 (1, 0, 0, 0))
            # 符号化: byte = round(value * 128 + 128)
            # w=1 -> 255 (または 255), x=0 -> 128, y=0 -> 128, z=0 -> 128
            rot_bytes = struct.pack("4B", 255, 128, 128, 128)

            binary.extend(pos_bytes)
            binary.extend(scale_bytes)
            binary.extend(color_bytes)
            binary.extend(rot_bytes)

    OUTPUT_PATH.write_bytes(binary)
    total_splats = len(binary) // 32
    print(f"Wrote {OUTPUT_PATH} ({len(binary)} bytes, {total_splats} splats)")


if __name__ == "__main__":
    main()
