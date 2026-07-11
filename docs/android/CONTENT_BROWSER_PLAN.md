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

1. **[DONE]** Core repo module + desktop CLI harness (buildable/testable in
   this env): CSDb provider, curated-index provider, download+extract+identify
   pipeline; end-to-end test = fetch a real demo, boot it in desktop JaC64.
2. Seed curated index (~20 entries: Krestage 3, Crest/Booze/Fairlight
   classics, ~12 open homebrew games) with license notes, on GitHub.
   (Sample 2-entry index bundled: `docs/android/curated-index-sample.json`,
   also shipped as the Android asset `curated-index.json`.)
3. **[DONE — needs on-device test]** Android UI: BrowseActivity (Latest /
   Games / Search) wired to MainActivity.loadFromUrl. Library tab + disk-swap
   prompt still TODO. (User builds the APK — no SDK here.)
4. OpenROMs bundling + ROM settings screen + "needs original ROMs" badges.
   (Curated index already carries the `needsOriginalRoms` flag; BrowseActivity
   shows a "[needs original ROMs]" badge.)
5. Polish: screenshots in list (CSDb has them), share deep links
   (`jac64://csdb/release/<id>`), resume-last-session, F-Droid-ready build.

## Status (2026-07-09)

Phases 1 + 3 landed (uncommitted). Shared core
`com.dreamfabric.c64utils.repo`: `ContentItem`, `ContentRepo`, `HttpFetch`,
`C64Files`, `CsdbRepo` (RSS latest / HTML-scrape search / webservice details +
best-link picker), `CuratedIndexRepo` (+`fromJson` for Android assets),
`RepoCLI`. Verified end-to-end on desktop: `RepoCLI get 48577` downloaded and
unzipped Krestage 3 to KRESTAGE3.D64; curated `aloft` -> Aloft-Side1.d64.

MCP server exposes `csdb_latest`, `csdb_search`, `csdb_load <id>` (delegate to
load_file). Verified over stdio. NOTE: MCP server needs a display (JFrame) —
not headless-safe.

Desktop Swing app (JaC64.java) now has feature parity: File -> "Browse
Content..." opens a Swing dialog (Latest / Games / Search + double-click to
load) reusing the same repo module; downloads to java.io.tmpdir/jac64-browse
and runs via reset+LOAD/runBasic. Curated sample index bundled into the
desktop jar (build.gradle `include docs/android/curated-index-sample.json`,
loaded as classpath resource). Compiles + jar-verified in this env.

So all four frontends share one backend: desktop CLI (RepoCLI), MCP, desktop
Swing GUI, and Android.

Android: `BrowseActivity` + `activity_browse.xml`, registered in manifest,
reachable from MainActivity menu ("Browse Content..."); returns a download URL
that MainActivity.loadFromUrl handles. Bundled asset `curated-index.json`.
Gson added to android/app/build.gradle. CANNOT be compiled/built in this env
(no Android SDK) — needs a real `./gradlew assembleDebug` on a machine with
the SDK + ROMs in assets/roms/.

Remaining for a great UX: on-device test; screenshots in rows (CSDb provides
`screenshotUrl` but loading images needs an async image path, no lib here);
Library/downloads tab; multi-d64 disk-swap prompt after browse-load.
