"""影 Mod (シェーダーを使えるようにする Mod) を入れた状態で確認するための準備。

ローダーと Minecraft のバージョンに合う影 Mod と、それが必要とする Mod、シェーダーパックを取得して、
起動フォルダに置き、シェーダーを有効にする設定を書く。

- Fabric: Iris (と Sodium)
- NeoForge: Iris (と Sodium)
- Forge 1.16.5 以降: Oculus (と Rubidium / Embeddium)
- Forge 1.7.10 - 1.15.2: OptiFine
- LiteLoader: OptiFine (Mod としてではなく、起動用のプロファイルに組み込む)
"""

import json
import re
import shutil
import urllib.parse
import urllib.request
import zipfile
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


def numbers(text):
    """バージョンの文字列の先頭にある数字の並び。比較に使う。"""
    match = re.match(r"(?:mc[\d.]+-)?v?(\d+(?:\.\d+)*)", text.strip())
    return tuple(int(part) for part in match.group(1).split(".")) if match else ()


def satisfies(version, spec):
    """Fabric の書き方 (">=0.6.9" や "0.5.x"、"0.5.9 || 0.5.11" など) で指定された条件に合うかどうか。"""
    if "||" in spec:
        # どれか 1 つに合えばよい
        return any(satisfies(version, alternative) for alternative in spec.split("||"))
    for part in spec.split():
        operator = re.match(r"[<>=~^]*", part).group(0)
        wanted = part[len(operator):]
        if wanted in ("*", ""):
            continue
        prefix = numbers(wanted.replace(".x", "").replace(".*", ""))
        if "x" in wanted or "*" in wanted or operator in ("~", "^"):
            # 先頭の数字が同じで、指定以上のもの
            fixed = prefix[:-1] if operator in ("~", "^") and len(prefix) > 1 else prefix
            if version[:len(fixed)] != fixed or (operator in ("~", "^") and version < prefix):
                return False
        elif operator == ">=" and version < prefix or operator == ">" and version <= prefix:
            return False
        elif operator == "<=" and version > prefix or operator == "<" and version >= prefix:
            return False
        elif operator in ("", "=") and version[:len(prefix)] != prefix:
            return False
    return True


def in_range(version, spec):
    """Forge / NeoForge の書き方 ("[0.3.1,)" など) で指定された範囲に入るかどうか。"""
    match = re.match(r"\s*([\[\(])([^,\]\)]*),?([^\]\)]*)([\]\)])\s*$", spec)
    if not match:
        prefix = numbers(spec)
        return not prefix or version[:len(prefix)] == prefix
    low, high = numbers(match.group(2)), numbers(match.group(3))
    if "," not in spec:
        return version[:len(low)] == low
    if low and (version < low or (match.group(1) == "(" and version == low)):
        return False
    if high and (version > high or (match.group(4) == ")" and version >= high)):
        return False
    return True


def declared_requirements(jar):
    """Mod の jar が、必要とする Mod について指定しているバージョンの条件。{Mod の ID: 判定する関数}

    1 つの jar に Fabric 用と NeoForge 用の指定が両方あるときは、両方に合うものだけを受け付ける
    (NeoForge 26.1 向けの Iris 1.11.3 は、mods.toml では Sodium 0.6 以上としているが、fabric.mod.json の 0.9.x が正しく、
    Sodium 0.8.9 と組み合わせると起動中に落ちる)。
    """
    found = {}

    def add(mod, accepts):
        previous = found.get(mod)
        found[mod] = accepts if previous is None else (lambda version: previous(version) and accepts(version))

    try:
        with zipfile.ZipFile(jar) as archive:
            names = set(archive.namelist())
            if "fabric.mod.json" in names:
                depends = json.loads(archive.read("fabric.mod.json").decode("utf-8")).get("depends", {})
                for mod, value in depends.items():
                    specs = [str(item) for item in (value if isinstance(value, list) else [value])]
                    add(mod, lambda version, specs=specs: any(satisfies(version, spec) for spec in specs))
            for name in ("META-INF/neoforge.mods.toml", "META-INF/mods.toml"):
                if name in names:
                    text = archive.read(name).decode("utf-8", errors="replace")
                    for mod, spec in re.findall(r'modId\s*=\s*"([^"]+)"[^\[]*?versionRange\s*=\s*"([^"]*)"', text):
                        add(mod, lambda version, spec=spec: in_range(version, spec))
    except (OSError, ValueError, zipfile.BadZipFile):
        pass
    return found


def declared_ranges(jar):
    """jar (と、その中に入っている jar) の mods.toml に書かれた、Minecraft と Forge / NeoForge の版の範囲。[(ID, 範囲)]

    Minecraft の範囲は、中に入っている jar のものだけを返す。外側の jar の Minecraft の範囲は、
    NeoForge が実際には確かめないことがある (NeoForge 1.21.1 向けの Iris 1.8.12 は「[1.21,1.21.1)」と書いているが、
    1.21.1 で読み込まれて動く) ので、Modrinth の対象バージョンの情報に任せる。
    """
    import io

    found = []

    def scan(archive, nested):
        names = archive.namelist()
        for name in ("META-INF/neoforge.mods.toml", "META-INF/mods.toml"):
            if name in names:
                text = archive.read(name).decode("utf-8", errors="replace")
                for mod_id, spec in re.findall(r'modId\s*=\s*"(minecraft|neoforge|forge)"[^\[]*?versionRange\s*=\s*"([^"]*)"',
                                               text):
                    if mod_id != "minecraft" or nested:
                        found.append((mod_id, spec))
        for name in names:
            if name.startswith("META-INF/jarjar/") and name.endswith(".jar"):
                with zipfile.ZipFile(io.BytesIO(archive.read(name))) as inner:
                    scan(inner, True)

    try:
        with zipfile.ZipFile(jar) as archive:
            scan(archive, False)
    except (OSError, zipfile.BadZipFile):
        pass
    return found


def fits(jar, minecraft, loader_version):
    """jar (と中の jar) が、この Minecraft と Forge / NeoForge の版で読み込まれるかどうか。

    Modrinth では対象のバージョン向けとされていても、jar の中の指定では動かないことがある
    (NeoForge 1.21 向けの Iris が必要とする Sodium は、中に入っている Fabric API の部品が 1.21.1 以上を指定している。
    26.1 向けとされている Sodium 0.9.2 は、NeoForge 26.1.2.10 以上を指定している)。
    """
    # 1.20.1 の NeoForge は「1.20.1-47.1.106」のように、先頭に Minecraft のバージョンが付く
    version = loader_version[len(minecraft) + 1:] if loader_version.startswith(minecraft + "-") else loader_version
    for mod_id, spec in declared_ranges(jar):
        if not spec.strip().startswith(("[", "(")):
            # 括弧のない版 ("1.21.4") は、Maven の書き方では「勧める版」で、範囲を限らない
            continue
        target = minecraft if mod_id == "minecraft" else version
        if not in_range(numbers(target), spec):
            return False
    return True


def matching_version(project, loader, minecraft, requirements):
    """jar の中の指定に合う、最も新しい版。指定がないか、合うものがなければ None。"""
    slug = fetch_json(f"{MODRINTH}/project/{project}").get("slug", "")
    accepts = requirements.get(slug)
    if accepts is None:
        return None
    query = urllib.parse.urlencode({"loaders": json.dumps([loader]), "game_versions": json.dumps([minecraft])})
    versions = fetch_json(f"{MODRINTH}/project/{project}/version?{query}")
    matching = [version for version in versions if accepts(numbers(version.get("version_number", "")))]
    if not matching:
        # 指定された版が、このバージョンの Minecraft 向けとしては登録されていないことがある
        # (Sodium 0.5.3 は 1.20.1 向けとしてだけ登録されているが、1.20 用の Iris 1.6.11 はこれを必要とする)。
        # 同じ大きなバージョン (1.20 なら 1.20.x) 向けの版から探す
        family = ".".join(minecraft.split(".")[:2])
        query = urllib.parse.urlencode({"loaders": json.dumps([loader])})
        everything = fetch_json(f"{MODRINTH}/project/{project}/version?{query}")
        matching = [version for version in everything if accepts(numbers(version.get("version_number", "")))
                    and any(game == family or game.startswith(family + ".") for game in version.get("game_versions", []))]
        # 対象のバージョンに最も近い Minecraft 向けのものを先にする (1.20 なら 1.20.1 向けを 1.20.2 向けより先に)
        target = numbers(minecraft)
        matching.sort(key=lambda version: min(
            (abs((numbers(game) + (0, 0, 0))[2] - (target + (0, 0, 0))[2])
             for game in version.get("game_versions", []) if game == family or game.startswith(family + ".")),
            default=99))
    releases = [version for version in matching if version.get("version_type") == "release"]
    return (releases or matching or [None])[0]

def modrinth_files(project, loader, minecraft, seen, cache_dir, version=None, loader_version=""):
    """Mod と、それが必要とする Mod のファイル (名前, URL) を集める。

    loader_version (起動に使う Forge / NeoForge の版) を渡すと、jar の中の指定でその版では読み込まれない Mod は使わない。
    """
    if version is None:
        version = modrinth_version(project, loader, minecraft)
    if version is None:
        return None
    files = []
    primary = next((file for file in version["files"] if file.get("primary")), version["files"][0])
    files.append((primary["filename"], primary["url"]))
    # 必要とする Mod の版は、jar の中の指定に合わせる (最新版同士だと合わないことがある)
    download(primary["url"], cache_dir / primary["filename"])
    if loader_version and not fits(cache_dir / primary["filename"], minecraft, loader_version):
        return None
    requirements = declared_requirements(cache_dir / primary["filename"])
    # fabric.mod.json の depends だけを見る (mods.toml には、必須でない指定や、相性の悪い版の指定も同じ形で並ぶため)
    game = fabric_requirement(cache_dir / primary["filename"], "minecraft")
    if game is not None and not game(numbers(minecraft)):
        # Modrinth ではこのバージョン向けとされていても、jar の中の条件では動かない
        # (Sodium 0.9.2 は 26.1 向けとしても登録されているが、jar では 26.1.2 だけを指定している)
        return None
    for dependency in version.get("dependencies", []):
        target = dependency.get("project_id")
        if dependency.get("dependency_type") != "required" or not target or target in seen:
            continue
        seen.add(target)
        if dependency.get("version_id"):
            chosen = fetch_json(f"{MODRINTH}/version/{dependency['version_id']}")
            if minecraft not in chosen.get("game_versions", []) \
                    and fetch_json(f"{MODRINTH}/project/{target}").get("slug", "") not in requirements:
                # 指定された版が、このバージョンの Minecraft 向けでないことがある
                # (Oculus 1.19-1.2.5a は 1.19.2 向けの Rubidium 0.6.2b を指定していて、1.19 / 1.19.1 では Mod なしでも落ちる)。
                # jar の中に版の条件も書かれていなければ、このバージョン向けの最新版を使う
                chosen = modrinth_version(target, loader, minecraft) or chosen
            slug = fetch_json(f"{MODRINTH}/project/{target}").get("slug", "")
            if slug in requirements and not requirements[slug](numbers(chosen.get("version_number", ""))):
                # Modrinth で指定された版が、jar の中の条件に合わない
                # (Iris 1.6.11 は Modrinth では Sodium 0.5.7 を指定しているが、jar では 0.5.3 を必要とする)
                chosen = matching_version(target, loader, minecraft, requirements) or chosen
        else:
            chosen = matching_version(target, loader, minecraft, requirements)
        required = modrinth_files(target, loader, minecraft, seen, cache_dir, chosen, loader_version)
        if required is None:
            return None
        files += required
    # Modrinth の情報には書かれていないが、jar (fabric.mod.json) では必須になっている Mod も入れる
    # (Iris 1.6.14 for 1.20.2 は Modrinth では依存なしだが、Sodium 0.5.5 がないと起動しない)
    for mod_id in fabric_hard_dependencies(cache_dir / primary["filename"]):
        if mod_id in seen or mod_id in SYSTEM_MODS:
            continue
        try:
            project = fetch_json(f"{MODRINTH}/project/{mod_id}")
        except OSError:
            continue
        if project.get("id") in seen:
            continue
        seen.update({mod_id, project.get("id")})
        chosen = matching_version(mod_id, loader, minecraft, requirements)
        if chosen is None:
            continue
        required = modrinth_files(mod_id, loader, minecraft, seen, cache_dir, chosen, loader_version)
        if required:
            files += required
    return files


def newest_working(project, loader, minecraft, cache_dir, tries=8, loader_version=""):
    """必要な Mod も含めてこのバージョンで動く組み合わせが揃う、最も新しい版のファイル。なければ None。

    最新版が必要とする Mod が、このバージョン向けにはまだないことがある
    (Iris 1.11.4 は Sodium 0.9 を必要とするが、26.1 / 26.1.1 で動く Sodium は 0.8 まで)。そのときは古い版を試す。
    """
    query = urllib.parse.urlencode({"loaders": json.dumps([loader]), "game_versions": json.dumps([minecraft])})
    versions = fetch_json(f"{MODRINTH}/project/{project}/version?{query}")
    releases = [version for version in versions if version.get("version_type") == "release"]
    others = [version for version in versions if version.get("version_type") != "release"]
    for version in (releases + others)[:tries]:
        files = modrinth_files(project, loader, minecraft, {project}, cache_dir, version, loader_version)
        if (files and project == "iris" and mod_numbers(version.get("version_number", "")) >= (1, 6)
                and not includes_sodium(files, cache_dir)):
            # Iris は 1.6 から Sodium がないと動かないが、そのことが jar にも Modrinth にも書かれていない版がある
            # (Iris 1.7.0+1.20.6 は 1.20.5 向けとされているが、どちらにも Sodium の指定がない。
            # NeoForge 1.21 向けの Iris 1.8.0-beta.2 / beta.3 も書かれていない)。
            # その Iris が、名前の上でもこのバージョン向け (版の名前に "+1.21-" のように含む) なら、
            # その Iris が出た時点の最新の Sodium を合わせる。そうでなければ、その版は使わない
            # (1.6 より前の Iris は、Sodium がなくても動く)
            if not re.search(rf"(?<![\d.]){re.escape(minecraft)}(?![\d.])", version.get("version_number", "")):
                continue
            sodium = sodium_of_the_time(loader, version.get("game_versions") or [minecraft],
                                        version.get("date_published", ""))
            extra = sodium and modrinth_files("sodium", loader, minecraft, {project, "sodium"}, cache_dir, sodium,
                                              loader_version)
            if not extra:
                continue
            files = files + extra
        if files:
            return files
    return None


def sodium_of_the_time(loader, game_versions, published):
    """このローダーと、game_versions のどれか向けの Sodium のうち、published (Modrinth の公開日時) までに出た最新の版。
    なければ None。

    Iris と同じ範囲のバージョンで探す (NeoForge 1.21 で動く Sodium 0.6.0-beta.1 は、Modrinth では 1.21.1 向けとだけ
    書かれている)。実際にこのバージョンで読み込まれるかは、呼び出し側が jar の中の指定で確かめる
    """
    if not published:
        return None
    query = urllib.parse.urlencode({"loaders": json.dumps([loader]), "game_versions": json.dumps(game_versions)})
    versions = [version for version in fetch_json(f"{MODRINTH}/project/sodium/version?{query}")
                if version.get("date_published", "") <= published]
    return max(versions, key=lambda version: version["date_published"], default=None)


def mod_numbers(text):
    """Mod の版の数字の並び。先頭に付いている Minecraft のバージョン ("mc1.18.1-"、"1.17.x-") は除く。"""
    return numbers(re.sub(r"^(?:mc[\d.]+|[\d.]+\.x)-", "", text.strip()))


def includes_sodium(files, cache_dir):
    """Mod のファイルの中に Sodium があるか (別の jar として、または Iris の jar の中に jar かクラスとして入っているか)。"""
    if any("sodium" in name.lower() for name, _ in files):
        return True
    try:
        with zipfile.ZipFile(cache_dir / files[0][0]) as archive:
            return any(("sodium" in name.lower() and name.endswith(".jar"))
                       or name.startswith(("me/jellysquid/mods/sodium/", "net/caffeinemc/mods/sodium/"))
                       for name in archive.namelist())
    except (OSError, zipfile.BadZipFile):
        return False


# ローダーやゲーム本体など、Mod として入れるものではない ID
SYSTEM_MODS = {"minecraft", "java", "fabricloader", "fabric", "fabric-api", "fabric-api-base", "quilt_loader"}


def fabric_requirement(jar, mod_id):
    """fabric.mod.json の depends にある、指定した Mod の版の条件を判定する関数。書かれていなければ None。"""
    try:
        with zipfile.ZipFile(jar) as archive:
            if "fabric.mod.json" not in archive.namelist():
                return None
            value = json.loads(archive.read("fabric.mod.json").decode("utf-8")).get("depends", {}).get(mod_id)
    except (OSError, ValueError, zipfile.BadZipFile):
        return None
    if value is None:
        return None
    specs = [str(item) for item in (value if isinstance(value, list) else [value])]
    return lambda version: any(satisfies(version, spec) for spec in specs)


def fabric_hard_dependencies(jar):
    """fabric.mod.json の depends に書かれている Mod の ID。"""
    try:
        with zipfile.ZipFile(jar) as archive:
            if "fabric.mod.json" not in archive.namelist():
                return []
            return list(json.loads(archive.read("fabric.mod.json").decode("utf-8")).get("depends", {}))
    except (OSError, ValueError, zipfile.BadZipFile):
        return []


def optifine_file(minecraft, forge=False):
    """OptiFine。forge なら Forge と一緒に使えるもの。なければ None。"""
    try:
        entries = fetch_json(f"{OPTIFINE}/{minecraft}")
    except OSError:
        return None
    # 試験版でないもののうち、最も新しいもの
    stable = [entry for entry in entries if not entry.get("filename", "").startswith("preview")]
    if forge:
        # 配布元の情報で、対応する Forge が「N/A」の版は Forge では動かない
        # (1.14.2 / 1.14.3 の OptiFine はどれもそうで、Forge と一緒には起動しないか、Forge に読み込まれない)
        stable = [entry for entry in stable if entry.get("forge") and "N/A" not in entry["forge"]]
    if not stable:
        return None
    entry = stable[-1]
    return entry["filename"], f"{OPTIFINE}/{minecraft}/{entry['type']}/{entry['patch']}"


def pack_version(pack, loader, minecraft):
    """シェーダーパックの、指定したバージョン向けの最新版。その表記がなければ、パックの最新版。"""
    version = modrinth_version(pack, loader, minecraft)
    if version is None:
        # シェーダーパックは Minecraft のバージョンにあまり依存しないので、対応表にないだけのことが多い
        query = urllib.parse.urlencode({"loaders": json.dumps([loader])})
        versions = fetch_json(f"{MODRINTH}/project/{pack}/version?{query}")
        releases = [item for item in versions if item.get("version_type") == "release"]
        version = (releases or versions or [None])[0]
    return version


RENDER_STAGE = b"MC_RENDER_STAGE"


def jar_contains(jar, text):
    """jar (と、その中の jar) のクラスファイルに、text が含まれるかどうか。"""
    import io

    def search(archive):
        for name in archive.namelist():
            if name.endswith(".class") and text in archive.read(name):
                return True
            if name.endswith(".jar"):
                with zipfile.ZipFile(io.BytesIO(archive.read(name))) as inner:
                    if search(inner):
                        return True
        return False

    try:
        with zipfile.ZipFile(jar) as archive:
            return search(archive)
    except (OSError, zipfile.BadZipFile):
        return True


def pack_uses(path, text):
    """シェーダーパックのシェーダーのコードに、text が含まれるかどうか。"""
    with zipfile.ZipFile(path) as archive:
        return any(text in archive.read(name) for name in archive.namelist()
                   if name.endswith((".glsl", ".fsh", ".vsh", ".gsh", ".csh")))


def pack_without(pack, loader, text, cache_dir):
    """シェーダーパックの版のうち、シェーダーのコードに text を使っていない最も新しいもの。なければ None。"""
    query = urllib.parse.urlencode({"loaders": json.dumps([loader])})
    versions = fetch_json(f"{MODRINTH}/project/{pack}/version?{query}")
    for version in versions:
        filename, url = version["files"][0]["filename"], version["files"][0]["url"]
        download(url, cache_dir / filename)
        if not pack_uses(cache_dir / filename, text):
            return version
    return None


def variant(pack_slug=None, shader_mod=None):
    """既定と違う構成で確認するときに、結果の名前に付ける文字列。既定どおりなら空。"""
    parts = [shader_mod or "", "" if (pack_slug or SHADER_PACK) == SHADER_PACK else pack_slug]
    return "-".join(part for part in parts if part)


# 選ばれる版では動かないことを実際に起動して確かめ、代わりに使う版。{(ローダー, Minecraft のバージョン): {プロジェクト: 版}}
# jar の中の指定からは分からない (起動してから、Mod の書き換えの対象が見つからずに落ちる) ので、ここに書く
REPLACEMENTS = {
    # Sodium 0.9.2 は NeoForge 26.3.0.76 の LevelExtractor の書き換えに失敗して、起動中に落ちる。0.9.3-beta.1 は動く
    ("neoforge", "26.3"): {"sodium": "mc26.3-0.9.3-beta.1-neoforge"},
}


def replace_versions(mods, loader, minecraft):
    """REPLACEMENTS にある Mod のファイルを、指定した版のものに差し替える。"""
    for project, wanted in REPLACEMENTS.get((loader, minecraft), {}).items():
        version = next((item for item in fetch_json(f"{MODRINTH}/project/{project}/version")
                        if item.get("version_number") == wanted), None)
        if version is None:
            continue
        primary = next((file for file in version["files"] if file.get("primary")), version["files"][0])
        mods = [(primary["filename"], primary["url"]) if name.lower().startswith(project) else (name, url)
                for name, url in mods]
    return mods


def resolve(loader, minecraft, cache_dir, pack_slug=SHADER_PACK, shader_mod=None, loader_version=""):
    """影 Mod の構成を返す。この組み合わせで使える影 Mod がなければ None。

    shader_mod に "optifine" を指定すると、Forge 1.16.5 以降でも OptiFine を使う。
    loader_version は起動に使う Forge / NeoForge の版。渡すと、その版で読み込まれない Mod は選ばない。

    戻り値: {"name": 影 Mod の名前, "mods": [(ファイル名, URL)], "config": 設定ファイルの名前, "pack": (ファイル名, URL)}
    """
    library = None
    try:
        if shader_mod == "optifine" and loader not in ("forge", "liteloader"):
            return None
        if loader in ("fabric", "neoforge"):
            mods = newest_working("iris", loader, minecraft, cache_dir, loader_version=loader_version)
            name, config = "Iris", "config/iris.properties"
        elif loader == "forge" and version_key(minecraft) >= (1, 16, 5) and shader_mod != "optifine":
            mods = newest_working("oculus", loader, minecraft, cache_dir, loader_version=loader_version)
            name, config = "Oculus", "config/oculus.properties"
        elif loader == "forge":
            optifine = optifine_file(minecraft, forge=True)
            mods = [optifine] if optifine else None
            name, config = "OptiFine", "optionsshaders.txt"
        elif loader == "liteloader":
            # LiteLoader には OptiFine を Mod として読み込む仕組みがないので、起動用のプロファイルに組み込む
            optifine = optifine_file(minecraft)
            if optifine is None:
                return None
            download(optifine[1], cache_dir / optifine[0])
            mods, library = [], cache_dir / optifine[0]
            name, config = "OptiFine", "optionsshaders.txt"
        else:
            return None
        if not mods and library is None:
            return None
        if mods:
            mods = replace_versions(mods, loader, minecraft)
        pack_loader = "optifine" if name == "OptiFine" else "iris"
        pack = pack_version(pack_slug, pack_loader, minecraft)
        if mods and name in ("Iris", "Oculus"):
            # 古い Iris (1.17 / 1.18 向けの 1.1.x) は、描画の段階を表す定義 (MC_RENDER_STAGE_*) を持たない。
            # 新しいシェーダーパックはそれを使うので組み立てられず、シェーダーなしの描画に戻ってしまう。
            # そのときは、それを使っていない版のシェーダーパックにする
            shader_jar = cache_dir / mods[0][0]
            download(mods[0][1], shader_jar)
            if not jar_contains(shader_jar, RENDER_STAGE):
                pack = pack_without(pack_slug, pack_loader, RENDER_STAGE, cache_dir) or pack
        if pack is None:
            return None
        return {"name": name, "mods": mods, "config": config, "pack_slug": pack_slug, "library": library,
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
        # 一部の版 (1.8.8 など) は「高速レンダリング」が最初から有効で、そのままではシェーダーを読み込まない
        (work_dir / "optionsof.txt").write_text("ofFastRender:false\n")
    else:
        config.write_text(f"enableShaders=true\nshaderPack={pack_name}\n")


# 影 Mod がシェーダーパックを読み込めず、通常の描画に戻したことを示すログ。
# 古い Iris (1.17 / 1.18 向けの 1.1.x) は、新しい BSL を組み立てられずにこうなる
FALLBACK_PATTERNS = [re.compile(r"falling back to vanilla shaders")]


def fell_back(log_text):
    """影 Mod がシェーダーパックを読み込めずに、通常の描画に戻したかどうか。"""
    return any(pattern.search(log_text) for pattern in FALLBACK_PATTERNS)


def is_active(log_text):
    """ログから、シェーダーが実際に有効になったかどうかを判定する。"""
    return any(pattern.search(log_text) for pattern in ACTIVE_PATTERNS) and not fell_back(log_text)


if __name__ == "__main__":
    import sys

    # 引数: loader:version または loader:version:シェーダーパックの名前 または loader:version:シェーダーパックの名前:optifine
    for item in sys.argv[1:]:
        loader_name, minecraft_version, *rest = item.split(":")
        result = resolve(loader_name, minecraft_version, Path.home() / ".marumasign-verify" / "shadermods", *rest)
        if result is None:
            print(f"{item}: no shader mod")
        else:
            print(f"{item}: {result['name']} {[name for name, _ in result['mods']] or result['library'].name}"
                  f" + {result['pack'][0]}")
