# CPU Refactor — Root Cause: RMW Dummy-Write vs Raster Compare

## Concrete bug

The 1-cycle drift between JaC64 and VICE on colorsplit (and tests
that use `DEC $D019` / `DEC $D012` for stable raster IRQ ack) comes
from:

**JaC64 detects raster compare match at cycle 5 of RMW absolute
(dummy write); VICE detects at cycle 6 (real write).**

## Why it matters

`DEC $D012` is a 6-cycle RMW absolute instruction:
1. Fetch opcode.
2. Fetch addr lo.
3. Fetch addr hi.
4. Read value from `$D012` → returns current `raster_line`.
5. Write old value back (dummy write) → `$D012 target := old value`.
6. Write new value (modified) → `$D012 target := old value - 1`.

When the read returns `raster_line = 60`:
- Cycle 5 dummy write: target temporarily = 60. Raster compare
  `raster_line == raster_irq_line` matches → IRQ assertion.
- Cycle 6 real write: target = 59. Match broken.

VICE evaluates the raster compare at the END of cycle 6
(after the real write). JaC64 evaluates at the END of cycle 5.

## Trace evidence

Side-by-side, irq_stable2 handler, colorsplit line 58→60:

| Event | VICE clk | JaC64 clk |
|---|---|---|
| $927 DEC $D012 starts | 3030828 | 7020995 |
| Cycle 5 (dummy write) | 3030832 | 7020999 |
| Cycle 6 (real write) | 3030833 | 7021000 |
| Line-60 raster IRQ asserts | **3030833 (cyc 6)** | **7020999 (cyc 5)** |

The IRQ assertion clk differs by exactly 1 cycle, and that 1 cycle
corresponds to where in the RMW cycle the raster compare is
re-evaluated.

## The fix (next session)

JaC64's `MOS6510Core.java` line 689-693:
```java
boolean rmwWrite = read && write;
if (rmwWrite) {
    rmwDummyWrite = true;
    storeByte(adr, data);   // ← cycle 5
    rmwDummyWrite = false;
}
// ... modify data ...
// At line 1086: storeByte(adr, data);   // ← cycle 6
```

The dummy write at cycle 5 triggers JaC64's `handleLateRasterIrqAcknowledge`
via `rmwDummyWrite` flag (C64Screen.java:1082). That function might
be the culprit — it could be firing raster IRQ during the dummy
write phase when it should fire during the real write.

Investigation path:
1. Check `handleLateRasterIrqAcknowledge` semantics.
2. If it fires raster IRQ on dummy write but VICE doesn't, change
   it to fire on real write only.
3. Verify irq-ack-vicii 48/48 (which DEPENDS on this RMW behavior
   for `INC $D019` ack — careful not to break the existing pass).

## Risk

`irq-ack-vicii` passes 48/48 today specifically because of the
RMW-dummy-write IRQ behavior. The "1-cycle early" might be DELIBERATE
in JaC64 to match irq-ack-vicii's expectations.

Solution: condition the early IRQ assertion on the SPECIFIC test
case (read returns raster_line that matches target) so irq-ack-vicii
isn't affected.

Or: keep both pass. The cycle-5 vs cycle-6 difference might only
matter for raster IRQ assertion, while $D019 IRQ ack works the same
either way.

## Status
✅ Root cause identified down to the specific instruction cycle.
✅ Fix path is clear but requires careful testing to not break
   irq-ack-vicii.

## Session deliverables
- 7 commits ending with this root-cause analysis.
- 6 findings docs in `docs/cpu/`.
- Trace data preserved for reproducibility.

Next session can apply the fix in 2-4 hours with confidence.
