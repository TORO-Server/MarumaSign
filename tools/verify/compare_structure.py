"""構造物の見え方を、ゲームそのものの描画と比べるための絵を撮る (目で見比べるための道具。matrix.py では使わない)。

バニラのサーバーとクライアント (Mod なし) を起動し、ゲームに入っている構造物 (村の家など) を /place template で置いて、
決まった向きから見た画面を撮る。同じ構造物を core の structureCheck で描いた絵と並べて見比べる。

    python tools/verify/compare_structure.py --template minecraft:village/plains/houses/plains_temple_3
    ./gradlew -PmarumaOnly=none :core:structureCheck -PstructureArgs="build/structure ..."  (同じテンプレートの .nbt を描く)
"""

import argparse
import math
import subprocess
import time
from pathlib import Path

import verify

ROOT = verify.DEFAULT_ROOT


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--minecraft", default="26.3")
    parser.add_argument("--template", action="append", required=True, help="置くテンプレート (複数指定すると順に撮る)")
    parser.add_argument("--yaw", type=float, default=150.0, help="視線の向き (Minecraft の角度。南が 0)")
    parser.add_argument("--pitch", type=float, default=25.0)
    parser.add_argument("--distance", type=float, default=16.0)
    parser.add_argument("--output", default=str(Path("build") / "compare"))
    parser.add_argument("--port", type=int, default=25591)
    args = parser.parse_args()

    root = Path(ROOT)
    main_dir = root / "main"
    work_dir = root / "runs" / f"compare-{args.minecraft}"
    # 最初の起動の案内などで止まらないように、確認と同じ設定にする (Mod は入れない)
    verify.prepare_work_dir(work_dir, None, "en_us")
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)

    server = verify.Server(args.minecraft, root, main_dir, args.port)
    server.prepare()
    server.start()
    client = None
    try:
        base = ["portablemc", "--timeout", "60", "--main-dir", str(main_dir), "--work-dir", str(work_dir), "--output", "human"]
        command = base + ["start", args.minecraft, "-u", verify.PLAYER, "-s", "127.0.0.1", "-p", str(args.port),
                          "--resolution", "1280x720"]
        client = subprocess.Popen(command, stdout=open(work_dir / "launcher-output.log", "w"), stderr=subprocess.STDOUT)
        if not server.joined.wait(600):
            raise RuntimeError("the player did not join")
        time.sleep(15)
        server.command(f"op {verify.PLAYER}")
        server.command(f"gamemode spectator {verify.PLAYER}")
        server.command("time set noon")
        server.command("weather clear")
        for index, template in enumerate(args.template):
            # 平らなワールドの地面 (y = -61) の上に置く
            origin = (index * 64, -60, 0)
            server.command(f"place template {template} {origin[0]} {origin[1]} {origin[2]}")
            time.sleep(3)
            # 構造物の中心のあたりを、指定した向きから見る
            centre = (origin[0] + 6, origin[1] + 4, origin[2] + 6)
            yaw = math.radians(args.yaw)
            pitch = math.radians(args.pitch)
            dx = -math.sin(yaw) * math.cos(pitch)
            dz = math.cos(yaw) * math.cos(pitch)
            dy = -math.sin(pitch)
            eye = (centre[0] - dx * args.distance, centre[1] - dy * args.distance - 1.62, centre[2] - dz * args.distance)
            server.command(f"tp {verify.PLAYER} {eye[0]:.2f} {eye[1]:.2f} {eye[2]:.2f} {args.yaw:.1f} {args.pitch:.1f}")
            time.sleep(8)
            name = template.split(":")[-1].replace("/", "__")
            path = output / f"game-{name}.png"
            verify.grab_screen(path, client)
            verify.log(f"saved {path}")
    finally:
        if client is not None:
            import psutil
            for pid in verify.process_tree(client):
                try:
                    psutil.Process(pid).kill()
                except Exception:
                    pass
        server.command("stop")
        time.sleep(5)


if __name__ == "__main__":
    main()
