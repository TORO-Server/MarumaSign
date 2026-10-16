"""ガウシアンスプラッティングの描画結果を、3DGS 標準の EWA ラスタライズ基準画像と比較する。

    python tools/verify/splat_compare.py
"""

import math
import struct
from pathlib import Path

import numpy as np
from PIL import Image

REPO = Path(__file__).resolve().parents[2]
DATA_DIR = Path(r"D:\Dev\MarumaSign-wt\splat-data")
PREVIEW_DIR = DATA_DIR / "preview"
PREVIEW_DIR.mkdir(parents=True, exist_ok=True)


def load_splat(path, max_splats=150000):
    content = Path(path).read_bytes()
    assert len(content) % 32 == 0 and len(content) > 0
    total = len(content) // 32

    # 32 バイト x N
    # pos (3f), scale (3f), rgba (4u1), rot (4u1)
    raw = np.frombuffer(content, dtype=np.uint8).reshape(total, 32)
    positions = raw[:, :12].view(np.float32).reshape(total, 3).copy()
    scales = np.abs(raw[:, 12:24].view(np.float32).reshape(total, 3)).copy()
    colors = raw[:, 24:27].astype(np.float32) / 255.0
    opacities = raw[:, 27].astype(np.float32) / 255.0

    rot_raw = raw[:, 28:32].astype(np.float32)
    w = (rot_raw[:, 0] - 128.0) / 128.0
    x = (rot_raw[:, 1] - 128.0) / 128.0
    y = (rot_raw[:, 2] - 128.0) / 128.0
    z = (rot_raw[:, 3] - 128.0) / 128.0
    lens = np.sqrt(x * x + y * y + z * z + w * w)
    lens[lens == 0] = 1.0
    rotations = np.stack([x / lens, y / lens, z / lens, w / lens], axis=-1)

    # 重要度でソートして max_splats に絞る (SplatLoader と同一)
    importance = opacities * (scales[:, 0] * scales[:, 1] + scales[:, 1] * scales[:, 2] + scales[:, 2] * scales[:, 0])
    if total > max_splats:
        idx = np.argpartition(importance, -max_splats)[-max_splats:]
        idx = idx[np.argsort(importance[idx])[::-1]]  # 降順
        positions = positions[idx]
        scales = scales[idx]
        colors = colors[idx]
        opacities = opacities[idx]
        rotations = rotations[idx]

    # flipYZ (SplatLoader と同一: +Yが上、+Zが手前に変換)
    positions[:, 1] = -positions[:, 1]
    positions[:, 2] = -positions[:, 2]
    # 四元数に (1, 0, 0, 0) を左から掛ける: (x,y,z,w) -> (w, -z, y, -x)
    rx, ry, rz, rw = rotations[:, 0], rotations[:, 1], rotations[:, 2], rotations[:, 3]
    rotations = np.stack([rw, -rz, ry, -rx], axis=-1)

    return positions, scales, rotations, opacities, colors


def compute_bounds(positions):
    lo = int(math.floor((len(positions) - 1) * 0.01))
    hi = int(math.ceil((len(positions) - 1) * 0.99))
    xs = np.sort(positions[:, 0])
    ys = np.sort(positions[:, 1])
    zs = np.sort(positions[:, 2])
    return [xs[lo], ys[lo], zs[lo], xs[hi], ys[hi], zs[hi]]


def standard_ewa_rasterize(positions, scales, rotations, opacities, colors, yaw_deg, pitch_deg, size=512):
    """3DGS 標準の EWA ラスタライズ (視点方向の投影 2D 共分散からガウス重みを計算し α-blending)。"""
    N = len(positions)
    box = compute_bounds(positions)
    cx = (box[0] + box[3]) * 0.5
    cy = (box[1] + box[4]) * 0.5
    cz = (box[2] + box[5]) * 0.5
    extent = max(box[3] - box[0], max(box[4] - box[1], box[5] - box[2]))
    fit = 1.6 / extent if extent > 0 else 1.0

    yaw = math.radians(yaw_deg)
    pitch = math.radians(pitch_deg)
    cosY, sinY = math.cos(yaw), math.sin(yaw)
    cosP, sinP = math.cos(pitch), math.sin(pitch)

    # 回転行列 R = Rx * Ry
    Ry = np.array([[cosY, 0, sinY], [0, 1, 0], [-sinY, 0, cosY]], dtype=np.float32)
    Rx = np.array([[1, 0, 0], [0, cosP, -sinP], [0, sinP, cosP]], dtype=np.float32)
    R_cam = Rx @ Ry

    # モデル中心化 & 回転
    centered = positions - np.array([cx, cy, cz], dtype=np.float32)
    rotated_pos = (centered @ R_cam.T) * fit

    # 3D 共分散 Sigma = R_splat * S^2 * R_splat^T
    # 各スプラットの回転行列
    qx, qy, qz, qw = rotations[:, 0], rotations[:, 1], rotations[:, 2], rotations[:, 3]
    R_splat = np.zeros((N, 3, 3), dtype=np.float32)
    R_splat[:, 0, 0] = 1 - 2 * (qy * qy + qz * qz)
    R_splat[:, 0, 1] = 2 * (qx * qy - qz * qw)
    R_splat[:, 0, 2] = 2 * (qx * qz + qy * qw)
    R_splat[:, 1, 0] = 2 * (qx * qy + qz * qw)
    R_splat[:, 1, 1] = 1 - 2 * (qx * qx + qz * qz)
    R_splat[:, 1, 2] = 2 * (qy * qz - qx * qw)
    R_splat[:, 2, 0] = 2 * (qx * qz - qy * qw)
    R_splat[:, 2, 1] = 2 * (qy * qz + qx * qw)
    R_splat[:, 2, 2] = 1 - 2 * (qx * qx + qy * qy)

    M = R_splat * scales[:, None, :]  # M = R * S
    # カメラ空間へ変換: M_cam = fit * (R_cam @ M)
    M_cam = fit * (R_cam[None, :, :] @ M)
    Sigma_cam = M_cam @ np.swapaxes(M_cam, 1, 2)  # Sigma_cam = M_cam @ M_cam^T

    # 2D 射影共分散 (x, y)
    Sigma_2d = Sigma_cam[:, :2, :2].copy()
    # 画面ピクセル空間へ変換: J = diag(size/2, -size/2)
    # V = J @ Sigma_2d @ J^T
    s = size * 0.5
    Sigma_pix = np.zeros((N, 2, 2), dtype=np.float32)
    Sigma_pix[:, 0, 0] = Sigma_2d[:, 0, 0] * (s * s)
    Sigma_pix[:, 0, 1] = -Sigma_2d[:, 0, 1] * (s * s)
    Sigma_pix[:, 1, 0] = -Sigma_2d[:, 1, 0] * (s * s)
    Sigma_pix[:, 1, 1] = Sigma_2d[:, 1, 1] * (s * s)
    # EWA low-pass filter (0.3 pixel radius)
    Sigma_pix[:, 0, 0] += 0.3
    Sigma_pix[:, 1, 1] += 0.3

    # ピクセル中心 (sx, sy)
    px = (rotated_pos[:, 0] * 0.5 + 0.5) * size
    py = (0.5 - rotated_pos[:, 1] * 0.5) * size
    pz = rotated_pos[:, 2]

    # 奥から手前へソート (pz が小さいものが奥、大きいものが手前)
    order = np.argsort(pz)

    # バッファの初期化 (市松模様)
    image = np.zeros((size, size, 4), dtype=np.float32)
    y_coords, x_coords = np.indices((size, size))
    dark = (((x_coords >> 5) + (y_coords >> 5)) % 2 == 0)
    bg_r = np.where(dark, 0x6E / 255.0, 0x84 / 255.0)
    bg_g = np.where(dark, 0x7F / 255.0, 0x94 / 255.0)
    bg_b = np.where(dark, 0x8D / 255.0, 0xA1 / 255.0)
    image[:, :, 0] = bg_r
    image[:, :, 1] = bg_g
    image[:, :, 2] = bg_b
    image[:, :, 3] = 1.0

    # スプラットの描画 (奥から手前へオーバーレイ合成)
    for idx in order:
        op = opacities[idx]
        if op < 0.05:
            continue
        cx_i = px[idx]
        cy_i = py[idx]
        cov = Sigma_pix[idx]
        det = cov[0, 0] * cov[1, 1] - cov[0, 1] * cov[1, 0]
        if det <= 1e-6:
            continue
        inv_cov = np.array([[cov[1, 1], -cov[0, 1]], [-cov[1, 0], cov[0, 0]]], dtype=np.float32) / det

        radius = int(math.ceil(3.0 * math.sqrt(max(cov[0, 0], cov[1, 1]))))
        min_x = max(0, int(math.floor(cx_i - radius)))
        max_x = min(size - 1, int(math.ceil(cx_i + radius)))
        min_y = max(0, int(math.floor(cy_i - radius)))
        max_y = min(size - 1, int(math.ceil(cy_i + radius)))
        if min_x > max_x or min_y > max_y:
            continue

        gx = np.arange(min_x, max_x + 1, dtype=np.float32) + 0.5 - cx_i
        gy = np.arange(min_y, max_y + 1, dtype=np.float32) + 0.5 - cy_i
        GX, GY = np.meshgrid(gx, gy)

        power = -0.5 * (GX * GX * inv_cov[0, 0] + 2 * GX * GY * inv_cov[0, 1] + GY * GY * inv_cov[1, 1])
        mask = power <= 0.0
        alpha = op * np.exp(np.clip(power, -10.0, 0.0)) * mask
        alpha_mask = alpha >= 0.02  # 小さな値は打ち切り

        if not np.any(alpha_mask):
            continue

        a = alpha[..., None]
        c = colors[idx][None, None, :]
        sub_img = image[min_y:max_y + 1, min_x:max_x + 1, :3]
        # アルファ合成: C = C_prev * (1 - a) + C_new * a
        image[min_y:max_y + 1, min_x:max_x + 1, :3] = sub_img * (1.0 - a) + c * a

    res = np.clip(image * 255.0, 0, 255).astype(np.uint8)
    return Image.fromarray(res, mode="RGBA")


def compute_psnr(img1, img2):
    a = np.array(img1.convert("RGB"), dtype=np.float64)
    b = np.array(img2.convert("RGB"), dtype=np.float64)
    mse = np.mean((a - b) ** 2)
    if mse == 0:
        return float("inf")
    return 20.0 * math.log10(255.0 / math.sqrt(mse))


def main():
    splat_file = DATA_DIR / "lizard_10k.splat"
    print(f"Loading {splat_file}...")
    positions, scales, rotations, opacities, colors = load_splat(splat_file)

    views = [
        ("front", 0, 0),
        ("diagonal", 35, 20),
        ("side", 90, 0)
    ]

    results = []
    for name, yaw, pitch in views:
        print(f"Rendering EWA reference for view: {name} (yaw={yaw}, pitch={pitch})...")
        ref_img = standard_ewa_rasterize(positions, scales, rotations, opacities, colors, yaw, pitch, size=512)
        ref_path = PREVIEW_DIR / f"lizard_10k_ref_{name}.png"
        ref_img.save(ref_path)

        ms_path = PREVIEW_DIR / f"lizard_10k_splat_{name}.png"
        if not ms_path.exists():
            print(f"Warning: {ms_path} not found")
            continue
        ms_img = Image.open(ms_path)

        psnr = compute_psnr(ref_img, ms_img)
        print(f"View {name}: PSNR = {psnr:.2f} dB")
        results.append((name, psnr, ref_path, ms_path))

        # 左右に並べた比較画像を生成
        w, h = ref_img.size
        comp = Image.new("RGBA", (w * 2 + 10, h), (30, 30, 30, 255))
        comp.paste(ref_img, (0, 0))
        comp.paste(ms_img, (w + 10, 0))
        comp_path = PREVIEW_DIR / f"lizard_10k_compare_{name}.png"
        comp.save(comp_path)
        print(f"Saved comparison to {comp_path}")

    print("\n=== Summary ===")
    for name, psnr, _, _ in results:
        print(f"  {name:<10}: PSNR = {psnr:.2f} dB")


if __name__ == "__main__":
    main()
