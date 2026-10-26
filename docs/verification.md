# 動作確認の結果

`tools/verify/matrix.py` による実機確認の結果です。このファイルは `tools/verify/report.py` が生成します。

確認の内容: バニラのサーバーを起動し、各ローダーを入れた実際のクライアントで接続して、
サバイバルモードのまま、ゲーム内のウィンドウでファイルを選び、看板を設置して表示されるまでを自動で行います。
1 回の起動で、画像 (PNG)、3D モデル (GLB)、動画 (音声付きの MP4)、ガウシアンスプラット (.splat)、PDF、構造物 (.nbt) の 6 つを順に確認し (PDF はページ送りも確かめ)、
最後に、6 つそれぞれの看板の文字をデータとして持たせた看板アイテムを渡して、手持ちの枠の中の見た目がその絵になることを確認します
(PDF のアイテムは URL の末尾に `#page=2` を付けて、2 ページ目から表示されることも確かめます)。
画面は自動の判定に加えて目でも確かめ、絵にすき間や縞模様がないこと (ガウシアンスプラットの重なり方) も調べています。

- **OK**: 6 つとも正しい向きで、すき間なく表示され、看板そのものは隠れていて、6 つの看板アイテムもその絵の見た目になり、
  Mod を入れていない状態と比べて新しい警告やエラーが出なかった。どのファイルも、看板が描かれようとしてから 15 秒以内に表示された
- **OK (1 回目は失敗)**: 1 回目の確認で失敗し、やり直して合格した (たまにしか起きない不具合かもしれないので、1 回目の内容を結果に残している)
- **OK (1 回目は NeoForge の接続の不具合で失敗)**: NeoForge が、バニラのサーバーにつなぐときの準備を 2 つのスレッドで同時に行って
  切断し (「Duplicate handler name: neoforge:vanilla_filter」)、やり直して合格した。NeoForge 自体の処理どうしがぶつかって起きるもので、
  この Mod は通信の処理に関わっていない
- **要確認**: 動作はしたが、新しい警告が出たか、表示を確認できなかった
- **失敗**: 動作しなかった
- **対象外**: Mod を入れていない状態でもゲームが起動しないか、サーバーに入れなかった (ローダーや影 Mod 側の問題)
- 空欄: そのローダーはこのバージョンに対応していない
- **未確認**: 対応しているが、配布するファイルの現在の内容ではまだ確認していない

Mod のバージョン: 4.1.0 / 作成日: 2026-10-26

| Minecraft | Fabric | Forge | NeoForge | LiteLoader |
| --- | --- | --- | --- | --- |
| 26.3 | OK | OK | OK |  |
| 26.2 | OK | OK | OK |  |
| 26.1.2 | OK | OK | OK |  |
| 26.1.1 | OK | OK | OK |  |
| 26.1 | OK | OK | OK |  |
| 1.21.11 | OK | OK | OK |  |
| 1.21.10 | OK | OK | OK |  |
| 1.21.9 | OK | OK | OK |  |
| 1.21.8 | OK | OK | OK |  |
| 1.21.7 | OK | OK | OK |  |
| 1.21.6 | OK | OK | OK |  |
| 1.21.5 | OK | OK | OK |  |
| 1.21.4 | OK | OK | OK |  |
| 1.21.3 | OK | OK | OK |  |
| 1.21.2 | OK |  | OK |  |
| 1.21.1 | OK | OK | OK |  |
| 1.21 | OK | OK | OK |  |
| 1.20.6 | OK | OK | OK |  |
| 1.20.5 | OK |  | OK |  |
| 1.20.4 | OK | OK | OK |  |
| 1.20.3 | OK | OK | OK |  |
| 1.20.2 | OK | OK | OK |  |
| 1.20.1 | OK | OK | OK |  |
| 1.20 | OK | OK |  |  |
| 1.19.4 | OK | OK |  |  |
| 1.19.3 | OK | OK |  |  |
| 1.19.2 | OK | OK |  |  |
| 1.19.1 | OK | OK |  |  |
| 1.19 | OK | OK |  |  |
| 1.18.2 | OK | OK |  |  |
| 1.18.1 | OK | OK |  |  |
| 1.18 | OK | OK |  |  |
| 1.17.1 | OK | OK |  |  |
| 1.17 | OK |  |  |  |
| 1.16.5 | OK | OK |  |  |
| 1.16.4 | OK | OK |  |  |
| 1.16.3 | OK | OK |  |  |
| 1.16.2 | OK | OK |  |  |
| 1.16.1 | OK | OK |  |  |
| 1.16 | OK |  |  |  |
| 1.15.2 | OK | OK |  |  |
| 1.15.1 | OK | OK |  |  |
| 1.15 | OK | OK |  |  |
| 1.14.4 | OK | OK |  |  |
| 1.14.3 | OK | OK |  |  |
| 1.14.2 | OK | OK |  |  |
| 1.14.1 | OK |  |  |  |
| 1.14 | OK |  |  |  |
| 1.12.2 |  | OK |  | OK |
| 1.12.1 |  | OK |  | OK |
| 1.12 |  | OK |  | OK |
| 1.11.2 |  | OK |  | OK |
| 1.11 |  | OK |  | OK |
| 1.10.2 |  | OK |  | OK |
| 1.10 |  | OK |  | OK |
| 1.9.4 |  | OK |  | OK |
| 1.9 |  | OK |  | OK |
| 1.8.9 |  | OK |  | OK |
| 1.8.8 |  | OK |  |  |
| 1.8 |  | OK |  | OK |
| 1.7.10 |  | OK |  | OK |

合計: OK 138

## 影 Mod を入れた状態

影 Mod (シェーダーを使えるようにする Mod) とシェーダーパックを入れて、同じ確認を行った結果です。
マスの中は「影 Mod + シェーダーパック: 結果」です。
Iris は Sodium と、Oculus は Rubidium または Embeddium と一緒に入れています。空欄は、確認していない組み合わせです。
シェーダーは絵にも影や空の色を付けるので、色そのものではなく、絵が正しい場所に正しい向きで写っていることを確認しています。

影 Mod とそれが必要とする Mod は、そのバージョン向けの版のうち、jar の中の指定 (Minecraft、Forge / NeoForge、必要とする Mod の版) に合うものを選んでいます。
1.17 / 1.18 の Iris (1.1.x) は、描画の段階を表す定義 (MC_RENDER_STAGE_*) を持たず新しいシェーダーパックを組み立てられないので、それを使っていない BSL 8.1 で確認しています。

次の組み合わせは、この Mod を入れなくても影 Mod が動かないため、確認の対象から外しています。

- Forge 1.14.2 / 1.14.3: その版の OptiFine はどれも Forge に対応していない (配布元の情報で Forge が「N/A」)。Oculus は 1.16.5 から
- Forge 1.21.1 + OptiFine 1.21.1 HD U J1: OptiFine の読み込み中にゲームが終了する
- OptiFine 1.8.9 + MakeUp Ultra Fast: 画面が真っ黒になる

| Minecraft | Fabric | Forge | NeoForge | LiteLoader |
| --- | --- | --- | --- | --- |
| 26.3 | Iris + BSL: OK |  | Iris + BSL: OK |  |
| 26.2 | Iris + BSL: OK |  | Iris + BSL: OK |  |
| 26.1.2 | Iris + BSL: OK<br>Iris + Complementary Reimagined: OK |  | Iris + BSL: OK |  |
| 26.1.1 | Iris + BSL: OK |  | Iris + BSL: OK |  |
| 26.1 | Iris + BSL: OK |  | Iris + BSL: OK |  |
| 1.21.11 | Iris + BSL: OK<br>Iris + MakeUp Ultra Fast: OK |  | Iris + BSL: OK |  |
| 1.21.10 | Iris + BSL: OK |  | Iris + BSL: OK<br>Iris + Photon: OK |  |
| 1.21.9 | Iris + BSL: OK |  |  |  |
| 1.21.8 | Iris + BSL: OK<br>Iris + Complementary Reimagined: OK |  | Iris + BSL: OK |  |
| 1.21.7 | Iris + BSL: OK |  | Iris + BSL: OK |  |
| 1.21.6 | Iris + BSL: OK |  | Iris + BSL: OK |  |
| 1.21.5 | Iris + BSL: OK |  | Iris + BSL: OK |  |
| 1.21.4 | Iris + BSL: OK<br>Iris + Photon: OK |  | Iris + BSL: OK |  |
| 1.21.3 | Iris + BSL: OK |  | Iris + BSL: OK |  |
| 1.21.2 | Iris + BSL: OK |  | Iris + BSL: OK |  |
| 1.21.1 | Iris + BSL: OK |  | Iris + BSL: 要確認 (ガウシアンスプラットが粒に見える)<br>Iris + Sildur's Vibrant: 要確認 (ガウシアンスプラットが粒に見える) |  |
| 1.21 | Iris + BSL: OK |  | Iris + BSL: OK |  |
| 1.20.6 | Iris + BSL: OK |  |  |  |
| 1.20.5 | Iris + BSL: OK |  |  |  |
| 1.20.4 | Iris + BSL: OK |  |  |  |
| 1.20.3 | Iris + BSL: OK |  |  |  |
| 1.20.2 | Iris + BSL: OK |  |  |  |
| 1.20.1 | Iris + BSL: OK<br>Iris + MakeUp Ultra Fast: OK | Oculus + BSL: OK<br>Oculus + Complementary Reimagined: OK<br>OptiFine + BSL: OK |  |  |
| 1.20 | Iris + BSL: OK | Oculus + BSL: OK |  |  |
| 1.19.4 | Iris + BSL: OK | Oculus + BSL: OK |  |  |
| 1.19.3 | Iris + BSL: OK |  |  |  |
| 1.19.2 | Iris + BSL: OK | Oculus + BSL: OK |  |  |
| 1.19.1 | Iris + BSL: OK | Oculus + BSL: OK |  |  |
| 1.19 | Iris + BSL: OK | Oculus + BSL: OK |  |  |
| 1.18.2 | Iris + BSL: OK | Oculus + BSL: OK<br>OptiFine + BSL: OK |  |  |
| 1.18.1 | Iris + BSL: OK |  |  |  |
| 1.18 | Iris + BSL: OK |  |  |  |
| 1.17.1 | Iris + BSL: OK |  |  |  |
| 1.17 | Iris + BSL: OK |  |  |  |
| 1.16.5 | Iris + BSL: OK<br>Iris + Sildur's Vibrant: OK | Oculus + BSL: OK<br>OptiFine + BSL: OK |  |  |
| 1.16.4 |  | OptiFine + BSL: OK |  |  |
| 1.16.3 |  | OptiFine + BSL: OK |  |  |
| 1.16.2 |  | OptiFine + BSL: OK |  |  |
| 1.16.1 |  | OptiFine + BSL: OK |  |  |
| 1.15.2 |  | OptiFine + BSL: OK |  |  |
| 1.14.4 |  | OptiFine + BSL: OK |  |  |
| 1.12.2 |  | OptiFine + BSL: OK<br>OptiFine + Complementary Reimagined: OK |  | OptiFine + BSL: OK |
| 1.12.1 |  | OptiFine + BSL: OK |  | OptiFine + BSL: OK |
| 1.12 |  | OptiFine + BSL: OK |  | OptiFine + BSL: OK |
| 1.11.2 |  | OptiFine + BSL: OK |  | OptiFine + BSL: OK |
| 1.11 |  | OptiFine + BSL: OK |  | OptiFine + BSL: OK |
| 1.10.2 |  | OptiFine + BSL: OK |  | OptiFine + BSL: OK |
| 1.10 |  | OptiFine + BSL: OK |  | OptiFine + BSL: OK |
| 1.9.4 |  | OptiFine + BSL: OK |  | OptiFine + BSL: OK |
| 1.8.9 |  | OptiFine + BSL: OK |  | OptiFine + BSL: OK |
| 1.8.8 |  | OptiFine + BSL: OK |  |  |
| 1.7.10 |  | OptiFine + BSL: OK<br>OptiFine + Sildur's Vibrant: OK |  | OptiFine + BSL: OK |
