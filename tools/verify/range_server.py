"""範囲指定 (Range) に対応した、テスト用の HTTP ファイルサーバー。

動画は途中から読めないと再生できない形式が多く、標準の http.server は範囲指定に対応していないため用意した。

    python tools/verify/range_server.py <フォルダ> <ポート>
"""

import http.server
import os
import re
import sys
from pathlib import Path


def make_handler(directory):
    root = Path(directory).resolve()

    class Handler(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *args):
            pass

        def do_HEAD(self):
            self._serve(False)

        def do_GET(self):
            self._serve(True)

        def _serve(self, with_body):
            path = (root / self.path.split("?", 1)[0].lstrip("/")).resolve()
            if root not in path.parents or not path.is_file():
                self.send_response(404)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            size = path.stat().st_size
            start, end = 0, size - 1
            match = re.match(r"bytes=(\d*)-(\d*)$", self.headers.get("Range", ""))
            if match and (match.group(1) or match.group(2)):
                if match.group(1):
                    start = int(match.group(1))
                    if match.group(2):
                        end = min(end, int(match.group(2)))
                else:
                    # 末尾からのバイト数での指定
                    start = max(0, size - int(match.group(2)))
                if start > end:
                    self.send_response(416)
                    self.send_header("Content-Range", f"bytes */{size}")
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                self.send_response(206)
                self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            else:
                self.send_response(200)
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(end - start + 1))
            self.end_headers()
            if not with_body:
                return
            try:
                with open(path, "rb") as source:
                    source.seek(start)
                    remaining = end - start + 1
                    while remaining > 0:
                        chunk = source.read(min(65536, remaining))
                        if not chunk:
                            break
                        self.wfile.write(chunk)
                        remaining -= len(chunk)
            except (ConnectionError, OSError):
                # 読み手が途中で接続を閉じた
                pass

    return Handler


def main():
    directory = sys.argv[1] if len(sys.argv) > 1 else os.getcwd()
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 18999
    server = http.server.ThreadingHTTPServer(("127.0.0.1", port), make_handler(directory))
    print(f"Serving {directory} on http://127.0.0.1:{port}/", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
