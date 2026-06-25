# VIC-II session 2026-05-17..21 — summary

Status: closed. Total session: **9 commits, -68% on 21-test 8565early cell-diff suite (~9128 → 2942)**, -72% on canon. All commits cite VICE source line + a divergent trace event per `docs/vic-ii/WORKPLAN.md` rules.

## Commits

| Commit | What | Impact |
|---|---|---:|
| `0aef938` | vc++ ordering before check_badline (matches VICE Phi1 fetch) | screenpos 2941→87, suite -2854 |
| `f54159f` | Idle gfx fetches `memory[0x3fff]` not 0 (matches VICE `vicii_fetch_idle_gfx`) | sprite-priority 1392→0, colorsplit 1428→136 |
| `6d35ab2` | cregs commit pipe-delay — defer CPU `$D02X` commit by 1 cycle | suite -143, sprite color family closer to VICE floor |
| `8a1131d` | `-Djac64.colorLatency` tunable (opt-in 8565 path) | opt-in, no default change |
| `9c40f7a` | `PAL_6569_CYCLE_TABLE[55]` missing `VIS_EN_M` bit | suite -500 (-14.5% in one bit-flip) |
| `4cb6c15`, `cc7b324`, `1644f69`, `159ad8a` | Trace plumbing for `$D01B`, `$D000-D00E`, grey-dot, badline-FSM | diagnostic infra |

Plus 2 doc commits: `b7fdd3b` (Krestage 3 + README refresh).

## Per-test deltas (8565early references)

| Test | Pre-session | Now | Δ | Gap to VICE |
|---|---:|---:|---:|---:|
| screenpos | 2941 | **14** | -2927 | +14 |
| colorsplit | 1428 | **52** | -1376 | +52 |
| rmwtest | 752 | 727 | -25 | **-530** (JaC64 BEATS VICE 1257) |
| modesplit | 984 | 795 | -189 | +449 |
| vicii_reg_timing | 876 | 794 | -82 | +508 |
| ss-exp-unexp-hires | 36 | 36 | 0 | +18 |
| ss-pri-mc-exp | 496 | **0** | -496 | 0 |
| ss-hires-color | 113 | 69 | -44 | +51 |
| greydot | 0 | 6 | +6 | **-294** (refs outdated; see below) |
| ss-pri | 179 | **0** | -179 | 0 |
| ss-pri-exp | 388 | **0** | -388 | 0 |
| ss-pri-mc | 329 | **0** | -329 | 0 |
| ss-xpos | 124 | 124 | 0 | +106 |
| ss-mc-color0 | 113 | 69 | -44 | +51 |
| ss-hires-mc | 80 | 80 | 0 | +62 |
| fetchsplit | 289 | 176 | -113 | +56 |

**4 tests at 0 cells** (ss-pri-*, all sprite-priority variants). **2 tests JaC64 beats VICE** (rmwtest, greydot).

## What's left & why each is hard

### CPU sub-cycle floor (~1500 cells)
- modesplit 795 (gap 449), vicii_reg_timing 794 (gap 508).
- Mid-line register writes land at sub-CPU-cycle precision in VICE; JaC64 writes at integer-cycle granularity.
- Fix path: Phi1/Phi2 CPU split in MOS6510Core. Multi-week scope per `project_cpu_subcycle_floor.md`.

### Half-cycle VIC pipeline (~270 cells)
- colorsplit 52 (col 39 4-pixel diff = half-cycle drift), screenpos 14 (col 2/26 screen-shift residual), fetchsplit 176 (transition cols), ss-exp-unexp-hires 36 (col 11/31 1-pixel).
- Fix path: 5-phase Phi1+Phi2 VIC pipeline refactor per `project_half_cycle_pipeline_roadmap.md`. 12-20h supervised session.
- Prior partial attempts regressed; this is "all phases or nothing".

### Sprite color edges (~200 cells if regen refs)
- ss-hires-color 69, ss-mc-color0 69, ss-hires-mc 80, ss-xpos 124.
- `-Djac64.colorLatency=false` (8565 path) drops 4 of these to VICE's floor (18 each) — but breaks greydot (6→300) because greydot refs are outdated.
- Fix path: regenerate greydot refs from current VICE x64sc and PR upstream to VICE-testprogs. Then flip colorLatency=false default.

### Reference quality issues
- **greydot**: current VICE x64sc shows identical 300-cell diff against the saved refs. The references predate VICE's grey-dot fixup. JaC64 colorLatency=false matches CURRENT VICE byte-for-byte; matching the OLD refs requires colorLatency=true (= 6569 path).
- See `project_greydot_ref_outdated.md`.

## Methodology

A focused 5-step playbook (memory: `feedback_vic_bug_methodology.md`):

1. **Pick a test where VICE achieves matching cell-diff** (= the test's floor).
2. **Trace CPU register writes on both sides** (instrument VICE store handlers; mirror with JaC64 plumbing). Verify byte-for-byte match BEFORE proposing CPU sub-cycle theories.
3. **Trace VIC pipeline state** at the failing pixels via `EV-DrawCycle`. Walk VICE source for the cycle's intended behavior.
4. **Implement the smallest fix consistent with VICE semantics**. Cite the VICE source line in the commit.
5. **Full suite regression check** (`tools/vice-compare/batch_diff.sh`) after each landed change.

Anti-pattern alerts (failures this session that the playbook caught):

- **"It's CPU sub-cycle" too early**: idle gfx + VIS_EN cyc 56 fixes both proved the bug was VIC-side after CPU writes matched. Don't jump to multi-week theories.
- **"Not a JaC64 bug" too early**: ss-pri was first diagnosed as "comparison harness mismatch"; turned out to be the real idle gfx fetch fix.
- **Naive vmli alignment**: moving `vmli++` into `updateVicStateVic` without auditing all `vmli` consumers regressed 87→2999 cells. Always audit all consumers when relocating state.

## Krestage 3 status

`-Djac64.vicRenderBuf=true` (default ON since cregs pipe-delay landed) renders the scroll-in + beast scenes per `project_krestage3_phase_e_fix.md` saved May-10 references.

Today's fresh capture shows visible regressions on the CREST title bar + subtitle text in the same scenes. Bisect was deferred per user direction. Suspected to be a side-effect of one of the four recent fixes; awaiting supervised session.

## Files

- `docs/SESSION_2026_05_21_VIC_FIXES.md` — this doc
- `docs/vic-ii/krestage3-nine-sprite-trick.md` — updated with current status (commit b7fdd3b)
- `docs/README.md` — index updated (commit b7fdd3b)
- `docs/vic-ii/WORKPLAN.md` — the methodology playbook (unchanged)
- Memory notes: see `project_session_2026_05_21_summary.md` for the cross-reference

## Next-session targets (ranked)

1. **Krestage 3 regression bisect** — find which recent commit broke the CREST title bar (suspect: VIS_EN cyc 56, idle gfx fetch, or cregs pipe-delay).
2. **Half-cycle pipeline refactor (supervised)** — 12-20h to close colorsplit/screenpos/ss-exp-unexp residuals.
3. **CPU Phi1/Phi2 sub-cycle split (multi-week)** — closes modesplit/vicii_reg_timing.
4. **Greydot ref regeneration upstream** — unblocks colorLatency=false default flip → ~200 cell suite gain.
