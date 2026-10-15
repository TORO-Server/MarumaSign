"""実機検証用の 3D モデル (test-model.glb) を作る。

正面にテスト画像を貼った薄い箱で、陰影を付けない材質にしてある。
画像の看板と同じ見え方になるので、スクリーンショットの判定をそのまま使える。
ノードの階層、拡大、回転、平行移動を通しても正しい位置と向きになることも確かめられる。

    python tools/verify/make_test_model.py
"""

import json
import struct
from pathlib import Path

HERE = Path(__file__).resolve().parent

WIDTH, HEIGHT, DEPTH = 1.6, 1.0, 0.08


def box():
    """(位置, 法線, テクスチャ座標, 頂点番号) を、正面とそれ以外に分けて返す。"""
    x, y, z = WIDTH / 2, HEIGHT / 2, DEPTH / 2
    faces = [
        # 法線, 4 つの角 (外から見て反時計回り)
        ((0, 0, 1), [(-x, -y, z), (x, -y, z), (x, y, z), (-x, y, z)]),
        ((0, 0, -1), [(x, -y, -z), (-x, -y, -z), (-x, y, -z), (x, y, -z)]),
        ((1, 0, 0), [(x, -y, z), (x, -y, -z), (x, y, -z), (x, y, z)]),
        ((-1, 0, 0), [(-x, -y, -z), (-x, -y, z), (-x, y, z), (-x, y, -z)]),
        ((0, 1, 0), [(-x, y, z), (x, y, z), (x, y, -z), (-x, y, -z)]),
        ((0, -1, 0), [(-x, -y, -z), (x, -y, -z), (x, -y, z), (-x, -y, z)]),
    ]
    uv = [(0, 1), (1, 1), (1, 0), (0, 0)]
    front = ([], [], [], [])
    rest = ([], [], [], [])
    for index, (normal, corners) in enumerate(faces):
        positions, normals, coords, indices = front if index == 0 else rest
        base = len(positions)
        for corner, coord in zip(corners, uv):
            positions.append(corner)
            normals.append(normal)
            coords.append(coord)
        indices += [base, base + 1, base + 2, base, base + 2, base + 3]
    return front, rest


def main():
    image = (HERE / "test-image.png").read_bytes()
    binary = bytearray()
    views = []
    accessors = []

    def view(data, target=None):
        while len(binary) % 4:
            binary.append(0)
        entry = {"buffer": 0, "byteOffset": len(binary), "byteLength": len(data)}
        if target is not None:
            entry["target"] = target
        binary.extend(data)
        views.append(entry)
        return len(views) - 1

    def accessor(values, kind, component, fmt, target, bounds=False):
        flat = [value for item in values for value in (item if isinstance(item, tuple) else (item,))]
        entry = {"bufferView": view(struct.pack(f"<{len(flat)}{fmt}", *flat), target),
                 "componentType": component, "count": len(values), "type": kind}
        if bounds:
            entry["min"] = [min(item[axis] for item in values) for axis in range(3)]
            entry["max"] = [max(item[axis] for item in values) for axis in range(3)]
        accessors.append(entry)
        return len(accessors) - 1

    primitives = []
    for material, (positions, normals, coords, indices) in enumerate(box()):
        primitives.append({
            "attributes": {
                "POSITION": accessor(positions, "VEC3", 5126, "f", 34962, True),
                "NORMAL": accessor(normals, "VEC3", 5126, "f", 34962),
                "TEXCOORD_0": accessor(coords, "VEC2", 5126, "f", 34962),
            },
            "indices": accessor(indices, "SCALAR", 5123, "H", 34963),
            "material": material,
        })
    image_view = view(image)
    binary.extend(b"\0" * (-len(binary) % 4))

    unlit = {"KHR_materials_unlit": {}}
    document = {
        "asset": {"version": "2.0", "generator": "MarumaSign make_test_model.py"},
        "extensionsUsed": ["KHR_materials_unlit"],
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        # 親で 2 倍に拡大して縦軸で 90 度回し、子で元に戻す。子は少しずらしてある
        "nodes": [
            {"name": "root", "children": [1], "scale": [2, 2, 2], "rotation": [0, 0.70710678, 0, 0.70710678]},
            {"name": "panel", "mesh": 0, "rotation": [0, -0.70710678, 0, 0.70710678], "translation": [3, 1, -2]},
        ],
        "meshes": [{"name": "panel", "primitives": primitives}],
        "materials": [
            {"name": "picture", "pbrMetallicRoughness": {"baseColorTexture": {"index": 0}, "metallicFactor": 0},
             "extensions": unlit},
            {"name": "frame", "pbrMetallicRoughness": {"baseColorFactor": [0.2, 0.2, 0.2, 1], "metallicFactor": 0},
             "extensions": unlit},
        ],
        "textures": [{"source": 0, "sampler": 0}],
        "samplers": [{"wrapS": 33071, "wrapT": 33071}],
        "images": [{"bufferView": image_view, "mimeType": "image/png"}],
        "accessors": accessors,
        "bufferViews": views,
        "buffers": [{"byteLength": len(binary)}],
    }

    text = json.dumps(document, separators=(",", ":")).encode("utf-8")
    text += b" " * (-len(text) % 4)
    total = 12 + 8 + len(text) + 8 + len(binary)
    output = HERE / "test-model.glb"
    with output.open("wb") as file:
        file.write(struct.pack("<III", 0x46546C67, 2, total))
        file.write(struct.pack("<II", len(text), 0x4E4F534A))
        file.write(text)
        file.write(struct.pack("<II", len(binary), 0x004E4942))
        file.write(bytes(binary))
    print(f"Wrote {output.name} ({total} bytes)")


if __name__ == "__main__":
    main()
