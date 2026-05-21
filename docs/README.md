# JaC64 docs

Notes, wiki-style deep-dives, and side-by-side analyses against the VICE
reference implementation.

Living documents — each page opens with its status line and its source of
truth.

## VIC-II

### Active reference

- [Krestage 3: 9-sprite trick & 50-pixel sprites](vic-ii/krestage3-nine-sprite-trick.md)
  — how the demo gets >48 px wide sprites via mid-line `$D000` re-write
  after sprite 0 DMA. Current `ViceSpritePipeline` + `viceRenderBuf=true`
  defaults render the scroll-in + beast scenes cleanly.

- [Badline, RC, and idle_state](vic-ii/badline-rc-idle-state.md) — the
  chip's character-matrix state machine, FLI semantics, and why JaC64's
  `-Djac64.fliRcFix` flag is not yet unconditional.

### Pipeline & refactor plans

- [WORKPLAN.md](vic-ii/WORKPLAN.md) — cycle-accuracy debugging playbook.
  Read **before** patching emulator timing bugs.
- [VICE port plan](vic-ii/VICE_PORT_PLAN.md) — staged port of VICE
  viciisc into JaC64. Many phases landed; see git log for status.
- [Sprite pipeline plan](vic-ii/SPRITE_VICE_PIPELINE_PLAN.md) — original
  spec; the result is `ViceSpritePipeline.java` (committed).
- [CPU sub-cycle next steps](vic-ii/CPU_SUBCYCLE_NEXT_STEPS.md) — the
  multi-week CPU Phi1/Phi2 split needed for modesplit / vicii_reg_timing.

### Historical / archived

The `IRQ_PHASE10*.md` and `PHASE_*_FINDINGS.md` files document the
multi-phase IRQ-acknowledge work; most are landed. See git log.

## CPU

- [CPU refactor plan](cpu/CPU_REFACTOR_PLAN.md)
- [VICE IRQ subsystem review](cpu/VICE_IRQ_SUBSYSTEM_REVIEW.md)
- Phase ALPHA/BETA/GAMMA/DELTA/EPSILON/ETA/THETA/ZETA/J `*_FINDINGS.md` —
  multi-phase port progress.
- [Final summary](cpu/FINAL_SUMMARY.md)

## Session methodology

For the agent-supervised refactor workflow that has produced the recent
single-line / structural fixes (vc++ ordering, idle gfx fetch, VIS_EN
cyc 56), see the memory note `feedback_vic_bug_methodology.md` in the
Claude project. Summarized:

1. Pick a test where VICE achieves matching cell-diff (= floor)
2. Trace CPU register writes both sides; verify match BEFORE proposing
   CPU sub-cycle theories
3. Trace VIC pipeline state at failing pixels; walk VICE source
4. Implement the smallest fix consistent with VICE semantics
5. Full suite regression check after each landed change

## Cell-diff comparison

- `tools/vice-compare/png_cell_diff.py` — per-cell binarized diff
  between two PNGs. Used for `*-8565early.png` ref comparisons.
- `tools/vice-compare/batch_diff.sh` — run JaC64 against a set of
  VICE-testprogs and compute cell-diff per test/variant.
- `tools/vice-trace-patches/` — patches against local VICE x64sc that
  emit `EV-*` events matching JaC64's trace plumbing.

## Conventions

- Source of truth first: always cite the VICE file + line range when
  describing hardware behavior.
- Code excerpts in fenced blocks, with relative paths when from this repo.
- Flag every page with a status line at the top (date + "what's verified vs.
  speculative").
- Open questions get their own labelled section at the end.
