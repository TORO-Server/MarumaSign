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


ERAS = {
    "v1_7": {"sources": legacy_sources("v1_7", "v1_7/forge")},
    "v1_8_0": {"sources": legacy_sources("v1_8_0")},
    "v1_8": {"sources": legacy_sources("v1_8")},
    "v1_9": {"sources": legacy_sources("v1_9")},
    "v1_10": {"sources": legacy_sources("v1_10")},
    "v1_11": {"sources": legacy_sources("v1_11")},
    "v1_12": {"sources": legacy_sources("v1_12")},
}

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
