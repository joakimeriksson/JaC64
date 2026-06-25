#!/usr/bin/env python3
# k3_scroll_gray.py — quantify the K3 picture-mover left-edge FLI-bug across a
# whole fpsCapture scroll sweep (NOT a single frame — single-frame capture
# passed while the live scroll failed, 2026-06-25).
#
# Metric per frame: number of picture rows whose leftmost non-black pixel is at
# the 38-col left-edge char (x in [LO,HI]) = the gray garbage column. A correct
# render shows that char as border/background (black) on (almost) all rows.
#
# Also reports a frame-to-frame STABILITY proxy: the stddev of per-frame gray
# counts across consecutive same-ish scroll frames — a scroll FAILURE (one-cell
# jump toggling per frame) shows as HIGH variance even if the mean drops.
#
# Usage: k3_scroll_gray.py '<glob>'   e.g. '/tmp/sweep_base/*.png'
import sys, glob, os, re
from PIL import Image

LO, HI = 39, 46  # the leftmost 38-col display char (measured x-range)

def gray_rows(path):
    im = Image.open(path).convert('RGB')
    w, h = im.size
    n = 0
    for y in range(h):
        x0 = -1
        for x in range(60):
            if im.getpixel((x, y)) != (0, 0, 0):
                x0 = x; break
        if LO <= x0 <= HI:
            n += 1
    return n

files = sorted(glob.glob(sys.argv[1]))
if not files:
    print("no files match", sys.argv[1]); sys.exit(1)

per_scroll = {}
total = 0
counts = []
for f in files:
    m = re.search(r'_s([0-9a-f]{2})\.png', f)
    s = m.group(1) if m else '??'
    g = gray_rows(f)
    total += g
    counts.append(g)
    per_scroll.setdefault(s, []).append(g)

import statistics
nz = [c for c in counts if c > 0]
print(f"frames={len(files)}  total_gray_rows={total}  "
      f"frames_with_gray={len(nz)}  max_per_frame={max(counts)}  "
      f"mean={statistics.mean(counts):.1f}  stdev={statistics.pstdev(counts):.1f}")
# per-scroll worst offenders
worst = sorted(((max(v), s, len(v)) for s, v in per_scroll.items()), reverse=True)[:12]
print("worst scrolls (max_gray, scroll, nframes):",
      ' '.join(f"{s}:{mx}" for mx, s, _ in worst))
