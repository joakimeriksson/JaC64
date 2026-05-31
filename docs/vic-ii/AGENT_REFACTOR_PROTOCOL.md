# Agent protocol for the JaC64 → VICE refactor

Key points to include in any agent prompt that drives serious
structural work toward VICE x64sc compatibility. Use these to
avoid the incrementalism / flag-flipping anti-pattern.

## The metric is the only judge

- Done = `Total cell-diffs: 0/8000 cells = 0.00%` from
  `tools/vice-compare/png_cell_diff.py` against the VICE x64sc
  reference PNG. Anything else is "in progress."
- Don't claim "matches VICE per cycle math" without the metric.
  If math says match but diff says no, the math is wrong.
- Run the diff after every change, not just at the end.

## Architectural commitments

- **No new `jac64.X` opt-in flags.** Reaching for `Boolean.getBoolean(...)`
  to make a change reversible IS the punt instinct. Make changes
  default and fix what breaks.
- **Remove "compensating wrong" flag pairs.** If a wrong label is
  compensated by a wrong delay, fix the label. Don't add a third
  flag to compensate the second.
- **Port VICE source verbatim.** Don't invent JaC64-style
  translations. Match VICE structure even if it requires
  restructuring the case dispatcher. Reference files:
  - `viciisc/vicii-cycle.c` — main vicii_cycle loop
  - `viciisc/vicii-fetch.c` — FetchC/FetchG/fetch_phi1/fetch_phi2
  - `viciisc/vicii-chip-model.c` — PAL fetch table (gold reference)
  - `c64/c64gluelogic.c` — PLA glue, immediate $DD00
  - `c64/c64memsc.c` — $D018 immediate

## Work isolation

- **EnterPlanMode first.** Read existing memory notes
  (`project_fetchsplit_*.md`, `project_irq_*.md`, `project_phi2_*.md`)
  for forbidden paths and dead-ends.
- **Work in a git worktree.** Don't touch master until 0-diff is
  achieved.
- **Show the plan before executing.** Plan must cover all stages
  with specific file:line references.

## Regression suite — run after every stage

```sh
for prg in \
  /Users/joakimeriksson/work/VICE-testprogs/interrupts/irq-ackn-bug/irq-ack-vicii.prg \
  /Users/joakimeriksson/work/VICE-testprogs/CIA/cia-timer/cia-timer-newcias.prg \
  /Users/joakimeriksson/work/VICE-testprogs/interrupts/irqnoack/{ackcia,ackcia2,ackcia3,ackraster}.prg \
  /Users/joakimeriksson/work/VICE-testprogs/VICII/split-tests/lightpen/lightpen.prg ; do
  java -Djac64.headless=true -Djac64.warp=true \
       -cp build/libs/JaC64.jar TestRaster $prg 2>&1 | grep "Test complete" | tail -1
done
```

- All 4 irqnoack tests, irq-ack-vicii, cia-timer, lightpen MUST stay PASS.
- fetchsplit cell-diffs MUST monotonically decrease toward 0.
- Don't move to the next stage with regressions.

## Forbidden phrases (= exit-ramp instincts)

If one of these enters the agent's head, recognize it as the punt
instinct and push through instead:

- "Let's commit progress and continue later"
- "This needs a separate session"
- "Multi-session work"
- "Would you like me to push and stop?"
- "I've made significant progress, want to stop here?"
- "The remaining diff requires deeper investigation"
- "Add a flag for safety"
- "Compensating wrong"
- "Defaults are optimal" (when the goal isn't reached)

## Allowed escape clauses

May check in with user (briefly, ≤200 words) only if:

- Genuine porting attempt is structurally complete but diff is
  stuck > 0 — share the specific row+col+bytes that differ.
- Hardware behavior found in trace that VICE source doesn't
  obviously model — share evidence, ask direction.
- Regression suite breaks in a way that doesn't seem fixable
  inside the refactor scope — share what broke and why.

In all those cases, no asking "should I continue" — share the
evidence and what would be tried if user said "keep going",
then keep going by default.

## Memory hygiene

- Read existing notes BEFORE starting to avoid forbidden paths.
- After each stage lands clean, update memory with what changed
  and what flags were removed.
- If existing memory has wrong claims, fix them in the same
  stage commit.

## Stages (per docs/vic-ii/FLAGS_INVENTORY.md)

- **§1**: c-access at VICE Phi2(15+K), g-access at Phi1(16+K).
  Restructure case dispatcher. Remove `cAccessShift`,
  `cAccessPhi2`, `gAccessShift` flags after.
- **§2**: $DD00 and $D018 immediate (PLA glue). Remove
  `cia2BankLatch`, `vicMemLatch`, `dd00BankDelayCycles` and all
  deferred-commit machinery.
- **§3**: Default `viceGfx=true` (per-cycle pixel pipeline).
  Verify Krestage 3 doesn't regress.
- **§4**: Default `newSprites=true` (V2 sprite pipeline). Verify
  Krestage 3 probe + Let's Scroll It don't regress.

May resequence stages if regressions pass more cleanly that way.
May NOT skip a stage.

## Don't ask permission to continue

The agent's natural instinct after each step is to ask "want me
to keep going?" Suppress it. Just continue. The user has already
authorized end-to-end execution by invoking this protocol.
