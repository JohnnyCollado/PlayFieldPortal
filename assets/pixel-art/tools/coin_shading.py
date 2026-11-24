# Step one for any Shiba coin: its shading, made against the original by overlay.
# Usage: coin_shading.py <work dir> <tier>   reads <tier>_ref64.raw, writes <tier>_shading.raw
# The original, reduced to 64 px, is the guide. Each round: place every pixel in the nearest colour of
# a small palette, lay the result over the guide, grade it, then move each palette colour to the
# average of the guide pixels it was given. Repeat until the grade reaches the target. Then merge
# stray single pixels into their neighbours and grade again.
import sys
import os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from coin_grade import grade
W = sys.argv[1]; TIER = sys.argv[2]; TARGET = 98.0
ref = open(W + '/' + TIER + '_ref64.raw', 'rb').read(); N = 4096
mask = [ref[i*4+3] >= 128 for i in range(N)]
px = [(ref[i*4], ref[i*4+1], ref[i*4+2]) for i in range(N)]

def render(pal, assign):
    out = bytearray(N * 4)
    for i in range(N):
        if mask[i]:
            c = pal[assign[i]]; out[i*4:i*4+4] = bytes((int(round(c[0])), int(round(c[1])), int(round(c[2])), 255))
    return out

def nearest(pal, p):
    best = 0; bd = 1e18
    for k, c in enumerate(pal):
        d = (c[0]-p[0])**2 + (c[1]-p[1])**2 + (c[2]-p[2])**2
        if d < bd: bd = d; best = k
    return best

def run(k):
    # Start palette: k colours spread evenly through the guide's pixels ordered dark to light.
    order = sorted([i for i in range(N) if mask[i]], key=lambda i: sum(px[i]))
    pal = [list(px[order[int((j + 0.5) * len(order) / k)]]) for j in range(k)]
    assign = [0] * N; g = 0
    for rnd in range(1, 41):
        for i in range(N):
            if mask[i]: assign[i] = nearest(pal, px[i])
        g = grade(ref, render(pal, assign))
        sums = [[0, 0, 0, 0] for _ in pal]
        for i in range(N):
            if mask[i]:
                s = sums[assign[i]]; p = px[i]; s[0] += p[0]; s[1] += p[1]; s[2] += p[2]; s[3] += 1
        new = [[s[0]/s[3], s[1]/s[3], s[2]/s[3]] if s[3] else pal[j] for j, s in enumerate(sums)]
        moved = max(abs(a - b) for c, d in zip(pal, new) for a, b in zip(c, d))
        pal = new
        if moved < 0.5: break
    return pal, assign, g

k = 12
while True:
    print('trying %d colours' % k)
    pal, assign, g = run(k)
    print('  after adjusting the palette: grade %.2f' % g)
    # Clean-up: a pixel with no same-colour neighbour beside it (up, down, left, right) and at least
    # three neighbours that agree with each other is noise; give it their colour.
    merged = 0
    for _ in range(3):
        for i in range(N):
            if not mask[i]: continue
            x, y = i % 64, i // 64
            nb = [assign[j] for j in ((i-1) if x > 0 else -1, (i+1) if x < 63 else -1, (i-64) if y > 0 else -1, (i+64) if y < 63 else -1)
                  if j >= 0 and mask[j]]
            if assign[i] in nb or len(nb) < 3: continue
            top = max(set(nb), key=nb.count)
            if nb.count(top) >= 3: assign[i] = top; merged += 1
    g2 = grade(ref, render(pal, assign))
    print('  after merging %d stray pixels: grade %.2f' % (merged, g2))
    if g2 >= TARGET: break
    k += 4
used = sorted(set(assign[i] for i in range(N) if mask[i]))
open(W + '/' + TIER + '_shading.raw', 'wb').write(render(pal, assign))
print('FINAL: %d colours, grade %.2f' % (len(used), g2))
