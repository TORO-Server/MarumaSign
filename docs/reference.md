# MarumaSign リファレンス

使い方の概要は [README](../README.md) にあります。この文書は、対応形式の細かい仕様と制限、安全のための決まり、開発者向けの手順をまとめた、ただ 1 つの詳しい文書です。
文章の書き方は [TORO の文章の書き方](https://github.com/TORO-Server/TORO-Website/blob/main/docs/WRITING-STYLE.md) に従います。

数値の上限は、コードの定数が正本です。この文書の数値は、コードと突き合わせて確かめたものだけを書いています (主な定数の場所: `core/src/main/java/marumasa/marumasa_sign/core/` の `image/ImageLoader.java` `net/Http.java` `net/DownloadCache.java` `sign/SignManager.java` `gltf/GltfLoader.java` `splat/SplatLoader.java` `structure/StructureLoader.java` `structure/NestedSigns.java` `pdf/PdfFile.java`)。

## 対応するバージョンとローダー

対応する Minecraft のバージョンとローダーの組み合わせの正本は、置き場直下の [`targets.json`](../targets.json) です (`tools/gen_targets.py` から作ります)。
どのローダーでも、前提にする他の Mod は宣言していません (`dist/build.gradle` が作る Fabric の定義に `depends` はありません)。

実機での動作確認 (`tools/verify`) の結果は、日付つきの記録になるためこの置き場には置きません。`python tools/verify/report.py` で手元に `docs/verification.md` として作れます (`.gitignore` で除外しています)。

## 対応している形式

ファイルの種類は、拡張子ではなく内容で判別します (内容に目印のない TGA・WBMP・XBM だけは、拡張子も使います)。

### 画像

次の形式は、MarumaSign だけで読めます (FFmpeg は要りません)。

- 一般的な形式: `.png` (アニメーション PNG を含む) `.gif` (アニメーション) `.jpg` / `.jpeg` `.bmp` `.webp` (アニメーションを含む) `.tif` / `.tiff` `.svg` / `.svgz`
- アイコン: `.ico` `.cur` `.ani` (アニメーション) `.icns`
- 画像編集・ゲームの形式: `.psd` / `.psb` (統合した画像) `.tga` `.qoi` `.dds` `.ktx2`
- 古い形式・研究用の形式: `.pcx` `.pbm` `.pgm` `.ppm` `.pnm` `.pam` `.pfm` `.sgi` / `.rgb` `.ras` `.xbm` `.xpm` `.wbmp` `.ff` (farbfeld) `.hdr` (Radiance) `.fits`

次の形式は、FFmpeg があれば読めます: `.avif` `.heic` / `.heif` `.jxl` (JPEG XL) `.jp2` (JPEG 2000) `.exr` `.dpx` `.iff` (ILBM) `.xwd` `.pgx`。
MarumaSign が自分で読めないファイルも、FFmpeg があればやり直します。

画像の一辺は 4096 ピクセルまでです (アニメーションは 2048 ピクセルまで)。

### 動画・音声

[FFmpeg](https://ffmpeg.org/) が読める形式 (`.mp4` `.webm` `.mkv` `.mov` `.avi` `.mp3` `.ogg` `.wav` `.flac` など) を再生できます。
動画に含まれる音声も再生され、看板から離れるほど小さく聞こえます。音量はウィンドウの「動画と音声の音量」で変えられます (設定は `config/marumasa_sign.json` に保存されます)。
同時に再生する動画・音声は 8 つまでで、近いものから再生します。

再生には FFmpeg が必要です。次のどちらかの方法で用意してください。

- FFmpeg をインストールして、`ffmpeg` と `ffprobe` をコマンドとして実行できる状態 (PATH が通った状態) にする
- ゲームフォルダ (`.minecraft` など) の中に `marumasign/ffmpeg` フォルダを作り、`ffmpeg` と `ffprobe` の実行ファイルを置く

FFmpeg が見つからない場合は、動画や音声の看板を見たときにチャットで知らせます。

### ライブ配信

HLS (`.m3u8`)、MPEG-DASH (`.mpd`)、HTTP-FLV、MJPEG の URL も、動画と同じように表示できます。URL が長くて看板に入りきらない場合は、自動で短縮します。
配信が途切れた場合は、少し待ってから自動でつなぎ直します。

### 3D モデル

glTF 2.0 の `.glb` と `.gltf` を表示できます。
`.gltf` が別のファイル (`.bin` や画像) を参照している場合、アップロードのときに自動で 1 つの `.glb` にまとめます。
まとめるのは、`.gltf` と同じフォルダ (とその中のフォルダ) にあるファイルだけです。フォルダの外を指すものは読みません。

モデルは、最も長い辺が画像の長い辺と同じ大きさになるように置かれ、正面 (+Z) が看板の正面を向きます。壁掛けの看板では、モデルの背面が壁に付きます。

対応している内容:

- ノードの階層、メッシュ、疎なアクセサ、整数に量子化された頂点
- 圧縮されたメッシュ: `KHR_draco_mesh_compression` `EXT_meshopt_compression` / `KHR_meshopt_compression` `KHR_mesh_quantization` `EXT_mesh_gpu_instancing`
- アニメーション (移動・回転・拡大・モーフィングの重み)。最初のアニメーションを繰り返し再生します。`KHR_animation_pointer` による材質と光源のアニメーションにも対応します
- スキニングとモーフターゲット
- 材質の拡張: `KHR_materials_unlit` `KHR_materials_emissive_strength` `KHR_materials_pbrSpecularGlossiness` `KHR_materials_clearcoat` `KHR_materials_sheen` `KHR_materials_specular` `KHR_materials_ior` `KHR_materials_transmission` `KHR_materials_volume` `KHR_materials_iridescence` `KHR_materials_anisotropy` `KHR_materials_diffuse_transmission` `KHR_materials_dispersion` `KHR_materials_variants`
- 光源: `KHR_lights_punctual` `EXT_lights_image_based`
- テクスチャ: PNG / JPEG、`KHR_texture_basisu` (KTX2)、`EXT_texture_webp`、`MSFT_texture_dds`、`EXT_texture_avif` (FFmpeg が必要)、`KHR_texture_transform`
- `KHR_xmp_json_ld` (付随する情報は読み飛ばします)

どのバージョンの Minecraft でも同じ見た目になるように、材質の計算は Mod の中で行っています。照明はモデルに固定です。

制限:

- 三角形は 15 万枚まで、ファイルは外部ファイルを含めて 64 MB まで
- 上に挙げていない拡張が必須 (`extensionsRequired`) のモデルは読み込めません

### ガウシアンスプラッティング

次の形式を表示できます。ファイルは 200 MB まで、表示する粒の数は 15 万個までです (多いときは目立つものから選びます)。

- `.ply` (標準の形式と、PlayCanvas / SuperSplat の圧縮された形式)
- `.spz`
- `.sog` (PlayCanvas の SOG)。zip にしていないもの (`meta.json` と WebP のファイルを並べたフォルダ) も、`meta.json` の URL かフォルダの URL で表示できます
- `.splat`
- `.ksplat`

`.splat` と `.ksplat` は内容に目印がないため、URL (短縮された URL ならリダイレクト先) かファイル名の拡張子で判別します。

置き方は 3D モデルと同じです。座標の向きは次のように決めます。

- `.spz` は、`SPZ_ADOBE_coordinate_system` で座標が書かれていれば、それに従います
- 書かれていない `.spz` は、床や机のような水平な面の位置から上を推定します。分からなければ +Y が上とします
- そのほかの形式は、撮影したカメラの座標 (+Y が下) として扱います
- URL の末尾に `#coord=RDF` のように座標を付けると、どの形式でもそれに従います (1 文字目が +X、2 文字目が +Y、3 文字目が +Z の向き。R 右・L 左・U 上・D 下・F 奥・B 手前)。推定が外れて逆さまに表示されるときに使います

同じ文字列の看板が複数あると、並べ替えのやり直しが続くため、CPU の負荷が上がります。

### PDF

`.pdf` を表示できます。最初は 1 ページ目を表示します。ファイルは 64 MB までです。

URL の末尾に `#page=ページ番号` を付けると、そのページから表示します (例: `https://example.com/manual.pdf#page=3`)。
`#zoom=50&page=3` のように、ほかの指定と並べて書いてもかまいません。ページの数より大きい番号は最後のページ、正しくない番号は 1 ページ目として扱います。
`#` から後ろはサーバーに送りません。URL を短縮するときは、`#` から後ろを除いた部分を短縮して、後ろに付け直します。

看板の絵に照準 (画面中央の十字) を合わせている間だけ、絵の下側にページ送りの帯が表示されます。帯の左右のボタンに照準を合わせて使用キー (右クリック) を押すと、前のページ・次のページに切り替わります。
ページの切り替えは自分の画面だけで、ほかのプレイヤーには伝わりません。

埋め込まれていないフォントは、パソコンにあるフォントで代わりに表示します。フォントの一覧は `marumasign/pdf-fonts.cache` に保存します。
PDF の読み込みと描画は、ライブラリを使わない独自の実装です (`core/src/main/java/marumasa/marumasa_sign/core/pdf`)。

### 構造物 (建築のファイル)

ブロックを並べた建築のファイルを、ブロックでできた 3D モデルとして表示できます。

- `.schem` (Sponge Schematic)
- `.litematic` (Litematica)
- `.nbt` (ストラクチャーブロック)
- `.mcstructure` (統合版のストラクチャーブロック)
- `.schematic` (MCEdit、古い WorldEdit、Schematica)

ブロックの形とテクスチャは、`GameAssets.VERSION` で決まるバージョンの Minecraft のものを使います。初めて構造物を表示するときに、Mojang のサーバーからそのバージョンのクライアントの jar を
ゲームフォルダの `marumasign/assets` にダウンロードします (ランチャーの `versions` フォルダに同じものがあれば、それを使います)。看板の文字に jar に入っていない文字があるときは、同じように Unifont を読みます。
構造物の中にある MarumaSign の形式の看板は、画像・PDF の 1 ページ目・3D モデル・構造物を描きます。動画・音声・ガウシアンスプラッティングは描きません。

制限:

- ファイルは 64 MB まで (圧縮をほどいたあとは 96 MB まで)
- ブロックの数が 1,677 万 (16 x 1024 x 1024) より多い構造物は、何ブロックかおきに間引いて表示します
- 描く面が 15 万枚より多くなる構造物は、2 x 2 x 2 ブロックを 1 つにまとめることを、15 万枚以下になるまで繰り返して表示します。エンティティと、中の看板が表示するものはまとめられないので、それらが多いときは 15 万枚を超えることがあります。その場合も 60 万枚までです
- 構造物の中の看板が読み込むのは、1 つの構造物 (中の構造物も含めて) で URL 16 個、合計 64 MB、20 秒までです。構造物の中の構造物は 2 段までで、同じ URL をたどり直すことはしません

## 看板のフォーマット

`[URL]|[X軸位置]|[Y軸位置]|[Z軸位置]|[横幅]|[高さ]|[Z軸回転]|[Y軸回転]|[X軸回転]`

看板の 4 行 (Minecraft 1.20 以降は表と裏の合計 8 行) をつなげた文字列が、この形式になっていると表示されます。数値は -1000 から 1000 までで、範囲を超えたものは端の値として扱います。
`NaN` や `Infinity` が書かれた看板は、MarumaSign の看板として扱いません。判定の正本は `core/src/main/java/marumasa/marumasa_sign/core/sign/SignSpec.java` です。

## 看板のアイテム

看板の文字をデータとして持っている看板のアイテムは、アイテムの見た目も、その看板と同じ内容になります。アイテムとしての見た目では、位置・大きさ・回転は使わず、アイテムの大きさに収まるように表示します。

## 影 Mod (シェーダー) との併用

OptiFine、Iris、Oculus などの影 Mod とシェーダーパックを使っていても表示されます。絵や 3D モデルはブロックと同じようにシェーダーの光と影を受けるため、色の見え方はシェーダーパックによって変わります。
確認した組み合わせは `tools/verify/shadermods.py` が正本です。

## 注意事項

- 看板に入りきらない長さの URL は、自動で短縮されます。Minecraft 1.7.10 は看板 1 行が 15 文字までのため、特に短縮されやすくなります。
- 画像は 10 MB まで、3D モデルと構造物は 64 MB まで、アップロードできる動画・音声とガウシアンスプラッティングは 200 MB までです。
- 一度読み込んだファイル (動画・音声を除く) は、ゲームフォルダの `marumasign/cache` に合計 512 MB まで保存します。変わったかを問い合わせる情報 (ETag / Last-Modified) を返さないサーバーのファイルは保存しません。合計が上限を超えると、最後に使ったのが古いものから消します。フォルダごと消してもかまいません。
- 画面とチャットのメッセージは、115 言語分の翻訳ファイル (`core/src/main/resources/marumasa_sign/lang`) があります。英語と日本語以外は、母語の話者による確認が済んでいるか分からないため、未確認として扱ってください。

## 悪用を防ぐしくみ

看板は誰でも書けるので、看板を見ただけで困ることが起きないようにしています。

- 自分のパソコン (`127.0.0.1`、`localhost` など) や家の中のネットワーク、クラウドの管理用のアドレスを指す URL は読みません。リダイレクトの行き先も、つなぐ前に確かめます。ダウンロードも動画・音声の再生 (FFmpeg) の通信も、MarumaSign の中のプロキシを通し、確かめたアドレスにそのままつなぎます。判定の正本は `core/.../net/UrlPolicy.java` です
- `http` と `https` 以外の URL (`file:` など) は読みません。ブラウザーがつながない番号のポートの URL も読みません
- 画像として FFmpeg に渡すものが再生リスト (HLS・DASH など) だった場合は渡しません (パソコンの中のファイルを看板に映させないように)
- ログに書く URL などの文字は、`${` や改行を無害にしてから書きます (Log4j の脆弱性 Log4Shell への対策)
- JPEG の画像と PDF の中の色の定義 (ICC プロファイル) は、Java の中のネイティブのライブラリに渡さず、Java で書いた処理で読みます
- 1 バイトずつゆっくり送り続けるようなサーバーは、速さが 16 KB/秒に届かなければ読むのをやめます (`net/Http.java`)
- 看板の中身が使うメモリは合計 1 GB ほどまでにします。いま見えていないものから消し、5 分見えなかったものも消します (`sign/SignManager.java`)

確認のために手元のサーバーから読む必要があるときは、Java の起動オプションに `-Dmarumasign.allowLocalUrls=true` を付けます。

## 開発者向け

### 構成

| フォルダ | 内容 |
| --- | --- |
| `core` | Minecraft に依存しない共通の処理 (看板のフォーマット、画像の取得、ゲーム内のウィンドウなど) |
| `adapters` | Minecraft のバージョンごとの実装。共通部分と、世代ごとの差分に分かれています |
| `bootstrap` | 各ローダーから呼ばれ、実行中のバージョンに合った実装を選ぶ入口 |
| `dist` | 配布するファイルの組み立て |
| `tools` | 対応表の生成と、実機での動作確認を自動で行うスクリプト |

LiteLoader だけ配布ファイルが分かれているのは、LiteLoader が Mod ごとに対応する Minecraft のバージョンを 1 つしか指定できないためです (`dist/build.gradle` の `litemod` の作業)。

対応するバージョンの一覧は `targets.json` にあり、`tools/gen_targets.py` から生成します。
Minecraft 1.14 以降向けの実装のうち、バージョンによって細部が違うファイルは `tools/gen_adapters.py` から生成します (生成したファイルも置いてあるので、ビルドには必要ありません)。

### ビルド

```bash
./gradlew :dist:assemble
```

配布するファイルは `dist/build/libs` に出力されます。初回は対応するすべてのバージョンの Minecraft を取得するため、時間がかかります。
GitHub Actions では対象を組に分けて並べてビルドします ([`.github/workflows/build.yml`](../.github/workflows/build.yml))。同じことを手元で行うには、次のようにします。

```bash
# 対象を選んで、対象ごとの jar を build/prebuilt に集める (組の分け方は python tools/ci_groups.py 8 で見られる)
./gradlew -PmarumaOnly=mc1_7_10,mc1_20_1 exportRemapped
# 集めた jar から、Minecraft の準備をせずに配布するファイルを組み立てる
./gradlew -PmarumaPrebuilt=$PWD/build/prebuilt :dist:assemble
```

### 実機での動作確認

```bash
python tools/verify/matrix.py
```

バニラのサーバーを起動し、各ローダーを入れた実際のクライアントで接続して、画像・3D モデル・ガウシアンスプラッティング・動画・PDF・構造物を看板に書き込んで表示されるまでと、看板アイテムの見た目を自動で確認します。
[portablemc](https://github.com/mindstorm38/portablemc)、Pillow、FFmpeg が必要です。

次のどれかに当てはまると、合格にしません (正本は `tools/verify/verify.py`)。

- Mod を入れていない状態と比べて、新しい警告やエラーがログに出た (`tools/verify/known-noise.txt` の行は除く)
- Mod がファイルの読み込みや再生に失敗した
- 看板が描かれようとしてから表示されるまでに 15 秒より長くかかった (`-Dmarumasign.selftest.loadLimit=ミリ秒` で変えられる)
- 看板アイテムが描かれなかった

```bash
# 3 つのクライアントを同時に動かす。前回合格したものは飛ばす
python tools/verify/matrix.py --jobs 3 --skip-passed
# 影 Mod とシェーダーパックを入れた状態で確認する
python tools/verify/matrix.py --shaders
# シェーダーパックを変える (Modrinth での名前)。Forge 1.16.5 以降で OptiFine を使うときは --shader-mod optifine
python tools/verify/matrix.py --shaders --shader-pack complementary-reimagined
# 結果を docs/verification.md にまとめる (Git には入れません)
python tools/verify/report.py
```

### Minecraft を起動しない検査

検査の一覧の正本は、`core/build.gradle` の `tasks.register` と `.github/scripts/run-core-checks.sh` です。主なものを挙げます。

```bash
# 配布する jar が、対応しているすべてのバージョンの Minecraft と正しくつながるかを確かめる
python tools/check_linkage.py
# GitHub Actions の「Quality」と同じ検査を、Gradle を使わずに javac と java だけで動かす (quick は PR ごと、long は毎晩。乱数の種は FUZZ_SEED で決められる)
bash .github/scripts/run-core-checks.sh quick
# ライブラリ (第三者のコード) を使っていないことを確かめる
python3 .github/scripts/check-no-libraries.py
# PDF の読み込みと描画の試験
./gradlew :core:pdfEngineCheck
# 壊した PDF を読ませて、想定外の例外や長い時間が出ないかを確かめる (回数, ファイルまたはフォルダ...)
./gradlew :core:pdfFuzz -PpdfFuzzArgs="300"
# 3D モデルを PNG に描く (出力先, アニメーションの時刻, モデルのファイル...)
./gradlew :core:gltfCheck -PgltfArgs="build/gltf 0.5 model.glb"
# ガウシアンスプラッティングを PNG に描く (出力先, ファイル...)
./gradlew :core:splatPreview -PsplatPreviewArgs="build/splat scene.spz"
# 構造物のファイルを PNG に描く (出力先, ファイルまたはフォルダ...)。-PassetJar でテクスチャに使う Minecraft の jar を指定できる
./gradlew :core:structureCheck -PstructureArgs="build/structure ../tools/verify/test-structure.nbt"
# 各形式の読み込みを、壊したファイルで確かめる (回数, ファイルまたはフォルダ...)
./gradlew :core:formatFuzz -PimageFuzzArgs="100 samples/pil"
# 看板の URL で、手元や家の中のネットワークにアクセスさせられないことを確かめる
./gradlew :core:securityCheck
# たくさんの看板を並べても、メモリと同時に再生する数が上限を超えないことを確かめる
./gradlew :core:signLimitsCheck
# 読み込みにかかる時間を、ファイルごとに測る (回数, ファイル...)
./gradlew :core:loadBenchmark -PloadBenchArgs="5 image.png model.glb scene.spz"
```

### 構造物の表示に使う表の作り方

構造物のファイルの表示には、次の表を使います。どれも `core/src/main/resources/marumasa_sign/structure` に gzip で圧縮して置いてあり、ビルドには作り直す必要はありません。
表示に使う Minecraft のバージョン (`GameAssets.VERSION`) を変えるときに作り直します。

| ファイル | 内容 | 作るもの (`tools/blockdata`) |
| --- | --- | --- |
| `blocks.txt.gz` | ブロックの性質 (性質の名前と値、既定の状態、面の隠れ方、衝突の形、光、液体、植物の位置のずれ) | `BlockDataDump.java` |
| `entity_models.txt.gz` | ゲームのコードで形が決まっているもの (チェスト、旗、頭、ベルなど) の形 | `EntityModelDump.java` |
| `legacy.txt.gz` | 1.12 までのブロックの番号から今のブロックへの対応 | `gen_tables.py` |
| `bedrock.txt.gz` | 統合版のブロックから Java バージョンのブロックへの対応 | `gen_tables.py` |

`BlockDataDump` と `EntityModelDump` は、Minecraft のクライアントのコードを動かして表を書き出します。Java 25 以降と、そのバージョンのクライアントの jar とライブラリが必要です。
次の例では、実機確認 (`tools/verify`) がダウンロードしたものを使います (`<バージョン>` は `GameAssets.VERSION` の値)。

```powershell
# クラスパス (クライアントの jar とライブラリ)
$main = "$HOME\.marumasign-verify\main"
$json = Get-Content "$main\versions\<バージョン>\<バージョン>.json" | ConvertFrom-Json
$libs = $json.libraries | Where-Object { $_.downloads.artifact -and -not $_.rules } | ForEach-Object { "$main\libraries\" + $_.downloads.artifact.path }
$cp = ($libs + "$main\versions\<バージョン>\<バージョン>.jar") -join ";"
# コンパイルして実行する (作業用のフォルダで実行する。Minecraft がログのフォルダなどを作るため)
javac -nowarn -cp $cp -d build\blockdata tools\blockdata\BlockDataDump.java tools\blockdata\EntityModelDump.java
Push-Location build\blockdata
java -cp "$cp;." BlockDataDump ..\..\core\src\main\resources\marumasa_sign\structure\blocks.txt.gz
java -cp "$cp;." EntityModelDump ..\..\core\src\main\resources\marumasa_sign\structure\entity_models.txt.gz
Pop-Location
```

`legacy.txt.gz` と `bedrock.txt.gz` は、[minecraft-data](https://github.com/PrismarineJS/minecraft-data) から対応表を取得して書き出します。

```bash
python tools/blockdata/gen_tables.py
```

確かめ方: テスト用のクラス `StructureShowcase` (`core/src/test`) が、すべてのブロックと特別な形のブロックを並べた確認用の構造物を作ります。作った構造物は `structureCheck` で PNG に描けます。
`structureCheck` は、モデルが見つからなかったブロックと、見つからなかったテクスチャを最後に表示します。

### PDF の表示に使う表の作り方

PDF の表示に使う表 (`core/src/main/resources/marumasa_sign/pdf`) は、`tools/pdfdata` の道具で作ります。

- `encodings.txt` `glyphlist.txt.gz` `afm.txt.gz` `cmaps.bin.gz`: `python3 tools/pdfdata/gen_pdf_data.py <元のフォルダ>` (元のフォルダに置くものは道具の先頭に書いてあります)
- `cmyk.lut`: `java tools/pdfdata/CmykLut.java <ISO Coated v2 300% (ECI) の ICC プロファイル> <出力先>`

### 取り込んでいるデータ

次のデータを `core` に取り込んでいます。それぞれの使用条件の全文は、jar と `core/src/main/resources/marumasa_sign/licenses` のファイルが正本です。

| データ | 使っているところ | 条件を書いたファイル |
| --- | --- | --- |
| [minecraft-data](https://github.com/PrismarineJS/minecraft-data) | 構造物の、古いブロックの番号と統合版のブロックの対応表 (`structure/legacy.txt.gz`, `structure/bedrock.txt.gz`) | `minecraft-data.txt` |
| [Adobe Glyph List](https://github.com/adobe-type-tools/agl-aglfn) | PDF のグリフの名前と Unicode の対応 (`pdf/glyphlist.txt.gz`) | `adobe-glyph-list.txt` |
| [Adobe CMap resources](https://github.com/adobe-type-tools/cmap-resources) | PDF の日本語・中国語・韓国語の定義済みの文字の対応表 (`pdf/cmaps.bin.gz`) | `adobe-cmap-resources.txt` |
| Adobe Core 14 AFM | PDF の標準 14 フォントの寸法 (`pdf/afm.txt.gz`) | `adobe-core14-afm.txt` |
| ISO Coated v2 300% (ECI) の ICC プロファイル | CMYK の色を sRGB にする表 (`pdf/cmyk.lut`。プロファイルから格子の点の色を求めたもの。プロファイルそのものは入れていません) | なし |

配布する jar には、ライブラリ (第三者のコード) を同梱していません (`check-no-libraries.py` と `check-jar-contents.sh` で検査します)。
PDF の読み込みと描画、ログの安全な書き出し、WebP・Draco・meshopt・BC6H / BC7・KTX2 の展開は、それぞれの仕様書をもとにした独自の実装です。
