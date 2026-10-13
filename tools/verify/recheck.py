"""保存済みのスクリーンショットを判定し直す。判定方法を変えたあとの確認に使う。

    python tools/verify/recheck.py [組み合わせ ...]     # 例: fabric-26.1 forge-1.12.2
"""

import sys

import verify


def main():
    runs = verify.DEFAULT_ROOT / "runs"
    names = sys.argv[1:] or sorted(path.name for path in runs.iterdir() if (path / "screenshot.png").is_file())
    for name in names:
        screenshot = runs / name / "screenshot.png"
        if not screenshot.is_file():
            print(f"{name:<28} (no screenshot)")
            continue
        visible, detail = verify.analyze_screenshot(screenshot)
        print(f"{name:<28} {'OK' if visible else 'NO':<3} {detail}")


if __name__ == "__main__":
    main()
