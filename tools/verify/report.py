"""実機での動作確認の結果 (matrix.py が保存したもの) から docs/verification.md を作る。

    python tools/verify/report.py
"""

import datetime
import json
import re
from pathlib import Path

import matrix
import verify

REPO = Path(__file__).resolve().parents[2]
LOADERS = ["fabric", "forge", "neoforge", "liteloader"]
LOADER_NAMES = {"fabric": "Fabric", "forge": "Forge", "neoforge": "NeoForge", "liteloader": "LiteLoader"}


def version_key(version):
    return tuple(int(part) for part in re.findall(r"\d+", version))


MEDIA_NAMES = {"image": "画像", "model": "3D モデル", "video": "動画", "splat": "ガウシアンスプラット", "pdf": "PDF",
               "structure": "構造物"}
# シェーダーパックの、表に書く名前
PACK_NAMES = {
    "bsl-shaders": "BSL",
    "complementary-reimagined": "Complementary Reimagined",
    "complementary-unbound": "Complementary Unbound",
    "sildurs-vibrant-shaders": "Sildur's Vibrant",
    "makeup-ultra-fast-shaders": "MakeUp Ultra Fast",
    "photon-shader": "Photon",
}


# 1 回目の失敗のうち、Mod の外 (ローダー) の不具合だと分かっているもの: (失敗の記録に含まれる文字, 表に書く原因)
LOADER_FAILURES = [
    # NeoForge が、バニラのサーバーにつなぐときの準備を 2 つのスレッドで同時に行い、通信の処理を二重に登録しようとして切断する
    ("Duplicate handler name: neoforge:vanilla_filter", "NeoForge の接続の不具合"),
]


# 自動の判定では合格したが、画面を目で確かめて見つけた問題: {結果の名前: 表に書く内容}
VISUAL_ISSUES = {
    # Iris + NeoForge 1.21.1 では、ガウシアンスプラットの粒が 1 つずつ影を付けて描かれ、なめらかに重ならない。
    # 公開済みの 4.1.0 でも同じ見た目になる (2026-10-26 に確認)
    "neoforge:1.21.1+shaders": "ガウシアンスプラットが粒に見える",
    "neoforge:1.21.1+shaders:sildurs-vibrant-shaders": "ガウシアンスプラットが粒に見える",
}


def cell(entry, digest, key=None):
    # 別のビルドで確認した結果は、いまのファイルの結果としては数えない
    if entry is None or entry.get("mod_sha256") != digest:
        return ""
    if entry.get("clean") and key in VISUAL_ISSUES:
        return f"要確認 ({VISUAL_ISSUES[key]})"
    if entry.get("baseline_failed"):
        # Mod を入れなくても起動しなかった。Mod の結果ではない
        return "対象外"
    if entry.get("clean"):
        notes = []
        missing = [name for kind, name in MEDIA_NAMES.items() if kind not in entry.get("media", [])]
        if missing:
            notes.append("・".join(missing) + "は未確認")
        else:
            # 看板アイテムとしての見た目も、種類ごとに確かめたか
            items = entry.get("item_media", [])
            no_item = [name for kind, name in MEDIA_NAMES.items() if matrix.MEDIA[kind] not in items]
            if no_item:
                notes.append("・".join(no_item) + "のアイテムは未確認")
        if entry.get("first_attempt"):
            # 1 回目は失敗して、やり直して合格した。原因が Mod の外にあると分かっているものは、その原因を書く
            cause = next((name for pattern, name in LOADER_FAILURES if pattern in entry["first_attempt"]), None)
            notes.append(f"1 回目は{cause}で失敗" if cause else "1 回目は失敗")
        return "OK" + (" (" + "、".join(notes) + ")" if notes else "")
    if entry.get("ok"):
        # 動作はしたが、基準にない警告が出たか、表示を確認できなかった
        return "要確認"
    return "失敗"


def main():
    summary_file = verify.DEFAULT_ROOT / "summary.json"
    summary = json.loads(summary_file.read_text(encoding="utf-8")) if summary_file.is_file() else {}

    supported = {}
    for loader, minecraft, _, mod in matrix.combinations(REPO / "dist" / "build" / "libs"):
        supported.setdefault(minecraft, set()).add(loader)
        supported.setdefault((minecraft, loader),
                             matrix.combination_digest(mod, loader, minecraft) if mod.is_file() else None)

    lines = [
        "# 動作確認の結果",
        "",
        "`tools/verify/matrix.py` による実機確認の結果です。このファイルは `tools/verify/report.py` が生成します。",
        "",
        "確認の内容: バニラのサーバーを起動し、各ローダーを入れた実際のクライアントで接続して、",
        "サバイバルモードのまま、ゲーム内のウィンドウでファイルを選び、看板を設置して表示されるまでを自動で行います。",
        "1 回の起動で、画像 (PNG)、3D モデル (GLB)、動画 (音声付きの MP4)、ガウシアンスプラット (.splat)、PDF、構造物 (.nbt) の 6 つを順に確認し (PDF はページ送りも確かめ)、",
        "最後に、6 つそれぞれの看板の文字をデータとして持たせた看板アイテムを渡して、手持ちの枠の中の見た目がその絵になることを確認します",
        "(PDF のアイテムは URL の末尾に `#page=2` を付けて、2 ページ目から表示されることも確かめます)。",
        "画面は自動の判定に加えて目でも確かめ、絵にすき間や縞模様がないこと (ガウシアンスプラットの重なり方) も調べています。",
        "",
        "- **OK**: 6 つとも正しい向きで、すき間なく表示され、看板そのものは隠れていて、6 つの看板アイテムもその絵の見た目になり、",
        "  Mod を入れていない状態と比べて新しい警告やエラーが出なかった。どのファイルも、看板が描かれようとしてから 15 秒以内に表示された",
        "- **OK (1 回目は失敗)**: 1 回目の確認で失敗し、やり直して合格した (たまにしか起きない不具合かもしれないので、1 回目の内容を結果に残している)",
        "- **OK (1 回目は NeoForge の接続の不具合で失敗)**: NeoForge が、バニラのサーバーにつなぐときの準備を 2 つのスレッドで同時に行って",
        "  切断し (「Duplicate handler name: neoforge:vanilla_filter」)、やり直して合格した。NeoForge 自体の処理どうしがぶつかって起きるもので、",
        "  この Mod は通信の処理に関わっていない",
        "- **要確認**: 動作はしたが、新しい警告が出たか、表示を確認できなかった",
        "- **失敗**: 動作しなかった",
        "- **対象外**: Mod を入れていない状態でもゲームが起動しないか、サーバーに入れなかった (ローダーや影 Mod 側の問題)",
        "- 空欄: そのローダーはこのバージョンに対応していない",
        "- **未確認**: 対応しているが、配布するファイルの現在の内容ではまだ確認していない",
        "",
        f"Mod のバージョン: {matrix.mod_version()} / 作成日: {datetime.date.today().isoformat()}",
        "",
        "| Minecraft | " + " | ".join(LOADER_NAMES[loader] for loader in LOADERS) + " |",
        "| --- | " + " | ".join("---" for _ in LOADERS) + " |",
    ]
    counts = {"OK": 0, "要確認": 0, "失敗": 0, "対象外": 0, "未確認": 0}
    for minecraft in sorted((key for key in supported if isinstance(key, str)), key=version_key, reverse=True):
        cells = []
        for loader in LOADERS:
            if loader not in supported[minecraft]:
                cells.append("")
                continue
            key = f"{loader}:{minecraft}"
            text = cell(summary.get(key), supported[(minecraft, loader)], key) or "未確認"
            counts[text.split(" ")[0]] += 1
            cells.append(text)
        lines.append(f"| {minecraft} | " + " | ".join(cells) + " |")
    lines += ["", "合計: " + "、".join(f"{name} {count}" for name, count in counts.items() if count), ""]

    # 影 Mod を入れた状態での確認
    shader_rows = []
    for minecraft in sorted((key for key in supported if isinstance(key, str)), key=version_key, reverse=True):
        cells = []
        for loader in LOADERS:
            # シェーダーパックごとの結果を、1 つのマスにまとめる
            prefix = f"{loader}:{minecraft}+shaders"
            parts = []
            for key in sorted(key for key in summary if key == prefix or key.startswith(prefix + ":")):
                entry = summary[key]
                text = cell(entry, supported.get((minecraft, loader)), key) if loader in supported[minecraft] else ""
                if not text:
                    continue
                pack = entry.get("shader_pack") or "bsl-shaders"
                parts.append(f"{entry.get('shader_mod') or '?'} + {PACK_NAMES.get(pack, pack)}: {text}")
            cells.append("<br>".join(parts))
        if any(cells):
            shader_rows.append(f"| {minecraft} | " + " | ".join(cells) + " |")
    if shader_rows:
        lines += [
            "## 影 Mod を入れた状態",
            "",
            "影 Mod (シェーダーを使えるようにする Mod) とシェーダーパックを入れて、同じ確認を行った結果です。",
            "マスの中は「影 Mod + シェーダーパック: 結果」です。",
            "Iris は Sodium と、Oculus は Rubidium または Embeddium と一緒に入れています。空欄は、確認していない組み合わせです。",
            "シェーダーは絵にも影や空の色を付けるので、色そのものではなく、絵が正しい場所に正しい向きで写っていることを確認しています。",
            "",
            "影 Mod とそれが必要とする Mod は、そのバージョン向けの版のうち、jar の中の指定 (Minecraft、Forge / NeoForge、"
            "必要とする Mod の版) に合うものを選んでいます。",
            "1.17 / 1.18 の Iris (1.1.x) は、描画の段階を表す定義 (MC_RENDER_STAGE_*) を持たず新しいシェーダーパックを組み立てられないので、"
            "それを使っていない BSL 8.1 で確認しています。",
            "",
            "次の組み合わせは、この Mod を入れなくても影 Mod が動かないため、確認の対象から外しています。",
            "",
            "- Forge 1.14.2 / 1.14.3: その版の OptiFine はどれも Forge に対応していない (配布元の情報で Forge が「N/A」)。"
            "Oculus は 1.16.5 から",
            "- Forge 1.21.1 + OptiFine 1.21.1 HD U J1: OptiFine の読み込み中にゲームが終了する",
            "- OptiFine 1.8.9 + MakeUp Ultra Fast: 画面が真っ黒になる",
            "",
            "| Minecraft | " + " | ".join(LOADER_NAMES[loader] for loader in LOADERS) + " |",
            "| --- | " + " | ".join("---" for _ in LOADERS) + " |",
        ] + shader_rows + [""]

    output = REPO / "docs" / "verification.md"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(lines), encoding="utf-8", newline="\n")
    print(f"Wrote {output}: {counts}")


if __name__ == "__main__":
    main()
