"""
Keeps one bird out of the source sheet.

The downloaded asset is a contact sheet of 12 kea silhouettes, not a single
mark. For a launcher icon we want exactly one bird, fully interior (some are
clipped by the sheet edge), and as square as possible so it fills the canvas.

Run:  python tools/pick_bird.py [source.jpg]
Writes a numbered contact sheet to brand/bird_candidates.png
"""

from __future__ import annotations

import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(
    os.environ.get("USERPROFILE", ""), "Downloads",
    "a-set-of-silhouettes-of-kea-parrot-in-flight-vector.jpg",
)


def components(mask: np.ndarray, min_area: int = 2000):
    """4-neighbour connected components.

    Returned **largest-area-first**, which makes the index a stable identifier:
    the `#N` printed by this tool is the same N that `make_icon.py --bird N`
    expects. Discovery order would be scan-order dependent and would silently
    disagree between the two tools.
    """
    h, w = mask.shape
    seen = np.zeros(mask.shape, dtype=bool)
    out = []
    ys, xs = np.nonzero(mask)
    for sy, sx in zip(ys.tolist(), xs.tolist()):
        if seen[sy, sx]:
            continue
        stack = [(sy, sx)]
        seen[sy, sx] = True
        miny = maxy = sy
        minx = maxx = sx
        n = 0
        while stack:
            y, x = stack.pop()
            n += 1
            miny, maxy = min(miny, y), max(maxy, y)
            minx, maxx = min(minx, x), max(maxx, x)
            for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                ny, nx = y + dy, x + dx
                if 0 <= ny < h and 0 <= nx < w and mask[ny, nx] and not seen[ny, nx]:
                    seen[ny, nx] = True
                    stack.append((ny, nx))
        if n >= min_area:
            out.append(dict(area=n, box=(minx, miny, maxx, maxy)))
    out.sort(key=lambda c: c["area"], reverse=True)
    return out


def clipped(box, size) -> bool:
    """True if the silhouette runs into the sheet edge and is therefore cut off."""
    x0, y0, x1, y1 = box
    return x0 <= 1 or y0 <= 1 or x1 >= size[0] - 2 or y1 >= size[1] - 2


def score(c: dict, size) -> float:
    """Prefer unclipped, large, and square silhouettes."""
    x0, y0, x1, y1 = c["box"]
    w, h = x1 - x0 + 1, y1 - y0 + 1
    if clipped(c["box"], size):
        return -1.0
    return c["area"] * (min(w, h) / max(w, h)) ** 2


def main() -> int:
    src = sys.argv[1] if len(sys.argv) > 1 else SRC
    im = Image.open(src).convert("L")
    mask = np.asarray(im) < 128
    comps = components(mask)   # already largest-first
    if not comps:
        print("no silhouettes found", file=sys.stderr)
        return 1

    ranked = sorted(range(len(comps)), key=lambda i: score(comps[i], im.size), reverse=True)
    print(f"{len(comps)} silhouettes found")
    for rank, i in enumerate(ranked):
        c = comps[i]
        x0, y0, x1, y1 = c["box"]
        print(
            f"  rank {rank:2d} -> component {i:2d}  area={c['area']:7d} "
            f"bbox=({x0},{y0})-({x1},{y1}) {x1-x0+1}x{y1-y0+1} "
            f"{'CLIPPED' if clipped(c['box'], im.size) else 'ok'}"
        )

    # Contact sheet, labelled with the component index used by make_icon.py.
    cell = 200
    cols = 4
    rows = (len(comps) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * cell, rows * (cell + 22)), (245, 245, 248))
    d = ImageDraw.Draw(sheet)
    try:
        font = ImageFont.truetype("arial.ttf", 13)
    except OSError:
        font = ImageFont.load_default()

    for i, c in enumerate(comps):
        x0, y0, x1, y1 = c["box"]
        pad = 8
        crop = im.crop((x0 - pad, y0 - pad, x1 + 1 + pad, y1 + 1 + pad))
        crop = crop.point(lambda v: 255 if v < 128 else 0).convert("L")
        rgba = Image.new("RGBA", crop.size, (94, 234, 212, 255))
        rgba.putalpha(crop)
        scale = min((cell - 24) / rgba.width, (cell - 24) / rgba.height)
        rgba = rgba.resize(
            (max(1, int(rgba.width * scale)), max(1, int(rgba.height * scale))),
            Image.LANCZOS,
        )
        cx = (i % cols) * cell + cell // 2
        cy = (i // cols) * (cell + 22) + cell // 2
        sheet.paste(rgba, (cx - rgba.width // 2, cy - rgba.height // 2), rgba)
        label = f"#{i}" + (" CLIPPED" if clipped(c["box"], im.size) else "")
        d.text(((i % cols) * cell + 8, (i // cols) * (cell + 22) + cell + 2),
               label, fill=(20, 20, 24), font=font)

    brand = os.path.join(ROOT, "brand")
    os.makedirs(brand, exist_ok=True)
    out = os.path.join(brand, "bird_candidates.png")
    sheet.save(out)
    print(f"wrote {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())