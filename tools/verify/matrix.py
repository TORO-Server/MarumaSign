"""targets.json にあるすべての組み合わせ (ローダー x Minecraft のバージョン) を順に実機確認する。

組み合わせごとに、まず Mod なしで起動して基準のログを取り (既にあれば省略)、
次に Mod を入れて動作確認を行う。結果は一覧にして表示し、JSON でも保存する。

使い方:
    python tools/verify/matrix.py                     # すべて
    python tools/verify/matrix.py --only forge:1.12.2 liteloader:1.7.10
    python tools/verify/matrix.py --skip-passed       # 前回合格したものは飛ばす
"""

import argparse
import hashlib
import io
import json
import shutil
import sys
import zipfile
from pathlib import Path
from types import SimpleNamespace

import shadermods

import verify

REPO = Path(__file__).resolve().parents[2]


def mod_version():
    for line in (REPO / "gradle.properties").read_text(encoding="utf-8").splitlines():
        if line.replace(" ", "").startswith("mod_version="):
            return line.split("=", 1)[1].strip()
    raise RuntimeError("mod_version was not found in gradle.properties")


def file_digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


ADAPTERS = "marumasa/marumasa_sign/"
NESTED = "META-INF/jars/marumasa_sign-fabric-"
_jar_entries = {}


def _entries(path):
    """jar の中身を {名前: 内容のハッシュ} にする。入れ子の jar は、その中身ごとに分ける。"""
    key = (str(path), Path(path).stat().st_mtime_ns)
    if key not in _jar_entries:
        entries = {}
        with zipfile.ZipFile(path) as archive:
            for name in archive.namelist():
                if name.endswith("/"):
                    continue
                data = archive.read(name)
                if name.startswith(NESTED):
                    # 入れ子の jar は、作り直すたびに中の日時が変わるので、中身で比べる
                    with zipfile.ZipFile(io.BytesIO(data)) as nested:
                        for inner in nested.namelist():
                            if not inner.endswith("/"):
                                entries[f"{name}!{inner}"] = hashlib.sha256(nested.read(inner)).hexdigest()
                else:
                    entries[name] = hashlib.sha256(data).hexdigest()
        _jar_entries.clear()
        _jar_entries[key] = entries
    return _jar_entries[key]


def target_of(loader, minecraft):
    """その組み合わせで使われる実装。(ビルド対象の名前, jar の中での置き場所のローダー名)"""
    table = json.loads((REPO / "targets.json").read_text(encoding="utf-8"))
    for target in table["targets"]:
        spec = target["loaders"].get(loader, {})
        if minecraft in spec.get("versions", []) or (loader == "liteloader" and spec and target["minecraft"] == minecraft):
            return target["name"], loader
        # Forge 用の実装で動く、Forge 互換の NeoForge
        if loader == "neoforge" and minecraft in target["loaders"].get("forge", {}).get("neoforge_launch", {}):
            return target["name"], "forge"
    raise KeyError(f"{loader}:{minecraft}")


def combination_digest(mod, loader, minecraft):
    """確認した内容を表すハッシュ。その組み合わせで使われる部分だけから求める。

    1 つの jar にすべてのバージョン向けの実装が入っているので、jar 全体で比べると、
    あるバージョン向けの実装だけを直したときにも、他のバージョンの確認結果まで無効になってしまう。
    """
    mod = Path(mod)
    if mod.suffix != ".jar":
        return file_digest(mod)
    target, directory = target_of(loader, minecraft)
    own_classes = f"{ADAPTERS}{directory}/{target}/"
    own_nested = f"{NESTED}{target}.jar!"
    digest = hashlib.sha256()
    for name, value in sorted(_entries(mod).items()):
        if name.startswith(NESTED):
            if loader != "fabric" or not name.startswith(own_nested):
                continue
        elif name.startswith((ADAPTERS + "forge/", ADAPTERS + "neoforge/")):
            if not name.startswith(own_classes):
                continue
        digest.update(f"{name}={value}\n".encode("utf-8"))
    return digest.hexdigest()


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
                # 起動に使うローダーのバージョン。バージョンごとの表があればそこから引く
                launch = spec.get("launch")
                loader_version = launch[minecraft] if isinstance(launch, dict) else spec["loader"]
                yield loader, minecraft, loader_version, universal
            # Forge 用の実装で動く、Forge 互換の NeoForge
            for minecraft, loader_version in spec.get("neoforge_launch", {}).items():
                yield "neoforge", minecraft, loader_version, universal


# 看板に表示して確かめるファイル。どれも同じテスト画像が見えるように作ってある
MEDIA = {
    "image": "test-image.png",
    "model": "test-model.glb",
    "splat": "test-splat.splat",
    "video": "test-video.mp4",
}


def keep_failed_attempt(work_dir):
    """やり直す前に、失敗した回の画面とログを「作業フォルダ名-failed」に写しておく (やり直すと作業フォルダは消える)。"""
    if not work_dir or not Path(work_dir).is_dir():
        return
    target = Path(work_dir + "-failed")
    shutil.rmtree(target, ignore_errors=True)
    target.mkdir(parents=True)
    source = Path(work_dir)
    for pattern in ("*.png", "*.log", "logs/*.log", "selftest/*"):
        for path in source.glob(pattern):
            if path.is_file():
                destination = target / path.relative_to(source)
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(path, destination)


def run_one(root, loader, minecraft, loader_version, mod, baseline, timeout, media=("image",), shaders=False,
            shader_pack=None, shader_mod=None):
    args = SimpleNamespace(
        minecraft=minecraft, loader=loader, loader_version=loader_version, spec="",
        mod=None if baseline else str(mod), baseline=baseline, shaders=shaders, shader_pack=shader_pack,
        shader_mod=shader_mod,
        image="|".join(str(REPO / "tools" / "verify" / MEDIA[kind]) for kind in media), lang="en_us",
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
    parser.add_argument("--media", nargs="+", choices=sorted(MEDIA), default=list(MEDIA),
                        help="看板に表示して確かめるものの種類。1 回の起動で順に確かめる")
    parser.add_argument("--shaders", action="store_true",
                        help="影 Mod とシェーダーパックを入れた状態で確かめる。結果は「loader:version+shaders」として記録する")
    parser.add_argument("--shader-pack", default=shadermods.SHADER_PACK,
                        help="--shaders で使うシェーダーパック (Modrinth での名前)。"
                             "既定以外を指定すると「loader:version+shaders:名前」として記録する")
    parser.add_argument("--shader-mod", choices=["optifine"], default=None,
                        help="--shaders で使う影 Mod を、ローダーごとの既定のものから変える (Forge 1.16.5 以降で OptiFine を使う)")
    parser.add_argument("--timeout", type=int, default=660)
    parser.add_argument("--root", default=str(verify.DEFAULT_ROOT))
    parser.add_argument("--libs", default=str(REPO / "dist" / "build" / "libs"),
                        help="確認する jar があるフォルダ。ビルドを続けながら確認するときは、コピーした先を指定する")
    args = parser.parse_args()

    root = Path(args.root)
    variant = shadermods.variant(args.shader_pack, args.shader_mod)
    suffix = "" if not args.shaders else "+shaders" + (":" + variant if variant else "")
    summary_file = root / "summary.json"
    summary = json.loads(summary_file.read_text(encoding="utf-8")) if summary_file.is_file() else {}

    selected = set()
    for loader, minecraft, loader_version, mod in combinations(Path(args.libs)):
        key = f"{loader}:{minecraft}"
        if args.only and key not in args.only:
            continue
        key += suffix
        selected.add(key)
        if not mod.is_file():
            summary[key] = {"ok": False, "clean": False, "detail": f"missing {mod.name}"}
            continue
        # 確認済みかどうかは、ファイルの内容で判断する (ビルドし直すと名前は同じでも中身が変わる)
        digest = combination_digest(mod, loader, minecraft)
        previous = summary.get(key, {})
        if (args.skip_passed and previous.get("clean") and previous.get("mod_sha256") == digest
                and set(args.media) <= set(previous.get("media", []))
                and {MEDIA[kind] for kind in args.media} <= set(previous.get("item_media", []))):
            continue

        baseline_file = root / "baselines" / (f"{loader}-{minecraft}" + suffix.replace("+", "-").replace(":", "-") + ".json")
        if args.rebaseline or not baseline_file.is_file():
            verify.log(f"=== baseline {key}")
            baseline = run_one(root, loader, minecraft, loader_version, mod, True, args.timeout, shaders=args.shaders,
                               shader_pack=args.shader_pack, shader_mod=args.shader_mod)
            if "No shader mod is available" in str(baseline.get("detail")):
                # この組み合わせで使える影 Mod がない。確認の対象から外す
                verify.log(f"=== skip {key}: no shader mod")
                summary.pop(key, None)
                selected.discard(key)
                continue
            if not baseline.get("ok"):
                # 初回の起動では、ローダーの準備などでクライアントがそのまま終了することがあるので、1 回だけやり直す
                verify.log(f"=== baseline again {key} ({str(baseline.get('detail'))[:120]})")
                baseline = run_one(root, loader, minecraft, loader_version, mod, True, args.timeout, shaders=args.shaders,
                                   shader_pack=args.shader_pack, shader_mod=args.shader_mod)
            if not baseline.get("ok"):
                # Mod を入れなくても起動しない組み合わせ。Mod の結果ではないので、報告書では対象外として扱う
                summary[key] = {"ok": False, "clean": False, "baseline_failed": True,
                                "detail": "baseline failed: " + str(baseline.get("detail"))[:300],
                                "mod": mod.name, "mod_sha256": digest,
                                "shader_mod": baseline.get("shader_mod"), "shader_pack": baseline.get("shader_pack")}
                summary_file.write_text(json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8")
                continue

        verify.log(f"=== verify {key}")
        result = run_one(root, loader, minecraft, loader_version, mod, False, args.timeout, args.media, args.shaders,
                             args.shader_pack, args.shader_mod)
        if not result.get("clean"):
            # 他の処理と重なって時間切れになることがあるので、1 回だけやり直す
            verify.log(f"=== retry {key} ({str(result.get('detail'))[:200]} / {result.get('image_detail')}"
                       f" / {result.get('sign_detail')} / {result.get('item_detail')}"
                       f" / {(result.get('new_client_findings', []) + result.get('new_server_findings', []))[:3]})")
            keep_failed_attempt(result.get("work_dir"))
            result = run_one(root, loader, minecraft, loader_version, mod, False, args.timeout, args.media, args.shaders,
                             args.shader_pack, args.shader_mod)
        summary[key] = {
            "ok": bool(result.get("ok")),
            "clean": bool(result.get("clean")),
            "detail": " / ".join([str(result.get("detail"))[:300], str(result.get("image_detail", "no screenshot")),
                                  str(result.get("sign_detail", "sign view not captured")),
                                  str(result.get("item_detail", "item view not captured"))]
                                 + ([f"shaders ({result.get('shader_mod')}): "
                                     + ("active" if result.get("shaders_active") else "NOT active")] if args.shaders else [])),
            "mod": mod.name,
            "mod_sha256": digest,
            "media": list(args.media),
            # 看板アイテムとして確かめたファイル
            "item_media": result.get("item_media", []) if result.get("item_visible") else [],
            "shader_mod": result.get("shader_mod"),
            "shader_pack": result.get("shader_pack"),
            "new_client_findings": result.get("new_client_findings", [])[:8],
            "new_server_findings": result.get("new_server_findings", [])[:8],
        }
        summary_file.parent.mkdir(parents=True, exist_ok=True)
        summary_file.write_text(json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8")

    print()
    print(f"{'combination':<24} {'works':<6} {'clean':<6} detail")
    failed = 0
    for key in sorted(summary):
        if key not in selected:
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
