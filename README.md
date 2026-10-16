# MarumaSign

看板に画像・動画・音声・3D モデル・ガウシアンスプラッティングを表示する Minecraft の Mod です。

https://www.curseforge.com/minecraft/mc-mods/marumasign

解説動画:
<https://www.youtube.com/watch?v=N-Q-Vtz7m-0>

サーバーに、このModを導入する必要はありません。すべてクライアント側で動作します。
バニラのサーバーでも、サバイバルモードでも使えます。

## ダウンロードするファイル

使っているローダーに合わせて、次のどちらかを `mods` フォルダに入れてください。

| ローダー | ファイル | 備考 |
| --- | --- | --- |
| Fabric / Forge / NeoForge | `MarumaSign-<バージョン>.jar` | Minecraft のどのバージョンでも同じファイルです |
| LiteLoader | `MarumaSign-<バージョン>-liteloader-mc<Minecraft のバージョン>.litemod` | Minecraft のバージョンに合ったものを選んでください |

LiteLoader だけファイルが分かれているのは、LiteLoader が Mod ごとに対応する Minecraft のバージョンを 1 つしか指定できないためです。

## 前提環境

各ローダー本体だけで動作します。Fabric API などの前提 Mod は必要ありません。

対応している Minecraft のバージョンとローダーの組み合わせ、および実機での動作確認の結果は
[docs/verification.md](docs/verification.md) を参照してください。

## 使用方法

1. ゲーム中、B キー を押します。( "キーの割り当て" の「その他」にある "MarumaSign" で変更できます)
2. 開いたウィンドウで、ファイルを選ぶか、URL を入力します。
   - 「参照...」ボタンでファイルを選べます。
   - Minecraft 1.14 以降では、ウィンドウにファイルをドロップすることもできます。
3. 大きさ・位置・回転を設定して、「看板に書き込む」ボタンを押します。
4. 看板を設置します。設置した看板に、表示するための文字が自動で書き込まれます。

ファイルを選んだ場合、ファイルは [Catbox](https://catbox.moe/) にアップロードされ、誰でもアクセスできる URL になります。
公開したくないファイルは選ばないでください。

### 対応している形式

ファイルの種類は、拡張子ではなく内容で判別します。

#### 画像

- `.png` (アニメーション PNG を含む)
- `.gif`
- `.jpg` / `.jpeg`
- `.bmp`

#### 動画・音声

[FFmpeg](https://ffmpeg.org/) が読める形式 (`.mp4` `.webm` `.mkv` `.mov` `.avi` `.mp3` `.ogg` `.wav` `.flac` など) を再生できます。
動画に含まれる音声も再生され、看板から離れるほど小さく聞こえます。音量はウィンドウの「動画と音声の音量」で変えられます。
WebP や AVIF、TIFF など、上の「画像」にない形式の画像も、FFmpeg を通して表示します。

再生には FFmpeg が必要です。次のどちらかの方法で用意してください。

- FFmpeg をインストールして、`ffmpeg` と `ffprobe` をコマンドとして実行できる状態 (PATH が通った状態) にする
- ゲームフォルダ (`.minecraft` など) の中に `marumasign/ffmpeg` フォルダを作り、`ffmpeg` と `ffprobe` の実行ファイルを置く

FFmpeg が見つからない場合は、動画や音声の看板を見たときにチャットで知らせます。

#### ライブ配信

次の形式のライブ配信の URL も、動画と同じように表示できます。URL が長くて看板に入りきらない場合は、自動で短縮します。

- HLS (`.m3u8`)
- MPEG-DASH (`.mpd`)
- HTTP-FLV
- MJPEG (ネットワークカメラなどの `multipart/x-mixed-replace` の配信)

配信が途切れた場合は、少し待ってから自動でつなぎ直します。

#### 3D モデル

glTF 2.0 の `.glb` と `.gltf` を表示できます。
`.gltf` が別のファイル (`.bin` や画像) を参照している場合、アップロードのときに自動で 1 つの `.glb` にまとめます。

モデルは、最も長い辺が画像の長い辺と同じ大きさになるように置かれ、正面 (+Z) が看板の正面を向きます。
壁掛けの看板では、モデルの背面が壁に付きます。

対応している内容:

- ノードの階層、メッシュ (三角形、帯、扇)、疎なアクセサ、整数に量子化された頂点
- 圧縮されたメッシュ: Draco (`KHR_draco_mesh_compression`)、meshopt (`EXT_meshopt_compression` / `KHR_meshopt_compression`)、`KHR_mesh_quantization`、インスタンス (`EXT_mesh_gpu_instancing`)
- アニメーション (移動・回転・拡大・モーフィングの重み、補間は LINEAR / STEP / CUBICSPLINE)。最初のアニメーションを繰り返し再生します
- `KHR_animation_pointer` による、材質 (色・発光・金属らしさ・粗さ・テクスチャの貼り方) と光源 (色・強さ) のアニメーション
- スキニング (1 頂点につきボーン 8 本まで) とモーフターゲット
- 材質: 色とテクスチャ、頂点の色、金属らしさと粗さ、法線マップ、遮蔽 (オクルージョン)、発光、透明度の扱い (OPAQUE / MASK / BLEND)、両面表示
- 材質の拡張: `KHR_materials_unlit` `KHR_materials_emissive_strength` `KHR_materials_pbrSpecularGlossiness` `KHR_materials_clearcoat` `KHR_materials_sheen` `KHR_materials_specular` `KHR_materials_ior` `KHR_materials_transmission` `KHR_materials_volume` `KHR_materials_iridescence` `KHR_materials_anisotropy` `KHR_materials_diffuse_transmission` `KHR_materials_dispersion` `KHR_materials_variants` (既定の材質を使う)
- 光源: `KHR_lights_punctual` (平行光・点光源・スポットライト)、`EXT_lights_image_based` (環境光として)
- テクスチャ: PNG / JPEG、KTX2 (`KHR_texture_basisu`、ETC1S / UASTC)、WebP (`EXT_texture_webp`)、DDS (`MSFT_texture_dds`、BC1 - BC7)、AVIF (`EXT_texture_avif`、FFmpeg が必要)。複数のテクスチャ座標、繰り返しの指定 (繰り返し・折り返し・端で止める)、`KHR_texture_transform`
- そのほか: `KHR_xmp_json_ld` (メタデータは読み飛ばします)

陰影について:

どのバージョンの Minecraft でも同じ見た目になるように、材質の計算 (シェーダーに相当する処理) は Mod の中で行っています。
照明はモデルに固定で、上面が最も明るく、側面、底面の順に暗くなります。
動かないモデルで、法線マップや金属らしさ・粗さ・発光のテクスチャを使っている材質は、読み込みのときにピクセル単位で計算した結果をテクスチャに焼き込みます。
アニメーションするモデルと、テクスチャの同じ場所を向きの違う多くの面で使い回しているモデルは、頂点単位の計算になり、これらのテクスチャの細かい模様は反映されません。

制限:

- 三角形は 15 万枚まで、ファイルは外部ファイルを含めて 64 MB まで
- 透過 (transmission) は屈折を計算せず、半透明として表示します。分散 (dispersion) は見た目に反映しません
- カメラの定義は使いません
- 上に挙げていない拡張が必須 (`extensionsRequired`) のモデルは読み込めません

#### ガウシアンスプラッティング

ガウシアンスプラッティング (3DGS) の次の形式を表示できます。

- `.ply` (3DGS の標準の形式と、PlayCanvas / SuperSplat の圧縮された形式)
- `.spz` (バージョン 2 - 4)
- `.sog` (PlayCanvas の SOG。バージョン 1 と 2)
- `.splat`
- `.ksplat` (圧縮レベル 0 - 2)

`.splat` と `.ksplat` は内容に目印がないため、URL (短縮された URL ならリダイレクト先) かファイル名の拡張子で判別します。

置き方は 3D モデルと同じで、最も長い辺が画像の長い辺と同じ大きさになり、正面 (+Z) が看板の正面を向きます。
`.spz` は仕様に従って +Y が上の座標として、そのほかの形式は撮影したカメラの座標 (+Y が下) として扱います。

見え方について:

- 1 つ 1 つの分布を、カメラの方を向いた楕円の板として描きます。カメラから遠い順に重ね、見る向きによる色の変化 (球面調和関数、3 次まで) も反映します
- 並べ替えは別のスレッドで行い、カメラが少し動くたびにやり直します
- 撮影したシーンに含まれる遠くの背景 (空のドームなど) で被写体が隠れないように、中心から遠い分布を省きます。
  中心は各軸の中央値で、中心からの距離の中央値の 4 倍より遠いものを省きます
- 分布が 15 万個より多いファイルは、目立つもの (不透明度と大きさで判断) から 15 万個を選んで表示します

制限:

- ファイルは 200 MB まで、表示する分布は 15 万個まで
- Minecraft の描画では不透明度が 0.1 未満の部分が描かれないため、分布の薄い縁は省かれ、実際のビューアより輪郭がはっきりします
- 同じ文字列の看板が複数あると、並べ替えのやり直しが続くため、CPU の負荷が上がります

### 看板のフォーマット

`[URL]|[X軸位置]|[Y軸位置]|[Z軸位置]|[横幅]|[高さ]|[Z軸回転]|[Y軸回転]|[X軸回転]`

看板の 4 行 (Minecraft 1.20 以降は表と裏の合計 8 行) をつなげた文字列が、この形式になっていると表示されます。
動画・音声・3D モデル・ガウシアンスプラッティングでも同じ形式です。

### 看板のアイテム

看板の文字をデータとして持っている看板のアイテム (クリエイティブモードで Ctrl を押しながらブロックを選択したときや、
`/give` でデータ付きの看板を渡されたときなど) は、アイテムの見た目も、その看板と同じ画像・動画・3D モデル・ガウシアンスプラッティングになります。
インベントリの枠、手に持ったとき、地面に落ちているとき、額縁に入れたときのどれでも同じです。
アイテムとしての見た目では、看板に書かれた位置・大きさ・回転は使わず、アイテムの大きさに収まるように表示します。

### 影 Mod (シェーダー) との併用

OptiFine、Iris、Oculus などの影 Mod とシェーダーパックを使っていても表示されます。
絵や 3D モデルは、ブロックと同じようにシェーダーの光と影を受けます。
そのため、色の見え方はシェーダーパックによって変わります (日なたでは明るく、日かげや夜は暗くなります)。
確認した影 Mod とシェーダーパックの組み合わせは [docs/verification.md](docs/verification.md) にあります。

## 注意事項

- 看板に入りきらない長さの URL は、自動で短縮されます。
- Minecraft 1.7.10 のサーバーは看板 1 行につき 15 文字までしか受け付けないため、特に URL が短縮されやすくなります。
- スペクテイターモードでは、画像と一緒に看板そのものも表示されます。
- 画像は 10 MB まで、3D モデルは 64 MB まで、アップロードできる動画・音声とガウシアンスプラッティングは 200 MB までです。
- 画面とチャットのメッセージは、Minecraft で選べるすべての言語 (115 言語) に翻訳してあります。英語と日本語以外は、ネイティブの話者による確認をしていません。

## 開発者向け

### 構成

| フォルダ | 内容 |
| --- | --- |
| `core` | Minecraft に依存しない共通の処理 (看板のフォーマット、画像の取得、ゲーム内のウィンドウなど) |
| `adapters` | Minecraft のバージョンごとの実装。共通部分と、世代ごとの差分に分かれています |
| `bootstrap` | 各ローダーから呼ばれ、実行中のバージョンに合った実装を選ぶ入口 |
| `dist` | 配布するファイルの組み立て |
| `tools` | 対応表の生成と、実機での動作確認を自動で行うスクリプト |

対応するバージョンの一覧は `targets.json` にあり、`tools/gen_targets.py` から生成します。
Minecraft 1.14 以降向けの実装のうち、バージョンによって細部が違うファイルは `tools/gen_adapters.py` から生成します
(生成したファイルもリポジトリに入っているので、ビルドには必要ありません)。

### ビルド

```bash
./gradlew :dist:assemble
```

配布するファイルは `dist/build/libs` に出力されます。
初回は対応するすべてのバージョンの Minecraft を取得するため、時間がかかります。

時間のほとんどは、バージョンごとの Minecraft (と Forge / NeoForge) の準備です。CI では対象を組に分けて並べてビルドします
([`.github/workflows/build.yml`](.github/workflows/build.yml))。同じことを手元で行うには、次のようにします。

```bash
# 対象を選んで、対象ごとの jar を build/prebuilt に集める (組の分け方は python tools/ci_groups.py 8 で見られる)
./gradlew -PmarumaOnly=mc1_7_10,mc1_20_1 exportRemapped
# 集めた jar から、Minecraft の準備をせずに配布するファイルを組み立てる
./gradlew -PmarumaPrebuilt=$PWD/build/prebuilt :dist:assemble
```

### 動作確認

```bash
python tools/verify/matrix.py
```

バニラのサーバーを起動し、各ローダーを入れた実際のクライアントで接続して、
画像・3D モデル・動画を看板に書き込んで表示するまでと、看板の文字を持たせた看板アイテムの見た目を自動で確認します。
[portablemc](https://github.com/mindstorm38/portablemc)、Pillow、FFmpeg が必要です。
`tools/verify/verify.py --image` には、ファイルのほかにライブ配信などの URL も `|` で区切って指定できます。

```bash
# 影 Mod とシェーダーパックを入れた状態で確認する
python tools/verify/matrix.py --shaders
# シェーダーパックを変える (Modrinth での名前)。Forge 1.16.5 以降で OptiFine を使うときは --shader-mod optifine
python tools/verify/matrix.py --shaders --shader-pack complementary-reimagined
# 結果を docs/verification.md にまとめる
python tools/verify/report.py
```

Minecraft を起動せずに確認できるものもあります。

```bash
# 配布する jar が、対応しているすべてのバージョンの Minecraft と正しくつながるかを確かめる
# (1 つのビルドを近いバージョンでも使っているため、バージョン間の細かな違いをここで見つける)
python tools/check_linkage.py
```

```bash
# ゲーム内のウィンドウの見た目を PNG に書き出す
./gradlew :core:uiPreview
# 3D モデルを PNG に描く (出力先, アニメーションの時刻, モデルのファイル...)
./gradlew :core:gltfCheck -PgltfArgs="build/gltf 0.5 model.glb"
# ガウシアンスプラッティングを正面・斜め・横から PNG に描く (出力先, ファイル...)
./gradlew :core:splatPreview -PsplatPreviewArgs="build/splat scene.spz"
# ガウシアンスプラッティングの読み込みの検査 (テスト用データのフォルダ)
./gradlew :core:splatCheck -PsplatArgs="splat-data"
```

### 取り込んでいるソースコード

次のライブラリのコードを、Java に書き直して (またはそのまま) `core` に取り込んでいます。
ライセンスの全文は jar の `marumasa_sign/licenses` にあります。

| ライブラリ | ライセンス | 使っているところ |
| --- | --- | --- |
| [openize-drako-java](https://github.com/openize-com/openize-drako-java) | MIT | Draco の展開 (`core/gltf/draco`) |
| [meshoptimizer](https://github.com/zeux/meshoptimizer) | MIT | meshopt の展開 (`core/gltf/meshopt`) |
| [TwelveMonkeys ImageIO](https://github.com/haraldk/TwelveMonkeys) | BSD 3-Clause | WebP の展開 (`core/texture/webp`) |
| [bcdec](https://github.com/iOrange/bcdec) | MIT | BC6H と BC7 の展開 (`core/texture/Bptc.java`) |
| [Basis Universal](https://github.com/BinomialLLC/basis_universal) | Apache 2.0 | KTX2 の ETC1S と UASTC の展開 (`core/texture/BasisEtc1s.java`, `Uastc.java`) |

## 貢献者

- ### [PizzaHarumaki](https://github.com/fhrk-78)

- ### [Sapporo_salmon](https://github.com/punipuni-3730)

- ### [Micni43](https://github.com/Micni43)
