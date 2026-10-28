#!/usr/bin/env bash
# core の不具合を探す試験をまとめて動かす (CI の quality.yml から使う。手元でも動く)。
# Gradle を使わず javac と java だけで動かすので、Minecraft の準備は要らない。
#
# 使い方: bash .github/scripts/run-core-checks.sh <quick|long> [試験の名前...]
#   quick: PR ごとに動かす短いもの。long: 毎晩動かす、ファジングを長くしたもの
#   試験の名前を渡すと、その試験だけを動かす (compile は必ず行う)
# 乱数の種は FUZZ_SEED で決められる (決めなければ毎回変わる)。失敗したファジングの入力は core/build に残る
set -uo pipefail

mode="${1:-quick}"
shift || true
only=("$@")
root="$(cd "$(dirname "$0")/../.." && pwd)"
out="$root/build/core-checks"
seed="${FUZZ_SEED:-$(date +%s)}"
failed=()

if [ "$mode" = "long" ]; then
  pdf_fuzz=40000; image_fuzz=3000; structure_fuzz=20000; webp_fuzz=30000; decoder_fuzz_seconds=300; font_fuzz=100000; gltf_fuzz=200000; splat_fuzz=300000; svg_fuzz=30000; image_ffmpeg_fuzz=300
else
  pdf_fuzz=3000; image_fuzz=300; structure_fuzz=1000; webp_fuzz=3000; decoder_fuzz_seconds=20; font_fuzz=5000; gltf_fuzz=3000; splat_fuzz=20000; svg_fuzz=1000; image_ffmpeg_fuzz=30
fi

echo "mode=$mode seed=$seed"
rm -rf "$out"
mkdir -p "$out/main" "$out/test"

# ---------- コンパイル ----------
# 警告は全部表示する。core/pdf と core/image/jpeg (独自の PDF の実装) は、警告が 1 つでもあれば失敗にする
cd "$root/core" || exit 1
if ! javac --release 8 -encoding UTF-8 -Xlint:all,-options -Xmaxwarns 10000 -d "$out/main" \
    $(find src/main/java -name '*.java') 2>&1 | tee "$out/javac-main.txt"; then
  echo "::error::core does not compile"
  exit 1
fi
if grep -q ': error:' "$out/javac-main.txt"; then
  echo "::error::core does not compile"
  exit 1
fi
strict_warnings="$(grep -E '^src/main/java/marumasa/marumasa_sign/core/(pdf|image/jpeg)/.*: warning:' "$out/javac-main.txt" || true)"
if [ -n "$strict_warnings" ]; then
  echo "::error::Compiler warnings in the PDF engine:"
  echo "$strict_warnings"
  exit 1
fi
if ! javac --release 8 -encoding UTF-8 -nowarn -cp "$out/main" -d "$out/test" $(find src/test/java -name '*.java'); then
  echo "::error::core tests do not compile"
  exit 1
fi
classpath="$out/main:$out/test:src/main/resources:src/test/resources"

# ---------- 試験 ----------
# check 名前 秒数 java の引数...
check() {
  local name="$1" seconds="$2"
  shift 2
  if [ "${#only[@]}" -gt 0 ] && [[ ! " ${only[*]} " =~ " $name " ]]; then
    return
  fi
  echo "::group::$name"
  local start=$SECONDS
  # 同じ場所で何度も起きた例外でも、どこで起きたかが分かるように、スタックトレースを省かせない
  if timeout --kill-after=30 "$seconds" java -XX:-OmitStackTraceInFastThrow -Dfuzz.seed="$seed" -cp "$classpath" "$@"; then
    echo "$name: OK ($((SECONDS - start)) s)"
  else
    local status=$?
    if [ "$status" -eq 124 ] || [ "$status" -eq 137 ]; then
      echo "::error::$name did not finish in $seconds s (hang?)"
    else
      echo "::error::$name failed (exit $status)"
    fi
    failed+=("$name")
  fi
  echo "::endgroup::"
}

# PDF: メモリとスタックを小さくして、使い切りや深すぎる再帰を見つける
check pdfEngineCheck 900 -Xmx512m -Xss512k marumasa.marumasa_sign.core.pdf.PdfCheck
check pdfFuzz 3600 -Xmx768m -Xss512k marumasa.marumasa_sign.core.pdf.PdfFuzz "$pdf_fuzz" \
  src/test/resources/pdf "$root/tools/verify/test-pdf.pdf"
# PDF のフォント (TrueType、CFF、Type 1、CMap) の解析を、パソコンにあるフォントを種にして直接壊して試す
check fontFuzz 1800 -Xmx768m -Xss512k marumasa.marumasa_sign.core.pdf.font.FontFuzz "$font_fuzz" /usr/share/fonts
check signPdfCheck 600 -Xmx1g marumasa.marumasa_sign.core.SignPdfCheck "$root/tools/verify/test-pdf.pdf" "$out/sign-pdf"
# ネットワーク (看板の URL から、パソコンの中や家の中のネットワークに触れられないこと)
check securityCheck 900 -Xmx1g marumasa.marumasa_sign.core.net.SecurityCheck
check downloadCacheCheck 600 -Xmx512m marumasa.marumasa_sign.core.net.DownloadCacheCheck
# メモリと再生の数の上限
check signLimitsCheck 1200 -Xmx3g marumasa.marumasa_sign.core.SignLimitsCheck
# 画像、構造物、3D モデル、スプラットの読み込み (壊したファイルを含む)
# 看板の読み込みと後片付け (止めた読み込み、途中で切れたダウンロード、壊れた保存、動画のメモリの上限など)
check runtimeRegressionCheck 600 -Xmx2g marumasa.marumasa_sign.core.RuntimeRegressionCheck
check argbPixelsCheck 300 -Xmx1g marumasa.marumasa_sign.core.image.ArgbPixelsCheck "$root/tools/verify/test-image.png"
# JPEG は libjpeg (ImageIO) を使わずに読むので、libjpeg (Pillow) の結果と比べる
check jpegCheck 300 -Xmx512m -Xss512k marumasa.marumasa_sign.core.image.format.JpegCheck src/test/resources/jpeg
check imageRegressionCheck 300 -Xmx512m -Xss512k marumasa.marumasa_sign.core.image.ImageRegressionCheck
# 自分で読む形式のファジングは、FFmpeg を使わずに行う (読めないファイルを FFmpeg に回すと、1 つずつ時間がかかる)
check imageFormatsFuzz 3600 -Xmx1g -Xss512k -Dmarumasign.ffmpeg=none marumasa.marumasa_sign.core.image.ImageFormatsFuzz "$image_fuzz" \
  "$root/tools/verify/test-image.png" src/test/resources/jpeg/*.jpg src/test/resources/image
# FFmpeg に回す道 (自分で読めない形式) は、回数を減らして試す (FFmpeg がなければ、上と同じ道になる)
check imageFormatsFfmpegFuzz 3600 -Xmx1g -Xss512k marumasa.marumasa_sign.core.image.ImageFormatsFuzz "$image_ffmpeg_fuzz" \
  "$root/tools/verify/test-image.png" src/test/resources/image
# SVG: 深い入れ子、use の爆弾、極端な CSS や transform、外部の実体などを作って読ませる
check svgFuzz 3600 -Xmx512m -Xss512k marumasa.marumasa_sign.core.image.SvgFuzz "$svg_fuzz" src/test/resources/image
check structureRegressionCheck 600 -Xmx2g -Xss512k marumasa.marumasa_sign.core.structure.StructureRegressionCheck
check structureFuzz 3600 -Xmx2g -Xss512k marumasa.marumasa_sign.core.structure.StructureFuzz "$structure_fuzz" \
  src/test/resources/structure "$root/tools/verify/test-structure.nbt"
# 3D モデルにするところまで (ゲームの jar の代わりに、試験用に作る資源を使う。通信はしない)
check structureMeshFuzz 3600 -Xmx2g -Xss512k -Dfuzz.mesh=true marumasa.marumasa_sign.core.structure.StructureFuzz "$structure_fuzz" \
  src/test/resources/structure "$root/tools/verify/test-structure.nbt"
# 3D モデル (glTF / GLB とテクスチャ) とスプラットの、直した不具合の試験と、壊したファイルのファジング
check gltfRegressionCheck 600 -Xmx1g -Xss512k marumasa.marumasa_sign.core.gltf.GltfRegressionCheck
check splatRegressionCheck 600 -Xmx1g -Xss512k marumasa.marumasa_sign.core.splat.SplatRegressionCheck
check gltfFuzz 3600 -Xmx1g -Xss512k -Dfuzz.strict=true marumasa.marumasa_sign.core.gltf.GltfFuzz "$gltf_fuzz" \
  src/test/resources/gltf "$root/tools/verify/test-model.glb" src/test/resources/marumasa_sign/test/texture
check splatFuzz 3600 -Xmx1g -Xss512k -Dfuzz.strict=true marumasa.marumasa_sign.core.splat.SplatFuzz "$splat_fuzz" \
  src/test/resources/splat "$root/tools/verify/test-splat.splat"
check dracoCheck 900 -Xmx1g -Xss512k marumasa.marumasa_sign.core.gltf.DracoCheck
check splatCheck 900 -Xmx2g -Xss512k marumasa.marumasa_sign.core.splat.SplatCheck
# 仕様書から書き直したデコーダの試験 (libwebp などの結果との比較と、壊したデータ)
check webpCheck 1800 -Xmx2g -Xss512k marumasa.marumasa_sign.core.texture.WebpCheck "$webp_fuzz"
check meshoptCheck 1800 -Xmx1g -Xss512k marumasa.marumasa_sign.core.gltf.meshopt.MeshoptCheck "$decoder_fuzz_seconds"
check compressedTextureCheck 1800 -Xmx1g -Xss512k marumasa.marumasa_sign.core.texture.CompressedTextureCheck "$decoder_fuzz_seconds"

if [ "${#failed[@]}" -gt 0 ]; then
  echo "::error::Failed checks: ${failed[*]} (seed=$seed)"
  exit 1
fi
echo "All checks passed (seed=$seed)"
