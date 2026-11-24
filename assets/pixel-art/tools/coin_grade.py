# Overlay grade: lays a 64 px draft over the original reduced to 64 px and scores how closely the
# colour (and so the shading) matches, pixel by pixel.
#   grade = 100 * (1 - mean absolute colour difference / 255), over every pixel either image covers.
# A pixel one covers and the other does not counts as fully wrong.
import sys
def load(p): return open(p, 'rb').read()
def grade(ref, d, N=64 * 64):
    tot = 0.0; n = 0
    for i in range(N):
        ra = ref[i*4+3] >= 128; da = d[i*4+3] >= 128
        if not ra and not da: continue
        n += 1
        if ra != da: tot += 255.0; continue
        tot += (abs(ref[i*4]-d[i*4]) + abs(ref[i*4+1]-d[i*4+1]) + abs(ref[i*4+2]-d[i*4+2])) / 3.0
    return 100.0 * (1 - tot / n / 255.0)
if __name__ == '__main__':
    ref = load(sys.argv[1])
    for p in sys.argv[2:]: print('%.2f  %s' % (grade(ref, load(p)), p.split('/')[-1]))
