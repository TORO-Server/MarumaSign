# MarumaSign

看板に画像を表示する Minecraft の Mod です。

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
2. 開いたウィンドウで、画像ファイルを選ぶか、画像の URL を入力します。
   - 「参照...」ボタンでファイルを選べます。
   - Minecraft 1.14 以降では、ウィンドウに画像ファイルをドロップすることもできます。
3. 大きさ・位置・回転を設定して、「看板に書き込む」ボタンを押します。
4. 看板を設置します。設置した看板に、画像を表示するための文字が自動で書き込まれます。

画像ファイルを選んだ場合、画像は [Catbox](https://catbox.moe/) にアップロードされ、誰でもアクセスできる URL になります。
公開したくない画像は選ばないでください。

### 対応している画像の形式

- `.png` (アニメーション PNG を含む)
- `.gif`
- `.jpg` / `.jpeg`

### 看板のフォーマット

`[URL]|[X軸位置]|[Y軸位置]|[Z軸位置]|[横幅]|[高さ]|[Z軸回転]|[Y軸回転]|[X軸回転]`

看板の 4 行 (Minecraft 1.20 以降は表と裏の合計 8 行) をつなげた文字列が、この形式になっていると画像が表示されます。

## 注意事項

- 看板に入りきらない長さの URL は、自動で短縮されます。
- Minecraft 1.7.10 のサーバーは看板 1 行につき 15 文字までしか受け付けないため、特に URL が短縮されやすくなります。
- スペクテイターモードでは、画像と一緒に看板そのものも表示されます。

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

### ビルド

```bash
./gradlew :dist:assemble
```

配布するファイルは `dist/build/libs` に出力されます。
初回は対応するすべてのバージョンの Minecraft を取得するため、時間がかかります。

### 動作確認

```bash
python tools/verify/matrix.py
```

バニラのサーバーを起動し、各ローダーを入れた実際のクライアントで接続して、
画像を看板に書き込んで表示するまでを自動で確認します。
[portablemc](https://github.com/mindstorm38/portablemc) と Pillow が必要です。

## 貢献者

- ### [PizzaHarumaki](https://github.com/fhrk-78)

- ### [Sapporo_salmon](https://github.com/punipuni-3730)

- ### [Micni43](https://github.com/Micni43)
