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


MEDIA_NAMES = {"image": "画像", "model": "3D モデル", "video": "動画"}
# シェーダーパックの、表に書く名前
PACK_NAMES = {
    "bsl-shaders": "BSL",
    "complementary-reimagined": "Complementary Reimagined",
    "complementary-unbound": "Complementary Unbound",
    "sildurs-vibrant-shaders": "Sildur's Vibrant",
    "makeup-ultra-fast-shaders": "MakeUp Ultra Fast",
    "photon-shader": "Photon",
}


def cell(entry, digest):
    # 別のビルドで確認した結果は、いまのファイルの結果としては数えない
    if entry is None or entry.get("mod_sha256") != digest:
        return ""
    if entry.get("clean"):
        missing = [name for kind, name in MEDIA_NAMES.items() if kind not in entry.get("media", [])]
        return "OK" if not missing else "OK (" + "・".join(missing) + "は未確認)"
    if entry.get("ok"):
        # 動作はしたが、基準にない警告が出たか、表示を確認できなかった
        return "要確認"
    return "失敗"


def main():
    summary_file = verify.DEFAULT_ROOT / "summary.json"
    summary = json.loads(summary_file.read_text(encoding="utf-8")) if summary_file.is_file() else {}

    supported = {}
    digests = {}
    for loader, minecraft, _, mod in matrix.combinations(REPO / "dist" / "build" / "libs"):
        supported.setdefault(minecraft, set()).add(loader)
        if mod not in digests:
            digests[mod] = matrix.file_digest(mod) if mod.is_file() else None
        supported.setdefault((minecraft, loader), digests[mod])

    lines = [
        "# 動作確認の結果",
        "",
        "`tools/verify/matrix.py` による実機確認の結果です。このファイルは `tools/verify/report.py` が生成します。",
        "",
        "確認の内容: バニラのサーバーを起動し、各ローダーを入れた実際のクライアントで接続して、",
        "サバイバルモードのまま、ゲーム内のウィンドウでファイルを選び、看板を設置して表示されるまでを自動で行います。",
        "1 回の起動で、画像 (PNG)、3D モデル (GLB)、動画 (音声付きの MP4) の 3 つを順に確認し、",
        "最後に、看板の文字をデータとして持たせた看板アイテムを渡して、手持ちの枠の中の見た目がその画像になることを確認します。",
        "",
        "- **OK**: 3 つとも正しい向きで表示され、看板そのものは隠れていて、看板アイテムも画像の見た目になり、",
        "  Mod を入れていない状態と比べて新しい警告やエラーが出なかった",
        "- **要確認**: 動作はしたが、新しい警告が出たか、表示を確認できなかった",
        "- **失敗**: 動作しなかった",
        "- 空欄: そのローダーはこのバージョンに対応していない",
        "- **未確認**: 対応しているが、配布するファイルの現在の内容ではまだ確認していない",
        "",
        f"Mod のバージョン: {matrix.mod_version()} / 作成日: {datetime.date.today().isoformat()}",
        "",
        "| Minecraft | " + " | ".join(LOADER_NAMES[loader] for loader in LOADERS) + " |",
        "| --- | " + " | ".join("---" for _ in LOADERS) + " |",
    ]
    counts = {"OK": 0, "要確認": 0, "失敗": 0, "未確認": 0}
    for minecraft in sorted((key for key in supported if isinstance(key, str)), key=version_key, reverse=True):
        cells = []
        for loader in LOADERS:
            if loader not in supported[minecraft]:
                cells.append("")
                continue
            text = cell(summary.get(f"{loader}:{minecraft}"), supported[(minecraft, loader)]) or "未確認"
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
                text = cell(entry, supported.get((minecraft, loader))) if loader in supported[minecraft] else ""
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
            "| Minecraft | " + " | ".join(LOADER_NAMES[loader] for loader in LOADERS) + " |",
            "| --- | " + " | ".join("---" for _ in LOADERS) + " |",
        ] + shader_rows + [""]

    output = REPO / "docs" / "verification.md"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(lines), encoding="utf-8", newline="\n")
    print(f"Wrote {output}: {counts}")


if __name__ == "__main__":
    main()
