"""実機検証用の構造物のファイル (test-structure.nbt。ストラクチャーブロックの形式) を作る。

正面 (+Z、南) から見て、左上が赤、右上が緑、左下が青、右下が黄色のブロックの壁で、
テスト画像と同じ色の並びになるので、スクリーンショットの判定をそのまま使える。

    python tools/verify/make_test_structure.py
"""

import gzip
import struct
from pathlib import Path

HERE = Path(__file__).resolve().parent

# (x, y, ブロック)。正面から見て右が +X、上が +Y
BLOCKS = [
    (0, 1, "minecraft:red_concrete"),
    (1, 1, "minecraft:emerald_block"),
    (0, 0, "minecraft:lapis_block"),
    (1, 0, "minecraft:yellow_concrete"),
]


def name(text):
    data = text.encode("utf-8")
    return struct.pack(">H", len(data)) + data


def tag(kind, key, payload):
    return bytes([kind]) + name(key) + payload


def compound(entries):
    return b"".join(entries) + b"\x00"


def int_list(values):
    return bytes([3]) + struct.pack(">i", len(values)) + b"".join(struct.pack(">i", v) for v in values)


def main():
    palette = []
    for _, _, block in BLOCKS:
        palette.append(compound([tag(8, "Name", name(block))]))
    blocks = []
    for index, (x, y, _) in enumerate(BLOCKS):
        blocks.append(compound([tag(9, "pos", int_list([x, y, 0])), tag(3, "state", struct.pack(">i", index))]))
    root = compound([
        tag(3, "DataVersion", struct.pack(">i", 4671)),
        tag(9, "size", int_list([2, 2, 1])),
        tag(9, "palette", bytes([10]) + struct.pack(">i", len(palette)) + b"".join(palette)),
        tag(9, "blocks", bytes([10]) + struct.pack(">i", len(blocks)) + b"".join(blocks)),
        tag(9, "entities", bytes([0]) + struct.pack(">i", 0)),
    ])
    data = bytes([10]) + name("") + root
    path = HERE / "test-structure.nbt"
    with open(path, "wb") as raw:
        with gzip.GzipFile(fileobj=raw, mode="wb", mtime=0) as out:
            out.write(data)
    print(path, path.stat().st_size, "bytes")


if __name__ == "__main__":
    main()
