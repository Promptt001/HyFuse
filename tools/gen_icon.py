#!/usr/bin/env python3
"""Generate the HyFuse mod icon: dark-navy rounded square, cyan 'fuse' bolt,
amber spark tip. Palette matches docs/images (navy #10192B dominant, cyan
#38D6E0, amber #F2B233). Output: assets/hyfuse/icon.png (128x128, RGBA)."""
from PIL import Image, ImageDraw

NAVY = (16, 25, 43, 255)
NAVY_EDGE = (23, 36, 61, 255)
CYAN = (56, 214, 224, 255)
CYAN_DARK = (32, 150, 160, 255)
AMBER = (242, 178, 51, 255)

S = 8  # supersample factor for smooth edges
img = Image.new("RGBA", (128 * S, 128 * S), (0, 0, 0, 0))
d = ImageDraw.Draw(img)

def rr(box, r, fill):
    d.rounded_rectangle(box, radius=r, fill=fill)

# rounded-square base with subtle edge band
pad = 6 * S
rr((pad, pad, 128 * S - pad, 128 * S - pad), 26 * S, NAVY_EDGE)
rr((pad + 2 * S, pad + 2 * S, 128 * S - pad - 2 * S, 128 * S - pad - 2 * S), 24 * S, NAVY)

# fuse bolt: a stylized zig lightning path (cyan) with amber tip spark
def bolt(pts, w, fill):
    d.line(pts, fill=fill, width=w, joint="curve")

cx = 64 * S
# main bolt (two strokes for a chunky look)
bolt1 = [(cx - 22 * S, 30 * S), (cx - 4 * S, 56 * S), (cx - 16 * S, 58 * S), (cx + 18 * S, 98 * S)]
bolt(bolt1, 9 * S, CYAN_DARK)   # shadow stroke
bolt2 = [(cx - 24 * S, 28 * S), (cx - 6 * S, 54 * S), (cx - 18 * S, 56 * S), (cx + 16 * S, 96 * S)]
bolt(bolt2, 6 * S, CYAN)
# amber spark at the top of the bolt
d.ellipse((cx - 34 * S, 20 * S, cx - 18 * S, 36 * S), fill=AMBER)
# small cyan node bottom-left of bolt for balance
d.ellipse((cx - 2 * S, 88 * S, cx + 10 * S, 100 * S), fill=CYAN_DARK)

img = img.resize((128, 128), Image.LANCZOS)
img.save("src/main/resources/assets/hyfuse_icon.png")
print("written", img.size)
