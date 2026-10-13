"""動作確認で看板に貼る画像を作る。4 色の領域と中央の円で、向きや反転が見て分かるようにしている。"""

from pathlib import Path

from PIL import Image, ImageDraw

WIDTH, HEIGHT = 320, 200
COLORS = [(230, 60, 60, 255), (60, 180, 75, 255), (60, 110, 230, 255), (250, 200, 40, 255)]

image = Image.new("RGBA", (WIDTH, HEIGHT), (0, 0, 0, 0))
draw = ImageDraw.Draw(image)
for index, color in enumerate(COLORS):
    x, y = (index % 2) * WIDTH // 2, (index // 2) * HEIGHT // 2
    draw.rectangle([x, y, x + WIDTH // 2 - 1, y + HEIGHT // 2 - 1], fill=color)
draw.ellipse([WIDTH // 2 - 50, HEIGHT // 2 - 50, WIDTH // 2 + 50, HEIGHT // 2 + 50],
             fill=(255, 255, 255, 255), outline=(0, 0, 0, 255), width=4)
draw.text((WIDTH // 2 - 12, HEIGHT // 2 - 6), "TEST", fill=(0, 0, 0, 255))

output = Path(__file__).with_name("test-image.png")
image.save(output)
print(f"Wrote {output}")
