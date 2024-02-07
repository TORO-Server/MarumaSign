# MarumaSign

看板に画像を表示する Minecraft の Mod です。

解説動画:
<https://www.youtube.com/watch?v=N-Q-Vtz7m-0>

現在開発中です。

## 使用方法

1. 看板に表示したい画像のURLを取得します。
1. [専用のコマンドジェネレータ](https://toro-server.github.io/marumasign-cmd-generator/)に1で取得したURLを入力し、コマンドを取得します。
1. 2で生成したコマンドをゲーム内で入力します。
1. 看板を設置して完了です。

### 対応している拡張子

- `.gif`
- `.png`

### 看板のフォーマット

`[URL]|[X軸位置]|[Y軸位置]|[Z軸位置]|[高さ]|[横幅]|[X軸回転]|[Y軸回転]|[Z軸回転]`

## 注意事項

- URLの長さが`https://` の部分を含めて44文字以上だと看板にURLが入り切らないため動作しません。

## 前提環境

- Minecraft **1.20.4**
- [Fabric Loader](https://fabricmc.net/use/installer/) 0.15.2 以降
- [Fabric API](https://www.curseforge.com/minecraft/mc-mods/fabric-api) 0.91.2 以降

## テクスチャ作成者

### [Micni43](https://github.com/Micni43)

assets/marumasa_sign/textures/misc/error.png
