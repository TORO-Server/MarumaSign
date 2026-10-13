"""古い Forge (Minecraft 1.14.2 / 1.14.3 用) を、ランチャー (portablemc) でインストールできるようにする。

    python forge_install_fix.py <新しめの Java 8 の java> <portablemc に渡す引数...>

そのままでは、インストールの後処理が次の 2 点で失敗する。

- ランチャーが使う古い Java (8u51) では、圧縮の結果が Forge の想定と違い、チェックサムが合わない
- 後処理の 1 つが、Minecraft 本体の jar の隣にあるバージョン情報を読むが、ランチャーが渡す jar の隣には
  Forge 用のバージョン情報があり、その書き方を読めない

後処理を起動するところだけを差し替えて、これらを避ける。
"""

import shutil
import subprocess
import sys
import types
from pathlib import Path

import portablemc.forge as forge
from portablemc.cli import main

JAVA = sys.argv[1]
real_run = subprocess.run


def run(args, *positional, **keywords):
    args = list(args)
    args[0] = JAVA
    if "--mc" in args and "DEOBF_REALMS" in args:
        # Forge 用のフォルダにある jar の代わりに、バニラのフォルダにある jar を渡す (中身は同じ)
        index = args.index("--mc") + 1
        forge_jar = Path(args[index])
        minecraft = forge_jar.parent.name.split("-")[1]
        vanilla_jar = forge_jar.parent.parent / minecraft / f"{minecraft}.jar"
        if not vanilla_jar.is_file():
            shutil.copyfile(forge_jar, vanilla_jar)
        args[index] = str(vanilla_jar)
    return real_run(args, *positional, **keywords)


shim = types.ModuleType("subprocess")
shim.__dict__.update(vars(subprocess))
shim.run = run
forge.subprocess = shim

if __name__ == "__main__":
    main(sys.argv[2:])
