# 構造物の表示に使う表の作り方

構造物のファイル (`.schem` `.litematic` `.nbt` `.mcstructure` `.schematic`) の表示には、次の表を使います。
どれも `core/src/main/resources/marumasa_sign/structure` に gzip で圧縮して置いてあり、ビルドには作り直す必要はありません。
表示に使う Minecraft の版 (`GameAssets.VERSION`、今は 26.3) を変えるときに作り直します。

| ファイル | 内容 | 作るもの |
| --- | --- | --- |
| `blocks.txt.gz` | ブロックの性質 (性質の名前と値、既定の状態、面の隠れ方、衝突の形、光、液体、植物の位置のずれ) | `BlockDataDump.java` |
| `entity_models.txt.gz` | ゲームのコードで形が決まっているもの (チェスト、旗、頭、ベル、飾り壺、本、コンジット、銅のゴーレムの像、防具立てと防具) の形 | `EntityModelDump.java` |
| `legacy.txt.gz` | 1.12 までのブロックの番号から今のブロックへの対応 | `gen_tables.py` |
| `bedrock.txt.gz` | 統合版のブロックから Java 版のブロックへの対応 | `gen_tables.py` |

## BlockDataDump / EntityModelDump

Minecraft のクライアントのコードを動かして表を書き出します。Java 25 以降と、その版のクライアントの jar とライブラリが必要です。
次の例では、実機確認 (`tools/verify`) がダウンロードしたものを使います。

```powershell
# クラスパス (クライアントの jar とライブラリ)
$main = "$HOME\.marumasign-verify\main"
$json = Get-Content "$main\versions\26.3\26.3.json" | ConvertFrom-Json
$libs = $json.libraries | Where-Object { $_.downloads.artifact -and -not $_.rules } | ForEach-Object { "$main\libraries\" + $_.downloads.artifact.path }
$cp = ($libs + "$main\versions\26.3\26.3.jar") -join ";"
# コンパイルして実行する (作業用のフォルダで実行する。Minecraft がログのフォルダなどを作るため)
javac -nowarn -cp $cp -d build\blockdata tools\blockdata\BlockDataDump.java tools\blockdata\EntityModelDump.java
Push-Location build\blockdata
java -cp "$cp;." BlockDataDump ..\..\core\src\main\resources\marumasa_sign\structure\blocks.txt.gz
java -cp "$cp;." EntityModelDump ..\..\core\src\main\resources\marumasa_sign\structure\entity_models.txt.gz
Pop-Location
```

## gen_tables.py

[minecraft-data](https://github.com/PrismarineJS/minecraft-data) (MIT) から対応表を取得して書き出します。

```bash
python tools/blockdata/gen_tables.py
```

## 確かめ方

```bash
# 確認用の構造物 (すべてのブロック、特別な形のブロックとエンティティ) を作る
./gradlew -PmarumaOnly=none :core:testClasses
java -cp "core/build/classes/java/main;core/build/classes/java/test;core/src/main/resources" marumasa.marumasa_sign.core.structure.StructureShowcase build/showcase
# PNG に描く
./gradlew -PmarumaOnly=none :core:structureCheck -PstructureArgs="build/structure ../build/showcase"
```

`structureCheck` は、モデルが見つからなかったブロックと、見つからなかったテクスチャを最後に表示します。
