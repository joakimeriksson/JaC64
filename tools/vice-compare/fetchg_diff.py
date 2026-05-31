#!/usr/bin/env python3
"""
fetchg_diff.py — data-layer JaC<->VICE diff: compares the actual g-fetch
ADDRESS / data / $D018 per (raster,cycle), phase-aligned via EV-State.

EV-State only carries vc/vmli/rc/idle/bad/abl/ys; the residual diffs on
border-mcbm / bitmap live in the FETCH DATA (addr, gbuf, $D018) which only
EV-FetchG captures. This tool:
  1. frame-splits both EV-State streams and aligns by YSCROLL/abl fingerprint
     (same as cycle_align), to find the matching JaC<->VICE frame + clk ranges;
  2. parses EV-FetchG (JaC) / EV-FetchG-VICE (VICE), restricts to those frames'
     clk ranges, keys by (rast,cyc), and reports addr/data/$D018 divergences.

Usage: fetchg_diff.py <jac.state> <vice.state> <jac.fg> <vice.fg>
                      [--rast LO-HI] [--max N]
"""
import re, sys, argparse

STATE = re.compile(r"EV-State clk=(\d+) rast=\$([0-9a-f]+) cyc=(\d+) .* abl=(\d+) ys=(\d+)")
# JaC: EV-FetchG clk=.. rast=$.. cyc=.. col=N addr=$X data=$Y d018=$Z vc=V rc=R
JFG = re.compile(r"EV-FetchG clk=(\d+) rast=\$([0-9a-f]+) cyc=(\d+) col=(\d+) addr=\$([0-9a-f]+) data=\$([0-9a-f]+) d018=\$([0-9a-f]+)")
# VICE: EV-FetchG-VICE clk=.. rast=$.. cyc=.. vmli=N vc=V rc=R addr=$X .. gbuf=$Y .. d018=$Z
VFG = re.compile(r"EV-FetchG-VICE clk=(\d+) rast=\$([0-9a-f]+) cyc=(\d+) vmli=(\d+) vc=\d+ rc=\d+ addr=\$([0-9a-f]+).* gbuf=\$([0-9a-f]+).* d018=\$([0-9a-f]+)")

def state_frames(path):
    """list of (clk_min, clk_max, {rast:(ys,abl)})."""
    frames, cur, prev = [], None, -1
    for ln in open(path):
        m = STATE.search(ln)
        if not m: continue
        clk, rast, cyc, abl, ys = int(m[1]), int(m[2],16), int(m[3]), int(m[4]), int(m[5])
        if rast < prev - 4:
            if cur: frames.append(cur)
            cur = [clk, clk, {}]
        if cur is None: cur = [clk, clk, {}]
        prev = rast
        cur[1] = clk
        if rast not in cur[2]: cur[2][rast] = (ys, abl)
    if cur: frames.append(cur)
    return frames

def align(jf, vf):
    def dist(a, b):
        common = set(a[2]) & set(b[2])
        if len(common) < 8: return 1e9
        return sum(a[2][r] != b[2][r] for r in common) / len(common)
    best = None
    for ji in range(max(0, len(jf)-5), len(jf)):
        for vi in range(len(vf)):
            d = dist(jf[ji], vf[vi])
            if best is None or d < best[0]: best = (d, ji, vi)
    return best

def fg_in_range(path, pat, clk_lo, clk_hi, is_vice):
    """{(rast,cyc): (col, addr, data, d018)} within [clk_lo,clk_hi]."""
    out = {}
    for ln in open(path):
        m = pat.search(ln)
        if not m: continue
        clk = int(m[1])
        if not (clk_lo <= clk <= clk_hi): continue
        rast, cyc, col, addr, data, d018 = int(m[2],16), int(m[3]), int(m[4]), int(m[5],16), int(m[6],16), int(m[7],16)
        # CAVEAT: JaC's addr is POST-bank, VICE's EV-FetchG addr is PRE-bank
        # (raw); VICE adds vbank1 separately (eff field). For VICE, fold the
        # bank in so addr is comparable to JaC. The eff= field carries it.
        if is_vice:
            me = re.search(r"eff=\$([0-9a-f]+)", ln)
            if me: addr = int(me.group(1), 16)
        out[(rast, cyc)] = (col, addr, data, d018)
    return out

def fg_frames(path, pat):
    """Frame-split the FetchG stream (raster wrap); each frame =
    ({(rast,cyc):(col,addr,data,d018)}, fingerprint {rast:(d018,data@lowcyc)})."""
    frames, cells, fp, prev = [], {}, {}, -1
    def flush():
        if cells: frames.append((dict(cells), dict(fp)))
    for ln in open(path):
        m = pat.search(ln)
        if not m: continue
        rast, cyc = int(m[2],16), int(m[3])
        col, addr, data, d018 = int(m[4]), int(m[5],16), int(m[6],16), int(m[7],16)
        if rast < prev - 4:
            flush(); cells, fp = {}, {}
        prev = rast
        cells[(rast, cyc)] = (col, addr, data, d018)
        if rast not in fp: fp[rast] = d018       # $D018 = the FLI signature
    flush()
    return frames

def align_fg(jf, vf):
    def dist(a, b):
        common = set(a[1]) & set(b[1])
        if len(common) < 6: return 1e9
        return sum(a[1][r] != b[1][r] for r in common) / len(common)
    best = None
    for ji in range(len(jf)):
        for vi in range(len(vf)):
            d = dist(jf[ji], vf[vi])
            if best is None or d < best[0]: best = (d, ji, vi)
    return best

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("jstate"); ap.add_argument("vstate"); ap.add_argument("jfg"); ap.add_argument("vfg")
    ap.add_argument("--rast", default=None); ap.add_argument("--max", type=int, default=50)
    a = ap.parse_args()
    rlo, rhi = (int(x,16) for x in a.rast.split("-")) if a.rast else (0, 0x140)

    # Align on the $D018-per-raster fingerprint from the FetchG streams (the FLI
    # signature); EV-State ys/abl alone matched wrong-$D018 phases.
    jfr, vfr = fg_frames(a.jfg, JFG), fg_frames(a.vfg, VFG)
    print(f"JaC fg frames: {len(jfr)}  VICE fg frames: {len(vfr)}")
    d, ji, vi = align_fg(jfr, vfr)
    print(f"aligned JaC fg frame {ji} <-> VICE fg frame {vi}  $D018-fp mismatch={d:.3f}")
    J, V = jfr[ji][0], vfr[vi][0]
    print(f"JaC fg cells: {len(J)}  VICE fg cells: {len(V)}  common: {len(set(J)&set(V))}")

    keys = sorted(set(J) & set(V))
    nd = {"addr":0,"data":0,"d018":0}
    print(f"\n(rast,cyc) col  field  JaC -> VICE")
    print("-"*52)
    shown = 0
    for k in keys:
        if not (rlo <= k[0] <= rhi): continue
        jc, ja, jd, j18 = J[k]; vc, va, vd, v18 = V[k]
        for name, jv, vv in (("addr",ja,va),("data",jd,vd),("d018",j18,v18)):
            if jv != vv:
                nd[name]+=1
                if shown < a.max:
                    print(f"  $%02x/%-2d c%-2d %-5s $%x -> $%x" % (k[0],k[1],jc,name,jv,vv)); shown+=1
    print("-"*52)
    print(f"divergences: addr={nd['addr']} data={nd['data']} d018={nd['d018']}")

if __name__ == "__main__":
    main()
