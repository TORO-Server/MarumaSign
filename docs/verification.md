# 動作確認の結果

`tools/verify/matrix.py` による実機確認の結果です。このファイルは `tools/verify/report.py` が生成します。

確認の内容: バニラのサーバーを起動し、各ローダーを入れた実際のクライアントで接続して、
サバイバルモードのまま、ゲーム内のウィンドウでファイルを選び、看板を設置して表示されるまでを自動で行います。
1 回の起動で、画像 (PNG)、3D モデル (GLB)、動画 (音声付きの MP4) の 3 つを順に確認し、
最後に、看板の文字をデータとして持たせた看板アイテムを渡して、手持ちの枠の中の見た目がその画像になることを確認します。

- **OK**: 3 つとも正しい向きで表示され、看板そのものは隠れていて、看板アイテムも画像の見た目になり、
  Mod を入れていない状態と比べて新しい警告やエラーが出なかった
- **要確認**: 動作はしたが、新しい警告が出たか、表示を確認できなかった
- **失敗**: 動作しなかった
- **対象外**: Mod を入れていない状態でもゲームが起動しないか、サーバーに入れなかった (ローダーや影 Mod 側の問題)
- 空欄: そのローダーはこのバージョンに対応していない
- **未確認**: 対応しているが、配布するファイルの現在の内容ではまだ確認していない

Mod のバージョン: 4.0.0 / 作成日: 2026-10-15

| Minecraft | Fabric | Forge | NeoForge | LiteLoader |
| --- | --- | --- | --- | --- |
| 26.3 | OK | 要確認 | OK |  |
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
| 1.20.3 | OK | 対象外 | OK |  |
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

合計: OK 136、要確認 1、対象外 1

## 影 Mod を入れた状態

影 Mod (シェーダーを使えるようにする Mod) とシェーダーパックを入れて、同じ確認を行った結果です。
マスの中は「影 Mod + シェーダーパック: 結果」です。
Iris は Sodium と、Oculus は Rubidium または Embeddium と一緒に入れています。空欄は、確認していない組み合わせです。
シェーダーは絵にも影や空の色を付けるので、色そのものではなく、絵が正しい場所に正しい向きで写っていることを確認しています。

次の組み合わせは、この Mod を入れなくても影 Mod が動かないため、確認の対象から外しています。

- Forge 1.21.1 + OptiFine 1.21.1 HD U J1: OptiFine の読み込み中にゲームが終了する
- Forge 1.14.2 + OptiFine 1.14.2 HD U F1: ゲームが起動しない
- OptiFine 1.8.9 + MakeUp Ultra Fast: 画面が真っ黒になる

| Minecraft | Fabric | Forge | NeoForge | LiteLoader |
| --- | --- | --- | --- | --- |
| 26.3 | Iris + BSL: OK |  | Iris + BSL: 対象外 |  |
| 26.2 | Iris + BSL: OK |  | Iris + BSL: OK |  |
| 26.1.2 | Iris + BSL: OK<br>Iris + Complementary Reimagined: OK |  | Iris + BSL: OK |  |
| 26.1.1 | Iris + BSL: 対象外 |  | Iris + BSL: 対象外 |  |
| 26.1 | Iris + BSL: 対象外 |  | Iris + BSL: 対象外 |  |
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
| 1.21.1 | Iris + BSL: OK |  | Iris + BSL: OK<br>Iris + Sildur's Vibrant: OK |  |
| 1.21 | Iris + BSL: OK |  | Iris + BSL: 対象外 |  |
| 1.20.6 | Iris + BSL: OK |  |  |  |
| 1.20.5 | Iris + BSL: 対象外 |  |  |  |
| 1.20.4 | Iris + BSL: OK |  |  |  |
| 1.20.3 | Iris + BSL: OK |  |  |  |
| 1.20.2 | Iris + BSL: 対象外 |  |  |  |
| 1.20.1 | Iris + BSL: OK<br>Iris + MakeUp Ultra Fast: OK | Oculus + BSL: OK<br>Oculus + Complementary Reimagined: OK<br>OptiFine + BSL: OK |  |  |
| 1.20 | Iris + BSL: 対象外 | Oculus + BSL: OK |  |  |
| 1.19.4 | Iris + BSL: OK | Oculus + BSL: OK |  |  |
| 1.19.3 | Iris + BSL: OK |  |  |  |
| 1.19.2 | Iris + BSL: OK | Oculus + BSL: OK |  |  |
| 1.19.1 | Iris + BSL: OK | Oculus + BSL: OK |  |  |
| 1.19 | Iris + BSL: OK | Oculus + BSL: OK |  |  |
| 1.18.2 | Iris + BSL: OK | Oculus + BSL: OK<br>OptiFine + BSL: OK |  |  |
| 1.18.1 | Iris + BSL: OK |  |  |  |
| 1.18 | Iris + BSL: OK |  |  |  |
| 1.17.1 | Iris + BSL: OK |  |  |  |
| 1.17 | Iris + BSL: 要確認 |  |  |  |
| 1.16.5 | Iris + BSL: OK<br>Iris + Sildur's Vibrant: OK | Oculus + BSL: OK<br>OptiFine + BSL: OK |  |  |
| 1.16.4 |  | OptiFine + BSL: OK |  |  |
| 1.16.3 |  | OptiFine + BSL: OK |  |  |
| 1.16.2 |  | OptiFine + BSL: OK |  |  |
| 1.16.1 |  | OptiFine + BSL: OK |  |  |
| 1.15.2 |  | OptiFine + BSL: OK |  |  |
| 1.14.4 |  | OptiFine + BSL: OK |  |  |
| 1.14.3 |  | OptiFine + BSL: 失敗 |  |  |
| 1.14.2 |  | OptiFine + BSL: 対象外 |  |  |
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
