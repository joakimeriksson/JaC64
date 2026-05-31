#!/usr/bin/env python3
"""
cycle_align.py — phase-aligned per-cycle VIC-II state diff, JaC64 vs VICE.

Both emit identical `EV-State clk=.. rast=$x cyc=N vc=.. vmli=.. rc=.. vcbase=..
idle=.. bad=.. abl=.. ys=..` lines (JaC: -Djac64.traceVicState=true; VICE:
JAC64_TRACE_FILE_STATE=path). Absolute clk drifts between the two emulators,
and for dynamic (FLI / per-line $D011) tests the demo PHASE drifts too, so we
cannot align by clk or by "last frame". Instead we:

  1. Split each trace into frames (a frame boundary = raster_line wraps high->low).
  2. Fingerprint each frame by its per-raster YSCROLL+abl pattern (the FLI
     signature — identical only at the same demo phase).
  3. Find the JaC frame and VICE frame whose fingerprints match best.
  4. Diff those two frames keyed by (raster, cycle); report state divergences.

Usage: cycle_align.py <jac.log> <vice.log> [--field vc,vmli,rc,idle,bad,abl,ys]
                                            [--rast LO-HI] [--max N]
"""
import re, sys, argparse
from collections import defaultdict

FIELDS = ["vc", "vmli", "rc", "vcbase", "idle", "bad", "abl", "ys"]
PAT = re.compile(
    r"EV-State clk=(\d+) rast=\$([0-9a-f]+) cyc=(\d+) "
    r"vc=(\d+) vmli=(\d+) rc=(\d+) vcbase=(\d+) idle=(\d+) bad=(\d+) abl=(\d+) ys=(\d+)")

def parse(path):
    """Return list of frames; each frame is dict[(rast,cyc)] = {field:val}."""
    frames, cur, prev_rast = [], {}, -1
    for ln in open(path):
        m = PAT.search(ln)
        if not m:
            continue
        clk = int(m.group(1)); rast = int(m.group(2), 16); cyc = int(m.group(3))
        vals = dict(zip(FIELDS, map(int, m.groups()[3:])))
        if rast < prev_rast - 4:           # raster wrapped -> new frame
            if cur: frames.append(cur)
            cur = {}
        prev_rast = rast
        cur[(rast, cyc)] = vals
    if cur: frames.append(cur)
    return frames

def fingerprint(frame):
    """Per-raster (ys,abl) at the lowest cyc seen for that raster — the FLI sig."""
    byrast = {}
    for (rast, cyc), v in frame.items():
        if rast not in byrast or cyc < byrast[rast][0]:
            byrast[rast] = (cyc, v["ys"], v["abl"])
    return {r: (t[1], t[2]) for r, t in byrast.items()}

def fp_distance(a, b):
    # The two traces deliberately cover different raster ranges (VICE EV-State
    # is gated to a window), so compare ONLY rasters present in both and do NOT
    # penalize differing length. Normalize by #common so a frame with few
    # common rasters can't win by accident; require >=8 common rasters.
    common = set(a) & set(b)
    if len(common) < 8:
        return 10**9
    mism = sum(a[r] != b[r] for r in common)
    return mism / len(common)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("jac"); ap.add_argument("vice")
    ap.add_argument("--field", default="vc,vmli,rc,idle,bad,abl,ys")
    ap.add_argument("--rast", default=None, help="LO-HI hex raster filter, e.g. 30-40")
    ap.add_argument("--max", type=int, default=60, help="max divergences to print")
    a = ap.parse_args()
    fields = a.field.split(",")
    rlo, rhi = (None, None)
    if a.rast:
        rlo, rhi = (int(x, 16) for x in a.rast.split("-"))

    jf, vf = parse(a.jac), parse(a.vice)
    print(f"JaC frames: {len(jf)}  VICE frames: {len(vf)}")
    if not jf or not vf:
        print("no frames parsed — check trace files"); return

    # pick the best-matching frame pair (search last few JaC frames vs all VICE)
    best = None
    for ji in range(max(0, len(jf) - 5), len(jf)):
        fpj = fingerprint(jf[ji])
        for vi in range(len(vf)):
            d = fp_distance(fpj, fingerprint(vf[vi]))
            if best is None or d < best[0]:
                best = (d, ji, vi)
    d, ji, vi = best
    print(f"aligned: JaC frame {ji} <-> VICE frame {vi}  (fingerprint mismatch={d} rasters)")
    J, V = jf[ji], vf[vi]

    keys = sorted(set(J) & set(V))
    ndiff = 0
    print(f"\n(rast,cyc)  field  JaC -> VICE")
    print("-" * 50)
    for (rast, cyc) in keys:
        if rlo is not None and not (rlo <= rast <= rhi):
            continue
        for f in fields:
            if J[(rast, cyc)][f] != V[(rast, cyc)][f]:
                if ndiff < a.max:
                    print(f"  $%02x/%-2d   %-5s  %s -> %s" %
                          (rast, cyc, f, J[(rast, cyc)][f], V[(rast, cyc)][f]))
                ndiff += 1
    onlyj = len(set(J) - set(V)); onlyv = len(set(V) - set(J))
    print("-" * 50)
    print(f"total field-divergences: {ndiff}   (cells only-in-JaC={onlyj}, only-in-VICE={onlyv})")

if __name__ == "__main__":
    main()
