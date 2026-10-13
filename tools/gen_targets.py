"""targets.json を生成する。

対応するバージョンが多く、手で書くと間違えやすいため、表からまとめて作る。
バージョンを追加・変更するときは、このファイルの表を直して実行する。

    python tools/gen_targets.py
"""

import json
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]


def legacy_sources(era, forge="legacy/forge"):
    """Minecraft 1.7.10 - 1.12.2 の世代で使うソースフォルダ。"""
    return {"main": ["legacy/main", f"{era}/main"], "forge": [forge], "liteloader": ["legacy/liteloader"]}


def modern_sources(*layers):
    """Minecraft 1.14 以降の世代で使うソースフォルダ。共通のものから順に重ねる。入口はどのバージョンでも共通。"""
    return {"main": [f"{layer}/main" for layer in layers], "fabric": ["common/fabric"], "forge": ["common/forge"],
            "neoforge": ["common/neoforge"]}


ERAS = {
    "v1_7": {"sources": legacy_sources("v1_7", "v1_7/forge")},
    "v1_8_0": {"sources": legacy_sources("v1_8_0")},
    "v1_8": {"sources": legacy_sources("v1_8")},
    "v1_9": {"sources": legacy_sources("v1_9")},
    "v1_10": {"sources": legacy_sources("v1_10")},
    "v1_11": {"sources": legacy_sources("v1_11")},
    "v1_12": {"sources": legacy_sources("v1_12")},
    "v1_15": {"sources": modern_sources("modern", "classic", "ber16", "tex_1", "v1_15")},
    "v1_16": {"sources": modern_sources("modern", "classic", "ber16", "tex_1", "v1_16")},
    "v1_16_2": {"sources": modern_sources("modern", "classic", "ber16", "tex_1", "v1_16_2")},
    "v1_17": {"sources": modern_sources("modern", "classic", "classic_ber", "tex_1", "v1_17")},
    "v1_19": {"sources": modern_sources("modern", "classic", "classic_ber", "tex_1", "v1_19")},
    "v1_19_3": {"sources": modern_sources("modern", "classic", "classic_ber", "tex_1", "v1_19_3")},
    "v1_20": {"sources": modern_sources("modern", "classic", "classic_ber", "tex_1", "v1_20")},
    "v1_21": {"sources": modern_sources("modern", "classic", "classic_ber", "tex_2", "v1_21")},
    "v1_21_2": {"sources": modern_sources("modern", "classic", "classic_ber", "tex_3", "v1_21_2")},
    "v1_21_5": {"sources": modern_sources("modern", "classic", "classic_ber", "tex_4", "v1_21_5")},
    "v1_21_6": {"sources": modern_sources("modern", "classic", "classic_ber", "tex_4", "v1_21_6")},
    "v1_21_9": {"sources": modern_sources("modern", "renderstate", "tex_4", "v1_21_9")},
    "v1_21_11": {"sources": modern_sources("modern", "renderstate", "tex_5", "v1_21_9")},
    "v26_1": {"sources": modern_sources("modern", "renderstate", "tex_5", "v26", "v26_1")},
    "v26_2": {"sources": modern_sources("modern", "renderstate", "tex_5", "v26", "v26_2")},
    "v26_3": {"sources": modern_sources("modern", "renderstate", "tex_5", "v26", "v26_3")},
}

# Minecraft 1.14 以降。1 つのビルドが、API の同じ複数のバージョンを受け持つ。
# (ビルドに使う Minecraft, 世代, マッピング, {受け持つバージョン: (Forge, NeoForge)})
# Forge / NeoForge が None のバージョンは、そのローダーが存在しないか、まだ対応していない。
FABRIC_LOADER = "0.19.5"
MODERN = [
    ("1.20.1", "v1_20", "mojmap", {
        "1.20": ("46.0.14", None),
        "1.20.1": ("47.4.26", None),
    }),
    ("1.20.4", "v1_20", "mojmap", {
        "1.20.2": ("48.1.0", "20.2.93"),
        "1.20.3": ("49.0.2", "20.3.8-beta"),
        "1.20.4": ("49.2.11", "20.4.251"),
    }),
    ("1.20.6", "v1_20", "mojmap", {
        "1.20.5": (None, "20.5.21-beta"),
        "1.20.6": ("50.2.12", "20.6.141"),
    }),
    ("1.21.1", "v1_21", "mojmap", {
        "1.21": ("51.0.33", "21.0.167"),
        "1.21.1": ("52.1.18", "21.1.259"),
    }),
    ("1.21.4", "v1_21_2", "mojmap", {
        "1.21.2": (None, "21.2.1-beta"),
        "1.21.3": ("53.1.14", "21.3.97"),
        "1.21.4": ("54.1.20", "21.4.158"),
    }),
    ("1.21.5", "v1_21_5", "mojmap", {
        "1.21.5": ("55.1.16", "21.5.98"),
    }),
    ("1.21.8", "v1_21_6", "mojmap", {
        "1.21.6": ("56.0.9", "21.6.20-beta"),
        "1.21.7": ("57.0.3", "21.7.25-beta"),
        "1.21.8": ("58.1.24", "21.8.54"),
    }),
    ("1.21.11", "v1_21_11", "mojmap", {
        "1.21.11": ("61.2.3", "21.11.45"),
    }),
    ("26.1", "v26_1", "none", {"26.1": ("62.0.9", "26.1.0.19-beta")}),
    ("26.1.1", "v26_1", "none", {"26.1.1": ("63.0.2", "26.1.1.15-beta")}),
    ("26.1.2", "v26_1", "none", {"26.1.2": ("64.1.4", "26.1.2.116")}),
    ("26.2", "v26_2", "none", {"26.2": ("65.1.4", "26.2.0.90")}),
    ("26.3", "v26_3", "none", {"26.3": ("66.0.11", "26.3.0.76")}),
]

# (Minecraft, 世代, MCP のマッピング, Forge, LiteLoader)
LEGACY = [
    ("1.7.10", "v1_7", "12-1.7.10", "10.13.4.1614-1.7.10", "1.7.10_04"),
    ("1.8", "v1_8_0", "18-1.8", "11.14.4.1577", "1.8"),
    ("1.8.8", "v1_8", "20-1.8.8", "11.15.0.1655", None),
    ("1.8.9", "v1_8", "22-1.8.9", "11.15.1.2318-1.8.9", "1.8.9-SNAPSHOT"),
    ("1.9", "v1_9", "24-1.9", "12.16.1.1938-1.9.0", "1.9-SNAPSHOT"),
    ("1.9.4", "v1_9", "26-1.9.4", "12.17.0.2317-1.9.4", "1.9.4-SNAPSHOT"),
    ("1.10", "v1_10", "29-1.10.2", "12.18.0.2000-1.10.0", "1.10-SNAPSHOT"),
    ("1.10.2", "v1_10", "29-1.10.2", "12.18.3.2511", "1.10.2-SNAPSHOT"),
    ("1.11", "v1_11", "32-1.11", "13.19.1.2199", "1.11-SNAPSHOT"),
    ("1.11.2", "v1_11", "32-1.11", "13.20.1.2588", "1.11.2-SNAPSHOT"),
    ("1.12", "v1_12", "39-1.12", "14.21.1.2443", "1.12-SNAPSHOT"),
    ("1.12.1", "v1_12", "39-1.12", "14.22.1.2485", "1.12.1-SNAPSHOT"),
    ("1.12.2", "v1_12", "39-1.12", "14.23.5.2860", "1.12.2-SNAPSHOT"),
]


def target_name(minecraft):
    return "mc" + minecraft.replace(".", "_")


def build():
    targets = []
    for minecraft, era, mcp, forge, liteloader in LEGACY:
        loaders = {}
        if forge:
            loaders["forge"] = {"loader": forge, "versions": [minecraft]}
        if liteloader:
            loaders["liteloader"] = {"loader": liteloader}
        targets.append({
            "name": target_name(minecraft), "era": era, "minecraft": minecraft,
            "mappings": f"mcp:stable:{mcp}", "loaders": loaders,
        })
    for minecraft, era, mappings, served in MODERN:
        versions = list(served)
        loaders = {"fabric": {"loader": FABRIC_LOADER, "versions": versions}}
        forge = {version: pair[0] for version, pair in served.items() if pair[0]}
        if forge:
            loaders["forge"] = {"loader": forge[minecraft], "versions": list(forge), "launch": forge}
        neoforge = {version: pair[1] for version, pair in served.items() if pair[1]}
        if neoforge:
            # ビルドツールにはビルド番号だけを、ランチャーには完全なバージョンを渡す
            loaders["neoforge"] = {"loader": neoforge[minecraft].rsplit(".", 1)[1], "versions": list(neoforge),
                                   "launch": neoforge}
        targets.append({
            "name": target_name(minecraft), "era": era, "minecraft": minecraft,
            "mappings": mappings, "loaders": loaders,
        })
    return {"eras": ERAS, "targets": targets}


def main():
    table = build()
    # 1 つの項目を 1 行にして、差分を読みやすくする
    lines = ["{", '  "eras": {']
    eras = list(table["eras"].items())
    for index, (name, era) in enumerate(eras):
        comma = "," if index < len(eras) - 1 else ""
        lines.append(f"    {json.dumps(name)}: {json.dumps(era)}{comma}")
    lines += ["  },", '  "targets": [']
    for index, target in enumerate(table["targets"]):
        comma = "," if index < len(table["targets"]) - 1 else ""
        lines.append(f"    {json.dumps(target)}{comma}")
    lines += ["  ]", "}"]
    (REPO / "targets.json").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"Wrote targets.json ({len(table['targets'])} targets)")


if __name__ == "__main__":
    main()
