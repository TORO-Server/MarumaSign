"""影 Mod (シェーダーを使えるようにする Mod) を入れた状態で確認するための準備。

ローダーと Minecraft のバージョンに合う影 Mod と、それが必要とする Mod、シェーダーパックを取得して、
起動フォルダに置き、シェーダーを有効にする設定を書く。

- Fabric: Iris (と Sodium)
- NeoForge: Iris (と Sodium)
- Forge 1.16.5 以降: Oculus (と Rubidium / Embeddium)
- Forge 1.7.10 - 1.12.2: OptiFine
"""

import json
import re
import shutil
import urllib.parse
import urllib.request
from pathlib import Path

MODRINTH = "https://api.modrinth.com/v2"
# OptiFine は公式サイトから機械的には取得できないため、ランチャーが使っているミラーから取得する
OPTIFINE = "https://bmclapi2.bangbang93.com/optifine"
USER_AGENT = "MarumaSign-verify/1.0 (local testing)"

# シェーダーパック。古い OptiFine から新しい Iris まで、幅広いバージョンで動くもの
SHADER_PACK = "bsl-shaders"

# シェーダーが有効になったことを示すログの行
ACTIVE_PATTERNS = [
    re.compile(r"Using shaderpack: "),               # Iris / Oculus
    re.compile(r"\[Shaders\] Loaded shaderpack: "),  # OptiFine
    re.compile(r"Loaded shaderpack: "),
]


def version_key(version):
    return tuple(int(part) for part in re.findall(r"\d+", version))


def fetch_json(url):
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def download(url, path):
    if path.is_file() and path.stat().st_size > 0:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    temporary = path.with_suffix(path.suffix + ".part")
    with urllib.request.urlopen(request, timeout=300) as response, temporary.open("wb") as file:
        shutil.copyfileobj(response, file)
    temporary.replace(path)


def modrinth_version(project, loader, minecraft):
    """プロジェクトの、指定したローダーとバージョン向けの最新版。なければ None。"""
    query = urllib.parse.urlencode({"loaders": json.dumps([loader]), "game_versions": json.dumps([minecraft])})
    versions = fetch_json(f"{MODRINTH}/project/{project}/version?{query}")
    if not versions:
        return None
    # 正式版があればそれを、なければ最新のものを使う
    releases = [version for version in versions if version.get("version_type") == "release"]
    return (releases or versions)[0]


def modrinth_files(project, loader, minecraft, seen):
    """Mod と、それが必要とする Mod のファイル (名前, URL) を集める。"""
    version = modrinth_version(project, loader, minecraft)
    if version is None:
        return None
    files = []
    primary = next((file for file in version["files"] if file.get("primary")), version["files"][0])
    files.append((primary["filename"], primary["url"]))
    for dependency in version.get("dependencies", []):
        target = dependency.get("project_id")
        if dependency.get("dependency_type") != "required" or not target or target in seen:
            continue
        seen.add(target)
        required = modrinth_files(target, loader, minecraft, seen)
        if required is None:
            return None
        files += required
    return files


def optifine_file(minecraft):
    """Forge と一緒に使える OptiFine。なければ None。"""
    try:
        entries = fetch_json(f"{OPTIFINE}/{minecraft}")
    except OSError:
        return None
    # 試験版でないもののうち、最も新しいもの
    stable = [entry for entry in entries if not entry.get("filename", "").startswith("preview")]
    if not stable:
        return None
    entry = stable[-1]
    return entry["filename"], f"{OPTIFINE}/{minecraft}/{entry['type']}/{entry['patch']}"


def resolve(loader, minecraft):
    """影 Mod の構成を返す。この組み合わせで使える影 Mod がなければ None。

    戻り値: {"name": 影 Mod の名前, "mods": [(ファイル名, URL)], "config": 設定ファイルの名前, "pack": (ファイル名, URL)}
    """
    try:
        if loader in ("fabric", "neoforge"):
            mods = modrinth_files("iris", loader, minecraft, {"iris"})
            name, config = "Iris", "config/iris.properties"
        elif loader == "forge" and version_key(minecraft) >= (1, 16, 5):
            mods = modrinth_files("oculus", loader, minecraft, {"oculus"})
            name, config = "Oculus", "config/oculus.properties"
        elif loader == "forge":
            optifine = optifine_file(minecraft)
            mods = [optifine] if optifine else None
            name, config = "OptiFine", "optionsshaders.txt"
        else:
            return None
        if not mods:
            return None
        pack = modrinth_version(SHADER_PACK, "optifine" if name == "OptiFine" else "iris", minecraft)
        if pack is None:
            return None
        return {"name": name, "mods": mods, "config": config,
                "pack": (pack["files"][0]["filename"], pack["files"][0]["url"])}
    except OSError:
        return None


def install(setup, cache_dir, work_dir):
    """影 Mod とシェーダーパックを起動フォルダに置き、シェーダーを有効にする。"""
    for filename, url in setup["mods"]:
        cached = cache_dir / filename
        download(url, cached)
        shutil.copy2(cached, work_dir / "mods" / filename)
    pack_name, pack_url = setup["pack"]
    cached = cache_dir / pack_name
    download(pack_url, cached)
    (work_dir / "shaderpacks").mkdir(exist_ok=True)
    shutil.copy2(cached, work_dir / "shaderpacks" / pack_name)

    config = work_dir / setup["config"]
    config.parent.mkdir(parents=True, exist_ok=True)
    if setup["name"] == "OptiFine":
        config.write_text(f"shaderPack={pack_name}\n")
    else:
        config.write_text(f"enableShaders=true\nshaderPack={pack_name}\n")


def is_active(log_text):
    """ログから、シェーダーが実際に有効になったかどうかを判定する。"""
    return any(pattern.search(log_text) for pattern in ACTIVE_PATTERNS)


if __name__ == "__main__":
    import sys

    for item in sys.argv[1:]:
        loader_name, minecraft_version = item.split(":")
        result = resolve(loader_name, minecraft_version)
        if result is None:
            print(f"{item}: no shader mod")
        else:
            print(f"{item}: {result['name']} {[name for name, _ in result['mods']]} + {result['pack'][0]}")
