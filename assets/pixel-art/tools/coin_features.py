# Step two for any Shiba coin: a clean outline and crisp features placed by hand over the shading.
# Usage: coin_features.py <work dir> <tier> [crown]   ("crown" redraws the platinum crown by hand); reads <tier>_shading.raw, writes <tier>_final.raw
# Every position below was read by eye from a numbered enlargement of that coin's own shading, so
# each feature sits where the original's does. Prints the overlay grade after each step.
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from coin_grade import grade
W, TIER = sys.argv[1], sys.argv[2]; N = 4096
def hexc(h): return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16))

# Colours per coin: the outline and features take a near-black tinted like the metal, the brow
# marks the coin's own palest tone, and the tongue stays the one warm accent.
# Shapes per coin: eyes are 2 x 2 squares, solid (the owner asked for black eyes, 21 Nov 2026).
SPEC = {
  'bronze': dict(K='2a1610', C1='f7ead6', T='c2493a', M='4a1a12',
                 eyes=(27, 36), eye_y=31, brows=((27, 27), (35, 27)), nose_x=(30, 33), mouth_x=(30, 33)),
  'silver': dict(K='14161e', C1='e8eaf2', T='b04a50', M='3a1a20',
                 eyes=(27, 36), eye_y=31, brows=((28, 28), (35, 28)), nose_x=(31, 33), mouth_x=(30, 34)),
  'gold': dict(K='2a1a08', C1='f8e8bc', T='c2493a', M='4a1a10',
               eyes=(26, 36), eye_y=31, brows=((28, 27), (35, 27)), nose_x=(30, 33), mouth_x=(30, 33)),
  'platinum': dict(K='0c1424', C1='f0f4fb', T='b8485a', M='30141e',
                   eyes=(26, 36), eye_y=31, brows=((27, 28), (35, 27)), nose_x=(30, 33), mouth_x=(30, 33)),
}[TIER]
K, C1, T, M = (hexc(SPEC[k]) for k in ('K', 'C1', 'T', 'M'))
ref = open(W + '/' + TIER + '_ref64.raw', 'rb').read()
d = bytearray(open(W + '/' + TIER + '_shading.raw', 'rb').read())
def put(x, y, c):
    i = y * 64 + x
    if d[i*4+3] >= 128: d[i*4:i*4+3] = bytes(c)
def step(name): print('  %-28s grade %.2f' % (name, grade(ref, d)))
step('shading only')
edge = []
for y in range(64):
    for x in range(64):
        if d[(y*64+x)*4+3] < 128: continue
        for nx, ny in ((x-1, y), (x+1, y), (x, y-1), (x, y+1)):
            if nx < 0 or nx > 63 or ny < 0 or ny > 63 or d[(ny*64+nx)*4+3] < 128: edge.append((x, y)); break
for x, y in edge: put(x, y, K)
step('+ outline')
ey = SPEC['eye_y']
for ex in SPEC['eyes']:
    for y in (ey, ey + 1):
        for x in (ex, ex + 1): put(x, y, K)
step('+ eyes, solid')
for bx, by in SPEC['brows']:
    for y in (by, by + 1):
        for x in (bx, bx + 1): put(x, y, C1)
step('+ brow marks')
n0, n1 = SPEC['nose_x']
mid = (n0 + n1) // 2
tip = (mid, mid + 1) if (n1 - n0) % 2 else (mid,)
for x in range(n0, n1 + 1): put(x, 35, K)
# Bronze keeps the narrower nose it was approved with; the others read as a fuller block.
for x in (tip if TIER == 'bronze' else range(n0, n1 + 1)): put(x, 36, K)
for x in tip: put(x, 37, K)
step('+ nose')
m0, m1 = SPEC['mouth_x']
put(m0 - 2, 39, K); put(m0 - 1, 40, K); put(m1 + 1, 40, K); put(m1 + 2, 39, K)
for x in range(m0, m1 + 1): put(x, 40, M); put(x, 41, T)
for x in range(m0 + 1, m1): put(x, 42, T)
put(m0, 42, K); put(m1, 42, K)
step('+ smile and tongue')
if TIER == 'platinum' and len(sys.argv) > 3 and sys.argv[3] == 'crown':
    # The platinum coin alone wears a crown. Shrunk, it was a blue smear over the head, so it is
    # redrawn as one clear shape: three points on a band, lit on their left sides, with a navy
    # outline. The corner positions were read from the original's crown, enlarged.
    CM, CL, CD = hexc('1f62c8'), hexc('9cc8f4'), hexc('123f96')
    poly = [(24, 22.5), (24.5, 14), (28, 18.5), (31.5, 10), (35, 18.5), (38.5, 14), (39, 22.5)]
    def inside(x, y):
        n = False; j = len(poly) - 1
        for i in range(len(poly)):
            (xi, yi), (xj, yj) = poly[i], poly[j]
            if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi) + xi: n = not n
            j = i
        return n
    crown = set((x, y) for y in range(8, 23) for x in range(20, 44) if inside(x + 0.5, y + 0.5))
    for x, y in crown:
        c = CM
        if (x - 1, y) not in crown: c = CL            # the side of each point that faces the light
        elif (x + 1, y) not in crown or y >= 21: c = CD   # the far side, and the band's foot
        put(x, y, c)
    for x, y in crown:
        for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1)):
            if (nx, ny) not in crown: put(nx, ny, K)
    step('+ crown, redrawn')
open(W + '/' + TIER + '_final.raw', 'wb').write(d)
print('  colours: %d' % len(set(bytes(d[i*4:i*4+3]) for i in range(N) if d[i*4+3] >= 128)))
