# MarumaSign

看板に画像・動画・音声・3D モデル・ガウシアンスプラッティング・PDF・構造物 (建築のファイル) を表示する Minecraft の Mod です。

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

ファイルの種類は、拡張子ではなく内容で判別します (内容に目印のない TGA・WBMP・XBM だけは、拡張子も使います)。

#### 画像

次の形式は、MarumaSign だけで読めます (FFmpeg は要りません)。

- 一般的な形式: `.png` (アニメーション PNG を含む) `.gif` (アニメーション) `.jpg` / `.jpeg` (CMYK と、写真の向き (Exif) に対応) `.bmp` `.webp` (アニメーションを含む) `.tif` / `.tiff` (BigTIFF、タイル、LZW・Deflate・PackBits・JPEG・Zstandard 圧縮、16 ビットと浮動小数点、CMYK に対応。RAW 写真は中の絵を使う) `.svg` / `.svgz`
- アイコン: `.ico` `.cur` `.ani` (アニメーション) `.icns`
- 画像編集・ゲームの形式: `.psd` / `.psb` (統合した画像) `.tga` `.qoi` `.dds` `.ktx2`
- 古い形式・研究用の形式: `.pcx` `.pbm` `.pgm` `.ppm` `.pnm` `.pam` `.pfm` `.sgi` / `.rgb` `.ras` `.xbm` `.xpm` `.wbmp` `.ff` (farbfeld) `.hdr` (Radiance) `.fits`

次の形式は、FFmpeg があれば読めます: `.avif` `.heic` / `.heif` (透明度つきを含む) `.jxl` (JPEG XL) `.jp2` (JPEG 2000) `.exr` `.dpx` `.iff` (ILBM) `.xwd` `.pgx`。
MarumaSign が自分で読めないファイル (CCITT 圧縮の TIFF など) も、FFmpeg があればやり直します。

画像の一辺は 4096 ピクセルまでです (アニメーションは 2048 ピクセルまで)。

#### 動画・音声

[FFmpeg](https://ffmpeg.org/) が読める形式 (`.mp4` `.webm` `.mkv` `.mov` `.avi` `.mp3` `.ogg` `.wav` `.flac` など) を再生できます。
動画に含まれる音声も再生され、看板から離れるほど小さく聞こえます。音量はウィンドウの「動画と音声の音量」で変えられます。

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
まとめるのは、`.gltf` と同じフォルダ (とその中のフォルダ) にある、バッファ (`.bin` など) と画像のファイルだけです。
フォルダの外 (`../` や `C:\` から始まる場所) を指すものは読まないので、人から受け取ったモデルでも、ほかのファイルが一緒にアップロードされることはありません。

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


#### PDF

`.pdf` を表示できます。最初は 1 ページ目を表示します。

Web ブラウザの PDF ビューアと同じく、URL の末尾に `#page=ページ番号` を付けると、読み込んだときにそのページから表示します
(例: `https://example.com/manual.pdf#page=3`)。

- `#zoom=50&page=3` のように、ほかの指定と並べて書いてもかまいません (ページ以外の指定は使いません)
- ページの数より大きい番号は最後のページ、正しくない番号 (`0` や数字でないもの) は 1 ページ目として扱います
- 画面でファイルを選んだときも、入力欄のファイルの名前の後ろに `#page=3` を付ければ、そのページから表示します。プレビューにもそのページが出ます
- `#page=` の指定だけが違う看板は、別々にページを送れます。`#` から後ろはサーバーに送らず、
  ダウンロードしたファイルを残しておく仕組みでも同じファイルとして扱います (サーバーが変わったかを答えられれば、2 回目からは問い合わせるだけで済みます)
- URL を短縮するときは、`#` から後ろを除いた部分を短縮して、後ろに付け直します

看板の絵に照準 (画面中央の十字) を合わせている間だけ、絵の下側にページ送りの帯が表示されます。
帯の左右のボタンに照準を合わせて使用キー (右クリック) を押すと、前のページ・次のページに切り替わり、
中央には「現在のページ / 総ページ数」が表示されます。照準を外すと帯は消えます。

- ページの切り替えは自分の画面だけで、ほかのプレイヤーには伝わりません。同じ URL (`#page=` の指定も同じ) の看板は、すべて同じページを表示します。
- 照準が絵に合っている間の使用キーは、ページ送りのためだけに使い、ブロックを置いたり看板を編集したりはしません。
- 埋め込まれていないフォントは、パソコンにあるフォントで代わりに表示します。フォントの一覧は、ゲームを起動して初めて PDF を読むときに作ります (フォントが 6000 個ほどあるパソコンで 0.3 秒ほど)。一覧は `marumasign/pdf-fonts.cache` に保存し、次からは変わったフォントだけを読み直します (変わったものがなければ 0.1 秒ほど)。
- ファイルは 64 MB までです。
- PDF の読み込みと描画は、ライブラリを使わない独自の実装です (暗号化された PDF、圧縮の形式、フォント、透明、模様、グラデーションなどに対応)。
  JPEG 2000 (JPX) と JBIG2 の画像は表示しません

#### 構造物 (建築のファイル)

次の形式の、ブロックを並べた建築のファイルを、ブロックでできた 3D モデルとして表示できます。

- `.schem` (Sponge Schematic のバージョン 1 - 3。WorldEdit、FastAsyncWorldEdit など)
- `.litematic` (Litematica)
- `.nbt` (ストラクチャーブロック。Minecraft 26.1 からの書き方にも対応)
- `.mcstructure` (統合版のストラクチャーブロック)
- `.schematic` (MCEdit、古い WorldEdit、Schematica。Minecraft 1.12 までのブロックの番号の形式)

見え方について:

- どのバージョンの Minecraft で表示しても同じ見た目になるように、ブロックの形とテクスチャは Minecraft 26.3 のものを使います。
  初めて構造物を表示するときに、Mojang のサーバーから Minecraft 26.3 のクライアントの jar (約 40 MB) を
  ゲームフォルダの `marumasign/assets` にダウンロードします (ランチャーの `versions` フォルダに同じものがあれば、それを使います)。
  看板の文字に、jar に入っていない文字 (日本語など) があるときは、同じように Unifont (約 1.5 MB) を読みます
- 古いバージョンで作られたファイルのブロックは、今の名前と状態に直して表示します。
  1.12 までの番号の形式と統合版のブロックは対応表で直し、フェンスや板ガラスのつながり、階段の角、
  レッドストーンの線のつながり、チェストのつながりなどは、まわりのブロックから計算します
- ブロックの面の省き方、なめらかな明るさ (環境光の遮蔽)、面の向きによる明るさ、草・葉・水の色 (ファイルにバイオームがあればバイオームごと)、
  花や草の位置のずれ、同じブロックの見た目の選び方は、ゲームと同じ計算にしています。明るさは、昼の屋外として計算します
- 水と溶岩は、流れの形と向きもゲームと同じ計算で描き、水や溶岩、炎などのテクスチャは動きます
- 次のものも描きます: チェスト (クリスマスの時期の見た目も)、シュルカーボックス、旗 (模様も)、頭 (プレイヤーの頭はスキンをダウンロード)、
  ベル、飾り壺 (陶片の模様も)、エンチャントテーブルと書見台の本、コンジット、銅のゴーレムの像、エンドポータル、エンドゲートウェイ、
  動いているピストンのブロック、看板の文字 (色、光る文字の縁取り、太字も)
- エンティティは、絵画、額縁 (中のアイテムも)、防具立て (姿勢、防具、手と頭のアイテムも)、ブロック・アイテム・文字のディスプレイを描きます。
  生き物、トロッコ、ボートなどは描きません
- エンドポータルとエンドゲートウェイの星空は、ゲームでは特別な描き方をするため、星の模様を貼った面で近づけています
- 構造物の中にある MarumaSign の形式で書かれた看板 (と、その文字を持つ看板のアイテム) は、ゲームと同じように、看板の板の代わりに
  画像 (アニメーションも)、PDF の 1 ページ目 (URL に `#page=` があればそのページ)、3D モデル、構造物を描きます。動画・音声・ガウシアンスプラッティングは描きません (看板の板は消えます)

制限:

- ファイルは 64 MB まで (圧縮をほどいたあとは 96 MB まで)
- ブロックの数が 1,677 万 (16 x 1024 x 1024) より多い構造物は、何ブロックかおきに間引いて表示します
- 描く面が 15 万枚より多くなる構造物は、2 x 2 x 2 ブロックを 1 つにまとめる (中で最も多いブロックにする) ことを、15 万枚以下になるまで繰り返して表示します。
  エンティティと、中の看板が表示するものはまとめられないので、それらが多いときは 15 万枚を超えることがあります。その場合も 60 万枚までで、超えた分は描きません
- 別の Mod のブロックは、ゲームと同じく紫と黒の模様の立方体で表示します
- 構造物の中の看板が読み込むのは、1 つの構造物 (中の構造物も含めて) で URL 16 個、合計 64 MB、20 秒までです。
  構造物の中の構造物は 2 段までで、同じ URL をたどり直すことはしません
- 構造物は 1 つずつ読み込みます (大きな構造物の看板がいくつも同時に読み込まれて、メモリを使い切らないように)

### 看板のフォーマット

`[URL]|[X軸位置]|[Y軸位置]|[Z軸位置]|[横幅]|[高さ]|[Z軸回転]|[Y軸回転]|[X軸回転]`

看板の 4 行 (Minecraft 1.20 以降は表と裏の合計 8 行) をつなげた文字列が、この形式になっていると表示されます。
動画・音声・3D モデル・ガウシアンスプラッティング・PDF・構造物でも同じ形式です。
数値は画面で入力できる範囲と同じ -1000 から 1000 までで、それより大きいものは 1000 (小さいものは -1000) として扱います。
`NaN` や `Infinity` が書かれた看板は、MarumaSign の看板として扱いません。

### 悪用を防ぐしくみ

看板は誰でも書けるので、看板を見ただけで困ることが起きないようにしています。

- 自分のパソコン (`127.0.0.1`、`localhost` など) や家の中のネットワーク (`192.168.x.x`、`10.x.x.x`、ルーター、NAS など)、
  クラウドの管理用のアドレス (`169.254.169.254`) を指す URL は読みません。リダイレクトの行き先も、つなぐ前に確かめます。
  ダウンロードも動画・音声の再生 (FFmpeg) の通信も、MarumaSign の中のプロキシを通し、確かめたアドレスにそのままつなぎます
  (HLS などの再生リストの中の URL も。確かめた後に名前の行き先をすり替えられても、そこにはつながりません)
- `http` と `https` 以外の URL (`file:` など) は読みません。メール (25 番) や SSH (22 番) など、ブラウザーがつながない番号の URL も読みません
- 画像として FFmpeg に渡すものが再生リスト (HLS・DASH など) だった場合は渡しません (パソコンの中のファイルを看板に映させないように)
- ログに書く URL などの文字は、`${` や改行を無害にしてから書きます (古い Minecraft の Log4j の脆弱性 Log4Shell への対策)
- PDF の中の圧縮されたデータを展開するのに使うメモリは、1 つあたり 128 MB までにします。描く時間 (20 秒)、命令の数、入れ子の深さ、
  関数・フォントの手続きの深さと回数にも上限を設け、輪になったページの木や相互参照の表、自分を呼ぶフォントでも止まるようにしています
- JPEG の画像 (看板の画像、3D モデルのテクスチャ、TIFF や PDF の中のもの) と PDF の中の色の定義 (ICC プロファイル) は、
  Java の中のネイティブのライブラリ (libjpeg、LCMS) に渡さず、Java で書いた処理で読みます (細工したデータでメモリを壊されないように)
- 1 バイトずつゆっくり送り続けるようなサーバーは、30 秒たっても速さが 16 KB/秒に届かなければ、読むのをやめます。
  応答が 60 秒たっても返らないときも、やめます
- 看板の中身が使うメモリは合計 1 GB ほどまでにします。超えるときは、いま見えていないものから消し (また見えたら読み込み直します)、
  見えているものだけで超えるときは、それ以上は読み込めなかった印を表示します。5 分見えなかったものも消します
- 同時に再生する動画・音声は 8 つまでで、近いものから再生します
- 構造物のファイルの中身 (ブロック、文字、エンティティの数など) にも上限を設けています ([構造物](#構造物-建築のファイル) の制限を参照)

確認のために手元のサーバーから読む必要があるときは、Java の起動オプションに `-Dmarumasign.allowLocalUrls=true` を付けます。

### 看板のアイテム

看板の文字をデータとして持っている看板のアイテム (クリエイティブモードで Ctrl を押しながらブロックを選択したときや、
`/give` でデータ付きの看板を渡されたときなど) は、アイテムの見た目も、その看板と同じ画像・動画・3D モデル・ガウシアンスプラッティング・構造物になります。
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
- 画像は 10 MB まで、3D モデルと構造物は 64 MB まで、アップロードできる動画・音声とガウシアンスプラッティングは 200 MB までです。
- 一度読み込んだファイル (動画・音声を除く) は、ゲームフォルダの `marumasign/cache` に合計 512 MB まで保存します。
  次に同じ看板を見たときは、サーバーにファイルが変わったかだけを問い合わせ、変わっていなければ保存したものを使います。
  サーバーにつながらないときも、保存したものを表示します。
  変わったかを問い合わせる情報 (ETag / Last-Modified) を返さないサーバーのファイルは保存しません。
  合計が 512 MB を超えると、最後に使ったのが古いものから消します。フォルダごと消してもかまいません。
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
画像・3D モデル・ガウシアンスプラッティング・動画・PDF・構造物を看板に書き込んで表示するまで (PDF はページ送りの操作も) と、看板の文字を持たせた看板アイテムの見た目を自動で確認します
(PDF のアイテムは URL に `#page=2` を付けて、2 ページ目から表示されることも確かめます)。
[portablemc](https://github.com/mindstorm38/portablemc)、Pillow、FFmpeg が必要です。
`tools/verify/verify.py --image` には、ファイルのほかにライブ配信などの URL も `|` で区切って指定できます。

次のどれかに当てはまると、合格にしません。

- Mod を入れていない状態 (基準) と比べて、新しい警告やエラーがログに出た。基準を取っていない組み合わせも合格にしない
  (`tools/verify/known-noise.txt` の、Mod によらないと分かっている行は除く。行ごとにクライアントとサーバーのどちらのログに当てはめるかを決めている)
- Mod がファイルの読み込みや再生に失敗した (ログでは INFO の行でも数える)
- 看板が描かれようとしてから表示されるまでに 15 秒より長くかかった (`-Dmarumasign.selftest.loadLimit=ミリ秒` で変えられる)。
  かかった時間は結果の `load_ms` に残り、`matrix.py` の最後に遅かったものを表示する
- 看板アイテムが描かれなかった

`matrix.py` は、失敗した組み合わせを 1 回だけやり直します。やり直して合格しても、1 回目の失敗の内容を結果に残し、表にも「1 回目は失敗」と書きます。

```bash
# 3 つのクライアントを同時に動かす (1 つあたりメモリを 4 GB ほど使う)。前回合格したものは飛ばす
python tools/verify/matrix.py --jobs 3 --skip-passed
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
# CI の「Quality」と同じ検査を、Gradle を使わずに javac と java だけで動かす (Minecraft の準備は要らない)。
# quick は PR ごと、long は毎晩のもの (ファジングが長い)。乱数の種は FUZZ_SEED で決められる
bash .github/scripts/run-core-checks.sh quick
# ライブラリ (第三者のコード) を使っていないことを確かめる
python3 .github/scripts/check-no-libraries.py
# PDF の読み込みと描画の試験 (暗号、圧縮、フォント、描画の結果、壊したファイルや悪意のあるファイル)
./gradlew :core:pdfEngineCheck
# 壊した PDF を読ませて、想定外の例外や長い時間、メモリの使い切りが出ないかを確かめる (回数, ファイルまたはフォルダ...)
./gradlew :core:pdfFuzz -PpdfFuzzArgs="300"
# JPEG の読み込み (libjpeg を使わないもの) を、libjpeg (Pillow) で戻した結果と比べる
./gradlew :core:jpegCheck
# ゲーム内のウィンドウの見た目を PNG に書き出す
./gradlew :core:uiPreview
# 3D モデルを PNG に描く (出力先, アニメーションの時刻, モデルのファイル...)
./gradlew :core:gltfCheck -PgltfArgs="build/gltf 0.5 model.glb"
# ガウシアンスプラッティングを正面・斜め・横から PNG に描く (出力先, ファイル...)
./gradlew :core:splatPreview -PsplatPreviewArgs="build/splat scene.spz"
# ガウシアンスプラッティングの読み込みの検査 (テスト用データのフォルダ)
./gradlew :core:splatCheck -PsplatArgs="splat-data"
# PDF の看板の表示とページ送りを確かめ、ページと帯を PNG に書き出す (PDF のファイル, 出力先。core フォルダからの位置か、絶対パスで指定する)
./gradlew :core:pdfCheck -PpdfArgs="../tools/verify/test-pdf.pdf build/pdf"
# 構造物のファイルを、斜め上の 2 方向から見た PNG に描く (出力先, ファイルまたはフォルダ...)。
# ブロックのテクスチャに使う Minecraft 26.3 の jar を -PassetJar で指定できる (指定しなければダウンロードする)
./gradlew :core:structureCheck -PstructureArgs="build/structure ../tools/verify/test-structure.nbt"
# 見比べるために、Mod なしのゲームでゲームに入っている構造物を置いて、画面を撮る
python tools/verify/compare_structure.py --template minecraft:village/plains/houses/plains_temple_3
# 画像のピクセルを直接読む処理が、BufferedImage.getRGB と同じ結果になるかを確かめる (画像のファイルを足して確かめることもできる)
./gradlew :core:argbCheck
# 画像の各形式のデコーダを、ImageMagick や Pillow で作ったファイルと比べて確かめる (出力先, 元の画像の入ったフォルダ, ファイルまたはフォルダ...)
./gradlew :core:formatCheck -PformatArgs="build/format samples samples/pil"
# 壊した画像のファイルを読ませて、想定外の例外や長い時間が出ないかを確かめる (回数, ファイルまたはフォルダ...)
./gradlew :core:formatFuzz -PimageFuzzArgs="100 samples/pil"
# ダウンロードしたファイルを保存しておく仕組みを、手元の HTTP サーバーで確かめる
./gradlew :core:downloadCacheCheck
# 看板の URL で、手元や家の中のネットワークにアクセスさせられないこと (動画の再生、リダイレクト、ゆっくり送るサーバーも) を確かめる
./gradlew :core:securityCheck
# 違う URL の看板や動画をたくさん並べられても、メモリと同時に再生する数が上限を超えないことを確かめる
./gradlew :core:signLimitsCheck
# 構造物の中の MarumaSign の看板 (自分を指す構造物、読めない URL、NaN、たくさんの URL など) を確かめる。-PassetJar は structureCheck と同じ
./gradlew :core:nestedSignCheck
# 壊した構造物のファイルを読ませて、想定外の例外が出ないかを確かめる (回数, ファイルまたはフォルダ...)。-PfuzzMesh で 3D モデルにするところまで
./gradlew :core:structureFuzz -PfuzzArgs="300 ../tools/verify/test-structure.nbt"
# 3D モデルを読み込んだ結果のハッシュを表示する。読み込みを速くしたときに、変える前と結果が同じかを比べる
./gradlew :core:gltfHash -PgltfHashArgs="model.glb other.gltf"
# ダウンロードと読み込みにかかる時間を、ファイルごとに測る (回数, ファイル...)
./gradlew :core:loadBenchmark -PloadBenchArgs="5 image.png model.glb scene.spz"
```

### 構造物の表示に使う表

構造物の表示に使うブロックの性質や対応表は `core/src/main/resources/marumasa_sign/structure` にあり、`tools/blockdata` の道具で作ります。
作り方は [tools/blockdata/README.md](tools/blockdata/README.md) を参照してください。

### PDF の表示に使う表

PDF の表示に使う表 (`core/src/main/resources/marumasa_sign/pdf`) は、`tools/pdfdata` の道具で作ります。

- `encodings.txt` `glyphlist.txt.gz` `afm.txt.gz` `cmaps.bin.gz`: `python3 tools/pdfdata/gen_pdf_data.py <元のフォルダ>` (元のフォルダに置くものは道具の先頭に書いてあります)
- `cmyk.lut`: `java tools/pdfdata/CmykLut.java <ISO Coated v2 300% (ECI) の ICC プロファイル> <出力先>`

### 取り込んでいるデータ

次の表 (データ) を `core` に取り込んでいます。ライセンスの全文は jar の `marumasa_sign/licenses` にあります。

| データ | ライセンス | 使っているところ |
| --- | --- | --- |
| [minecraft-data](https://github.com/PrismarineJS/minecraft-data) | MIT | 構造物の、古いブロックの番号と統合版のブロックの対応表 (`marumasa_sign/structure/legacy.txt.gz`, `bedrock.txt.gz`) |
| [Adobe Glyph List](https://github.com/adobe-type-tools/agl-aglfn) | BSD 3-Clause | PDF のグリフの名前と Unicode の対応 (`marumasa_sign/pdf/glyphlist.txt.gz`) |
| [Adobe CMap resources](https://github.com/adobe-type-tools/cmap-resources) | BSD 3-Clause | PDF の日本語・中国語・韓国語の定義済みの CMap (`marumasa_sign/pdf/cmaps.bin.gz`) |
| Adobe Core 14 AFM | Adobe の使用条件 (`adobe-core14-afm.txt`) | PDF の標準 14 フォントの寸法 (`marumasa_sign/pdf/afm.txt.gz`、必要な項目だけを残したもの) |
| ISO Coated v2 300% (ECI) の ICC プロファイル | (表だけを使用) | CMYK の色を sRGB にする表 (`marumasa_sign/pdf/cmyk.lut`、プロファイルから格子の点の色を求めたもの) |

配布する jar には、ライブラリ (第三者のコード) を同梱していません。PDF の読み込みと描画 (`core/pdf`)、記録 (`SafeLog`)、
配布物の組み立て (`dist` のパッケージ名の書き換え) は、それぞれ Apache PDFBox 2.0、Log4j、ASM を参考にした独自の実装です。
WebP、Draco、meshopt、BC6H / BC7、KTX2 の ETC1S / UASTC の展開は、それぞれの仕様書 (RFC 6386、RFC 9649、Draco Bitstream
Specification、KHR_meshopt_compression、Khronos Data Format Specification、KTX 2.0) から書いた独自の実装です
(以前は TwelveMonkeys ImageIO、openize-drako-java、meshoptimizer、bcdec、Basis Universal を移植していました)。
参考にしたライブラリにあった次の問題は、独自の実装では起きないようにしています。

- PDF の中の ICC プロファイルをネイティブの LCMS に渡すこと (細工したプロファイルによるメモリ破壊の危険) → 渡さない
- Type 1 フォントの seac (アクセント付きの文字) の入れ子でスタックを使い切ること → 入れ子を受け付けない
- CMap の範囲をすべての文字に展開してメモリを使うこと → 範囲のまま持つ
- 1 ページを描く時間と命令の数に上限がないこと → 上限を設けた (CI の `pdfFuzz` で、壊したファイルでも止まることを毎回確かめます)
- 座標が極端な道で、Java 8 の Java2D が 1 つの道に数十秒かかること → 画面から遠く伸びる部分を、渡す前に切り取る
- WebP: 非可逆圧縮のループフィルターの強さと色差の量子化の誤り、正しいファイルで例外になること → 直した (libwebp と画素単位で一致)。
  ハフマン符号の表のあふれ (CVE-2023-4863 と同じ種類) は、符号の長さが正しい木になるかを先に確かめて防ぐ
- Draco: 正しいファイルの一部 (EdgeBreaker の速さ 0 - 1) で例外になること、法線の最後の桁の誤り、
  65536 個以上の要素の識別子を見つけられないこと → 直した (Google の公式のデコーダと出力が一致)。
  壊れた角や面の番号による範囲外の書き込み、宣言された数による巨大なメモリの確保、無限の繰り返しも防ぐ
- meshopt、BC6H / BC7、ETC1S / UASTC: 仕様にない値 (使われていない形式の番号、書かれていない表の位置、データの終わりを越える読み込み) を
  黙って使うこと → 読めないものとして扱う

CI のファジングと見直しで見つけた、この Mod 自身の不具合も直しています (主なもの)。

- 画像: GIF・APNG・ICO・TIFF・EXR・PSD・Sun Raster の、見出しの大きさによる巨大なメモリの確保と数のあふれ。
  GIF の LZW の表が前の絵の分を残していた誤り、灰色の APNG が明るすぎた誤り
- SVG: 深い入れ子でのスタックの使い切り、正規表現の遅さ、use の爆弾。1 つの文書の描画の手間と時間 (2 秒) に上限を設けた。
  外部の実体や外の URL は読まない
- 3D モデルとスプラット: 焼き付けの無限の繰り返し、共有するデータの何度もの読み直しや宣言された数によるメモリの使い切り、
  Zstandard の検査の漏れ、NaN の位置
- 構造物: Litematica の大きさのあふれ、看板の文字やブロックの状態の探し方が遅いこと、入れ子の看板の 64 MB の上限を超えること
- 通信と再生: IPv6 の一部の書き方 (IPv4 を埋め込んだもの、NAT64、Teredo) で家の中のネットワークを指せたこと。
  再生を止めても ffprobe が残ること。FFmpeg と ffprobe には時間の上限を設け、ゲームを終えるときに止める
- PDF: CFF のフォントの辞書、JPEG の区切り、暗号の鍵の長さでの範囲外の読み込み。
  Java 8 で、点線の位相や座標が極端な値のときに描画が終わらないこと。
  極端に小さいページの箱や、小さなファイルから作らせる巨大な名前・入れ子・オブジェクトでのメモリの使い切り、
  /Length が次々に別のストリームを指すときのスタックの使い切り。4 ビットの予測つきの色、EOL のある CCITT G4、
  インライン画像の終わりの見分け、90 度の倍数でない /Rotate、一部の表の作り直しで新しい版や隠した層が戻ること
- 画像とアニメーション: APNG の半透明の重ね合わせで色があふれて黒くなること、絵のない制御の拡張がある GIF、
  長い表示時間のコマで時刻があふれること、行をバイト単位で詰めた DDS
- 通信と再生: Content-Length を偽るサーバーで、届く前に上限まで (スプラットなら 200 MB) 確保させられたこと。
  音の出力を開いている途中で止めると、出力が開いたまま残ること。8 つより多い動画で、遠ざかった動画が近いものとして選ばれ、
  近い動画が最初から再生し直しになること
- 画面と看板: 入力欄の文字が短くなったときに前の文字が隠れたままになること、絵文字のある文字が看板の 4 行に入りきるのに
  書き込めないこと、テクスチャを 1 つ消せないと残りも消されないこと

## 貢献者

- ### [PizzaHarumaki](https://github.com/fhrk-78)

- ### [Sapporo_salmon](https://github.com/punipuni-3730)

- ### [Micni43](https://github.com/Micni43)
