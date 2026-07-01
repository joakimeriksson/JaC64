#!/usr/bin/env python3
"""data_align.py — align JaC vs VICE FetchG/FetchC DATA traces and diff the
fetched bytes (gbuf/vbuf/cbuf). State (vc/rc) is known to match; this finds
the DATA-layer divergence (the deer light-gray). Frames are split on raster
wrap and fingerprint-matched by the per-raster vc@cyc16 sequence; cells are
keyed by (rast, vc) so the vmli emission offset doesn't misalign them.

Usage: data_align.py jac.fg vice.fg [--rast LO-HI]
"""
import re, sys

def parse(path, is_vice):
    # returns list of frames; frame = dict[(rast,vc)] -> gbuf(int)
    rx = re.compile(r"rast=\$([0-9a-f]+) cyc=(\d+) ")
    gx = re.compile(r"gbuf=\$([0-9a-f]+)" if is_vice else r" data=\$([0-9a-f]+)")
    vx = re.compile(r" vc=(\d+)")
    # VICE prints the g-fetch addr; JaC prints addr=$.. too. Key by addr (the
    # physical fetch) so the vc pre/post-inc label offset doesn't misalign.
    ax = re.compile(r" addr=\$([0-9a-f]+)")
    tag = "EV-FetchG-VICE" if is_vice else "EV-FetchG "
    frames, cur, prev = [], {}, -1
    for line in open(path):
        if tag not in line: continue
        m = rx.search(line); g = gx.search(line); v = vx.search(line); a = ax.search(line)
        if not (m and g and v and a): continue
        rast = int(m.group(1),16); cyc=int(m.group(2)); vc=int(v.group(1))
        gb=int(g.group(1),16); addr=int(a.group(1),16)
        if rast < prev - 4:
            if cur: frames.append(cur)
            cur = {}
        prev = rast
        cur[(rast,addr)] = (gb, cyc)
    if cur: frames.append(cur)
    return frames

def fp(frame):
    # fingerprint: sorted list of (rast, min-vc) — FLI vcbase pattern per raster
    per = {}
    for (rast,vc) in frame:
        per[rast] = min(per.get(rast, 1<<30), vc)
    return per

def fpdist(a, b):
    ks = set(a)&set(b)
    if not ks: return 1e9
    return sum(abs(a[r]-b[r]) for r in ks)/len(ks) + abs(len(a)-len(b))

def main():
    jac_p, vice_p = sys.argv[1], sys.argv[2]
    rlo, rhi = 0, 0x140
    if "--rast" in sys.argv:
        lo,hi = sys.argv[sys.argv.index("--rast")+1].split("-")
        rlo,rhi = int(lo,16), int(hi,16)
    J = parse(jac_p, False); V = parse(vice_p, True)
    print(f"JaC frames={len(J)} VICE frames={len(V)}")
    # best matching frame pair
    best=None
    for i,jf in enumerate(J):
        for k,vf in enumerate(V):
            d=fpdist(fp(jf),fp(vf))
            if best is None or d<best[0]: best=(d,i,k)
    d,i,k = best
    jf,vf = J[i],V[k]
    print(f"aligned JaC[{i}] <-> VICE[{k}] fpdist={d:.2f}")
    # compare gbuf by (rast,vc)
    keys = sorted(set(jf)&set(vf))
    diffs=0; shown=0
    for (rast,vc) in keys:
        if rast<rlo or rast>rhi: continue
        jg,jc = jf[(rast,vc)]; vg,vc2 = vf[(rast,vc)]
        if jg!=vg:
            diffs+=1
            if shown<50:
                print(f"  rast=${rast:x} vc={vc} (jcyc={jc} vcyc={vc2}): JaC gbuf=${jg:02x} -> VICE gbuf=${vg:02x}")
                shown+=1
    common=sum(1 for kk in keys if rlo<=kk[0]<=rhi)
    print(f"gbuf diffs: {diffs}/{common} common (rast,vc) cells")

main()
