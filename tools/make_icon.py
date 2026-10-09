"""
Generates KEA's launcher icon set from the kea silhouette sheet.

The source is a contact sheet of 12 dark kea silhouettes on white, not a single
mark, so this tool first isolates one silhouette (see pick_bird.py for the
picker), knocks out the white to real transparency, then renders every platform
asset from it. Keeping the generator in the repo means the icon is reproducible
rather than a mystery binary.

Run:  python tools/make_icon.py [--bird N] [source-image]

--bird picks the silhouette by component index from pick_bird.py. Default is the
      auto-scored best: unclipped, large, close to square.

Outputs:
    android/app/src/main/res/mipmap-*/ic_launcher*.png
    android/app/src/main/res/drawable-nodpi/kea_silhouette.png
    ios/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png
    brand/kea-mark.png, brand/kea-mark-white.png
"""

from __future__ import annotations

import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pick_bird import components, clipped, score  # noqa: E402

# --------------------------------------------------------------------------
# Palette (mirrors res/values/colors.xml)
# --------------------------------------------------------------------------

BG_DEEP = (11, 20, 18)        # #0B1412
BG_GLOW = (18, 44, 40)        # lifted centre, so the mark sits on a soft pool
ACCENT = (94, 234, 212)       # #5EEAD4
WHITE = (255, 255, 255)

ADAPTIVE_DP = 108             # adaptive icon canvas
SAFE_FRACTION = 0.58          # mark fills this much of the canvas; Android may
                              # crop the outer ~19% on each side
LEGACY_DP = 48

DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_SRC = os.path.join(
    os.environ.get("USERPROFILE", ""), "Downloads",
    "a-set-of-silhouettes-of-kea-parrot-in-flight-vector.jpg",
)


# --------------------------------------------------------------------------
# Source -> single transparent silhouette
# --------------------------------------------------------------------------


def isolate(src: Image.Image, bird: int | None) -> Image.Image:
    """Return one bird as teal-on-transparent.

    The alpha ramp keeps the anti-aliased edge instead of hard-thresholding it,
    and a median pass removes the JPEG ringing and the contact-sheet's
    hairline rules around some silhouettes.
    """
    grey = src.convert("L")

    if bird is None:
        comps = components(np.asarray(grey) < 128)
        if not comps:
            raise SystemExit("no silhouettes found in the source")
        best = max(range(len(comps)), key=lambda i: score(comps[i], src.size))
        box = comps[best]["box"]
        print(f"auto-selected silhouette: #{best} bbox={box}")
    else:
        comps = components(np.asarray(grey) < 128)
        if not 0 <= bird < len(comps):
            raise SystemExit(f"--bird must be 0..{len(comps) - 1}")
        box = comps[bird]["box"]
        print(f"using silhouette: #{bird} bbox={box}")

    # Widen the crop so neighbouring birds and sheet rules are excluded.
    x0, y0, x1, y1 = box
    pad = 6
    crop = grey.crop((max(0, x0 - pad), max(0, y0 - pad),
                      min(grey.width, x1 + 1 + pad), min(grey.height, y1 + 1 + pad)))

    lo, hi = 96, 232
    lut = []
    for v in range(256):
        t = (v - lo) / (hi - lo)
        lut.append(max(0, min(255, int(round(255 * (1.0 - t))))))
    alpha = crop.point(lut).filter(ImageFilter.MedianFilter(size=3))

    out = Image.new("RGBA", crop.size, WHITE + (0,))
    out.paste(Image.new("RGBA", crop.size, ACCENT + (255,)), mask=alpha)
    return out


def trim(im: Image.Image, pad_frac: float = 0.01) -> Image.Image:
    bbox = im.getbbox()
    if bbox is None:
        raise SystemExit("silhouette is empty — threshold too aggressive?")
    pad = int(max(im.size) * pad_frac)
    x0, y0, x1, y1 = bbox
    return im.crop((max(0, x0 - pad), max(0, y0 - pad),
                    min(im.width, x1 + pad), min(im.height, y1 + pad)))


def fit(im: Image.Image, size: int, fraction: float) -> Image.Image:
    target_w = int(size * fraction)
    scale = target_w / im.width
    target_h = max(1, int(round(im.height * scale)))
    resized = im.resize((target_w, target_h), Image.LANCZOS)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.paste(resized, ((size - target_w) // 2, (size - target_h) // 2))
    return canvas


def recolour(im: Image.Image, colour) -> Image.Image:
    out = Image.new("RGBA", im.size, tuple(colour) + (255,))
    out.putalpha(im.getchannel("A"))
    return out


def background(size: int) -> Image.Image:
    bg = Image.new("RGBA", (size, size), BG_DEEP + (255,))
    glow = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(glow)
    radius = int(size * 0.70)
    cx = cy = size / 2
    for i in range(64, 0, -1):
        t = i / 64
        r = radius * t
        d.ellipse([cx - r, cy - r, cx + r, cy + r],
                  fill=int(255 * (1 - t) ** 1.7))
    glow = glow.filter(ImageFilter.GaussianBlur(size / 20))
    bg.paste(Image.new("RGBA", (size, size), BG_GLOW + (255,)), mask=glow)
    return bg


def round_mask(size: int) -> Image.Image:
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).ellipse([0, 0, size - 1, size - 1], fill=255)
    return m


def squircle_mask(size: int, radius_frac: float = 0.22) -> Image.Image:
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).rounded_rectangle(
        [0, 0, size - 1, size - 1], radius=int(size * radius_frac), fill=255)
    return m


# --------------------------------------------------------------------------


def main() -> int:
    args = sys.argv[1:]
    bird: int | None = None
    if "--bird" in args:
        i = args.index("--bird")
        bird = int(args[i + 1])
        del args[i:i + 2]
    src_path = args[0] if args else DEFAULT_SRC
    if not os.path.isfile(src_path):
        print(f"source image not found: {src_path}", file=sys.stderr)
        return 1

    print(f"source: {src_path}")
    mark = trim(isolate(Image.open(src_path), bird))
    print(f"silhouette: {mark.size[0]}x{mark.size[1]}")

    accent = recolour(mark, ACCENT)
    white = recolour(mark, WHITE)
    written = 0

    res = os.path.join(ROOT, "android", "app", "src", "main", "res")
    for name, scale in DENSITIES.items():
        px = int(ADAPTIVE_DP * scale)
        d = os.path.join(res, f"mipmap-{name}")
        os.makedirs(d, exist_ok=True)

        fit(accent, px, SAFE_FRACTION).save(os.path.join(d, "ic_launcher_fg.png"))
        background(px).save(os.path.join(d, "ic_launcher_bg.png"))
        fit(white, px, SAFE_FRACTION).save(os.path.join(d, "ic_launcher_mono.png"))
        written += 3

        leg = int(LEGACY_DP * scale)
        leg_mark = fit(accent, leg, 0.74)
        for fname, mask in (("ic_launcher.png", squircle_mask(leg)),
                            ("ic_launcher_round.png", round_mask(leg))):
            comp = background(leg).convert("RGBA")
            comp.alpha_composite(leg_mark)
            comp.putalpha(Image.composite(
                comp.getchannel("A"), Image.new("L", (leg, leg), 0), mask))
            comp.save(os.path.join(d, fname))
            written += 1

    nodpi = os.path.join(res, "drawable-nodpi")
    os.makedirs(nodpi, exist_ok=True)
    fit(accent, 1024, 0.84).save(os.path.join(nodpi, "kea_silhouette.png"))
    written += 1

    ios_icons = os.path.join(ROOT, "ios", "Assets.xcassets", "AppIcon.appiconset")
    os.makedirs(ios_icons, exist_ok=True)
    ios_mark = fit(accent, 1024, 0.68)
    ios_master = background(1024).convert("RGB")
    ios_master.paste(ios_mark.convert("RGB"), (0, 0), ios_mark.getchannel("A"))
    ios_master.save(os.path.join(ios_icons, "AppIcon-1024.png"))
    written += 1
    with open(os.path.join(ios_icons, "Contents.json"), "w", encoding="utf-8") as fh:
        fh.write(
            '{\n  "images" : [\n    {\n      "filename" : "AppIcon-1024.png",\n'
            '      "idiom" : "universal",\n      "platform" : "ios",\n'
            '      "size" : "1024x1024"\n    }\n  ],\n'
            '  "info" : {\n    "author" : "xcode",\n    "version" : 1\n  }\n}\n'
        )

    brand = os.path.join(ROOT, "brand")
    os.makedirs(brand, exist_ok=True)
    fit(accent, 1024, 0.84).save(os.path.join(brand, "kea-mark.png"))
    fit(white, 1024, 0.84).save(os.path.join(brand, "kea-mark-white.png"))
    written += 2

    print(f"wrote {written} files")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())