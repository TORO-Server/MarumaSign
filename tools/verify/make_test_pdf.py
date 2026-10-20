"""動作確認で看板に貼る PDF を作る。

1 ページ目はテスト画像 (test-image.png) と同じ 4 色の領域と中央の円、2 ページ目はそれを 180 度回したもの。
ページ送りのあとに、画面の色の並びで 2 ページ目が表示されていることを確かめられるようにしている。
画像を埋め込むのではなく、図形と文字 (標準フォント) で描いて、PDF の描画そのものも確かめる。
"""

from pathlib import Path

WIDTH, HEIGHT = 320, 200
RED, GREEN, BLUE, YELLOW = (230, 60, 60), (60, 180, 75), (60, 110, 230), (250, 200, 40)


def color(rgb):
    return " ".join(f"{c / 255:.3f}" for c in rgb)


def page_content(top_left, top_right, bottom_left, bottom_right, label):
    half_w, half_h = WIDTH // 2, HEIGHT // 2
    ops = []
    # PDF の座標は左下が原点
    for rgb, x, y in ((top_left, 0, half_h), (top_right, half_w, half_h),
                      (bottom_left, 0, 0), (bottom_right, half_w, 0)):
        ops.append(f"{color(rgb)} rg {x} {y} {half_w} {half_h} re f")
    # 中央の白い円 (4 本のベジエ曲線) と黒い縁
    cx, cy, r = half_w, half_h, 50
    k = r * 0.5523
    ops.append("1 1 1 rg 0 0 0 RG 4 w")
    ops.append(f"{cx + r} {cy} m "
               f"{cx + r} {cy + k} {cx + k} {cy + r} {cx} {cy + r} c "
               f"{cx - k} {cy + r} {cx - r} {cy + k} {cx - r} {cy} c "
               f"{cx - r} {cy - k} {cx - k} {cy - r} {cx} {cy - r} c "
               f"{cx + k} {cy - r} {cx + r} {cy - k} {cx + r} {cy} c b")
    ops.append(f"0 0 0 rg BT /F1 14 Tf {cx - 22} {cy - 5} Td ({label}) Tj ET")
    return "\n".join(ops).encode("ascii")


def build():
    pages = [page_content(RED, GREEN, BLUE, YELLOW, "TEST 1"),
             page_content(YELLOW, BLUE, GREEN, RED, "TEST 2")]
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [" + b" ".join(f"{4 + i * 2} 0 R".encode() for i in range(len(pages)))
        + f"] /Count {len(pages)} >>".encode(),
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ]
    for index, content in enumerate(pages):
        content_id = 5 + index * 2
        objects.append(f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 {WIDTH} {HEIGHT}] "
                       f"/Resources << /Font << /F1 3 0 R >> >> /Contents {content_id} 0 R >>".encode())
        objects.append(f"<< /Length {len(content)} >>\nstream\n".encode() + content + b"\nendstream")

    out = bytearray(b"%PDF-1.4\n")
    offsets = []
    for number, body in enumerate(objects, start=1):
        offsets.append(len(out))
        out += f"{number} 0 obj\n".encode() + body + b"\nendobj\n"
    xref = len(out)
    out += f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode()
    for offset in offsets:
        out += f"{offset:010d} 00000 n \n".encode()
    out += f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()
    return bytes(out)


output = Path(__file__).with_name("test-pdf.pdf")
output.write_bytes(build())
print(f"Wrote {output}")
