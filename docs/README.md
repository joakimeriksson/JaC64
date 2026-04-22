# JaC64 docs

Notes, wiki-style deep-dives, and side-by-side analyses against the VICE
reference implementation.

Living documents — each page opens with its status line and its source of
truth.

## VIC-II

- [badline, RC, and idle_state](vic-ii/badline-rc-idle-state.md) — the
  chip's character-matrix state machine, FLI semantics, and why JaC64's
  `-Djac64.fliRcFix` flag is not yet unconditional.

## Conventions

- Source of truth first: always cite the VICE file + line range when
  describing hardware behavior.
- Code excerpts in fenced blocks, with relative paths when from this repo.
- Flag every page with a status line at the top (date + "what's verified vs.
  speculative").
- Open questions get their own labelled section at the end.
