#!/usr/bin/env bash
# 配布する jar / litemod に FFmpeg の実行ファイルが入っていないことを確かめる
set -euo pipefail

dir="${1:?usage: check-no-ffmpeg.sh <directory>}"
status=0
found=0

for archive in "$dir"/*.jar "$dir"/*.litemod; do
  [ -e "$archive" ] || continue
  found=1
  # クラスファイル (Ffmpeg.class) は実行ファイルではないので除く。
  # jar の中の jar (Fabric 用) も調べる
  if unzip -Z1 "$archive" | grep -Ei '(^|/)(ffmpeg|ffprobe)(\.exe)?$|\.(exe|dll|so|dylib)$'; then
    echo "::error::$archive に実行ファイルが入っています"
    status=1
  fi
  nested="$(mktemp -d)"
  unzip -q -o "$archive" 'META-INF/jars/*.jar' -d "$nested" 2>/dev/null || true
  for inner in "$nested"/META-INF/jars/*.jar; do
    [ -e "$inner" ] || continue
    if unzip -Z1 "$inner" | grep -Ei '(^|/)(ffmpeg|ffprobe)(\.exe)?$|\.(exe|dll|so|dylib)$'; then
      echo "::error::$archive の中の $(basename "$inner") に実行ファイルが入っています"
      status=1
    fi
  done
  rm -rf "$nested"
done

if [ "$found" -eq 0 ]; then
  echo "::error::$dir に jar も litemod もありません"
  exit 1
fi
exit "$status"
