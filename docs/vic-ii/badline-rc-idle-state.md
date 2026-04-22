# VIC-II: badline, RC, and idle_state

> How the character-matrix state machine drives text/bitmap rendering in VIC-II,
> and why JaC64's FLI ends up looking different from VICE.
>
> Audience: someone who understands C64 basics (VIC-II, PAL 63 cycles/line,
> 40×25 chars) but wants the exact state-transition model before touching
> rendering code.
> Source of truth: `../vice-emu/vice/src/viciisc/vicii-cycle.c`.
>
> Status (2026-04-22): authoritative VICE logic documented; JaC64 deviations
> noted; open questions flagged.

## The three variables that matter

VIC-II's text/bitmap rendering is driven by three counters and one state bit,
all living on the chip:

| name        | purpose                                             |
|-------------|-----------------------------------------------------|
| `vc`        | video counter, current char column (0-39 per row)  |
| `vcbase`    | start-of-current-char-row index, saved between rows |
| `rc`        | row counter within char row (0-7)                   |
| `vmli`      | video matrix line index (column cache slot)         |
| `idle_state`| 1 when chip is NOT producing graphics output        |

`rc` is the one FLI demos abuse, and the one that tripped us up.

## The badline condition

From `vicii-cycle.c:51-60`:

```c
static inline void check_badline(void)
{
    /* Check badline condition (line range and "allow bad lines" handled outside */
    if ((vicii.raster_line & 7) == vicii.ysmooth) {
        vicii.bad_line = 1;
        vicii.idle_state = 0;
    } else {
        vicii.bad_line = 0;
    }
}
```

Two things to notice:

1. The badline test is purely `(line & 7) == ysmooth`. Whenever that holds
   within `first_dma_line..last_dma_line` and `allow_bad_lines`, we're on a
   badline.
2. **Entering a badline clears `idle_state`.** It does not do anything to
   `rc` yet — that happens at cycle 14.

## The two per-line cycles that move state

From `vicii-cycle.c:541-564`:

```c
/* Update VC (Cycle 14 on PAL) */
if (cycle_is_update_vc(vicii.cycle_flags)) {
    vicii.vc = vicii.vcbase;
    vicii.vmli = 0;
    if (vicii.bad_line) {
        vicii.rc = 0;
    }
}

/* Update RC (Cycle 58 on PAL) */
if (cycle_is_update_rc(vicii.cycle_flags)) {
    /* `rc' makes the chip go to idle state when it reaches the
       maximum value.  */
    if (vicii.rc == 7) {
        vicii.idle_state = 1;
        vicii.vcbase = vicii.vc;
    }
    if (!vicii.idle_state || vicii.bad_line) {
        vicii.rc = (vicii.rc + 1) & 0x7;
        vicii.idle_state = 0;
    }
}
```

Read that carefully. There are only two places the chip touches `rc`:

**Cycle 14 ("update VC")** — happens every line:

- Always `vc := vcbase`, `vmli := 0`.
- If this is a badline, additionally `rc := 0`.

**Cycle 58 ("update RC")** — happens every line:

- If `rc == 7` (we just drew the last line of a char row), enter idle
  (`idle_state = 1`) and save the current column position
  (`vcbase := vc`).
- If we are not idle, or we are on a badline, increment rc (modulo 8) and
  force `idle_state = 0`.

That's the whole state machine for `rc`/`vcbase`/`idle_state`.

## Walking through a normal, non-FLI text line

YSCROLL=3, lines 48..55 of the first char row.

| line | cyc 14 (pre)          | cyc 14 (post)     | cyc 58                                    |
|------|-----------------------|-------------------|-------------------------------------------|
| 48   | rc was 7, idle=1      | **badline → rc=0**, vc=vcbase | rc==0 (not 7). idle=1 but badline → rc=1, idle=0 |
| 49   | rc=1, idle=0          | no badline        | rc=2                                      |
| 50   | rc=2                  | no badline        | rc=3                                      |
| ...  | ...                   | ...               | ...                                       |
| 54   | rc=6                  | no badline        | rc=7                                      |
| 55   | rc=7                  | no badline        | rc==7 → idle=1, vcbase:=vc. idle=1, no badline → rc unchanged |

Next line (56) is a badline (56 & 7 == 0 doesn't match YSCROLL=3, so actually
next badline is at 56 → 56&7==0 mismatch; correct first-next-badline is line
56 only if YSCROLL==0 — for YSCROLL=3, next badline is at 56-3=? no, the
pattern repeats every 8 lines, so next badline after 48 is 56. With
YSCROLL=3 and 56&7=0, that's a mismatch. Skip to 48+8=56: 56&7=0 ≠ 3.
57&7=1. 58&7=2. 59&7=3 ✓ — next badline is line 59).

Correction for the table: the next badline is line 59 (the one where
(line & 7) == ysmooth == 3 first holds after 48). The exact spacing is
always 8 lines, so 48 → 56 if YSCROLL=0, 49 → 57 if YSCROLL=1, etc. Between
badlines the chip just walks rc 0→7 and idles after.

## Why FLI "works" — and what it actually produces

FLI demos force every visible line to be a badline by writing `$D011` late
in the line with `YSCROLL == (vbeam & 7)` for the next line. So every line
the VIC-II enters fires `check_badline → bad_line=1, idle_state=0`, then at
cycle 14 does `rc := 0` unconditionally.

Trace in the pure VICE model:

| line | cyc 14 | cyc 58   |
|------|--------|----------|
| 50   | rc=0   | rc=1     |
| 51   | **rc=0** (reset, badline) | rc=1 |
| 52   | rc=0                      | rc=1 |
| ...  | ...                       | ...  |

**rc never leaves 0–1**. So during the fetch cycles (15–54) rc is 0, and the
bitmap-data fetch reads `bitmap[vc*8 + 0]` for every scan line: the *first*
byte of each char cell, repeated on every line.

The entire visual point of FLI is *not* extra bitmap detail. It's that the
COLOR data (fetched via the per-line `$D018` writes → different videoMatrix
→ different color RAM) can change every line, so you get up to 8 unique
color combinations within each 8-line char cell even though the pixel
pattern is constant. That is exactly what
[Christian Bauer's VIC article](https://www.zimmers.net/cbmpics/cbm/c64/vic-ii.txt)
§3.14 describes.

## What JaC64 does (and does not)

At the time of writing JaC64 does not model `idle_state` explicitly. It
models a similar signal called `gfxVisible`:

```java
// C64Screen.java:1780
if (rc == 7) {
    vcBase = vc;
    gfxVisible = false;   // analogue of idle_state = 1
}

// C64Screen.java:1792
if (badLine || gfxVisible) {
    rc = (rc + 1) & 7;
    gfxVisible = true;    // analogue of idle_state = 0
}
```

That end-of-line block is a reasonable translation of VICE's cycle-58
"update RC". The subtle break is at the start-of-line side.

JaC64 has two places that set `rc = 0`:

1. `handleBadLineStart(vicCycle, wasVisible)` at `C64Screen.java:603`,
   triggered by mid-line `$D011` writes that turn on badline before cycle 14.
2. The cycle-13 case at `C64Screen.java:1619`, triggered on every badline.

Both are gated today behind `-Djac64.fliRcFix=true` so the rc-reset is
*skipped*. With the flag on:

- Krestage 3's beast scene changes from vertical color stripes to a
  recognizable image — more bitmap detail is produced than VICE would
  produce.
- The intro runners develop short static horizontal streaks on some scan
  lines.

These two observations together say that *never* resetting rc is not the
same as VICE, and the "better-looking" beast scene is probably a
coincidence: rc walks 0→7 naturally and the bitmap data at those
offsets happens to encode more image than row 0 alone.

## The open questions

1. **Does VICE really render Krestage 3's beast scene as nothing but
   horizontal stripes?** If yes, our pre-fix output was correct and the fix
   is a misleading prettification. If no, VICE has some additional
   mechanism (sprite overlays? mid-line `vicBase` tricks we haven't traced?)
   that accounts for the image detail.
2. **Where do the intro streaks come from?** The intro has only three
   `$D011` writes (at boot). There are no mid-line badline transitions.
   Disabling the fliRcFix flag removes the streaks. That strongly suggests
   the fliRcFix path fires somewhere we don't expect — possibly in the
   initial badline of each char row when rc was still 7 from the previous
   row (see "double-render at char-row boundary" in TODO).
3. **Is `gfxVisible` the right proxy for `idle_state`?** VICE's check
   `!idle_state || bad_line` at cycle 58 corresponds roughly to
   `badLine || gfxVisible` in JaC64, and we've verified the advance
   semantics match. But the *reset* semantics may differ — VICE resets on
   every badline; JaC64 behind the flag resets only when not already
   visible, which is not strictly equivalent.

## Reproducing

- `test-demos/krestage3.prg` is our reference FLI stress test.
- Capture the beast scene:
  ```
  java -Djac64.newSprites=true -Djac64.fliRcFix=true \
       -Djac64.captureFrames=3 -Djac64.traceDelayMs=44000 \
       -cp build/libs/JaC64.jar TestRaster /tmp/krestage3.prg
  ```
- Capture the intro (with streaks):
  ```
  java -Djac64.newSprites=true -Djac64.fliRcFix=true \
       -Djac64.captureFrames=2 -Djac64.traceDelayMs=32000 \
       -cp build/libs/JaC64.jar TestRaster /tmp/krestage3.prg
  ```
- Trace D011/D018/D016 writes during a run:
  ```
  -Djac64.traceFli=true
  ```

To compare against VICE's actual output, build and run `x64sc` from
`../vice-emu/vice/` on the same PRG. The setup is documented in
`memory/reference_vice_comparison.md`.

## Files touched for the current fliRcFix

- `com/dreamfabric/jac64/C64Screen.java:603` — gated `rc = 0` in
  `handleBadLineStart`.
- `com/dreamfabric/jac64/C64Screen.java:1619` — gated `rc = 0` at cycle 13
  under badline.

To remove the flag and make the fix unconditional we need to resolve the
intro streak question first.
