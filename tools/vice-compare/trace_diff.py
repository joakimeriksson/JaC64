#!/usr/bin/env python3
"""
Diff two VIC-II per-cycle traces (VICE x64sc with JAC64_TRACE_VICE=1 vs
JaC64 with -Djac64.traceVicCycle=true).

Both emitters write EV-* events with absolute clk + (raster_line, raster_cycle)
context. Absolute clks diverge between emulators, so this tool aligns by
the (line, cycle) tuple within ONE chosen frame and reports events that
fire in one trace but not the other (or with different payloads).

Usage:
    trace_diff.py vice.log jac64.log [--vice-frame N] [--jac64-frame N]

If --vice-frame / --jac64-frame are omitted the LAST captured frame in
each trace is used (typically what you want when you ran with
-limitcycles long enough to reach the visible scene).
"""

import argparse
import re
import sys
from collections import defaultdict


def parse_clk(s):
    m = re.search(r"clk=(\d+)", s)
    return int(m.group(1)) if m else None


def parse_line(s):
    # Accept either "rast=$3a" (hex) or "from=N to=M"
    m = re.search(r"rast=\$([0-9a-fA-F]+)", s)
    if m:
        return int(m.group(1), 16)
    m = re.search(r"\bto=(\d+)", s)
    return int(m.group(1)) if m else None


def parse_cyc(s):
    m = re.search(r"\bcyc=(\d+)", s)
    if m:
        return int(m.group(1))
    m = re.search(r"\brastCyc=(\d+)", s)
    return int(m.group(1)) if m else None


def parse_event(s):
    """Return (event_name, payload_text)."""
    m = re.search(r"\b(EV-[A-Za-z0-9_]+|VICE-[A-Za-z0-9_]+)\b", s)
    if not m:
        return None, None
    name = m.group(1)
    # Payload = rest of the line minus the matched event name and clk/line/cyc context
    payload = re.sub(r"clk=\d+", "", s)
    payload = re.sub(r"rast=\$[0-9a-fA-F]+", "", payload)
    payload = re.sub(r"\bcyc=\d+", "", payload)
    payload = re.sub(r"\bfrom=\d+", "", payload)
    payload = re.sub(r"\bto=\d+", "", payload)
    payload = re.sub(r"\brastCyc=\d+", "", payload)
    payload = re.sub(name, "", payload, count=1)
    payload = re.sub(r"\s+", " ", payload).strip()
    return name, payload


def split_frames(path):
    """Yield list-of-events per frame, keyed by frame index.

    Frame boundary = EV-LineInc with from=0 (start of new frame's first
    actual line). VICE skips the 311->0 transition, JaC64 emits it
    separately — both still emit from=0 -> to=1 at frame top.

    Returns dict {frame_idx: [(line, cyc, name, payload, raw)]}.
    Also returns events with line/cyc None when not derivable (stored
    with the current frame in time order).
    """
    frames = defaultdict(list)
    frame_idx = 0
    pending = []
    with open(path, "r") as f:
        for raw in f:
            raw = raw.rstrip("\n")
            name, payload = parse_event(raw)
            if name is None:
                continue
            line = parse_line(raw)
            cyc = parse_cyc(raw)
            # Frame boundary: LineInc with from=0
            if name == "EV-LineInc":
                m = re.search(r"\bfrom=(\d+)", raw)
                if m and int(m.group(1)) == 0:
                    frames[frame_idx] = pending
                    pending = []
                    frame_idx += 1
            pending.append((line, cyc, name, payload, raw))
    if pending:
        frames[frame_idx] = pending
    return frames


def index_frame(events):
    """Group events by (line, cyc) within a frame."""
    out = defaultdict(list)
    for (line, cyc, name, payload, raw) in events:
        key = (line, cyc)
        out[key].append((name, payload, raw))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("vice_log")
    ap.add_argument("jac64_log")
    ap.add_argument("--vice-frame", type=int, default=None,
                    help="Frame index in VICE trace (default: last)")
    ap.add_argument("--jac64-frame", type=int, default=None,
                    help="Frame index in JaC64 trace (default: last)")
    ap.add_argument("--max-diffs", type=int, default=50,
                    help="Stop after N divergent (line,cyc) buckets")
    ap.add_argument("--ignore", action="append", default=[],
                    help="Event-name regex to ignore (repeatable)")
    ap.add_argument("--summary", action="store_true",
                    help="Print per-frame event-count summary (alignment aid)")
    args = ap.parse_args()

    ignore_re = [re.compile(p) for p in args.ignore]

    def is_ignored(name):
        return any(r.search(name) for r in ignore_re)

    print(f"Parsing VICE: {args.vice_log}")
    vice = split_frames(args.vice_log)
    print(f"  {len(vice)} frames")
    print(f"Parsing JaC64: {args.jac64_log}")
    jac = split_frames(args.jac64_log)
    print(f"  {len(jac)} frames")

    if args.summary:
        def fingerprint(events):
            c = defaultdict(int)
            for (_, _, n, _, _) in events:
                c[n] += 1
            return dict(c)

        def first_clk(events):
            for (_, _, _, _, raw) in events:
                clk = parse_clk(raw)
                if clk is not None:
                    return clk
            return None

        print("\nVICE frames:")
        for k in sorted(vice.keys())[-5:]:
            fp = fingerprint(vice[k])
            clk = first_clk(vice[k])
            print(f"  frame {k:5} clk≈{clk}  fp={fp}")
        print("\nJaC64 frames:")
        for k in sorted(jac.keys())[-5:]:
            fp = fingerprint(jac[k])
            clk = first_clk(jac[k])
            print(f"  frame {k:5} clk≈{clk}  fp={fp}")
        sys.exit(0)

    vidx = args.vice_frame if args.vice_frame is not None else max(vice.keys())
    jidx = args.jac64_frame if args.jac64_frame is not None else max(jac.keys())
    print(f"\nComparing VICE frame {vidx} vs JaC64 frame {jidx}\n")

    v_events = vice.get(vidx, [])
    j_events = jac.get(jidx, [])
    if not v_events or not j_events:
        print("ERR: one or both frames empty", file=sys.stderr)
        sys.exit(1)

    v_idx = index_frame(v_events)
    j_idx = index_frame(j_events)

    all_keys = sorted(set(v_idx.keys()) | set(j_idx.keys()),
                      key=lambda k: ((k[0] if k[0] is not None else -1),
                                     (k[1] if k[1] is not None else -1)))

    diffs = 0
    for key in all_keys:
        v_ev = [(n, p) for (n, p, _) in v_idx.get(key, []) if not is_ignored(n)]
        j_ev = [(n, p) for (n, p, _) in j_idx.get(key, []) if not is_ignored(n)]
        if v_ev == j_ev:
            continue
        # Normalize to event-name multisets for first-pass insight.
        v_names = sorted(n for (n, _) in v_ev)
        j_names = sorted(n for (n, _) in j_ev)
        only_v = sorted(set(v_names) - set(j_names))
        only_j = sorted(set(j_names) - set(v_names))
        line, cyc = key
        line_s = f"${line:x}" if line is not None else "?"
        cyc_s = str(cyc) if cyc is not None else "?"
        print(f"line={line_s} cyc={cyc_s}:")
        if only_v:
            print(f"  only-VICE: {only_v}")
        if only_j:
            print(f"  only-JaC64: {only_j}")
        # If same name set but different payloads, show payload mismatch.
        if v_names == j_names:
            for (v_n, v_p), (j_n, j_p) in zip(sorted(v_ev), sorted(j_ev)):
                if v_p != j_p:
                    print(f"  {v_n} payload diff:")
                    print(f"    VICE : {v_p}")
                    print(f"    JaC64: {j_p}")
        diffs += 1
        if diffs >= args.max_diffs:
            print(f"\n[stopped after {diffs} divergent buckets]")
            break

    if diffs == 0:
        print("Frames match — no divergence at the event-name level.")


if __name__ == "__main__":
    main()
