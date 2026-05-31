#!/usr/bin/env python3
"""
fetchsplit_diff.py — pixel-level diff between JaC64 and VICE x64sc fetchsplit
output. Shape-only comparison (ignores palette differences) by binarizing
each pixel as fg/bg per cell. Reports diffs per char-cell + per-raster.

Usage: python3 fetchsplit_diff.py <vice.png> <jac64.png>
"""

import sys
import subprocess
import numpy as np

try:
    from PIL import Image
except ModuleNotFoundError:
    Image = None


def load_rgb(path):
    if Image is not None:
        return np.array(Image.open(path).convert('RGB'))

    size = subprocess.check_output(
        ['magick', 'identify', '-format', '%w %h', path],
        text=True,
    )
    width, height = [int(part) for part in size.split()]
    raw = subprocess.check_output(
        ['magick', path, '-alpha', 'off', '-depth', '8', 'rgb:-']
    )
    return np.frombuffer(raw, dtype=np.uint8).reshape((height, width, 3)).copy()

def find_title_y(im):
    """Find first raster where non-border content appears."""
    a = np.array(im)
    border_color = a[5, 5]
    for y in range(a.shape[0]):
        diff = np.abs(a[y].astype(int) - border_color.astype(int)).sum(axis=1)
        if (diff > 50).sum() > 30:
            return y
    return 0


def bitmap_byte(arr, y, col, x0, char_w=8):
    """Encode 8 pixels of a char cell as a pattern of color-clusters.
    Each pixel gets a 0..N cluster id based on PROXIMITY in RGB space to
    the cell's color centroids. Bypasses palette-brightness sensitivity:
    two cells with the same shape but different palettes still hash to
    the same byte.

    Returns an integer encoding: bits 0..7 are pixel 7..0's cluster id
    modulo 2 (= bg/fg binary), matching legacy behaviour for 2-color
    cells. Multi-color cells encode the FIRST unique-color transition
    pattern."""
    cell = arr[y, x0 + col*char_w:x0 + (col+1)*char_w]
    # Find unique color clusters in this cell (within ~15 RGB units)
    cell_arr = cell.astype(int)
    cluster_ids = []
    centroids = []
    for px in range(char_w):
        p = cell_arr[px]
        best = -1
        for i, c in enumerate(centroids):
            if abs(p[0]-c[0]) + abs(p[1]-c[1]) + abs(p[2]-c[2]) < 60:
                best = i
                break
        if best < 0:
            best = len(centroids)
            centroids.append(p)
        cluster_ids.append(best)
    # bg = most-common cluster (= dominant background)
    counts = {}
    for cid in cluster_ids:
        counts[cid] = counts.get(cid, 0) + 1
    bg_cluster = max(counts.keys(), key=lambda k: counts[k])
    byte = 0
    for px in range(char_w):
        if cluster_ids[px] != bg_cluster:
            byte |= 1 << (7 - px)
    return byte


def diff_pngs(vice_path, jac_path):
    vice = load_rgb(vice_path)
    jac = load_rgb(jac_path)

    print(f"VICE:  {vice.shape}  Title-y: {find_title_y(vice)}")
    print(f"JaC64: {jac.shape}   Title-y: {find_title_y(jac)}")

    # Find display area horizontally — typically x=32..352 (40 chars * 8 px + 32 border)
    # PAL display visible: 320 px = cols 0..39
    x0 = 32
    num_cols = 40
    char_w = 8
    char_h = 8

    # Compute per-char bitmap byte for both. Char rows are 8 raster lines high.
    # Test display has 25 char rows.
    vice_title_y = find_title_y(vice)
    jac_title_y = find_title_y(jac)
    # Bitmap area starts AFTER the title char row
    vice_bm_y = vice_title_y + char_h
    jac_bm_y = jac_title_y + char_h

    # For each char row & each raster within row, compute per-col bitmap bytes
    print()
    print("=" * 92)
    print("Char-row diff summary (each row = 8 raster lines):")
    print(f"  Char-rows compared: rows 0..24 (200 raster lines)")
    print()

    total_diffs = 0
    diffs_per_row = []
    diffs_per_col = [0] * num_cols
    for char_row in range(25):
        row_diffs = 0
        col_diff_set = set()
        for raster_in_row in range(char_h):
            v_y = vice_bm_y + char_row * char_h + raster_in_row
            j_y = jac_bm_y + char_row * char_h + raster_in_row
            if v_y >= vice.shape[0] or j_y >= jac.shape[0]:
                continue
            for col in range(num_cols):
                vb = bitmap_byte(vice, v_y, col, x0)
                jb = bitmap_byte(jac, j_y, col, x0)
                if vb != jb:
                    row_diffs += 1
                    col_diff_set.add(col)
                    diffs_per_col[col] += 1
        diffs_per_row.append((char_row, row_diffs, sorted(col_diff_set)))
        total_diffs += row_diffs

    # Print per-row summary, only rows with diffs
    print(f"  {'row':<4} {'diffs':>5}  cols-with-diffs")
    print(f"  {'---':<4} {'-----':>5}  {'-' * 60}")
    for row, d, cols in diffs_per_row:
        if d > 0:
            print(f"  {row:<4} {d:>5}  {cols}")
    print()
    print(f"Total cell-diffs: {total_diffs}/{25*8*40} cells = {100*total_diffs/(25*8*40):.2f}%")

    print()
    print("Diffs per col (across all rows/rasters):")
    for col, d in enumerate(diffs_per_col):
        if d > 0:
            print(f"  col {col:2d}: {d}")

    # Show specific raster-line bitmap byte comparison for the most-different row
    if total_diffs > 0:
        print()
        worst_row = max(diffs_per_row, key=lambda x: x[1])
        cr = worst_row[0]
        print(f"=== Worst row: char-row {cr} ({worst_row[1]} diffs) ===")
        for raster_in_row in range(char_h):
            v_y = vice_bm_y + cr * char_h + raster_in_row
            j_y = jac_bm_y + cr * char_h + raster_in_row
            if v_y >= vice.shape[0] or j_y >= jac.shape[0]:
                continue
            v_line = [bitmap_byte(vice, v_y, col, x0) for col in range(num_cols)]
            j_line = [bitmap_byte(jac, j_y, col, x0) for col in range(num_cols)]
            diff = ['.' if a == b else 'X' for a, b in zip(v_line, j_line)]
            print(f"  raster_y={v_y}/{j_y}: " + ''.join(diff))


if __name__ == '__main__':
    if len(sys.argv) != 3:
        print(f"Usage: {sys.argv[0]} <vice.png> <jac64.png>")
        sys.exit(1)
    diff_pngs(sys.argv[1], sys.argv[2])
