"""構造物のファイルの読み込みに使う、ブロックの対応表を作る (開発用)。

minecraft-data (https://github.com/PrismarineJS/minecraft-data, MIT) から次の表を取得して、
core/src/main/resources/marumasa_sign/structure に gzip で圧縮して書き出す。

- legacy.txt.gz  : Java 版 1.12 までの番号 (ID:データ値) から、1.13 以降のブロックの状態への対応と、1.12 のブロックの名前と番号
- bedrock.txt.gz : 統合版のブロックの状態から Java 版のブロックの状態への対応 (統合版 1.17.0 以降のすべての版の表を合わせたもの)

使い方: python tools/blockdata/gen_tables.py
"""

import gzip
import json
import pathlib
import urllib.request

BASE = "https://raw.githubusercontent.com/PrismarineJS/minecraft-data/master/data/"
OUT = pathlib.Path(__file__).resolve().parents[2] / "core/src/main/resources/marumasa_sign/structure"


def fetch(path):
    with urllib.request.urlopen(BASE + path) as response:
        return json.loads(response.read().decode("utf-8"))


def normalize(state):
    """"名前[性質=値,...]" を、性質を名前の順に並べた形にする。"""
    if "[" not in state:
        return state
    name, rest = state.split("[", 1)
    body = rest.rstrip("]")
    pairs = sorted(p for p in body.split(",") if p)
    return name + ("[" + ",".join(pairs) + "]" if pairs else "")


def write(name, lines):
    OUT.mkdir(parents=True, exist_ok=True)
    data = ("\n".join(lines) + "\n").encode("utf-8")
    # 作り直しても同じ内容になるように、時刻を入れない
    with open(OUT / name, "wb") as raw:
        with gzip.GzipFile(fileobj=raw, mode="wb", mtime=0) as out:
            out.write(data)
    print(name, len(lines), "lines")


def legacy():
    blocks = fetch("pc/common/legacy.json")["blocks"]
    names = fetch("pc/1.12/blocks.json")
    lines = ["# id:data state (minecraft-data pc/common/legacy.json)"]
    for key in sorted(blocks, key=lambda k: tuple(int(x) for x in k.split(":"))):
        lines.append(key + " " + normalize(blocks[key]))
    lines.append("# N name id (minecraft-data pc/1.12/blocks.json)")
    for block in names:
        lines.append("N minecraft:" + block["name"] + " " + str(block["id"]))
    write("legacy.txt.gz", lines)


def version_key(version):
    return tuple(int(x) for x in version.split("."))


def bedrock():
    paths = fetch("dataPaths.json")["bedrock"]
    sources = sorted({info["blocksB2J"] for info in paths.values() if info.get("blocksB2J")},
                     key=lambda p: version_key(p.split("/")[1]))
    table = {}
    # 古い版から順に重ね、同じ状態は新しい版の対応で上書きする
    for source in sources:
        mapping = fetch(source + "/blocksB2J.json")
        for key, value in mapping.items():
            table[normalize(key)] = normalize(value)
        print(source, len(mapping))
    lines = ["# bedrock state -> java state (minecraft-data " + ", ".join(sources) + ")"]
    for key in sorted(table):
        lines.append(key + " " + table[key])
    write("bedrock.txt.gz", lines)


if __name__ == "__main__":
    legacy()
    bedrock()
