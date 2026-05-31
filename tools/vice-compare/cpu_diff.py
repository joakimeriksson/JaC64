#!/usr/bin/env python3
"""Phase J — Per-cycle CPU diff between JaC64 and VICE traces.

Aligns instruction sequences by skipping past boot until both traces
reach the SAME PC, then walks instruction-by-instruction comparing
state.

Trace format (both emulators):
    I=N PC=$xxxx op=$xx clk=NNN A=$xx X=$xx Y=$xx SP=$xx P=$xx
        rast=N cyc=N ba=N

Usage:
    cpu_diff.py <vice_trace> <jac64_trace>
        [--skip-boot]            Skip until first SYS jump
        [--ignore-op]            Don't compare op byte (JaC64 quirk)
        [--max-divergences N]    Stop after N divergent events
        [--alignment-pc 0xXXXX]  PC at which to anchor alignment
"""
import argparse
import re
import sys


LINE_RE = re.compile(
    r'^I=(\d+) PC=\$([0-9a-f]+) op=\$([0-9a-f]+) clk=(\d+) '
    r'A=\$([0-9a-f]+) X=\$([0-9a-f]+) Y=\$([0-9a-f]+) '
    r'SP=\$([0-9a-f]+) P=\$([0-9a-f]+) '
    r'rast=(\d+) cyc=(-?\d+) ba=(\d+)$'
)


def parse_line(line):
    m = LINE_RE.match(line.strip())
    if not m:
        return None
    return {
        'I': int(m.group(1)),
        'PC': int(m.group(2), 16),
        'op': int(m.group(3), 16),
        'clk': int(m.group(4)),
        'A': int(m.group(5), 16),
        'X': int(m.group(6), 16),
        'Y': int(m.group(7), 16),
        'SP': int(m.group(8), 16),
        'P': int(m.group(9), 16),
        'rast': int(m.group(10)),
        'cyc': int(m.group(11)),
        'ba': int(m.group(12)),
    }


def load_trace(path):
    entries = []
    bad = 0
    with open(path) as f:
        for line in f:
            e = parse_line(line)
            if e is None:
                bad += 1
                continue
            entries.append(e)
    return entries, bad


def find_alignment(vice, jac64, anchor_pc=None):
    """Find the first index pair (vi, ji) where both PC match and the
    sequence aligns for at least N=10 instructions. If anchor_pc is
    given, find the FIRST occurrence of that PC in both traces."""
    if anchor_pc is not None:
        vi = next((i for i, e in enumerate(vice) if e['PC'] == anchor_pc), None)
        ji = next((i for i, e in enumerate(jac64) if e['PC'] == anchor_pc), None)
        if vi is None or ji is None:
            return None
        return (vi, ji)
    # Otherwise: scan for first 10-instruction PC match
    for vi in range(len(vice) - 10):
        v_pcs = tuple(vice[vi + k]['PC'] for k in range(10))
        for ji in range(max(0, vi - 5000), min(len(jac64) - 10, vi + 5000)):
            j_pcs = tuple(jac64[ji + k]['PC'] for k in range(10))
            if v_pcs == j_pcs:
                return (vi, ji)
    return None


def diff(vice, jac64, vi_start, ji_start, max_divergences, ignore_op):
    """Walk both traces from vi_start/ji_start in lockstep, report
    divergences. Aligns by instruction sequence number (each step
    advances both vi and ji by 1)."""
    out = []
    fields = ['PC', 'A', 'X', 'Y', 'SP', 'P', 'clk_delta', 'rast', 'cyc', 'ba']
    if not ignore_op:
        fields.insert(1, 'op')
    prev_v_clk = None
    prev_j_clk = None
    n = min(len(vice) - vi_start, len(jac64) - ji_start)
    for step in range(n):
        v = vice[vi_start + step]
        j = jac64[ji_start + step]
        v_dclk = (v['clk'] - prev_v_clk) if prev_v_clk is not None else 0
        j_dclk = (j['clk'] - prev_j_clk) if prev_j_clk is not None else 0
        prev_v_clk = v['clk']
        prev_j_clk = j['clk']
        diff_fields = []
        for f in fields:
            if f == 'clk_delta':
                if step > 0 and v_dclk != j_dclk:
                    diff_fields.append(('clk_delta', v_dclk, j_dclk))
            elif f == 'op':
                if v['op'] != j['op']:
                    diff_fields.append((f, v['op'], j['op']))
            elif f in ('PC', 'A', 'X', 'Y', 'SP', 'P', 'rast', 'cyc', 'ba'):
                if v[f] != j[f]:
                    diff_fields.append((f, v[f], j[f]))
        if diff_fields:
            out.append((step, v, j, diff_fields))
            if len(out) >= max_divergences:
                break
    return out, n


def fmt_state(e, prev_clk=None):
    dclk = e['clk'] - prev_clk if prev_clk else 0
    return (f"I={e['I']} PC=${e['PC']:04x} op=${e['op']:02x} clk={e['clk']}"
            f" (+{dclk}) A=${e['A']:02x} X=${e['X']:02x} Y=${e['Y']:02x}"
            f" SP=${e['SP']:02x} P=${e['P']:02x}"
            f" rast={e['rast']} cyc={e['cyc']} ba={e['ba']}")


def main():
    p = argparse.ArgumentParser(description="JaC64 vs VICE per-cycle CPU diff")
    p.add_argument('vice', help='VICE PC trace path')
    p.add_argument('jac64', help='JaC64 PC trace path')
    p.add_argument('--ignore-op', action='store_true',
                   help='Ignore op byte (JaC64 reads bogus byte for ROM regions)')
    p.add_argument('--max-divergences', type=int, default=10)
    p.add_argument('--alignment-pc', type=lambda s: int(s, 0),
                   help='Anchor PC for alignment (hex with 0x or decimal)')
    args = p.parse_args()

    print(f"Loading {args.vice}...", file=sys.stderr)
    vice, vbad = load_trace(args.vice)
    print(f"Loading {args.jac64}...", file=sys.stderr)
    jac64, jbad = load_trace(args.jac64)
    print(f"VICE: {len(vice)} events ({vbad} unparsed)", file=sys.stderr)
    print(f"JaC64: {len(jac64)} events ({jbad} unparsed)", file=sys.stderr)

    aligned = find_alignment(vice, jac64, args.alignment_pc)
    if aligned is None:
        print("ERROR: could not align traces.", file=sys.stderr)
        return 2
    vi, ji = aligned
    print(f"Aligned at VICE I={vice[vi]['I']} (idx {vi}) PC=${vice[vi]['PC']:04x}"
          f", JaC64 I={jac64[ji]['I']} (idx {ji}) PC=${jac64[ji]['PC']:04x}",
          file=sys.stderr)
    print(file=sys.stderr)

    divs, n_compared = diff(vice, jac64, vi, ji, args.max_divergences, args.ignore_op)
    print(f"Compared {n_compared} instructions from alignment point.", file=sys.stderr)
    print(f"Divergences found: {len(divs)}", file=sys.stderr)
    print(file=sys.stderr)

    if not divs:
        print("✓ No divergences detected.")
        return 0

    for i, (step, v, j, diff_fields) in enumerate(divs):
        print(f"=== Divergence #{i+1} at step {step} (VICE I={v['I']}, JaC64 I={j['I']}) ===")
        print(f"  VICE : {fmt_state(v)}")
        print(f"  JaC64: {fmt_state(j)}")
        print(f"  Diffs:")
        for f, vv, jv in diff_fields:
            if f in ('PC', 'op'):
                print(f"    {f}: VICE=${vv:x}, JaC64=${jv:x}")
            elif f in ('A', 'X', 'Y', 'SP', 'P'):
                print(f"    {f}: VICE=${vv:02x}, JaC64=${jv:02x}")
            else:
                print(f"    {f}: VICE={vv}, JaC64={jv}")
        print()
    return 1


if __name__ == '__main__':
    sys.exit(main())
