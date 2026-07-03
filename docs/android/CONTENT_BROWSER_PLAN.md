# Android content browser — plan (2026-07-03)

Goal: one-tap in-app browsing on Android: Browse → pick a demo/game →
download, mount, run. No file managers, no sideloading.

Grounding: `MainActivity.loadFile(Uri)` already does URI→temp-file copy, zip
extraction, and PRG/D64/tape dispatch (android/.../MainActivity.java:345) —
the browser plugs into that path.

## Content sources (legal backbone)

1. **CSDb web service** (`https://csdb.dk/webservice/`) for demos — XML for
   releases, latest/top charts, search, download links + screenshots. Scene
   demos are openly distributed; CSDb is the sanctioned channel other
   emulator frontends use.
2. **Curated index for games** — JSON index hosted in a GitHub repo we
   control (e.g. `jac64-content`): title, author, license/permission note,
   screenshot URL, download URL, file type, SHA. Only genuinely open
   content (itch.io freeware homebrew with author's blessing, PD
   collections, CSDb game releases). Remote index = built-in takedown
   mechanism.
3. Later: HVSC browsing for SIDPlayActivity; Assembly64 REST API.

## ROM strategy (the distribution blocker)

App cannot ship Commodore ROMs. Bundle **OpenROMs** (GPL, redistributable)
as default; settings option to import original ROMs via existing file
picker. Curated index carries "works best with original ROMs" flags (some
demos need KERNAL timing). This unblocks install-and-play AND F-Droid/Play
distribution.

## Architecture

- **`com.dreamfabric.c64utils.repo` in the SHARED CORE, not android/**:
  `ContentProvider` interface (list/search/resolve-download),
  `CsdbProvider`, `CuratedIndexProvider`, `ContentItem` model, and a
  zip/d64/prg sniffer (MOVE MainActivity's extraction logic into core and
  reuse). Plain java.net — no Android APIs. Rationale: this dev env has NO
  Android SDK (android/local.properties points at a missing dir), so all
  testable logic must build with desktop `javac` and be exercised via a CLI
  harness + the desktop emulator/MCP.
- **Thin Android layer**: `BrowseActivity` (RecyclerView; tabs
  Demos/Games/Search), async download into `getExternalFilesDir`, a
  Library tab of downloaded items, call into existing `loadFile()`;
  disk-swap prompt for multi-d64 releases (swap_disk exists).

## Phases

1. Core repo module + desktop CLI harness (buildable/testable in this env):
   CSDb provider, curated-index provider, download+extract+identify
   pipeline; end-to-end test = fetch a real demo, boot it in desktop JaC64.
2. Seed curated index (~20 entries: Krestage 3, Crest/Booze/Fairlight
   classics, ~12 open homebrew games) with license notes, on GitHub.
3. Android UI: BrowseActivity + Library + progress/error states; wire to
   loadFile; disk-swap prompt. (User builds the APK — no SDK here.)
4. OpenROMs bundling + ROM settings screen + "needs original ROMs" badges.
5. Polish: screenshots in list (CSDb has them), share deep links
   (`jac64://csdb/release/<id>`), resume-last-session, F-Droid-ready build.

Phase 1 can start immediately in the shared core. Status: NOT STARTED.
