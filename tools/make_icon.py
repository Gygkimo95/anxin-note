"""生成启动图标：纸色底 + 苔绿的一圈和一个勾。

跑一次就够，产物已经提交在 res/mipmap-* 里。
    python3 tools/make_icon.py
"""

import os

from PIL import Image, ImageDraw

PAPER = (238, 242, 234, 255)
MOSS = (67, 96, 63, 255)

# 前景在自适应图标里会被裁掉边缘，内容留在中间约 66% 的安全区。
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res")


def draw_icon(size, inset_ratio, with_background):
    scale = 8
    canvas = size * scale
    image = Image.new("RGBA", (canvas, canvas), PAPER if with_background else (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)

    inset = canvas * inset_ratio
    box = (inset, inset, canvas - inset, canvas - inset)
    ring = max(2, int(canvas * 0.055))
    draw.ellipse(box, outline=MOSS, width=ring)

    # 圈里一个勾，笔画端点圆一点，别太尖。
    left, top, right, bottom = box
    width = right - left
    points = [
        (left + width * 0.28, top + width * 0.52),
        (left + width * 0.44, top + width * 0.68),
        (left + width * 0.73, top + width * 0.34),
    ]
    draw.line(points, fill=MOSS, width=ring, joint="curve")
    for point in points:
        radius = ring / 2
        draw.ellipse(
            (point[0] - radius, point[1] - radius, point[0] + radius, point[1] + radius),
            fill=MOSS,
        )

    return image.resize((size, size), Image.LANCZOS)


def main():
    for density, size in DENSITIES.items():
        target = os.path.join(ROOT, "mipmap-" + density)
        os.makedirs(target, exist_ok=True)

        draw_icon(size, 0.16, True).save(os.path.join(target, "ic_launcher.png"))
        # 自适应前景是 108dp 画布，可见区只有中间 72dp，所以内容再缩一圈。
        draw_icon(int(size * 108 / 48), 0.30, False).save(
            os.path.join(target, "ic_launcher_foreground.png")
        )
        print("wrote", target)


if __name__ == "__main__":
    main()
