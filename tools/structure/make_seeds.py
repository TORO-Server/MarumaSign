"""構造物のファイルの読み込みを確かめるための、小さな種のファイル (core/src/test/resources/structure) を作る。

対応しているすべての形式 (.nbt / .schem v1 - v3 / .litematic / .mcstructure / .schematic) と、gzip・zlib・圧縮なしの包み方、
ビット数が 64 を割り切らない Litematica の番号 (long の境目をまたぐもの)、2 バイトになる Sponge の番号、
複数の領域や負の大きさの領域、ブロックエンティティ、エンティティ、バイオーム、構造物の中の MarumaSign の看板を含める。
StructureFuzz の種と、StructureRegressionCheck の読み込みの確認 (expected.txt に書いた位置とブロック) に使う。
NBT は Python の標準ライブラリだけで手で書く (同じものが何度でも作れるように、乱数の種と gzip の時刻は固定)。

    python tools/structure/make_seeds.py
"""

import gzip
import json
import random
import struct
import zlib
from pathlib import Path

OUT = Path(__file__).resolve().parents[2] / "core" / "src" / "test" / "resources" / "structure"

END, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, BYTE_ARRAY, STRING, LIST, COMPOUND, INT_ARRAY, LONG_ARRAY = range(13)


class Tag:
    """種類を決めた値。リストは要素の種類 (element) も持つ"""

    def __init__(self, kind, value, element=None):
        self.kind, self.value, self.element = kind, value, element


def b(v): return Tag(BYTE, v)
def s(v): return Tag(SHORT, v)
def i(v): return Tag(INT, v)
def f(v): return Tag(FLOAT, v)
def d(v): return Tag(DOUBLE, v)
def ba(v): return Tag(BYTE_ARRAY, bytes(v))
def lst(element, items): return Tag(LIST, list(items), element)
def ia(v): return Tag(INT_ARRAY, list(v))
def la(v): return Tag(LONG_ARRAY, list(v))
def ints(v): return lst(INT, [i(x) for x in v])
def floats(v): return lst(FLOAT, [f(x) for x in v])
def doubles(v): return lst(DOUBLE, [d(x) for x in v])
def compounds(v): return lst(COMPOUND, v)


def tag(value):
    if isinstance(value, Tag):
        return value
    if isinstance(value, int):
        return i(value)
    if isinstance(value, float):
        return d(value)
    if isinstance(value, str):
        return Tag(STRING, value)
    if isinstance(value, dict):
        return Tag(COMPOUND, value)
    raise TypeError(value)


def nbt(root, little=False, name=""):
    """一番外側が複合タグの NBT。little なら統合版のリトルエンディアン"""
    e = "<" if little else ">"
    out = bytearray()

    def string(text):
        data = text.encode("utf-8")
        out.extend(struct.pack(e + "H", len(data)))
        out.extend(data)

    def value(t):
        k, v = t.kind, t.value
        if k == BYTE: out.extend(struct.pack(e + "b", v))
        elif k == SHORT: out.extend(struct.pack(e + "h", v))
        elif k == INT: out.extend(struct.pack(e + "i", v))
        elif k == LONG: out.extend(struct.pack(e + "q", v))
        elif k == FLOAT: out.extend(struct.pack(e + "f", v))
        elif k == DOUBLE: out.extend(struct.pack(e + "d", v))
        elif k == BYTE_ARRAY:
            out.extend(struct.pack(e + "i", len(v)))
            out.extend(v)
        elif k == STRING: string(v)
        elif k == LIST:
            items = [tag(x) for x in v]
            out.append(t.element if items else END)
            out.extend(struct.pack(e + "i", len(items)))
            for item in items: value(item)
        elif k == COMPOUND:
            for key, item in v.items():
                item = tag(item)
                out.append(item.kind)
                string(key)
                value(item)
            out.append(END)
        elif k == INT_ARRAY:
            out.extend(struct.pack(e + "i", len(v)))
            for x in v: out.extend(struct.pack(e + "i", x))
        elif k == LONG_ARRAY:
            out.extend(struct.pack(e + "i", len(v)))
            for x in v: out.extend(struct.pack(e + "q", x))

    out.append(COMPOUND)
    string(name)
    value(Tag(COMPOUND, root))
    return bytes(out)


def gz(data):
    return gzip.compress(data, mtime=0)


def varints(values):
    out = bytearray()
    for v in values:
        while True:
            low = v & 0x7F
            v >>= 7
            out.append(low | (0x80 if v else 0))
            if not v:
                break
    return bytes(out)


def litematic_longs(indices, bits):
    """LitematicaBitArray と同じ詰め方 (番号は long の境目をまたぐ)"""
    longs = [0] * ((len(indices) * bits + 63) // 64)
    for n, v in enumerate(indices):
        start = n * bits
        at, offset = start >> 6, start & 63
        longs[at] |= (v << offset) & (2 ** 64 - 1)
        if offset + bits > 64:
            longs[at + 1] |= v >> (64 - offset)
    return [x - 2 ** 64 if x >= 2 ** 63 else x for x in longs]


def state_tag(state, new_style=False):
    name, properties = state if isinstance(state, tuple) else (state, {})
    entry = {"id" if new_style else "Name": name}
    if properties:
        entry["properties" if new_style else "Properties"] = {k: v for k, v in properties.items()}
    return entry


def name_of(state):
    return state[0] if isinstance(state, tuple) else state


def sign_data(lines, front=True):
    written = [json.dumps(t) for t in lines] + ['""'] * (4 - len(lines))
    empty = ['""'] * 4
    side = lambda m: {"messages": lst(STRING, m), "color": "black", "has_glowing_text": b(0)}
    return {"id": "minecraft:sign", "front_text": side(written if front else empty), "back_text": side(empty if front else written), "is_waxed": b(0)}


BLOCKS = ["minecraft:stone", "minecraft:dirt", "minecraft:oak_planks", "minecraft:glass", "minecraft:gold_block",
          "minecraft:white_wool", "minecraft:red_wool", "minecraft:blue_wool", "minecraft:bricks", "minecraft:sand",
          "minecraft:gravel", "minecraft:iron_block", "minecraft:diamond_block", "minecraft:emerald_block",
          "minecraft:lapis_block", "minecraft:redstone_block", "minecraft:coal_block", "minecraft:netherrack",
          "minecraft:end_stone", "minecraft:obsidian", "minecraft:glowstone", "minecraft:sea_lantern",
          "minecraft:prismarine", "minecraft:terracotta", "minecraft:white_concrete", "minecraft:black_concrete",
          "minecraft:mossy_cobblestone", "minecraft:cobblestone", "minecraft:smooth_stone", "minecraft:quartz_block"]


def many_states(n):
    """n 種類のブロックの状態 (名前を繰り返し、知らない性質の値で区別する)"""
    result = []
    for k in range(n):
        base = BLOCKS[k % len(BLOCKS)]
        variant = k // len(BLOCKS)
        result.append((base, {"seed": str(variant)}) if variant else base)
    return result


class Seeds:
    def __init__(self):
        self.files = {}
        self.expected = {}
        self.entities = {}

    def add(self, name, data, expected=None, entities=None):
        """expected は {(x, y, z): 名前}、entities は構造物の角からのエンティティの位置 [(名前, x, y, z)]"""
        self.files[name] = data
        if expected:
            self.expected[name] = expected
        if entities:
            self.entities[name] = entities


def template(size, palette, blocks, entities=(), data_version=4671, new_style=False, palettes=0):
    """ストラクチャーブロックの形式 (.nbt)"""
    entries = [state_tag(p, new_style) for p in palette]
    root = {"DataVersion": i(data_version), "size": ints(size)}
    if palettes:
        root["palettes"] = lst(LIST, [compounds(entries) for _ in range(palettes)])
    else:
        root["palette"] = compounds(entries)
    items = []
    for block in blocks:
        entry = {"pos": ints(block[:3]), "state": i(block[3])}
        if len(block) > 4:
            entry["nbt"] = block[4]
        items.append(entry)
    root["blocks"] = compounds(items)
    root["entities"] = compounds([{"pos": doubles(p), "blockPos": ints([int(x) for x in p]), "nbt": n} for p, n in entities])
    return root


def sponge(version, size, palette, indices, block_entities=(), entities=(), biomes=None, offset=(0, 0, 0)):
    """Sponge Schematic (.schem)。indices は x、z、y の順。
    バージョン 2 までは WorldEdit と同じく、Offset に構造物の角のワールドの座標を書き、エンティティの位置はワールドの座標にする"""
    w, h, l = size
    pal = {name: i(n) for n, name in enumerate(palette)}
    if version >= 3:
        blocks = {"Palette": pal, "Data": ba(varints(indices)),
                  "BlockEntities": compounds([{"Pos": ia(p), "Id": n, "Data": data} for p, n, data in block_entities])}
        schematic = {"Version": i(3), "DataVersion": i(3955), "Width": s(w), "Height": s(h), "Length": s(l), "Offset": ia([0, 0, 0]),
                     "Blocks": blocks, "Entities": compounds([{"Pos": doubles(p), "Id": n, "Data": data} for p, n, data in entities])}
        if biomes:
            names, values = biomes
            schematic["Biomes"] = {"Palette": {name: i(n) for n, name in enumerate(names)}, "Data": ba(varints(values))}
        return {"Schematic": schematic}
    root = {"Version": i(version), "Width": s(w), "Height": s(h), "Length": s(l), "PaletteMax": i(len(palette)), "Palette": pal,
            "BlockData": ba(varints(indices)), "Offset": ia(offset)}
    entities = [([p[k] + offset[k] for k in range(3)], n, data) for p, n, data in entities]
    tiles = compounds([dict({"Pos": ia(p), "Id": n}, **data) for p, n, data in block_entities])
    if version >= 2:
        root["DataVersion"] = i(3465)
        root["BlockEntities"] = tiles
        root["Entities"] = compounds([dict({"Pos": doubles(p), "Id": n}, **data) for p, n, data in entities])
        if biomes:
            names, values = biomes
            root["BiomePalette"] = {name: i(n) for n, name in enumerate(names)}
            root["BiomeData"] = ba(varints(values))
    else:
        root["TileEntities"] = tiles
    return root


def litematic_region(position, size, palette, indices, tiles=(), entities=()):
    """Litematica の領域。indices は x、z、y の順"""
    return {
        "Position": {"x": i(position[0]), "y": i(position[1]), "z": i(position[2])},
        "Size": {"x": i(size[0]), "y": i(size[1]), "z": i(size[2])},
        "BlockStatePalette": compounds([state_tag(p) for p in palette]),
        "BlockStates": la(litematic_longs(indices, max(2, (max(1, len(palette)) - 1).bit_length()))),
        "TileEntities": compounds([dict({"x": i(x), "y": i(y), "z": i(z)}, **data) for x, y, z, data in tiles]),
        "Entities": compounds([dict({"Pos": doubles(p)}, **data) for p, data in entities]),
        "PendingBlockTicks": lst(END, []),
    }


def litematic(regions):
    return {"Version": i(6), "SubVersion": i(1), "MinecraftDataVersion": i(3465),
            "Metadata": {"Name": "seed", "Author": "make_seeds.py", "RegionCount": i(len(regions))}, "Regions": regions}


def build():
    seeds = Seeds()
    rng = random.Random(20261028)

    # ---------- ストラクチャーブロック (.nbt) ----------
    palette = ["minecraft:air", "minecraft:stone",
               ("minecraft:oak_stairs", {"facing": "east", "half": "bottom", "shape": "straight", "waterlogged": "false"}),
               ("minecraft:oak_sign", {"rotation": "4", "waterlogged": "false"}),
               ("minecraft:chest", {"facing": "north", "type": "single", "waterlogged": "false"}),
               ("minecraft:white_banner", {"rotation": "0"}), ("minecraft:water", {"level": "0"}), "minecraft:oak_fence"]
    blocks = [(0, 0, 0, 1), (1, 0, 0, 1), (2, 0, 0, 1), (3, 0, 0, 1), (0, 1, 0, 2),
              (1, 1, 0, 3, sign_data(["Hello", {"text": "World", "color": "red", "bold": True}, [{"text": "a"}, "b"]])),
              (2, 1, 0, 4, {"id": "minecraft:chest", "Items": lst(END, [])}),
              (3, 1, 0, 5, {"id": "minecraft:banner", "patterns": compounds([{"color": "red", "pattern": "minecraft:creeper"}])}),
              (0, 0, 1, 6), (1, 0, 1, 7), (2, 0, 1, 7), (0, 2, 0, 1), (3, 2, 1, 1)]
    entities = [((1.5, 2.0, 1.03), {"id": "minecraft:painting", "variant": "minecraft:kebab", "facing": b(0)}),
                ((2.5, 2.5, 1.03), {"id": "minecraft:item_frame", "Facing": b(3), "Item": {"id": "minecraft:diamond", "count": i(1)}}),
                ((3.5, 1.0, 1.5), {"id": "minecraft:armor_stand", "Rotation": floats([30, 0]), "ShowArms": b(1),
                                   "equipment": {"head": {"id": "minecraft:iron_helmet", "count": i(1)}}}),
                ((0.5, 3.0, 0.5), {"id": "minecraft:block_display", "block_state": {"Name": "minecraft:gold_block"},
                                   "transformation": {"translation": floats([0, 0, 0]), "left_rotation": floats([0, 0, 0, 1]),
                                                      "scale": floats([0.5, 0.5, 0.5]), "right_rotation": floats([0, 0, 0, 1])}}),
                ((1.5, 3.0, 0.5), {"id": "minecraft:item_display", "item": {"id": "minecraft:apple", "count": i(1)}, "item_display": "fixed"}),
                ((2.5, 3.0, 0.5), {"id": "minecraft:text_display", "text": json.dumps({"text": "Text", "color": "gold"})})]
    expected = {(x, y, z): name_of(palette[p]) for x, y, z, p, *_ in blocks}
    root = template([4, 3, 2], palette, blocks, entities)
    seeds.add("template_basic.nbt", gz(nbt(root)), expected)
    seeds.add("template_raw.nbt", nbt(root), expected)
    seeds.add("template_zlib.nbt", zlib.compress(nbt(root)), expected)
    seeds.add("template_new_style.nbt", gz(nbt(template([4, 3, 2], palette, blocks, entities, data_version=4800, new_style=True))), expected)
    seeds.add("template_palettes.nbt", gz(nbt(template([4, 3, 2], palette, blocks, palettes=3))), expected)

    # 構造物の中の MarumaSign の看板 (読まない手元の URL。値に NaN や大きな数のものも) と、看板のアイテムを入れた額縁
    url = "http://127.0.0.1:1/"
    palette = ["minecraft:air", "minecraft:stone", ("minecraft:oak_sign", {"rotation": "0", "waterlogged": "false"}),
               ("minecraft:oak_wall_sign", {"facing": "south", "waterlogged": "false"})]
    blocks = [(0, 0, 0, 1), (1, 0, 0, 1), (2, 0, 0, 1),
              (0, 1, 0, 2, sign_data([url + "a.png|0|0|0|1|1|0|0|0"])),
              (1, 1, 0, 2, sign_data([url + "b.nbt|1e30|-1e30|0|1000|1000|0|0|0"], front=False)),
              (2, 1, 0, 3, sign_data([url + "c.glb|NaN|0|0|1|1|0|0|0"]))]
    item = {"id": "minecraft:oak_sign", "count": i(1),
            "components": {"minecraft:block_entity_data": sign_data([url + "d.png|0|0|0|1|1|0|0|0"])}}
    entities = [((0.5, 2.5, 0.03), {"id": "minecraft:item_frame", "Facing": b(3), "Item": item})]
    seeds.add("template_nested_signs.nbt", gz(nbt(template([3, 3, 1], palette, blocks, entities))),
              {(x, y, z): name_of(palette[p]) for x, y, z, p, *_ in blocks})

    # ---------- Sponge Schematic (.schem) ----------
    def sponge_seed(name, version, size, count, extra=True, offset=(0, 0, 0)):
        w, h, l = size
        palette = ["minecraft:air"] + [p if isinstance(p, str) else p[0] + "[" + ",".join(k + "=" + v for k, v in p[1].items()) + "]"
                                       for p in many_states(count - 1)]
        indices = [rng.randrange(count) for _ in range(w * h * l)]
        block_entities, entities, biomes = (), (), None
        if extra:
            palette.append("minecraft:oak_sign[rotation=0,waterlogged=false]")
            indices[0] = len(palette) - 1
            block_entities = [([0, 0, 0], "minecraft:sign", sign_data(["Sponge", "v%d" % version]))]
            entities = [((1.5, 1.0, 1.5), "minecraft:armor_stand", {"Rotation": floats([10, 0])}),
                        ((0.5, 1.0, 0.03), "minecraft:item_frame", {"Facing": b(3), "Item": {"id": "minecraft:stone", "count": i(1)}})]
            names = ["minecraft:plains", "minecraft:desert", "minecraft:swamp"]
            biomes = (names, [rng.randrange(3) for _ in range(w * h * l if version >= 3 else w * l)])
        expected = {}
        for n, v in enumerate(indices):
            if v:
                expected[(n % w, n // (w * l), (n // w) % l)] = palette[v].split("[")[0]
        seeds.add(name, gz(nbt(sponge(version, size, palette, indices, block_entities, entities, biomes, offset))), expected,
                  [(n, p[0], p[1], p[2]) for p, n, _ in entities] if version >= 2 else None)
        return palette, indices

    sponge_seed("sponge_v1.schem", 1, (5, 3, 4), 6)
    sponge_seed("sponge_v2.schem", 2, (7, 4, 5), 12, offset=(100, 64, -200))
    sponge_seed("sponge_v3.schem", 3, (9, 3, 6), 20)
    # 番号が 2 バイトの可変長の整数になる大きなパレット
    sponge_seed("sponge_v2_varint.schem", 2, (16, 4, 16), 400)
    # バージョン 3 なのに Schematic の中に入れずに書いたもの
    palette = ["minecraft:air", "minecraft:stone", "minecraft:glass"]
    indices = [1, 2, 0, 1, 2, 0, 1, 2]
    seeds.add("sponge_v3_flat.schem", gz(nbt(sponge(3, (2, 2, 2), palette, indices)["Schematic"])),
              {(n % 2, n // 4, (n // 2) % 2): palette[v] for n, v in enumerate(indices) if v})

    # ---------- Litematica (.litematic) ----------
    # 番号のビット数 2、3、5、9 (3、5、9 は 64 を割り切らないので、long の境目をまたぐ番号がある)
    for count in (4, 5, 17, 300):
        palette = ["minecraft:air"] + many_states(count - 1)
        sx, sy, sz = 7, 5, 3
        indices = [rng.randrange(count) for _ in range(sx * sy * sz)]
        expected = {(n % sx, n // (sx * sz), (n // sx) % sz): name_of(palette[v]) for n, v in enumerate(indices) if v}
        bits = max(2, (count - 1).bit_length())
        seeds.add("litematic_bits%d.litematic" % bits, gz(nbt(litematic({"main": litematic_region((0, 0, 0), (sx, sy, sz), palette, indices)}))), expected)
    # 複数の領域。2 つ目は大きさが負 (位置から負の向きに広がる)
    expected = {}
    palette_a = ["minecraft:air", "minecraft:stone", ("minecraft:oak_sign", {"rotation": "8", "waterlogged": "false"})]
    indices_a = [1] * 12
    indices_a[5] = 2
    for n, v in enumerate(indices_a):
        expected[(n % 3, n // 6, (n // 3) % 2)] = name_of(palette_a[v])
    palette_b = ["minecraft:air", "minecraft:gold_block", "minecraft:diamond_block"]
    indices_b = [rng.randrange(1, 3) for _ in range(12)]
    for n, v in enumerate(indices_b):
        expected[(5 + n % 2, 2 + n // 6, 2 + (n // 2) % 3)] = palette_b[v]
    regions = {"A": litematic_region((0, 0, 0), (3, 2, 2), palette_a, indices_a, tiles=[(2, 0, 1, sign_data(["Litematic"]))],
                                     entities=[((1.5, 2.0, 1.5), {"id": "minecraft:armor_stand"})]),
               "B": litematic_region((6, 3, 4), (-2, -2, -3), palette_b, indices_b,
                                     entities=[((-0.5, -0.5, -1.0), {"id": "minecraft:painting", "variant": "minecraft:kebab", "facing": b(0)})])}
    seeds.add("litematic_regions.litematic", gz(nbt(litematic(regions))), expected)

    # ---------- 統合版 (.mcstructure、リトルエンディアンで圧縮なし) ----------
    palette = [("minecraft:air", {}), ("minecraft:stone", {"stone_type": "stone"}), ("minecraft:water", {"liquid_depth": i(0)}),
               ("minecraft:oak_stairs", {"upside_down_bit": b(0), "weirdo_direction": i(1)}),
               ("minecraft:standing_sign", {"ground_sign_direction": i(4)}), ("minecraft:chest", {"facing_direction": i(2)}),
               ("minecraft:bed", {"direction": i(0), "head_piece_bit": b(0), "occupied_bit": b(0)}),
               ("minecraft:cobblestone_wall", {}), ("minecraft:fence", {"wood_type": "oak"})]
    sx, sy, sz = 4, 3, 3

    def at(x, y, z):
        return (x * sy + y) * sz + z

    primary = [0] * (sx * sy * sz)
    secondary = [-1] * len(primary)
    expected = {}
    for x in range(sx):
        for z in range(sz):
            primary[at(x, 0, z)] = 1
            expected[(x, 0, z)] = "minecraft:stone"
    for (x, y, z), p in {(0, 1, 0): 3, (1, 1, 0): 4, (2, 1, 0): 5, (3, 1, 0): 5, (0, 1, 2): 6, (1, 1, 2): 7, (2, 1, 2): 8, (3, 1, 2): 8}.items():
        primary[at(x, y, z)] = p
    secondary[at(0, 1, 0)] = 2
    position_data = {
        at(1, 1, 0): {"id": "Sign", "FrontText": {"Text": "Bedrock\nsign", "SignTextColor": i(-16777216)}, "x": i(1), "y": i(1), "z": i(0)},
        at(2, 1, 0): {"id": "Chest", "pairx": i(3), "pairz": i(0), "x": i(2), "y": i(1), "z": i(0)},
        at(0, 1, 2): {"id": "Bed", "color": b(14)}}
    entities = [{"identifier": "minecraft:armor_stand", "Pos": floats([101.5, 65.0, 201.5]), "Rotation": floats([0, 0])},
                {"identifier": "minecraft:painting", "Pos": floats([100.5, 66.0, 202.0]), "Motive": "Kebab", "Direction": b(0)}]
    states = []
    for name, properties in palette:
        states.append({"name": name, "states": {k: v for k, v in properties.items()}, "version": i(18100737)})
    root = {"format_version": i(1), "size": ints([sx, sy, sz]),
            "structure": {"block_indices": lst(LIST, [ints(primary), ints(secondary)]), "entities": compounds(entities),
                          "palette": {"default": {"block_palette": compounds(states),
                                                  "block_position_data": {str(k): {"block_entity_data": v} for k, v in position_data.items()}}}},
            "structure_world_origin": ints([100, 64, 200])}
    seeds.add("bedrock_basic.mcstructure", nbt(root, little=True), expected)

    # ---------- MCEdit / Schematica (.schematic、1.12 までの番号) ----------
    w, h, l = 6, 3, 4

    def legacy_index(x, y, z):
        return (y * l + z) * w + x

    blocks = bytearray(w * h * l)
    data = bytearray(w * h * l)
    for x in range(w):
        for z in range(l):
            blocks[legacy_index(x, 0, z)] = 1
    placed = {(0, 1, 0): (53, 0), (1, 1, 0): (53, 2), (2, 1, 0): (63, 4), (3, 1, 0): (54, 2), (4, 1, 0): (54, 2), (0, 1, 2): (85, 0),
              (1, 1, 2): (85, 0), (2, 1, 2): (139, 0), (3, 1, 2): (55, 0), (4, 1, 2): (26, 0), (5, 1, 2): (144, 1), (5, 1, 3): (140, 0)}
    for (x, y, z), (block, meta) in placed.items():
        blocks[legacy_index(x, y, z)] = block
        data[legacy_index(x, y, z)] = meta
    tiles = [{"id": "Sign", "x": i(2), "y": i(1), "z": i(0), "Text1": json.dumps("Legacy"), "Text2": json.dumps({"text": "sign", "color": "blue"}),
              "Text3": '""', "Text4": '""'},
             {"id": "Chest", "x": i(3), "y": i(1), "z": i(0), "Items": lst(END, [])},
             {"id": "Bed", "x": i(4), "y": i(1), "z": i(2), "color": i(11)},
             {"id": "Skull", "x": i(5), "y": i(1), "z": i(2), "SkullType": b(4), "Rot": b(3)},
             {"id": "FlowerPot", "x": i(5), "y": i(1), "z": i(3), "Item": "minecraft:red_flower", "Data": i(2)}]
    # WorldEdit で書いたものと同じく、エンティティの位置はワールドの座標で、構造物の角のワールドの座標を WEOrigin に書く
    origin = (-30, 70, 12)
    entities = [{"id": "Painting", "Pos": doubles([1.5 + origin[0], 2.0 + origin[1], 0.03 + origin[2]]), "Motive": "Kebab", "Facing": b(0)},
                {"id": "ItemFrame", "Pos": doubles([2.5 + origin[0], 2.0 + origin[1], 0.03 + origin[2]]), "Facing": b(0),
                 "Item": {"id": "minecraft:diamond", "Count": b(1), "Damage": s(0)}}]
    root = {"Width": s(w), "Height": s(h), "Length": s(l), "Materials": "Alpha", "Blocks": ba(blocks), "Data": ba(data),
            "TileEntities": compounds(tiles), "Entities": compounds(entities),
            "WEOriginX": i(origin[0]), "WEOriginY": i(origin[1]), "WEOriginZ": i(origin[2]), "WEOffsetX": i(0), "WEOffsetY": i(0), "WEOffsetZ": i(0)}
    seeds.add("mcedit_basic.schematic", gz(nbt(root, name="Schematic")),
              {(x, 0, z): "minecraft:stone" for x in range(w) for z in range(l)},
              [("Painting", 1.5, 2.0, 0.03), ("ItemFrame", 2.5, 2.0, 0.03)])
    # AddBlocks (番号の上位 4 ビット) と、名前で書いた番号の付け替え (Schematica と MCEdit 2)
    blocks = bytearray([1, 2, 3, 4, 300 & 0xFF, 5, 7, 300 & 0xFF])
    add = bytearray(4)
    add[2] |= 300 >> 8          # 番号 4 (偶数) は下位 4 ビット
    add[3] |= (300 >> 8) << 4   # 番号 7 (奇数) は上位 4 ビット
    root = {"Width": s(2), "Height": s(2), "Length": s(2), "Materials": "Alpha", "Blocks": ba(blocks), "Data": ba(bytearray(8)),
            "AddBlocks": ba(add), "SchematicaMapping": {"minecraft:stone": s(300), "minecraft:dirt": s(2)},
            "BlockIDs": {"300": "minecraft:glass"}, "TileEntities": lst(END, []), "Entities": lst(END, [])}
    names = ["minecraft:stone", "minecraft:dirt", "minecraft:dirt", "minecraft:cobblestone", "minecraft:glass", "minecraft:oak_planks",
             "minecraft:bedrock", "minecraft:glass"]
    seeds.add("mcedit_add_blocks.schematic", gz(nbt(root, name="Schematic")),
              {(n % 2, n // 4, (n // 2) % 2): name for n, name in enumerate(names)})

    # ---------- 直した不具合の再現 ----------
    # Litematica: 領域の大きさのかけ算 (2^21 x 2^21 x 2^22) が long であふれて 0 になり、配列の外を読んでいた
    near = 2 ** 31 - 2 ** 20
    region_a = litematic_region((near, near, 2 ** 31 - 2 ** 21), (2 ** 21, 2 ** 21, 2 ** 22), ["minecraft:air", "minecraft:stone"], [])
    region_a["BlockStates"] = la([1, 1, 1, 1])
    region_b = litematic_region((near, near, 2 ** 31 - 2 ** 21), (1, 1, 1), ["minecraft:air", "minecraft:stone"], [1])
    seeds.add("regress_litematic_overflow.litematic", gz(nbt(litematic({"A": region_a, "B": region_b}))))
    # NaN や無限大、とても遠い位置のエンティティ (モデルの大きさが壊れ、構造物が見えなくなっていた)
    nan, inf = float("nan"), float("inf")
    entities = [((nan, 1.0, 0.5), {"id": "minecraft:armor_stand"}),
                ((1e30, 1.0, 0.5), {"id": "minecraft:item_frame", "Facing": b(3), "Item": {"id": "minecraft:stone", "count": i(1)}}),
                ((0.5, inf, 0.5), {"id": "minecraft:painting", "variant": "minecraft:kebab", "facing": b(0)}),
                ((0.5, 1.0, 0.5), {"id": "minecraft:armor_stand", "Rotation": floats([nan, 0]), "Pose": {"Head": floats([inf, 0, 0])}}),
                ((0.5, 1.0, 0.5), {"id": "minecraft:block_display", "block_state": {"Name": "minecraft:stone"},
                                   "transformation": {"scale": floats([nan, 1e30, 1])}})]
    root = template([1, 1, 1], ["minecraft:air", "minecraft:stone"], [(0, 0, 0, 1)], [((x if x == x and abs(x) < 1e9 else 0, y if abs(y) < 1e9 else 0, z), n) for (x, y, z), n in entities])
    for entry, ((x, y, z), _) in zip(root["entities"].value, entities):
        entry["pos"] = doubles([x, y, z])
    seeds.add("regress_entity_positions.nbt", gz(nbt(root)), {(0, 0, 0): "minecraft:stone"})
    return seeds


def main():
    seeds = build()
    OUT.mkdir(parents=True, exist_ok=True)
    total = 0
    for name, data in seeds.files.items():
        (OUT / name).write_bytes(data)
        total += len(data)
    lines = ["# 種のファイルを読んだときの、空気でないブロックの位置と名前 (性質と minecraft: は除く)。tools/structure/make_seeds.py が書く",
             "# [ファイル名] の後に x,y,z 名前。多いものは、間をあけて 64 個までにする。E で始まる行はエンティティの名前と位置"]
    for name, expected in seeds.expected.items():
        lines.append("[%s]" % name)
        items = sorted(expected.items())
        step = max(1, -(-len(items) // 64))
        for (x, y, z), block in items[::step]:
            lines.append("%d,%d,%d %s" % (x, y, z, block.replace("minecraft:", "")))
        # エンティティ: E 名前 x,y,z (構造物の角からの位置)
        for entity, x, y, z in seeds.entities.get(name, []):
            lines.append("E %s %r,%r,%r" % (entity, x, y, z))
    (OUT / "expected.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(len(seeds.files), "files,", total, "bytes ->", OUT)


if __name__ == "__main__":
    main()
