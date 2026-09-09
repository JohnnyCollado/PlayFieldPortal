"""Generate Touch UI helper glyphs matching the ctl_* controller icon set.

Style contract, measured from core/core-ui/src/main/res/drawable-nodpi/ctl_*.png:
  - 128x128 RGBA, pure #FFFFFF, transparent ground, no colour, no gradients
  - flat outline/solid-fill mix, ~7px stroke, round joins
  - content bbox ~94px centred (dpad glyphs), up to ~119px for wide shapes
"""
from PIL import Image, ImageDraw
import os, math

# The PNGs sit beside this script, so regenerating overwrites them in place.
OUT = os.path.dirname(os.path.abspath(__file__))

S = 6            # supersample factor
N = 128          # final canvas
C = N * S
W = (255, 255, 255, 255)


def new():
    im = Image.new("RGBA", (C, C), (255, 255, 255, 0))
    return im, ImageDraw.Draw(im)


def s(v):
    return v * S


def circle(d, cx, cy, r, fill=W):
    d.ellipse([s(cx - r), s(cy - r), s(cx + r), s(cy + r)], fill=fill)


def ring(d, cx, cy, r_out, stroke, fill=W):
    """Ring whose OUTER edge sits at r_out, with thickness `stroke`.

    PIL strokes inward from the bounding box, so the bbox radius is r_out.
    """
    d.ellipse([s(cx - r_out), s(cy - r_out), s(cx + r_out), s(cy + r_out)],
              outline=fill, width=int(round(s(stroke))))


def arc(d, cx, cy, r_out, stroke, a0, a1, fill=W):
    """Arc on the same radius convention, with round caps."""
    d.arc([s(cx - r_out), s(cy - r_out), s(cx + r_out), s(cy + r_out)],
          a0, a1, fill=fill, width=int(round(s(stroke))))
    rc = r_out - stroke / 2.0          # cap centres ride the stroke midline
    for a in (a0, a1):
        circle(d, cx + rc * math.cos(math.radians(a)),
               cy + rc * math.sin(math.radians(a)), stroke / 2.0, fill)


def bar(d, x0, y0, x1, y1, stroke, fill=W):
    """Straight bar with round caps."""
    d.line([s(x0), s(y0), s(x1), s(y1)], fill=fill, width=int(round(s(stroke))))
    circle(d, x0, y0, stroke / 2.0, fill)
    circle(d, x1, y1, stroke / 2.0, fill)


def tri(d, pts, fill=W):
    d.polygon([(s(x), s(y)) for x, y in pts], fill=fill)


def save(im, name):
    out = im.resize((N, N), Image.LANCZOS)
    out.save(os.path.join(OUT, name + ".png"))
    return out


# ── Shared geometry ──────────────────────────────────────────────────────────
STROKE = 7.0     # matches ctl_ns_dpad_* outline weight
RING_R = 47.0    # outer radius -> 94px content box, same as the dpad glyphs
DOT_R = 17.0     # the contact point, shared by every glyph in the set

# ── Tap: contact point inside one closed ring (a single quick contact) ───────
im, d = new()
ring(d, 64, 64, RING_R, STROKE)
circle(d, 64, 64, DOT_R)
save(im, "ctl_touch_tap")

# ── Long-press: same contact point, doubled ring (a held, spreading contact) ─
# Radii give an even rhythm outward -- dot 17 | gap 9 | band 7 | gap 7 | band 7.
INNER_RING_R = 33.0
im, d = new()
ring(d, 64, 64, RING_R, STROKE)
ring(d, 64, 64, INNER_RING_R, STROKE)
circle(d, 64, 64, DOT_R)
save(im, "ctl_touch_long_press")

# ── Swipe: contact point + travel shaft + solid arrowhead ────────────────────
# Arrowhead is deliberately broad: a bare arrow reads optically lighter than
# the 94px-wide dpad/face glyphs it shares a row with.
APEX_Y, BASE_Y, HALF_W = 18.0, 50.0, 27.0
SHAFT_STROKE = 12.0
SHAFT_Y0, SHAFT_Y1 = 50.0, 70.0
DOT_CY = 103.0          # 10px clear of the shaft cap so the two never merge at 22dp

im, d = new()
tri(d, [(64, APEX_Y), (64 - HALF_W, BASE_Y), (64 + HALF_W, BASE_Y)])
bar(d, 64, SHAFT_Y0, 64, SHAFT_Y1, SHAFT_STROKE)
circle(d, 64, DOT_CY, DOT_R)      # same contact point as tap / long-press
up = im

save(up, "ctl_touch_swipe_up")
save(up.transpose(Image.FLIP_TOP_BOTTOM), "ctl_touch_swipe_down")
save(up.rotate(-90), "ctl_touch_swipe_right")
save(up.rotate(90), "ctl_touch_swipe_left")

# ── Swipe any direction: sibling of ctl_*_dpad_all ───────────────────────────
im, d = new()
circle(d, 64, 64, 17.0)
A_APEX, A_BASE, A_HALF = 14.0, 36.0, 19.0
for k in range(4):
    th = math.radians(90 * k)
    pts = [(64, A_APEX), (64 - A_HALF, A_BASE), (64 + A_HALF, A_BASE)]
    tri(d, [(64 + (x - 64) * math.cos(th) - (y - 64) * math.sin(th),
             64 + (x - 64) * math.sin(th) + (y - 64) * math.cos(th))
            for x, y in pts])
save(im, "ctl_touch_swipe_all")

print("wrote", len([f for f in os.listdir(OUT) if f.endswith(".png")]), "PNGs to", OUT)
