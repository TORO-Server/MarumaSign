"""targets.json にあるすべての組み合わせ (ローダー x Minecraft のバージョン) を順に実機確認する。

組み合わせごとに、まず Mod なしで起動して基準のログを取り (既にあれば省略)、
次に Mod を入れて動作確認を行う。結果は一覧にして表示し、JSON でも保存する。

使い方:
    python tools/verify/matrix.py                     # すべて
    python tools/verify/matrix.py --only forge:1.12.2 liteloader:1.7.10
    python tools/verify/matrix.py --skip-passed       # 前回合格したものは飛ばす
"""

import argparse
import json
import sys
from pathlib import Path
from types import SimpleNamespace

import verify

REPO = Path(__file__).resolve().parents[2]


def mod_version():
    for line in (REPO / "gradle.properties").read_text(encoding="utf-8").splitlines():
        if line.replace(" ", "").startswith("mod_version="):
            return line.split("=", 1)[1].strip()
    raise RuntimeError("mod_version was not found in gradle.properties")


def combinations(libs):
    """(ローダー, Minecraft のバージョン, ローダーのバージョン, Mod のファイル) を列挙する。"""
    table = json.loads((REPO / "targets.json").read_text(encoding="utf-8"))
    version = mod_version()
    universal = libs / f"MarumaSign-{version}.jar"
    for target in table["targets"]:
        for loader, spec in target["loaders"].items():
            if loader == "liteloader":
                yield loader, target["minecraft"], "", libs / f"MarumaSign-{version}-liteloader-mc{target['minecraft']}.litemod"
                continue
            for minecraft in spec["versions"]:
                # 対応表のバージョンがビルドに使ったものと違う場合は、ランチャーに選ばせる
                loader_version = spec["loader"] if minecraft == target["minecraft"] else spec.get("launch", {}).get(minecraft, "")
                yield loader, minecraft, loader_version, universal


def run_one(root, loader, minecraft, loader_version, mod, baseline, timeout):
    args = SimpleNamespace(
        minecraft=minecraft, loader=loader, loader_version=loader_version, spec="",
        mod=None if baseline else str(mod), baseline=baseline,
        image=str(REPO / "tools" / "verify" / "test-image.png"), lang="en_us",
        port=25570, timeout=timeout, root=str(root),
    )
    try:
        return verify.run(args)
    except Exception as error:  # 1 つの失敗で全体を止めない
        return {"ok": False, "clean": False, "detail": f"{type(error).__name__}: {error}"}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--only", nargs="*", default=[], help="loader:version の形式で対象を絞る")
    parser.add_argument("--skip-passed", action="store_true", help="前回合格した組み合わせを飛ばす")
    parser.add_argument("--rebaseline", action="store_true", help="基準のログを取り直す")
    parser.add_argument("--timeout", type=int, default=420)
    parser.add_argument("--root", default=str(verify.DEFAULT_ROOT))
    parser.add_argument("--libs", default=str(REPO / "dist" / "build" / "libs"),
                        help="確認する jar があるフォルダ。ビルドを続けながら確認するときは、コピーした先を指定する")
    args = parser.parse_args()

    root = Path(args.root)
    summary_file = root / "summary.json"
    summary = json.loads(summary_file.read_text(encoding="utf-8")) if summary_file.is_file() else {}

    for loader, minecraft, loader_version, mod in combinations(Path(args.libs)):
        key = f"{loader}:{minecraft}"
        if args.only and key not in args.only:
            continue
        if args.skip_passed and summary.get(key, {}).get("clean") and summary[key].get("mod") == mod.name:
            continue
        if not mod.is_file():
            summary[key] = {"ok": False, "clean": False, "detail": f"missing {mod.name}"}
            continue

        baseline_file = root / "baselines" / f"{loader}-{minecraft}.json"
        if args.rebaseline or not baseline_file.is_file():
            verify.log(f"=== baseline {key}")
            baseline = run_one(root, loader, minecraft, loader_version, mod, True, args.timeout)
            if not baseline.get("ok"):
                summary[key] = {"ok": False, "clean": False, "detail": "baseline failed: " + str(baseline.get("detail"))[:300]}
                summary_file.write_text(json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8")
                continue

        verify.log(f"=== verify {key}")
        result = run_one(root, loader, minecraft, loader_version, mod, False, args.timeout)
        summary[key] = {
            "ok": bool(result.get("ok")),
            "clean": bool(result.get("clean")),
            "detail": str(result.get("detail"))[:300] + " / " + str(result.get("image_detail", "no screenshot")),
            "mod": mod.name,
            "new_client_findings": result.get("new_client_findings", [])[:8],
            "new_server_findings": result.get("new_server_findings", [])[:8],
        }
        summary_file.parent.mkdir(parents=True, exist_ok=True)
        summary_file.write_text(json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8")

    print()
    print(f"{'combination':<24} {'works':<6} {'clean':<6} detail")
    failed = 0
    for key in sorted(summary):
        if args.only and key not in args.only:
            continue
        entry = summary[key]
        if not entry.get("clean"):
            failed += 1
        print(f"{key:<24} {'yes' if entry.get('ok') else 'NO':<6} {'yes' if entry.get('clean') else 'NO':<6} {entry.get('detail', '')}")
        for line in entry.get("new_client_findings", []) + entry.get("new_server_findings", []):
            print(f"    ! {line[:200]}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
