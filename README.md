# MarumaSign

看板に画像・動画・音声・3D モデル・ガウシアンスプラッティング・PDF・構造物 (建築のファイル) を表示する Minecraft の Mod です。

- 配布ページ: <https://www.curseforge.com/minecraft/mc-mods/marumasign>
- 解説動画: <https://www.youtube.com/watch?v=N-Q-Vtz7m-0>

サーバーに、この Mod を入れる必要はありません。すべてクライアント側で動作します。バニラのサーバーでも、サバイバルモードでも使えます。

## ダウンロードするファイル

使っているローダーに合わせて、次のどちらかを `mods` フォルダに入れてください。

| ローダー | ファイル |
| --- | --- |
| Fabric / Forge / NeoForge | `MarumaSign-<バージョン>.jar` (Minecraft のどのバージョンでも同じファイル) |
| LiteLoader | `MarumaSign-<バージョン>-liteloader-mc<Minecraft のバージョン>.litemod` (Minecraft のバージョンに合ったもの) |

最新のファイルは、[Releases](https://github.com/TORO-Server/MarumaSign/releases) にあります。
対応する Minecraft のバージョンとローダーの組み合わせは、[`targets.json`](targets.json) にあります。

## 使い方

1. ゲーム中、B キーを押します (キーの割り当ての「その他」にある「MarumaSign」で変えられます)。
2. 開いたウィンドウで、ファイルを選ぶか、URL を入力します。
3. 大きさ・位置・回転を設定して、「看板に書き込む」ボタンを押します。
4. 看板を設置します。設置した看板に、表示するための文字が自動で書き込まれます。

ファイルを選んだ場合、ファイルは [Catbox](https://catbox.moe/) にアップロードされ、誰でもアクセスできる URL になります。公開したくないファイルは選ばないでください。

動画・音声を再生するには、[FFmpeg](https://ffmpeg.org/) が必要です。

## 詳しい説明

対応している形式と制限、看板の書き方、安全のためのしくみ、ビルドと動作確認の方法は、[docs/reference.md](docs/reference.md) にまとめています。
