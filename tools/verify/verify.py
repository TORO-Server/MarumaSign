"""実機での動作確認を自動で行う。

バニラサーバーを起動し、指定したローダーを入れた実際のクライアントで接続して、
Mod 内蔵のセルフテスト (画面を開く -> 画像を選ぶ -> 看板を設置 -> 描画を確認) を実行する。
終了後、クライアントとサーバーのログから警告とエラーを集めて結果を JSON で出力する。

使い方:
    python tools/verify/verify.py --minecraft 1.12.2 --loader forge --loader-version 14.23.5.2860 \
        --mod dist/build/libs/MarumaSign-4.0.0.jar
"""

import argparse
import http.server
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DEFAULT_ROOT = Path(os.environ.get("MARUMASIGN_VERIFY_ROOT", Path.home() / ".marumasign-verify"))
PLAYER = "Tester"
MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"


# 一部の配布サーバーは既定の User-Agent を拒否する
_opener = urllib.request.build_opener()
_opener.addheaders = [("User-Agent", "MarumaSign-verify/1.0 (+https://github.com/TORO-Server/MarumaSign)")]
urllib.request.install_opener(_opener)


def log(message):
    print(f"[verify] {message}", flush=True)


def version_key(version):
    """バージョン文字列を比較できる形にする ("1.12.2" -> (1, 12, 2))。"""
    return tuple(int(part) for part in re.findall(r"\d+", version)[:3])


def fetch_json(url):
    with urllib.request.urlopen(url, timeout=60) as response:
        return json.load(response)


def download(url, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".part")
    with urllib.request.urlopen(url, timeout=120) as response, open(temporary, "wb") as output:
        shutil.copyfileobj(response, output)
    temporary.replace(path)


# ---------- 画像のアップロード先と URL 短縮の代わりをするサーバー ----------

class MockHost:
    def __init__(self):
        self.files = {}
        self.requests = []
        host = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def _send(self, status, body, content_type="text/plain"):
                # 動画は途中から読めないと再生できないことがあるので、範囲指定に対応する
                total = len(body)
                requested = re.match(r"bytes=(\d*)-(\d*)$", self.headers.get("Range", "")) if status == 200 else None
                if requested and (requested.group(1) or requested.group(2)):
                    if requested.group(1):
                        start = int(requested.group(1))
                        end = min(int(requested.group(2)), total - 1) if requested.group(2) else total - 1
                    else:
                        start = max(0, total - int(requested.group(2)))
                        end = total - 1
                    if start > end or start >= total:
                        self.send_response(416)
                        self.send_header("Content-Range", f"bytes */{total}")
                        self.send_header("Content-Length", "0")
                        self.end_headers()
                        return
                    body = body[start:end + 1]
                    status = 206
                self.send_response(status)
                self.send_header("Content-Type", content_type)
                self.send_header("Content-Length", str(len(body)))
                if status in (200, 206):
                    self.send_header("Accept-Ranges", "bytes")
                if status == 206:
                    self.send_header("Content-Range", f"bytes {start}-{end}/{total}")
                self.end_headers()
                try:
                    self.wfile.write(body)
                except (BrokenPipeError, ConnectionError):
                    # 動画の再生では、読む側が途中で接続を閉じることがある
                    pass

            def do_POST(self):
                body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                host.requests.append("POST " + self.path)
                # multipart の中からファイルの本体を取り出す
                boundary = self.headers.get("Content-Type", "").split("boundary=")[-1].encode()
                data = b""
                extension = ".png"
                for part in body.split(b"--" + boundary):
                    if b'name="fileToUpload"' in part:
                        header, content = part.split(b"\r\n\r\n", 1)
                        data = content.rsplit(b"\r\n", 1)[0]
                        # 実際のアップロード先と同じように、元のファイルの拡張子を引き継ぐ
                        filename = re.search(rb'filename="[^"]*?(\.[A-Za-z0-9]{1,5})"', header)
                        if filename:
                            extension = filename.group(1).decode().lower()
                name = f"{len(host.files) + 1}{extension}"
                host.files[name] = data
                self._send(200, f"http://127.0.0.1:{host.port}/i/{name}".encode())

            def do_GET(self):
                host.requests.append("GET " + self.path)
                parsed = urllib.parse.urlparse(self.path)
                if parsed.path.startswith("/i/") or parsed.path.startswith("/s/"):
                    data = host.files.get(parsed.path[3:])
                    if data is None:
                        self._send(404, b"not found")
                    else:
                        self._send(200, data, "application/octet-stream")
                elif parsed.path == "/shorten":
                    target = urllib.parse.parse_qs(parsed.query).get("url", [""])[0]
                    name = target.rsplit("/", 1)[-1]
                    self._send(200, f"http://127.0.0.1:{host.port}/s/{name}".encode())
                else:
                    self._send(404, b"not found")

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()


# ---------- Java ----------

def java_major_for(minecraft):
    key = version_key(minecraft)
    if key >= (26, 0, 0):
        return 25
    if key >= (1, 20, 5):
        return 21
    if key >= (1, 17, 0):
        return 17
    return 8


def find_java(major, main_dir):
    """サーバーの起動に使う java を探す。"""
    candidates = []
    # クライアント用にランチャーがダウンロードした Mojang 配布の Java
    component = {8: "jre-legacy", 17: "java-runtime-gamma", 21: "java-runtime-delta", 25: "java-runtime-epsilon"}.get(major)
    if component:
        candidates += list((main_dir / "jvm").glob(f"{component}/bin/java.exe"))
    for base in (Path.home() / ".jdks", Path.home() / ".gradle" / "jdks", Path("C:/Program Files/Java")):
        if base.is_dir():
            for java in base.glob("*/bin/java.exe"):
                candidates.append(java)
            for java in base.glob("*/*/bin/java.exe"):
                candidates.append(java)
    for java in candidates:
        try:
            output = subprocess.run([str(java), "-version"], capture_output=True, text=True, timeout=30).stderr
        except (OSError, subprocess.SubprocessError):
            continue
        match = re.search(r'version "(?:1\.)?(\d+)', output)
        if match and int(match.group(1)) == major:
            return java
    raise RuntimeError(f"Java {major} was not found")


# ---------- サーバー ----------

def sign_item(minecraft):
    return "minecraft:oak_sign" if version_key(minecraft) >= (1, 14, 0) else "minecraft:sign"


class Server:
    def __init__(self, minecraft, root, main_dir, port):
        self.minecraft = minecraft
        self.directory = root / "servers" / minecraft
        self.main_dir = main_dir
        self.port = port
        self.process = None
        self.lines = []
        self.joined = threading.Event()
        self.ready = threading.Event()

    def prepare(self):
        self.directory.mkdir(parents=True, exist_ok=True)
        jar = self.directory / "server.jar"
        if not jar.exists():
            manifest = fetch_json(MANIFEST_URL)
            entry = next((v for v in manifest["versions"] if v["id"] == self.minecraft), None)
            if entry is None:
                raise RuntimeError(f"Unknown Minecraft version: {self.minecraft}")
            url = fetch_json(entry["url"])["downloads"]["server"]["url"]
            log(f"downloading server {self.minecraft}")
            download(url, jar)

        # 毎回まっさらなワールドで始める
        shutil.rmtree(self.directory / "world", ignore_errors=True)
        for name in ("logs", "ops.json", "ops.txt"):
            target = self.directory / name
            if target.is_dir():
                shutil.rmtree(target, ignore_errors=True)
            elif target.exists():
                target.unlink()
        (self.directory / "eula.txt").write_text("eula=true\n")
        (self.directory / "server.properties").write_text("\n".join([
            f"server-port={self.port}",
            "server-ip=127.0.0.1",
            "online-mode=false",
            "enforce-secure-profile=false",
            # 新しいバージョンでは、既定でホワイトリストが有効になっている
            "white-list=false",
            "enforce-whitelist=false",
            "level-type=flat" if version_key(self.minecraft) >= (1, 13, 0) else "level-type=FLAT",
            "generate-structures=false",
            "spawn-protection=0",
            "gamemode=survival" if version_key(self.minecraft) >= (1, 14, 0) else "gamemode=0",
            "force-gamemode=true",
            "difficulty=peaceful" if version_key(self.minecraft) >= (1, 14, 0) else "difficulty=0",
            "spawn-monsters=false",
            "spawn-animals=false",
            "spawn-npcs=false",
            "view-distance=4",
            "max-players=2",
            "enable-command-block=false",
            "motd=MarumaSign verify",
            "",
        ]))

    def start(self):
        java = find_java(java_major_for(self.minecraft), self.main_dir)
        log(f"starting server {self.minecraft} with {java}")
        self.process = subprocess.Popen(
            [str(java), "-Xmx1G", "-jar", "server.jar", "nogui"],
            cwd=self.directory, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace",
        )
        threading.Thread(target=self._read, daemon=True).start()
        if not self.ready.wait(300):
            raise RuntimeError("The server did not start. Last lines:\n" + "\n".join(self.lines[-20:]))

    def _read(self):
        for line in self.process.stdout:
            line = line.rstrip()
            self.lines.append(line)
            if "Done (" in line:
                self.ready.set()
            if f"{PLAYER} joined the game" in line:
                self.joined.set()

    def command(self, text):
        if self.process and self.process.poll() is None:
            self.process.stdin.write(text + "\n")
            self.process.stdin.flush()

    def move_player(self, distance):
        """プレイヤーを、いまの場所から離れた場所へ移動させる。"""
        if version_key(self.minecraft) >= (1, 13, 0):
            self.command(f"execute as {PLAYER} at @s run tp @s ~{distance} ~ ~")
        else:
            self.command(f"tp {PLAYER} ~{distance} ~ ~")

    def on_join(self):
        """プレイヤーが入ったら、サバイバルモードで看板を持たせる。"""
        time.sleep(2)
        if version_key(self.minecraft) >= (1, 13, 0):
            self.command(f"gamemode survival {PLAYER}")
        else:
            self.command(f"gamemode 0 {PLAYER}")
        self.command(f"give {PLAYER} {sign_item(self.minecraft)} 16")
        self.command("time set 6000" if version_key(self.minecraft) < (1, 13, 0) else "time set noon")
        self.command("weather clear")

    def stop(self):
        if self.process is None:
            return
        if self.process.poll() is None:
            self.command("stop")
            try:
                self.process.wait(60)
            except subprocess.TimeoutExpired:
                self.process.kill()


# ---------- クライアント ----------

def launcher_spec(minecraft, loader, loader_version):
    if loader == "vanilla":
        return minecraft
    if loader == "fabric":
        return f"fabric:{minecraft}:{loader_version}" if loader_version else f"fabric:{minecraft}"
    if loader == "forge":
        return f"forge:{minecraft}-{loader_version}" if loader_version else f"forge:{minecraft}"
    if loader == "neoforge":
        return f"neoforge:{loader_version}"
    raise RuntimeError(f"Unsupported loader: {loader}")


# ---------- LiteLoader ----------
# ランチャーは LiteLoader を直接扱えないため、公式のバージョン情報から起動用のプロファイルを作る

LITELOADER_VERSIONS_URL = "https://dl.liteloader.com/versions/versions.json"
MAVEN_CENTRAL = "https://repo1.maven.org/maven2/"


def file_md5(path):
    import hashlib
    return hashlib.md5(path.read_bytes()).hexdigest()


def maven_path(name):
    group, artifact, version = name.split(":")[:3]
    return f"{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.jar"


def fetch_library(main_dir, name, repositories):
    """ライブラリをランチャーのフォルダに置く。SNAPSHOT は最新のものを取得する。"""
    target = main_dir / "libraries" / maven_path(name)
    if target.is_file():
        return
    group, artifact, version = name.split(":")[:3]
    errors = []
    for repository in repositories:
        base = f"{repository.rstrip('/')}/{group.replace('.', '/')}/{artifact}/{version}/"
        urls = [base + f"{artifact}-{version}.jar"]
        if version.endswith("-SNAPSHOT"):
            try:
                with urllib.request.urlopen(base + "maven-metadata.xml", timeout=60) as response:
                    metadata = response.read().decode("utf-8", "replace")
                timestamp = re.search(r"<timestamp>([^<]+)</timestamp>", metadata)
                build = re.search(r"<buildNumber>([^<]+)</buildNumber>", metadata)
                if timestamp and build:
                    resolved = version.replace("SNAPSHOT", f"{timestamp.group(1)}-{build.group(1)}")
                    urls.insert(0, base + f"{artifact}-{resolved}.jar")
            except OSError as error:
                errors.append(f"{base}: {error}")
        for url in urls:
            try:
                download(url, target)
                return
            except OSError as error:
                errors.append(f"{url}: {error}")
    raise RuntimeError(f"Could not download {name}:\n" + "\n".join(errors))


def install_liteloader(main_dir, minecraft, base_command):
    """LiteLoader の起動用プロファイルを作り、そのバージョン ID を返す。"""
    versions = fetch_json(LITELOADER_VERSIONS_URL)["versions"]
    if minecraft not in versions:
        raise RuntimeError(f"LiteLoader does not support Minecraft {minecraft}")
    entry = versions[minecraft]
    # リリース版の配布サーバーは一部が停止しているため、スナップショットがあればそちらを使う
    snapshot = "snapshots" in entry
    stream = entry["snapshots"] if snapshot else entry["artefacts"]
    build = stream["com.mumfrey:liteloader"]["latest"]
    repository = entry["repo"]["url"].replace("http://", "https://")
    if snapshot:
        repository = "https://repo.mumfrey.com/content/repositories/snapshots/"

    # バニラのプロファイルが必要なので、先にバニラを用意する
    subprocess.run(base_command + ["start", "--dry", minecraft, "-u", PLAYER], capture_output=True)
    vanilla = json.loads((main_dir / "versions" / minecraft / f"{minecraft}.json").read_text(encoding="utf-8"))

    libraries = [{"name": f"com.mumfrey:liteloader:{build['version']}"}]
    libraries += list(build.get("libraries", [])) + list(stream.get("libraries", []))
    seen = set()
    unique = []
    for library in libraries:
        if library["name"] in seen:
            continue
        seen.add(library["name"])
        unique.append({"name": library["name"]})
        if library["name"].startswith("com.mumfrey:liteloader:"):
            target = main_dir / "libraries" / maven_path(library["name"])
            if not target.is_file() or file_md5(target) != build["md5"]:
                # 配布の形式がバージョンによって違うので、候補を順に試してチェックサムが合うものを使う。
                # 実環境用 (難読化された名前) の jar は release という種別になっていることが多い
                base = f"{repository.rstrip('/')}/com/mumfrey/liteloader/{build['version']}/"
                names = [build["version"]]
                if build["version"].endswith("-SNAPSHOT"):
                    with urllib.request.urlopen(base + "maven-metadata.xml", timeout=60) as response:
                        metadata = response.read().decode("utf-8", "replace")
                    timestamp = re.search(r"<timestamp>([^<]+)</timestamp>", metadata).group(1)
                    number = re.search(r"<buildNumber>([^<]+)</buildNumber>", metadata).group(1)
                    names.insert(0, build["version"].replace("SNAPSHOT", f"{timestamp}-{number}"))
                candidates = []
                for name in names:
                    candidates += [base + f"liteloader-{name}-release.jar", base + f"liteloader-{name}.jar"]
                # 古いリリース版は Minecraft のバージョンごとのフォルダに置かれている
                candidates.append(f"{repository.rstrip('/')}/com/mumfrey/liteloader/{minecraft}/{build['file']}")
                downloaded = []
                for url in candidates:
                    try:
                        download(url, target)
                    except OSError:
                        continue
                    downloaded.append(url)
                    if file_md5(target) == build["md5"]:
                        break
                else:
                    # 公式の一覧より新しいビルドが公開されているとチェックサムは合わない。
                    # その場合は最新の実環境用の jar を使い、そのことを記録に残す
                    if not downloaded:
                        raise RuntimeError(f"LiteLoader for {minecraft} could not be downloaded")
                    download(downloaded[0], target)
                    log(f"note: LiteLoader {minecraft} does not match the listed checksum; using {downloaded[0]}")
        else:
            fetch_library(main_dir, library["name"], [
                "https://libraries.minecraft.net/", library.get("url", MAVEN_CENTRAL), MAVEN_CENTRAL,
                "https://repo.spongepowered.org/maven/",
            ])

    version_id = f"{minecraft}-LiteLoader"
    profile = {
        "id": version_id,
        "inheritsFrom": minecraft,
        "type": "release",
        "time": vanilla.get("time", ""),
        "releaseTime": vanilla.get("releaseTime", ""),
        "mainClass": "net.minecraft.launchwrapper.Launch",
        "minecraftArguments": vanilla["minecraftArguments"] + " --tweakClass " + build["tweakClass"],
        "libraries": unique,
    }
    directory = main_dir / "versions" / version_id
    directory.mkdir(parents=True, exist_ok=True)
    (directory / f"{version_id}.json").write_text(json.dumps(profile, indent=2), encoding="utf-8")
    return version_id


# ---------- NeoForge ----------
# ランチャーは新しい NeoForge のインストーラーに対応していないため、公式のインストーラーを直接実行する

NEOFORGE_MAVEN = "https://maven.neoforged.net/releases/net/neoforged/neoforge"


def install_neoforge(main_dir, minecraft, version, base_command):
    """NeoForge をインストールし、そのバージョン ID を返す。"""
    version_id = f"neoforge-{version}"
    if (main_dir / "versions" / version_id / f"{version_id}.json").is_file():
        return version_id

    # バニラのファイルと Java を先に用意する
    subprocess.run(base_command + ["start", "--dry", minecraft, "-u", PLAYER], capture_output=True, timeout=1500)
    installer = main_dir / "installers" / f"neoforge-{version}-installer.jar"
    if not installer.is_file():
        download(f"{NEOFORGE_MAVEN}/{version}/neoforge-{version}-installer.jar", installer)
    profiles = main_dir / "launcher_profiles.json"
    if not profiles.is_file():
        # インストーラーは公式ランチャーのプロファイルがあることを前提にしている
        profiles.write_text('{"profiles": {}}', encoding="utf-8")

    java = find_java(java_major_for(minecraft), main_dir)
    result = subprocess.run([str(java), "-jar", str(installer), "--install-client", str(main_dir)],
                            capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=1500)
    if result.returncode != 0:
        raise RuntimeError("NeoForge installer failed: " + (result.stdout + result.stderr)[-1500:])
    return version_id


OPTIONS = """\
pauseOnLostFocus:false
tutorialStep:none
onboardAccessibility:false
skipMultiplayerWarning:true
joinedFirstServer:true
narrator:0
fullscreen:false
renderDistance:4
maxFps:60
fov:0.0
soundCategory_master:0.0
lang:{lang}
"""


def prepare_work_dir(work_dir, mod, lang):
    shutil.rmtree(work_dir, ignore_errors=True)
    (work_dir / "mods").mkdir(parents=True)
    if mod is not None:
        shutil.copy2(mod, work_dir / "mods" / mod.name)
    (work_dir / "options.txt").write_text(OPTIONS.format(lang=lang))
    # 動画や音声の確認で音が鳴らないように、音量を 0 にしておく
    (work_dir / "config").mkdir(exist_ok=True)
    (work_dir / "config" / "marumasa_sign.json").write_text('{\n  "MaxThreads": 5,\n  "Volume": 0\n}\n')


def minecraft_windows():
    """Minecraft のウィンドウを (ハンドル, 位置) の一覧で返す。"""
    import ctypes
    from ctypes import wintypes

    user32 = ctypes.windll.user32
    found = []

    @ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
    def visit(window, _):
        if user32.IsWindowVisible(window):
            title = ctypes.create_unicode_buffer(256)
            user32.GetWindowTextW(window, title, 256)
            if title.value.startswith("Minecraft"):
                rect = wintypes.RECT()
                user32.GetWindowRect(window, ctypes.byref(rect))
                if rect.right - rect.left > 200:
                    found.append((window, (rect.left, rect.top, rect.right, rect.bottom)))
        return True

    user32.EnumWindows(visit, 0)
    return found


def minecraft_window_box():
    """Minecraft のウィンドウの位置を返す。見つからなければ None。"""
    windows = minecraft_windows()
    return windows[0][1] if windows else None


def close_minecraft_window():
    """ウィンドウの閉じるボタンを押したのと同じ方法で、ゲームを通常どおり終了させる。"""
    import ctypes

    for window, _ in minecraft_windows():
        ctypes.windll.user32.PostMessageW(window, 0x0010, 0, 0)  # WM_CLOSE


def grab_screen(path):
    """Minecraft のウィンドウだけを画像として保存する。"""
    try:
        from PIL import ImageGrab
    except ImportError:
        return False
    if sys.platform != "win32":
        return False
    windows = minecraft_windows()
    if not windows:
        return False
    import ctypes
    # 他のウィンドウに隠れていると写らないので、手前に出してから撮る
    window, box = windows[0]
    ctypes.windll.user32.ShowWindow(window, 9)  # SW_RESTORE
    ctypes.windll.user32.SetForegroundWindow(window)
    time.sleep(0.7)
    ImageGrab.grab(bbox=box, all_screens=True).save(path)
    return True


# テスト画像の 4 つの領域の色 (左上, 右上, 左下, 右下)
TEST_COLORS = {"red": (230, 60, 60), "green": (60, 180, 75), "blue": (60, 110, 230), "yellow": (250, 200, 40)}


def analyze_screenshot(path):
    """スクリーンショットにテスト画像が正しい向きで写っているかを調べる。"""
    from PIL import Image

    image = Image.open(path).convert("RGB")
    image.thumbnail((640, 640))
    width, height = image.size
    pixels = image.load()
    sums = {name: [0, 0, 0] for name in TEST_COLORS}
    for y in range(height):
        for x in range(width):
            name = classify(*pixels[x, y])
            if name is not None:
                entry = sums[name]
                entry[0] += 1
                entry[1] += x
                entry[2] += y
    return judge_colours(sums, width, height)


def analyze_sign_view(path):
    """看板の位置を見下ろした画面に、看板の板が写っていないかを調べる。"""
    from PIL import Image

    image = Image.open(path).convert("RGB")
    image.thumbnail((640, 640))
    width, height = image.size
    pixels = image.load()
    # 看板は画面の中央付近に来る。右下に写る手持ちの看板は範囲に入れない
    wood = total = 0
    for y in range(int(height * 0.15), int(height * 0.70)):
        for x in range(int(width * 0.30), int(width * 0.70)):
            r, g, b = pixels[x, y]
            total += 1
            # 木の板の色 (赤 > 緑 > 青 で、くすんだ茶色)
            if 90 < r < 225 and 12 <= r - g <= 65 and 25 <= g - b <= 95:
                wood += 1
    ratio = wood / max(1, total)
    if ratio > 0.012:
        return False, f"sign board seems visible ({ratio:.1%} of the centre is wood-coloured)"
    return True, "sign hidden"


def classify(r, g, b):
    """ピクセルがテスト画像のどの色かを、明るさに左右されないよう色の比率で判定する。"""
    if r > 80 and r > 2.5 * g and r > 2.5 * b:
        return "red"
    if g > 80 and g > 2.0 * r and g > 1.8 * b:
        return "green"
    if b > 80 and b > 1.6 * g and b > 2.5 * r:
        return "blue"
    if r > 100 and g < r < 1.6 * g and b < 0.35 * g:
        return "yellow"
    return None


def judge_colours(sums, width, height):
    counts = {name: entry[0] for name, entry in sums.items()}
    minimum = width * height // 400
    if any(count < minimum for count in counts.values()):
        return False, f"test image colours not found (pixels: {counts})"
    centre = {name: (entry[1] / entry[0], entry[2] / entry[0]) for name, entry in sums.items()}
    ordered = (centre["red"][0] < centre["green"][0] and centre["blue"][0] < centre["yellow"][0]
               and centre["red"][1] < centre["blue"][1] and centre["green"][1] < centre["yellow"][1])
    if not ordered:
        return False, f"test image is mirrored or rotated (centres: {centre})"
    return True, "test image visible"


# ---------- ログの確認 ----------

LEVEL_PATTERN = re.compile(r"(\[|/)(WARN|WARNING|ERROR|FATAL|SEVERE)(\]|:)")


def scan_log(path):
    """警告とエラーの行を集める。"""
    if not path.is_file():
        return None
    findings = []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if LEVEL_PATTERN.search(line) or "Exception" in line or "marumasa" in line.lower() and "error" in line.lower():
            findings.append(line[:400])
    return findings


def load_known_noise():
    """Mod 由来でないと分かっている行の正規表現を読み込む。"""
    patterns = []
    for line in Path(__file__).with_name("known-noise.txt").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            patterns.append(re.compile(line))
    return patterns


def normalize(line):
    """時刻や数値の違いを無視して比較できる形にする。"""
    line = re.sub(r"^\[[^\]]*\d\d:\d\d:\d\d[^\]]*\]\s*", "", line)
    # 基準の取得と本番では作業フォルダの名前が違う
    line = line.replace("-baseline\\", "\\").replace("-baseline/", "/")
    # オブジェクトのハッシュ値 (ModConfig@110dc235 など) は実行のたびに変わる
    line = re.sub(r"@[0-9a-fA-F]{4,16}\b", "@H", line)
    return re.sub(r"\d+", "N", line)


def unique(lines):
    """同じ内容の行をまとめる。"""
    seen = set()
    result = []
    for line in lines:
        key = normalize(line)
        if key not in seen:
            seen.add(key)
            result.append(line)
    return result


def find_client_log(work_dir):
    for candidate in (work_dir / "logs" / "latest.log", work_dir / "logs" / "fml-client-latest.log", work_dir / "output-client.log"):
        if candidate.is_file():
            return candidate
    return work_dir / "logs" / "latest.log"


def run(args):
    root = Path(args.root)
    main_dir = root / "main"
    if args.baseline:
        args.mod = None
    name = f"{args.loader}-{args.minecraft}" + ("-baseline" if args.baseline else "")
    work_dir = root / "runs" / name
    result_dir = work_dir / "selftest"
    mod = Path(args.mod).resolve() if args.mod else None
    # 看板に表示して確かめるファイル。| で区切って複数指定できる
    images = [Path(item).resolve() for item in str(args.image).split("|") if item]

    prepare_work_dir(work_dir, mod, args.lang)
    result = {
        "minecraft": args.minecraft, "loader": args.loader, "loader_version": args.loader_version,
        "mod": mod.name if mod else None, "ok": False, "detail": "",
    }

    host = MockHost()
    # 通信が途中で止まっても待ち続けないように、ランチャーの通信にタイムアウトを付ける
    base = ["portablemc", "--timeout", "60", "--main-dir", str(main_dir), "--work-dir", str(work_dir), "--output", "human"]
    if args.spec:
        spec = args.spec
    elif args.loader == "liteloader":
        spec = install_liteloader(main_dir, args.minecraft, base)
    elif args.loader == "neoforge" and not args.loader_version.startswith("1.20.1-"):
        spec = install_neoforge(main_dir, args.minecraft, args.loader_version, base)
    else:
        spec = launcher_spec(args.minecraft, args.loader, args.loader_version)

    # 先にクライアントのファイルを揃える (サーバー用の Java もここで手に入る)
    log(f"installing {spec}")
    failure = ""
    for attempt in range(3):
        # ダウンロードの失敗は一時的なことが多いので、何度か試す
        try:
            install = subprocess.run(base + ["start", "--dry", spec, "-u", PLAYER], capture_output=True, text=True,
                                     encoding="utf-8", errors="replace", timeout=1500)
        except subprocess.TimeoutExpired:
            failure = "timed out"
            continue
        if install.returncode == 0:
            failure = ""
            break
        failure = (install.stdout + install.stderr)[-1500:]
    if failure:
        result["detail"] = "Launcher install failed: " + failure
        host.close()
        return result

    server = Server(args.minecraft, root, main_dir, args.port)
    client = None
    try:
        server.prepare()
        server.start()

        jvm_args = " ".join([
            "-Xmx2G",
            f"-Dmarumasign.selftest={result_dir}",
            "-Dmarumasign.selftest.image=" + "|".join(str(image) for image in images),
            f"-Dmarumasign.uploadUrl=http://127.0.0.1:{host.port}/upload",
            f"-Dmarumasign.shortenUrl=http://127.0.0.1:{host.port}/shorten",
        ])
        command = base + ["start", spec, "-u", PLAYER, "-s", "127.0.0.1", "-p", str(args.port),
                          "--resolution", "1280x720", f"--jvm-args={jvm_args}"]
        log("starting client")
        client_log = open(work_dir / "launcher-output.log", "w", encoding="utf-8", errors="replace")
        client = subprocess.Popen(command, stdout=client_log, stderr=subprocess.STDOUT)

        deadline = time.time() + args.timeout
        joined_handled = False
        joined_at = 0.0
        captured = False
        # ファイルごとの結果と、次に待つ合図
        media = [{"name": image.name} for image in images]
        current = 0
        stage = "view"
        while time.time() < deadline:
            if client.poll() is not None:
                break
            if server.joined.is_set() and not joined_handled:
                joined_handled = True
                joined_at = time.time()
                log("player joined")
                threading.Thread(target=server.on_join, daemon=True).start()
            if (result_dir / "result.txt").is_file() and not captured:
                captured = True
                time.sleep(0.5)
                if not args.baseline and grab_screen(work_dir / "screenshot.png"):
                    result["screenshot"] = str(work_dir / "screenshot.png")
            if not args.baseline and current < len(media):
                entry = media[current]
                if stage == "view" and (result_dir / f"view-{current}.txt").is_file():
                    # 画像の全体が写っている状態
                    shot = work_dir / f"screenshot-{current}.png"
                    if grab_screen(shot):
                        entry["visible"], entry["detail"] = analyze_screenshot(shot)
                    stage = "sign"
                elif stage == "sign" and (result_dir / f"sign-view-{current}.txt").is_file():
                    # 看板の位置を見下ろしている状態
                    shot = work_dir / f"screenshot-sign-{current}.png"
                    if grab_screen(shot):
                        entry["sign_hidden"], entry["sign_detail"] = analyze_sign_view(shot)
                    stage = "move"
                elif stage == "move" and (result_dir / f"move-{current}.txt").is_file():
                    # 次のファイルは、前の看板が見えない場所で確認する
                    server.move_player(300)
                    time.sleep(1.0)
                    (result_dir / f"moved-{current}.txt").write_text("done\n")
                    current += 1
                    stage = "view"
            if args.baseline and joined_handled and time.time() - joined_at > 25:
                # 基準の取得では、ワールドに入ってしばらくしたら終了する
                result["detail"] = "baseline"
                break
            time.sleep(0.5)
        else:
            result["detail"] = "Timed out"

        if client.poll() is None and sys.platform == "win32":
            # 終了時に出る警告も記録できるよう、まず通常の方法で閉じる
            close_minecraft_window()
            try:
                client.wait(45)
            except subprocess.TimeoutExpired:
                pass
        if client.poll() is None:
            # クライアントが自分で終了しなかった
            subprocess.run(["taskkill", "/F", "/T", "/PID", str(client.pid)], capture_output=True)
        client_log.close()
    finally:
        server.stop()
        host.close()

    if not args.baseline:
        result["media"] = media
        result["image_visible"] = all(entry.get("visible") for entry in media)
        result["sign_hidden"] = all(entry.get("sign_hidden") for entry in media)
        result["image_detail"] = ", ".join(f"{entry['name']}: {entry.get('detail', 'not captured')}" for entry in media)
        result["sign_detail"] = ", ".join(f"{entry['name']}: {entry.get('sign_detail', 'not captured')}" for entry in media)

    result_file = result_dir / "result.txt"
    if result_file.is_file():
        lines = result_file.read_text(encoding="utf-8").splitlines()
        result["ok"] = bool(lines) and lines[0] == "OK"
        result["detail"] = lines[1] if len(lines) > 1 else ""
    elif not result["detail"]:
        result["detail"] = "The client exited without a self test result"
    result["joined"] = server.joined.is_set()
    result["uploads"] = len(host.files)
    result["http_requests"] = host.requests

    client_findings = scan_log(find_client_log(work_dir))
    result["client_log"] = str(find_client_log(work_dir))
    client_findings = unique(client_findings) if client_findings is not None else ["(no client log)"]
    server_findings = unique(line[:400] for line in server.lines if LEVEL_PATTERN.search(line) or "Exception" in line)
    (work_dir / "server-output.log").write_text("\n".join(server.lines), encoding="utf-8")

    # Mod を入れていない状態 (基準) でも出る警告は、ローダーや Minecraft 自体のもの
    baseline_file = root / "baselines" / f"{args.loader}-{args.minecraft}.json"
    if args.baseline:
        result["ok"] = server.joined.is_set()
        baseline_file.parent.mkdir(parents=True, exist_ok=True)
        baseline_file.write_text(json.dumps({
            "client": sorted({normalize(line) for line in client_findings}),
            "server": sorted({normalize(line) for line in server_findings}),
        }, indent=2, ensure_ascii=False), encoding="utf-8")
        result["baseline_client_count"] = len(client_findings)
        result["baseline_server_count"] = len(server_findings)
    elif baseline_file.is_file():
        baseline = json.loads(baseline_file.read_text(encoding="utf-8"))
        # 保存後に正規化の規則が増えていても比較できるように、もう一度正規化する
        baseline = {side: {normalize(line) for line in lines} for side, lines in baseline.items()}
        noise = load_known_noise()

        def is_new(line, known):
            return normalize(line) not in known and not any(pattern.search(line) for pattern in noise)

        result["baseline"] = True
        result["new_client_findings"] = [line for line in client_findings if is_new(line, baseline["client"])]
        result["new_server_findings"] = [line for line in server_findings if is_new(line, baseline["server"])]
        result["clean"] = (result["ok"] and bool(result.get("image_visible")) and bool(result.get("sign_hidden"))
                           and not result["new_client_findings"] and not result["new_server_findings"])
    else:
        result["baseline"] = False
        result["client_findings"] = client_findings
        result["server_findings"] = server_findings

    (work_dir / "result.json").write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--minecraft", required=True)
    parser.add_argument("--loader", required=True, choices=["vanilla", "forge", "fabric", "neoforge", "liteloader"])
    parser.add_argument("--loader-version", default="")
    parser.add_argument("--spec", default="", help="ランチャーに渡すバージョン指定を直接指定する")
    parser.add_argument("--mod", default=None, help="mods フォルダに入れるファイル。省略すると Mod なしで起動する")
    parser.add_argument("--baseline", action="store_true",
                        help="Mod を入れずに起動し、ローダーや Minecraft 自体が出す警告を基準として記録する")
    parser.add_argument("--image", default=str(REPO / "tools" / "verify" / "test-image.png"))
    parser.add_argument("--lang", default="en_us")
    parser.add_argument("--port", type=int, default=25570)
    parser.add_argument("--timeout", type=int, default=420)
    parser.add_argument("--root", default=str(DEFAULT_ROOT))
    args = parser.parse_args()

    result = run(args)
    print(json.dumps(result, indent=2, ensure_ascii=False))
    sys.exit(0 if result.get("clean", result["ok"]) else 1)


if __name__ == "__main__":
    main()
