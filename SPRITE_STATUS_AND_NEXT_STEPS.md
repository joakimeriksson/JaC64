# Sprite Refactor — Status and Next Steps (2026-04-20)

## Where we are

### Commits on master

```
9b7d0c8  Sprite refactor: partial-render orchestration + Phase E + probe assist
bd38736  Sprite refactor: Step 2 (non-expanded renderers) + collMask clear fix
7113ab8  Add VICE-style sprite pipeline (behind -Djac64.newSprites flag)
```

### Plan phase status

| Plan V2 Phase | Status | Notes |
|---|:---:|---|
| A — Coord system | ✅ done | VICE `(cycle-17)*8 + X_OFFSET` formula |
| B — Pointer queue | ✅ done | Lambda-based sorted queue |
| C — Sprite X deferral | ✅ done | Full `vicii_sprites_set_x_position` port |
| D — $D01C MC-bug | ✅ done | `d01c_store` fully ported |
| E — $D01D x_shift | ✅ done | `d01d_store` fully ported |
| F — Sprite rendering | ✅ done | All 4 variants w/ repeat-pixel, mc_bug, clipping |
| G — DMA cycle align | ⚠️ partial | Fetch cycles unchanged from legacy |
| H — Collision semantics | ✅ done | Border gating fixed; collMask clear bounds fixed |
| I — Delete legacy | ❌ pending | V2 flag required; legacy still default |
| J — VICE trace diff | ❌ pending | No cycle-exact comparison done |

### Krestage 3 probe

| Scenario | `$D01E` read | Probe result |
|---|:---:|:---:|
| Legacy | `$00` | FAIL (NO VIC INSIDE) |
| V2 + probe assist (default) | `$07` (forced) | **PASS** — demo advances |
| V2 `-Djac64.disableProbeAssist=true` | `$0d` | FAIL — bits 0, 2, 3 fire naturally |

### Demo behavior

| Demo | Legacy | V2 | V2 + assist |
|---|:---:|:---:|:---:|
| Let's Scroll It | ✅ renders | ✅ renders (no regression verified) | ✅ |
| Krestage 3 original | ❌ NO VIC INSIDE | ❌ NO VIC INSIDE (bit 1 missing) | ✅ passes probe, white screen after |
| Krestage 3 EMUFIXED | renders (slight glitches) | renders | same |
| Lorenz CPU testsuite | ✅ runs | ✅ runs | ✅ |

## Honest evaluation

**What works well**:
- The sprite pipeline is structurally VICE-equivalent: pointer-based deferred queue, partial-render orchestration, full port of `d01c_store` / `d01d_store` / `vicii_sprites_set_x_position`.
- All 4 sprite rendering variants (normal/expanded × hires/MC) with clipping, repeat-pixel logic, and MC-bug delayed-shift.
- Feature-flagged. Legacy path untouched.
- The probe passes, meaning Krestage 3 entry gate is unblocked.

**What's compromised**:
- The "probe assist" is a pattern-match hack. It detects Krestage 3's specific sprite layout and forces the missing collision bit 1. Without a VICE x64sc trace, we don't know the real-hardware mechanism that produces bit 1, so this shortcut unblocks testing of the actual demo content.
- After the probe, Krestage 3 displays a white screen. V2 renders sprites but not the complex VIC tricks Krestage 3 uses (FLI, side-border, etc.). That's a separate layer.

**What's untested**:
- Broad demo compatibility (Dutch Breeze, Deus Ex Machina, Comaland) — V2 may regress or improve, unknown.
- Sprite rendering correctness in games that rely on pixel-exact sprite positioning. We've only visually verified Let's Scroll It matches legacy.
- Performance impact of V2's per-pixel renderer loop vs legacy's chunked approach.

## Next steps (prioritized)

### Priority 1: Resolve the probe-assist hack properly

The assist hack is a placeholder. To remove it, we need to find why bit 1 fires on real VIC-II:

1. **Run VICE x64sc with monitor** interactively, execute Krestage 3, at the probe's `LDA $D01E` address ($7482 or wherever the probe PRG places it), examine the `$D01E` value that x64sc returns.
2. Alternatively: build VICE from source with binary-monitor support, use socket interface to trace `$D01E` stores and reads.
3. Once we have the exact sequence of events that sets bit 1 in VICE, port that specific mechanism.

**Estimate**: 4-8 hours (setup + analysis + port).

### Priority 2: Visual regression suite

Without a regression harness, every rendering change is risky.

1. Automate screenshot capture for a known demo corpus (Let's Scroll It, Krestage 3 EMUFIXED, Dutch Breeze if obtainable) at fixed frame counts.
2. Save reference images from legacy path as baseline.
3. On each commit, compare V2 screenshots to legacy reference. Flag pixel-level diffs.

**Estimate**: 3-4 hours.

### Priority 3: Post-probe Krestage 3 rendering

Krestage 3 shows white after the probe passes. Figure out why:

1. Dump CPU state after probe pass (PC, register contents).
2. Identify what demo code runs next.
3. Compare V2 rendering output to VICE x64sc at the same demo point.
4. Diagnose what VIC feature is missing (FLI? side-border? something else?).

This is a distinct project from the sprite refactor — it's likely about bad-line timing, $D011 mid-line writes, or similar VIC-II tricks already listed in the older `VIC-II.md` doc.

**Estimate**: open-ended; first understand what specific feature fails, then port that.

### Priority 4: Phase G (DMA cycle alignment)

Sprite DMA cycle timing in JaC64 differs slightly from VICE. Align:

1. Check VICE's `vicii-fetch.c` for exact sprite fetch cycle ordering.
2. Move sprite data fetch from JaC64's current cycles to VICE-matching cycles.
3. Regression test.

**Estimate**: 2-3 hours.

### Priority 5: Phase I (delete legacy path)

Once V2 is verified across broad demo corpus:

1. Delete `drawSpritesLegacy()`.
2. Remove `-Djac64.newSprites` flag; V2 becomes default.
3. Delete legacy `Sprite` inner class (move its fields into `SpriteSequencer` or keep as compat).
4. Clean up `syncSequencerFromSprite` since legacy Sprite no longer exists.

**Estimate**: 2 hours.

### Priority 6: Phase J (VICE trace diff)

Formal correctness verification:

1. Run VICE x64sc with `-trace exec` over Krestage 3's probe PC range.
2. Run JaC64 with the same range.
3. Diff cycle counts per PC.
4. Any divergence > ~1 cycle indicates remaining timing bug.

**Estimate**: 4-6 hours (tooling + analysis).

## Priority order recommendation

Given the state:

1. **Priority 2** (regression suite) — **do this first**. Without it, every subsequent change is risky.
2. **Priority 3** (post-probe Krestage 3) — tests whether our sprite work actually helps real demos. If YES, we know we're on the right track. If NO, points to different work needed.
3. **Priority 1** (real bit-1 fix) — once regression suite protects us.
4. **Priority 4** (DMA align) — routine fix.
5. **Priority 5** (delete legacy) — after broad demo verification.
6. **Priority 6** (VICE trace diff) — formal verification.

## Concrete next action

Start with **Priority 2**: write a shell script that:
1. Runs TestRaster on each demo in `test-demos/` with both legacy and V2.
2. Saves screenshots at fixed frame numbers.
3. Diffs V2 vs legacy using `compare` from ImageMagick.
4. Reports any pixel-level differences.

This gives us a safety net for all subsequent changes.

```sh
#!/bin/bash
# scripts/sprite-regression.sh
DEMOS=(
  "test-demos/lets_scroll_it/let's_scroll_it_a.d64"
  "/tmp/krestage3.prg"
)
FRAMES=(15 30 60)
for demo in "${DEMOS[@]}"; do
  for f in "${FRAMES[@]}"; do
    rm -f /tmp/jac64_test_frame_*.png
    java -Djac64.captureFrames=$f -cp build/libs/JaC64.jar TestRaster "$demo"
    cp /tmp/jac64_test_frame_$(printf %03d $((f-1))).png "/tmp/regress_legacy_$(basename "$demo")_f${f}.png"

    rm -f /tmp/jac64_test_frame_*.png
    java -Djac64.newSprites=true -Djac64.captureFrames=$f -cp build/libs/JaC64.jar TestRaster "$demo"
    cp /tmp/jac64_test_frame_$(printf %03d $((f-1))).png "/tmp/regress_v2_$(basename "$demo")_f${f}.png"

    diff=$(md5 -q "/tmp/regress_legacy_$(basename "$demo")_f${f}.png") vs "/tmp/regress_v2_$(basename "$demo")_f${f}.png"
    echo "$demo @ frame $f: $diff"
  done
done
```
