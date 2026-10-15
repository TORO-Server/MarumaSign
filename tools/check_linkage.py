"""配布する jar が、対応しているすべての Minecraft のバージョンで正しくつながるかを、起動せずに確かめる。

    python tools/check_linkage.py [--jar dist/build/libs/MarumaSign-x.y.z.jar] [--only fabric:1.15 ...]

1 つのビルド対象 (例: 1.15.2 向け) を、近いバージョン (1.15 や 1.15.1) でも使っている。
近いバージョンでも名前は同じだが、引数や戻り値の型が違うメソッドがあると、そこを通ったときに初めて落ちる。
そこで、jar の中の各バージョン向けのクラスが参照している Minecraft のクラス・メソッド・フィールドを取り出し、
「ビルドしたバージョンにはあるのに、実際に使われるバージョンにはない」ものを探す。
オーバーライドしているメソッドが、そのバージョンでは別の形になっていないかも確かめる。

Minecraft 本体は、実行時と同じ名前で調べる。

- Fabric (26.1 より前): 中間名。Mojang の配布する本体に、Fabric の対応表を当てる
- Forge 1.20.4 以前 / NeoForge 1.20.1: SRG 名。Forge のインストールでできた jar を使う
  (実機での確認ツールが一度インストールしたものが必要。まだないバージョンは飛ばして、最後に一覧を出す)
- Forge 1.20.6 以降 / NeoForge 1.20.2 以降: 公式の名前。Mojang の配布する本体に、公式の対応表を当てる
- 26.1 以降: 本体が最初から公式の名前なので、そのまま使う
"""

import argparse
import io
import json
import pickle
import re
import struct
import sys
import urllib.request
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
ROOT = Path.home() / ".marumasign-verify"
CACHE = ROOT / "linkage"
MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
INTERMEDIARY = "https://maven.fabricmc.net/net/fabricmc/intermediary/{0}/intermediary-{0}-v2.jar"
USER_AGENT = "MarumaSign-verify/1.0 (local testing)"
PACKAGE = "marumasa/marumasa_sign/"

ACC_PRIVATE, ACC_STATIC, ACC_ABSTRACT = 0x0002, 0x0008, 0x0400
OBJECT_METHODS = {"hashCode", "equals", "toString", "getClass", "clone", "finalize", "notify", "notifyAll", "wait"}


def version_key(version):
    return tuple(int(part) for part in re.findall(r"\d+", version))


def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=300) as response:
        return response.read()


def cached(path, url):
    if not path.is_file():
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_suffix(path.suffix + ".part")
        temporary.write_bytes(fetch(url))
        temporary.replace(path)
    return path


# ---------- クラスファイル ----------

class ClassInfo:
    __slots__ = ("name", "parent", "interfaces", "access", "fields", "methods", "references", "classes")

    def __init__(self):
        self.fields = {}      # (名前, 型) -> アクセスフラグ
        self.methods = {}     # (名前, 型) -> アクセスフラグ
        self.references = []  # (種類, 持ち主, 名前, 型)
        self.classes = set()  # 参照しているクラス


def parse_class(data, with_references=False):
    count = struct.unpack_from(">H", data, 8)[0]
    pool = [None] * count
    position = 10
    index = 1
    while index < count:
        tag = data[position]
        if tag == 1:
            length = struct.unpack_from(">H", data, position + 1)[0]
            pool[index] = data[position + 3:position + 3 + length].decode("utf-8", "replace")
            position += 3 + length
        elif tag in (3, 4):
            position += 5
        elif tag in (5, 6):
            position += 9
            index += 1
        elif tag in (7, 8, 16, 19, 20):
            pool[index] = (tag, struct.unpack_from(">H", data, position + 1)[0])
            position += 3
        elif tag in (9, 10, 11, 12, 17, 18):
            pool[index] = (tag,) + struct.unpack_from(">HH", data, position + 1)
            position += 5
        elif tag == 15:
            position += 4
        else:
            raise ValueError(f"unknown constant tag {tag}")
        index += 1

    def class_name(number):
        return pool[pool[number][1]] if number else None

    info = ClassInfo()
    info.access, this_class, super_class, interface_count = struct.unpack_from(">HHHH", data, position)
    position += 8
    info.name = class_name(this_class)
    info.parent = class_name(super_class)
    info.interfaces = [class_name(number) for number in struct.unpack_from(f">{interface_count}H", data, position)]
    position += 2 * interface_count
    for members in (info.fields, info.methods):
        member_count = struct.unpack_from(">H", data, position)[0]
        position += 2
        for _ in range(member_count):
            access, name, descriptor, attribute_count = struct.unpack_from(">HHHH", data, position)
            position += 8
            for _ in range(attribute_count):
                position += 6 + struct.unpack_from(">I", data, position + 2)[0]
            members[(pool[name], pool[descriptor])] = access
    if with_references:
        for entry in pool:
            if not isinstance(entry, tuple):
                continue
            if entry[0] == 7:
                name = pool[entry[1]]
                while name.startswith("["):
                    name = name[1:]
                if name.startswith("L") and name.endswith(";"):
                    name = name[1:-1]
                if len(name) > 1:
                    info.classes.add(name)
            elif entry[0] in (9, 10, 11):
                owner = pool[pool[entry[1]][1]]
                name_and_type = pool[entry[2]]
                if not owner.startswith("["):
                    info.references.append(("field" if entry[0] == 9 else "method", owner,
                                            pool[name_and_type[1]], pool[name_and_type[2]]))
    return info


def read_classes(jar_bytes_or_path, prefix="", with_references=False):
    classes = {}
    with zipfile.ZipFile(jar_bytes_or_path) as archive:
        for name in archive.namelist():
            if name.endswith(".class") and name.startswith(prefix) and not name.startswith("META-INF/"):
                try:
                    info = parse_class(archive.read(name), with_references)
                except (ValueError, struct.error, IndexError):
                    continue
                classes[info.name] = info
    return classes


# ---------- Minecraft 本体を、実行時の名前で見たもの ----------

def map_descriptor(descriptor, class_map):
    return re.sub(r"L([^;]+);", lambda match: "L" + class_map.get(match.group(1), match.group(1)) + ";", descriptor)


def remap_view(classes, class_map, method_map, field_map):
    """難読化された名前のクラスの一覧を、対応表で実行時の名前に直す。"""

    def method_name(owner, name, descriptor, seen):
        # 親から受け継いだメソッドは、親の側にだけ対応が書かれていることがある
        if owner in seen or owner not in classes:
            return None
        seen.add(owner)
        mapped = method_map.get((owner, name, descriptor))
        if mapped is not None:
            return mapped
        info = classes[owner]
        for parent in [info.parent] + info.interfaces:
            if parent:
                mapped = method_name(parent, name, descriptor, seen)
                if mapped is not None:
                    return mapped
        return None

    view = {}
    for obfuscated, info in classes.items():
        mapped = ClassInfo()
        mapped.name = class_map.get(obfuscated, obfuscated)
        mapped.access = info.access
        mapped.parent = class_map.get(info.parent, info.parent) if info.parent else None
        mapped.interfaces = [class_map.get(name, name) for name in info.interfaces]
        for (name, descriptor), access in info.methods.items():
            new_name = name if name.startswith("<") else (method_name(obfuscated, name, descriptor, set()) or name)
            mapped.methods[(new_name, map_descriptor(descriptor, class_map))] = access
        for (name, descriptor), access in info.fields.items():
            new_name = field_map.get((obfuscated, name, descriptor)) or field_map.get((obfuscated, name)) or name
            mapped.fields[(new_name, map_descriptor(descriptor, class_map))] = access
        view[mapped.name] = mapped
    return view


def version_json(minecraft):
    path = CACHE / minecraft / "version.json"
    if not path.is_file():
        manifest = json.loads(cached(CACHE / "manifest.json", MANIFEST).read_bytes())
        entry = next((item for item in manifest["versions"] if item["id"] == minecraft), None)
        if entry is None:
            # 一覧が古い
            (CACHE / "manifest.json").unlink()
            manifest = json.loads(cached(CACHE / "manifest.json", MANIFEST).read_bytes())
            entry = next(item for item in manifest["versions"] if item["id"] == minecraft)
        cached(path, entry["url"])
    return json.loads(path.read_bytes())


def client_jar(minecraft):
    return cached(CACHE / minecraft / "client.jar", version_json(minecraft)["downloads"]["client"]["url"])


def intermediary_view(minecraft):
    classes = read_classes(client_jar(minecraft))
    jar = cached(CACHE / minecraft / "intermediary.jar", INTERMEDIARY.format(minecraft))
    with zipfile.ZipFile(jar) as archive:
        lines = archive.read("mappings/mappings.tiny").decode("utf-8").splitlines()
    class_map, method_map, field_map = {}, {}, {}
    owner = None
    for line in lines[1:]:
        parts = line.split("\t")
        if parts[0] == "c":
            owner = parts[1]
            class_map[owner] = parts[2]
        elif parts[0] == "" and len(parts) >= 5 and parts[1] == "m":
            method_map[(owner, parts[3], parts[2])] = parts[4]
        elif parts[0] == "" and len(parts) >= 5 and parts[1] == "f":
            field_map[(owner, parts[3], parts[2])] = parts[4]
    return remap_view(classes, class_map, method_map, field_map)


PRIMITIVES = {"void": "V", "boolean": "Z", "byte": "B", "char": "C", "short": "S", "int": "I", "long": "J",
              "float": "F", "double": "D"}


def java_type(name, to_obfuscated):
    dimensions = name.count("[]")
    name = name.replace("[]", "")
    if name in PRIMITIVES:
        base = PRIMITIVES[name]
    else:
        internal = name.replace(".", "/")
        base = "L" + to_obfuscated.get(internal, internal) + ";"
    return "[" * dimensions + base


def official_maps(minecraft):
    """公式の対応表。(クラス, メソッド, フィールド) の、難読化された名前から公式の名前への対応。なければ None。"""
    downloads = version_json(minecraft)["downloads"]
    if "client_mappings" not in downloads:
        return None
    text = cached(CACHE / minecraft / "client.txt", downloads["client_mappings"]["url"]).read_text(encoding="utf-8")
    to_obfuscated = {}
    for line in text.splitlines():
        if line and not line.startswith((" ", "#")):
            named, obfuscated = line.rstrip(":").split(" -> ")
            to_obfuscated[named.replace(".", "/")] = obfuscated.replace(".", "/")
    class_map = {obfuscated: named for named, obfuscated in to_obfuscated.items()}
    method_map, field_map = {}, {}
    owner = None
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        if not line.startswith(" "):
            owner = to_obfuscated[line.split(" -> ")[0].replace(".", "/")]
            continue
        left, obfuscated = line.strip().split(" -> ")
        kind, signature = left.rsplit(" ", 1)
        kind = kind.split(":")[-1]
        if "(" in signature:
            name, arguments = signature.rstrip(")").split("(")
            descriptor = "(" + "".join(java_type(item, to_obfuscated) for item in arguments.split(",") if item) + ")" \
                         + java_type(kind, to_obfuscated)
            method_map[(owner, obfuscated, descriptor)] = name
        else:
            field_map[(owner, obfuscated, java_type(kind, to_obfuscated))] = signature
    return class_map, method_map, field_map


def official_view(minecraft):
    classes = read_classes(client_jar(minecraft))
    maps = official_maps(minecraft)
    # 対応表がないのは、本体が最初から公式の名前になっているバージョン
    return classes if maps is None else remap_view(classes, *maps)


MCP_CONFIG = "https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp_config/"


def srg_view(minecraft, installed=False):
    """SRG 名の本体。Forge の対応表から組み立てる。

    installed が True なら、Forge のインストールでできた jar をそのまま読む (組み立てた結果の確認用)。
    """
    if installed:
        base = ROOT / "main" / "libraries" / "net" / "minecraft" / "client"
        jars = sorted(base.glob(f"{minecraft}-*/client-{minecraft}-*-srg.jar"))
        return read_classes(jars[-1]) if jars else None
    metadata = cached(CACHE / "mcp_config.xml", MCP_CONFIG + "maven-metadata.xml").read_text(encoding="utf-8")
    versions = [item for item in re.findall(r"<version>([^<]+)</version>", metadata)
                if item == minecraft or item.startswith(minecraft + "-")]
    lines = None
    for version in reversed(versions):
        # 新しい版には、SRG 名ではなく公式の名前が入っているものがある。SRG 名が入っている最も新しい版を使う
        archive_path = cached(CACHE / minecraft / f"mcp_config-{version}.zip",
                              f"{MCP_CONFIG}{version}/mcp_config-{version}.zip")
        with zipfile.ZipFile(archive_path) as archive:
            candidate = archive.read("config/joined.tsrg").decode("utf-8")
        if re.search(r" (func_\d+_\w+|m_\d+_)\s", candidate):
            lines = candidate.splitlines()
            break
    if lines is None:
        return None
    class_map, method_map, field_map = {}, {}, {}
    owner = None
    for line in lines:
        if line.startswith("tsrg2") or line.startswith("\t\t") or not line.strip():
            continue
        parts = line.strip().split(" ")
        if not line.startswith("\t"):
            owner = parts[0]
            class_map[owner] = parts[1]
        elif parts[1].startswith("("):
            method_map[(owner, parts[0], parts[1])] = parts[2]
        else:
            field_map[(owner, parts[0])] = parts[1]
    if version_key(minecraft) >= (1, 17):
        # 1.17 以降は、クラスの名前だけ公式のものを使う
        class_map = official_maps(minecraft)[0]
    return remap_view(read_classes(client_jar(minecraft)), class_map, method_map, field_map)


def runtime_names(loader, minecraft):
    key = version_key(minecraft)
    if key >= (26, 1):
        return "official"
    if loader == "fabric":
        return "intermediary"
    if loader == "forge":
        if key < (1, 13):
            # 1.12.2 以前は、バージョンごとに専用のビルドがあるので、突き合わせる相手がない
            return None
        return "srg" if key <= (1, 20, 4) else "official"
    if loader == "neoforge":
        return "srg" if key <= (1, 20, 1) else "official"
    return None


VIEWS = {}


def view_of(loader, minecraft):
    names = runtime_names(loader, minecraft)
    if names is None:
        return None
    key = (names, minecraft)
    if key not in VIEWS:
        path = CACHE / minecraft / f"view-{names}.pickle"
        if path.is_file():
            VIEWS[key] = pickle.loads(path.read_bytes())
        else:
            view = {"intermediary": intermediary_view, "official": official_view, "srg": srg_view}[names](minecraft)
            if view is not None:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(pickle.dumps(view))
            VIEWS[key] = view
    return VIEWS[key]


# ---------- 突き合わせ ----------

def find_member(view, owner, kind, name, descriptor, extra=None):
    """メンバーが見つかれば True、ないと言い切れれば False、本体の外のクラスにたどり着いたら None。"""
    pending, seen, unknown = [owner], set(), False
    while pending:
        current = pending.pop()
        if current in seen or current is None:
            continue
        seen.add(current)
        info = view.get(current) or (extra or {}).get(current)
        if info is None:
            if current == "java/lang/Object":
                if kind == "method" and name in OBJECT_METHODS:
                    return True
            else:
                unknown = True
            continue
        if (name, descriptor) in (info.methods if kind == "method" else info.fields):
            return True
        pending += [info.parent] + info.interfaces
    return None if unknown else False


def minecraft_ancestors(view, own, name):
    """自分のクラスの親をたどって、Minecraft 本体のクラスに行き着いたものを返す。"""
    found, pending, seen = [], [name], set()
    while pending:
        current = pending.pop()
        if current in seen or current is None:
            continue
        seen.add(current)
        if current in own:
            pending += [own[current].parent] + own[current].interfaces
        elif current in view:
            found.append(current)
    return found


def check(own, reference, actual):
    """own: 自分のクラス。reference: ビルドしたバージョンの本体。actual: 実際に使われるバージョンの本体。"""
    problems = set()
    for info in own.values():
        for name in info.classes:
            if name in reference and name not in actual:
                problems.add(f"class {name} is missing (used by {info.name.rsplit('/', 1)[-1]})")
        for kind, owner, name, descriptor in info.references:
            if owner in own or owner not in reference:
                # 自分のクラス経由の参照は、下の親クラスの確認で見る
                if owner not in own:
                    continue
                ancestors = minecraft_ancestors(reference, own, owner)
                if not any(find_member(reference, parent, kind, name, descriptor) for parent in ancestors):
                    continue
                if any(find_member(actual, parent, kind, name, descriptor) for parent in ancestors):
                    continue
                problems.add(f"{kind} {owner.rsplit('/', 1)[-1]}.{name} {descriptor} (inherited) is missing"
                             f" (used by {info.name.rsplit('/', 1)[-1]})")
                continue
            if find_member(reference, owner, kind, name, descriptor) is not True:
                continue
            if owner not in actual:
                problems.add(f"class {owner} is missing (used by {info.name.rsplit('/', 1)[-1]})")
            elif find_member(actual, owner, kind, name, descriptor) is not True:
                problems.add(f"{kind} {owner}.{name} {descriptor} is missing (used by {info.name.rsplit('/', 1)[-1]})")
        # オーバーライドしているメソッドが、そのバージョンでも同じ形であること
        ancestors = minecraft_ancestors(reference, own, info.name)
        for (name, descriptor), access in info.methods.items():
            if name.startswith("<") or access & (ACC_PRIVATE | ACC_STATIC):
                continue
            overrides = [parent for parent in ancestors if find_member(reference, parent, "method", name, descriptor)]
            if overrides and not any(find_member(actual, parent, "method", name, descriptor) is not False
                                     for parent in overrides):
                problems.add(f"override {info.name.rsplit('/', 1)[-1]}.{name} {descriptor} does not match"
                             f" any method of {overrides[0]}")
        # そのバージョンで実装が必要なメソッドが、欠けていないこと
        if not info.access & ACC_ABSTRACT and ancestors:
            for missing in unimplemented(own, actual, info.name):
                if missing not in unimplemented(own, reference, info.name):
                    problems.add(f"abstract method {missing[0]} {missing[1]} is not implemented"
                                 f" by {info.name.rsplit('/', 1)[-1]}")
    return sorted(problems)


def unimplemented(own, view, name):
    """クラスが実装していない抽象メソッド。本体の外のクラスが絡むところは調べない。"""
    abstract, concrete, pending, seen = set(), set(), [name], set()
    while pending:
        current = pending.pop()
        if current in seen or current is None:
            continue
        seen.add(current)
        info = own.get(current) or view.get(current)
        if info is None:
            continue
        for key, access in info.methods.items():
            if access & ACC_ABSTRACT:
                abstract.add(key)
            elif not access & (ACC_PRIVATE | ACC_STATIC):
                concrete.add(key)
        pending += [info.parent] + info.interfaces
    return abstract - concrete


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--jar", default=None)
    parser.add_argument("--only", nargs="*", default=[], help="loader:version の形式で対象を絞る")
    args = parser.parse_args()

    jar = Path(args.jar) if args.jar else next(
        path for path in sorted((REPO / "dist" / "build" / "libs").glob("MarumaSign-*.jar")))
    targets = json.loads((REPO / "targets.json").read_text(encoding="utf-8"))["targets"]
    built_for = {target["name"]: target["minecraft"] for target in targets}
    jar_bytes = jar.read_bytes()
    with zipfile.ZipFile(jar) as archive:
        table = archive.read("marumasa_sign/targets.properties").decode("utf-8")
        nested = {name: archive.read(name) for name in archive.namelist()
                  if name.startswith("META-INF/jars/") and name.endswith(".jar")}

    # (ローダー, バージョン, ビルド対象)。Forge と NeoForge は jar の中の対応表から、
    # Fabric はバージョンごとに入れ子の jar になっているので、ビルド対象の一覧から求める
    combinations = []
    for line in table.splitlines():
        if "=" in line and not line.startswith("#"):
            key, target = line.strip().split("=")
            loader, minecraft = key.split(".", 1)
            combinations.append((loader, minecraft, target))
    for target in targets:
        for minecraft in target["loaders"].get("fabric", {}).get("versions", []):
            combinations.append(("fabric", minecraft, target["name"]))

    failed, skipped, checked = 0, [], 0
    own_cache = {}
    for loader, minecraft, target in sorted(combinations, key=lambda item: (item[0], version_key(item[1]))):
        if args.only and f"{loader}:{minecraft}" not in args.only:
            continue
        if runtime_names(loader, minecraft) is None:
            continue
        reference = view_of(loader, built_for[target])
        actual = view_of(loader, minecraft)
        if reference is None or actual is None:
            skipped.append(f"{loader}:{minecraft}" + ("" if actual is None else f" (no {built_for[target]})"))
            continue
        if (loader, target) not in own_cache:
            if loader == "fabric":
                data = nested.get(f"META-INF/jars/marumasa_sign-fabric-{target}.jar")
                own_cache[(loader, target)] = read_classes(io.BytesIO(data), PACKAGE, True) if data else {}
            else:
                own_cache[(loader, target)] = read_classes(io.BytesIO(jar_bytes), f"{PACKAGE}{loader}/{target}/", True)
        own = own_cache[(loader, target)]
        if not own:
            skipped.append(f"{loader}:{minecraft} (no classes for {target})")
            continue
        checked += 1
        problems = check(own, reference, actual)
        if problems:
            failed += 1
            print(f"{loader}:{minecraft} (built for {built_for[target]}): {len(problems)} problem(s)")
            for problem in problems:
                print("    " + problem)
    print(f"checked {checked} combination(s), {failed} with problems")
    if skipped:
        print("skipped (the Minecraft jar in runtime names is not available): " + ", ".join(skipped))
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
