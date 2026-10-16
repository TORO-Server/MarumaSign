"""CI で並べてビルドするために、targets.json の対象をいくつかの組に分ける。

対象ごとの Minecraft の準備 (Forge / NeoForge のインストールなど) に時間がかかり、1 つのジョブで順にやると 2 時間を超える。
準備にかかる時間の目安で重みを付け、どの組もなるべく同じ時間になるように分ける。

使い方:
    python tools/ci_groups.py 8        # GitHub Actions の matrix に渡す JSON を出力する
"""

import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]


def weight(target):
    """準備にかかる時間の目安 (分)。CI のログで測った値に合わせてある。"""
    parts = [int(p) for p in target["minecraft"].split(".")]
    major, minor = parts[0], parts[1]
    patch = parts[2] if len(parts) > 2 else 0
    if major >= 26:
        # 難読化されていないので、名前の付け替えがほとんどない
        return 0.3
    if minor <= 12:
        return 0.6
    if minor <= 16:
        return 1.6
    if minor <= 18:
        return 3.3
    # 1.19 以降は、新しいほど Forge / NeoForge の準備が重い
    return 3.5 + (minor - 19) * 0.6 + patch * 0.12


def main():
    count = int(sys.argv[1]) if len(sys.argv) > 1 else 8
    targets = json.loads((REPO / "targets.json").read_text(encoding="utf-8"))["targets"]
    groups = [{"names": [], "weight": 0.0} for _ in range(max(1, min(count, len(targets))))]
    # 重いものから順に、いちばん軽い組へ入れる
    for target in sorted(targets, key=weight, reverse=True):
        group = min(groups, key=lambda g: g["weight"])
        group["names"].append(target["name"])
        group["weight"] += weight(target)
    matrix = [{"index": i, "targets": ",".join(g["names"]), "estimate": round(g["weight"], 1)}
              for i, g in enumerate(groups)]
    print(json.dumps({"include": matrix}))


if __name__ == "__main__":
    main()
