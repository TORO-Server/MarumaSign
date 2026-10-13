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
                self.send_response(status)
                self.send_header("Content-Type", content_type)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_POST(self):
                body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                host.requests.append("POST " + self.path)
                # multipart の中から画像ファイルの本体を取り出す
                boundary = self.headers.get("Content-Type", "").split("boundary=")[-1].encode()
                data = b""
                for part in body.split(b"--" + boundary):
                    if b'name="fileToUpload"' in part:
                        data = part.split(b"\r\n\r\n", 1)[1].rsplit(b"\r\n", 1)[0]
                name = f"{len(host.files) + 1}.png"
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
                        self._send(200, data, "image/png")
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
    stream = entry.get("snapshots") or entry.get("artefacts")
    build = stream["com.mumfrey:liteloader"]["latest"]
    repository = entry["repo"]["url"].replace("http://", "https://")

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
                if build["version"].endswith("-SNAPSHOT"):
                    # 実環境用 (難読化された名前) の jar は release という種別で配布されている
                    base = f"{repository.rstrip('/')}/com/mumfrey/liteloader/{build['version']}/"
                    with urllib.request.urlopen(base + "maven-metadata.xml", timeout=60) as response:
                        metadata = response.read().decode("utf-8", "replace")
                    timestamp = re.search(r"<timestamp>([^<]+)</timestamp>", metadata).group(1)
                    number = re.search(r"<buildNumber>([^<]+)</buildNumber>", metadata).group(1)
                    resolved = build["version"].replace("SNAPSHOT", f"{timestamp}-{number}")
                    download(base + f"liteloader-{resolved}-release.jar", target)
                else:
                    # リリース版は Minecraft のバージョンごとのフォルダに置かれている
                    download(f"{repository.rstrip('/')}/com/mumfrey/liteloader/{minecraft}/{build['file']}", target)
                if file_md5(target) != build["md5"]:
                    raise RuntimeError(f"Checksum mismatch: {target}")
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
    box = minecraft_window_box() if sys.platform == "win32" else None
    if box is None:
        return False
    ImageGrab.grab(bbox=box, all_screens=True).save(path)
    return True


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
    image = Path(args.image).resolve()

    prepare_work_dir(work_dir, mod, args.lang)
    result = {
        "minecraft": args.minecraft, "loader": args.loader, "loader_version": args.loader_version,
        "mod": mod.name if mod else None, "ok": False, "detail": "",
    }

    host = MockHost()
    base = ["portablemc", "--main-dir", str(main_dir), "--work-dir", str(work_dir), "--output", "human"]
    if args.spec:
        spec = args.spec
    elif args.loader == "liteloader":
        spec = install_liteloader(main_dir, args.minecraft, base)
    else:
        spec = launcher_spec(args.minecraft, args.loader, args.loader_version)

    # 先にクライアントのファイルを揃える (サーバー用の Java もここで手に入る)
    log(f"installing {spec}")
    install = subprocess.run(base + ["start", "--dry", spec, "-u", PLAYER], capture_output=True, text=True, encoding="utf-8", errors="replace")
    if install.returncode != 0:
        result["detail"] = "Launcher install failed: " + (install.stdout + install.stderr)[-1500:]
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
            f"-Dmarumasign.selftest.image={image}",
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
                if grab_screen(work_dir / "screenshot.png"):
                    result["screenshot"] = str(work_dir / "screenshot.png")
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
        noise = load_known_noise()

        def is_new(line, known):
            return normalize(line) not in known and not any(pattern.search(line) for pattern in noise)

        result["baseline"] = True
        result["new_client_findings"] = [line for line in client_findings if is_new(line, baseline["client"])]
        result["new_server_findings"] = [line for line in server_findings if is_new(line, baseline["server"])]
        result["clean"] = result["ok"] and not result["new_client_findings"] and not result["new_server_findings"]
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
