## 開発環境メモ

複数人で環境を共有できるよう、pythonのvenv機能を利用してライブラリパッケージやその他の環境を再現できるようにしています。

venv機能はpython3では標準で使えます。

### 構築

venvを構築してアクティブにする、
`requirements.txt`からvenvへライブラリをインストール

```bat
python -m venv
.\venv\Scripts\activate
pip install -r requirements.txt
```

### 構築されている仮想環境をアクティブにする

コマンドプロンプトやPowerShellで仮想環境を開く

```bat
.\venv\Scripts\activate
```

### ビルドする

pyinstallerを使用して`/dist`フォルダに出力する

```bat
pyinstaller marumasign_autotyper.py --noconfirm --onefile --onedir --noconsole --clean --icon=icon.ico
```

### 仮想環境に新しいパッケージを追加する

ライブラリを追加し、共有できるように`requirements.txt`に出力する

```bat
pip install [ライブラリ]
pip freeze > requirements.txt
```

#### (開いている仮想環境を無効化する)

仮想環境を開いている状態から抜け出す

```bat
deactive
```
