#!/usr/bin/env bash
# 配布する jar / litemod に、この Mod 自身のもの以外 (ライブラリのクラスなど) が入っていないことを確かめる。
# 許すもの: marumasa/ のクラス、marumasa_sign/ と assets/ の資源、META-INF/ (Fabric の中の jar を含む)、
# ローダーが読む説明のファイル、ライセンスの文
set -euo pipefail

dir="${1:?usage: check-jar-contents.sh <directory>}"
status=0
found=0
allowed='^(marumasa/|marumasa_sign/|assets/|META-INF/|data/|pack\.mcmeta$|mcmod\.info$|fabric\.mod\.json$|litemod\.json$|LICENSE_marumasa_sign$)'

check() {
  local label="$1" listing="$2"
  local bad
  bad="$(grep -Ev "$allowed" <<<"$listing" | grep -v '/$' || true)"
  if [ -n "$bad" ]; then
    echo "::error::$label に Mod のもの以外のファイルが入っています:"
    echo "$bad" | head -50
    status=1
  fi
}

for archive in "$dir"/*.jar "$dir"/*.litemod; do
  [ -e "$archive" ] || continue
  found=1
  check "$archive" "$(unzip -Z1 "$archive")"
  nested="$(mktemp -d)"
  unzip -q -o "$archive" 'META-INF/jars/*.jar' -d "$nested" 2>/dev/null || true
  for inner in "$nested"/META-INF/jars/*.jar; do
    [ -e "$inner" ] || continue
    check "$archive の中の $(basename "$inner")" "$(unzip -Z1 "$inner")"
  done
  rm -rf "$nested"
done

if [ "$found" -eq 0 ]; then
  echo "::error::$dir に jar も litemod もありません"
  exit 1
fi
exit "$status"
