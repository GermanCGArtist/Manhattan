# How Manhattan is built

Minecraft 26.1.1, Fabric (Mojang names, unobfuscated), Java 25, Baritone API 1.18.0. Client-only, with client mixins (`baritonequeue.mixins.json`) for Dear ImGui rendering and input routing.

## Packages
- `dev.baritonequeue` – the language, the editor and the runner. Entry point `QueueMod`.
- `dev.survivalkit` – eat, defence, sleep, restock, respawn/resume/reconnect. Entry point `SurvivalKitMod`.
- `dev.buildtools` – water fill (`TerminatorProcess`), drain, flood, scaffold, escape, safe areas. `BuildToolsMod`.
- `dev.loadchest` – unloading into chests and picking up drops. `LoadChestMod`.

The last three were separate addons until 2.0. They still have their own config files and chat commands, but none
acts on its own: the script starts them. The packages talk through small `Api` classes and a few system properties
(they were separate jars once):

| property | set by | meaning |
|---|---|---|
| `baritonequeue.active` | Runner | a script is running |
| `baritonequeue.step` | Runner | what it is doing now (for recovery messages) |
| `baritonequeue.helpers.on` | `enable()` | helpers allowed to act by themselves while the script runs |
| `baritonequeue.addons.off` | `disable()` | helpers switched off completely |
| `baritonequeue.halt` | helpers | asks the runner to stop the script |
| `survivalkit.recovering` / `survivalkit.safe` | Recovery | waiting after a death / safe to carry on |

## The language (`dev.baritonequeue`)
- `Lexer` → `Parser` → AST (`Ast`, nodes run themselves) → `Interp`.
- Each script runs on its own thread (`Fiber`), but only while the game thread waits for it (semaphore handoff),
  so the game is never touched from two threads at once. It yields every 4000 steps.
- Anything that takes time is an **Action** (`Actions`): `#` lines, chat lines, chest jobs, `fill_water`, `sleep` …
  The script asks for one and yields; the game thread starts it, ticks it and wakes the script when it is done.
  Functions that change the game at once (`protect`, `set`, `area` …) run as `Actions.Now` on the game thread.
- `on <cond> … end` (`Runner`): conditions are checked on the game thread every 10 ticks and fire when they become
  true (again after 60 s if still true). The running action is suspended, the block runs on its own fiber, then the
  action resumes. Handlers stack by order: one written higher up can interrupt one below it.
- Built-in functions: `Builtins` (general), `GameBuiltins` (world, getters, actions, chests, areas, helpers).
  Getters such as `health` work without `()`. `Modules` reads and writes every helper setting by reflection for
  `option("group.field", v)`.
- Editor: `QueueScreen`, `Syntax` (colours from the lexer), `Suggest` (Tab completion, looked-at block as `pos(…)`),
  `ScriptCheck` (Test), `Places` (raycast up to 96 blocks), `Estimate` / `ProgressModel` / `ProgressView` (time and
  segmented step progress shared by the vanilla HUD/editor and the ImGui dashboard).
- View-models & Docked Panels (`RunnerView`, `ProgressModel`, `ChestIndexView`, `AreasView`, `PlacesView`, `LogBuffer`, `DockedPanels`):
  `ChestIndexView` queries `dev.survivalkit.ChestIndex` (`Api.indexedChests()`) with search, category filters, low-stock
  detection, column sorting, and world highlight / clipboard / Baritone `#goto` actions. `LogBuffer` is a 500-entry ring
  buffer fed by `Util.msg` with `ALL`, `ERRORS`, and `BARITONE` filters. `DockedPanels` renders the 6 docked panels
  (`Script Editor`, `Presets`, `Chests`, `Areas`, `Capture`, `Log`) plus the `Assistant` tab stub inside `ImGuiManager`'s
  2x2 `DockSpace`.
- Dear ImGui (`ImGuiManager`, `RunnerView`, `ProgressModel`, `DockedPanels`): `imgui-java` 1.90.0 rendered at `RenderSystem.flipFrame` before buffer swap,
  with input forwarded from `KeyboardHandlerMixin` and `MouseHandlerMixin` (`installCallbacks = false` so GLFW callbacks
  are never overwritten). `ui.mode = vanilla | docked` (default `vanilla`); key **F9** toggles the Stage 0 runner spike window.
  When `ui.mode = docked` and F8 fullscreen (`key.baritonequeue.fullscreen`) is off, `ImGuiManager` hosts a persistent
  2x2 `DockSpace` (Top-left: `Script Editor`, `Presets`; Top-right: `Game Viewport`; Bottom-left: `Chests`, `Areas`, `Capture`;
  Bottom-right: `Log`, `Assistant`) saved to `config/manhattan-imgui.ini` with a Top Bar (`Reset layout`, `Skip`, `Stop`, `Fullscreen game (F8)` — Pause is omitted because `Runner` and custom processes do not support mid-action pause/resume outside `on` handlers).
  `mainRenderTarget` is blitted into the top-right quarter Game Viewport via `GlStateManager._glBlitFrameBuffer` using
  aspect-ratio-preserving bounds (`ImGuiManager.fittedViewportRect`) so resizing docked panels never stretches the game view.
  `MouseHandlerMixin` isolates mouse look in UI mode (blocking `grabMouse` and `turnPlayer` while ImGui is active) and
  linearly remaps cursor coordinates from the Game Viewport rectangle to full-screen GUI coordinates so clicks inside
  the viewport open and interact with UI screens (Game Menu, Chat, Chests). Keyboard input is routed to the game only when the Game Viewport is focused.
  The Script Editor in `DockedPanels` uses single-pass `ImDrawList` syntax text rendering with token prefix measurement and
  a custom blinking caret over a 100% transparent `InputTextMultiline` buffer, eliminating double-rendered text.
- Capture menu: `CaptureScreen` (the menu), `Capture` (roles, sheet files per world and set, rapid aiming,
  particle outlines, full context), `CaptureSheet` (the sheet's text without the game; tested in `LangTest`).
- `Migrate` converts pre-2.0 scripts and presets once on load.

## Chunks and the job ledger (`Chunker`, `Ledger`, `Jobs`, `SchemProcess`)
- A plan bigger than `Config.chunkSize` (32) in x or z is cut by `Chunker` (pure: boxes of full height, aligned to the plan's
  min corner, `next` = nearest open chunk by centre, `upcoming` = the order the next n chunks will come in). `SchemProcess`
  gets an outer loop around its phase machine (`startPhase` / `tick` / `endPhase` are unchanged, they just work on one box):
  pick chunk, `WALK` (Baritone `GoalXZ` at the centre), `LOAD` (wait until `Schem.count(box).unloaded == 0`, then
  `Plan.markOpen(box)` once per chunk), `NEEDS` (`chunk_needs` is true for 20 ticks), `RUN` (the phases), then `DONE`, one retry,
  or `DEFERRED`; one more pass over the deferred chunks; the report. A plan of one chunk skips all of this.
- The box limit is in `Schem.Wrapper` (Baritone's origin is the box's corner and its size the box's, so the builder
  scans only the chunk), `Schem.count(.., box)`, `SchemPlacer.begin(.., box)` and `Plan.markOpen(.., box)`.
- `chunk_needs` is a plain getter on the engine's state. The script's `on` block uses the normal suspend/resume path
  (`suspendRun` / `carryOn`); the flag is down for at least one handler check (10 ticks) between two chunks, so the
  block fires again for each chunk. `chunk_needed(n)` = `Schem.needed` over `Chunker.upcoming`: world where loaded, plan alone elsewhere.
- `Ledger` is one JSON file per world (`config/manhattan-jobs/<world>_main.json`, Gson, written through a temp file and a
  move on every change): job id -> `{state, tries, left, reason, sig, vars, time}`. `sig` is the plan's corner, size and chunk
  size: an entry with another one is stale and ignored. `Jobs` is the in-memory tree the runner and UI read (job -> action ->
  chunk) and builds the ids; mining/water chunks and `job` blocks are meant to use the same nodes and ledger entries.
- `ChunkLoop` is the loop itself (stage 2 moved it out of `SchemProcess`): pick, `WALK`, `LOAD`, `NEEDS`, `RUN`, DONE / retry once /
  DEFERRED, the deferred pass, the report, all with the ledger. The host (`ChunkLoop.Host`) says what a chunk is: `empty`,
  `check` (ledger entry and world -> SKIP / DONE / RUN), `loaded`, `arrive`, `run`, `resumeWork`, `cells`, `allDone`, and calls
  `finished(left, why)` when its work on the chunk is through. `SchemProcess` is one host (phases per chunk, world recount of
  loaded DONE chunks); `Actions.Chunked` is the other.
- `Actions.Chunked` wraps `clear_area`, `fill_water` and `drain` (`GameBuiltins` picks it when `willChunk()`: the selection
  has more than one non-empty chunk; else the old action runs untouched). Chunks = `Chunker.split` of the box around all
  selection boxes, minus those that hold no selection. `run(box)` narrows the Baritone selection to the box (`Areas.apply`,
  `Areas.narrowed` keeps `Areas.remember` from saving it as "last"), makes the unchanged `Feature` through a supplier, and
  the selection is restored when the chunk ends, is cancelled or fails. Rule: a `DONE` entry means SKIP, no recount
  (`verdict`); anything else runs. The chunk's result (blocks left; drain: none) feeds `finished`. An error of the process
  (no blocks, no area) fails the whole action as it does unchunked (chunk entry `FAILED`). `fill_water`'s chest trips are
  `Actions.FillWater`, the same rules as the script-level loop, as one action per chunk. `chunk_needs` is true for the
  schematic loop or `Actions.Chunked.needs()`.
- Protection: `Schem.refreshWanted()` (at `ClearProcess.begin`) makes one `Plan` of all enabled placements, cached by their
  files, mtimes and transforms; `Schem.wantedAt(pos, state)` = the plan has the same non-air, non-fluid block there (the
  check of the `scaffoldOutsideSchematic` hook). `ClearProcess.protectedOrForbidden` includes it, so `wanted()`, the stops and
  the final "left" all skip such blocks, in open chunks too.

## Script jobs and the Queue panel (`Ast.Job`, `Jobs`, `DockedPanels.renderQueuePanel`)
- `job "name" ... end`: `Lexer` keyword, `Parser` (`jobDepth` forbids nesting), `Ast.Job`. `exec` evaluates the name and asks the ledger:
  `DONE` -> `Jobs.savedVars` are `env.assign`ed and the body is not run. Otherwise `Jobs.begin` (`RUNNING`, sets `Jobs.currentJob`
  so `Jobs.action` builds ids `<job>/<fn>@<line>(args)#n/...`), the body runs in the surrounding `Env` (like an `if` body), a
  `Values.ScriptError` becomes `FAILED` (message with line) and is rethrown (the Runner stops the script as before), a normal end becomes
  `DONE` (or `DEFERRED` when a chunk below it is) through `Jobs.finish`. Anything else (stop, `return`, `Killed`) leaves it `RUNNING`.
- Saved variables: the names the body assigns (static walk, `Ast.Job.collectAssigned`) plus every variable whose packed value differs
  before and after (`Jobs.outerValues` packs copies of all non-builtin variables, so in-place changes and changes made by functions are seen).
  `Jobs.pack` / `unpack`: numbers, text, bool, null, lists, maps with text keys, positions (`{"$pos": [x, y, z]}`); anything else is `NOT_PLAIN` and left out.
  Gson reads numbers back as doubles, which is what the language uses anyway.
- A job's ledger entry is the bare name (no `/`); chunk entries are `<job>/<action>#n/<x0>,<z0>`. `Jobs.rows()` rebuilds jobs -> actions -> chunks from the ids, so
  the panel works from the ledger alone (also with no script running). `Jobs.skip` writes `DONE` + reason `skipped`; `SchemProcess` does not recount a skipped chunk.
- The ledger file name comes from `Jobs.script`: `Runner.start(lines, name)` gets the editor tab name (`Jobs.editorScript()`, `main` when unknown); `Jobs.fileSafe` keeps `[A-Za-z0-9_.-]`.
- `Jobs.chunkText()` ("chunk 7/26" = done + 1 of all, for the action that has a `RUNNING` chunk) goes in front of the step text in `ProgressModel`.
- Known limit: `Jobs.currentJob` is global, so an action started by an `on` block while a job runs gets ids under that job.

## Water fill (`dev.buildtools.TerminatorProcess`)
- A wet cell is any cell with a water fluid that does not block movement (seagrass, kelp, bubble columns count).
- A cell is filled only when its column below is dry inside the selection: strictly bottom-up.
- States WORK, WALK, SWIM, SINK, UNDER, RISE. It plans over the whole selection with a time budget; for each cell
  a stand on the shore (checked with `Placer.placeableFrom`) or a dive spot. Walking uses Baritone with `GoalNear`,
  digging only when the stand needs it; swimming and diving use its own key holds.
- Safe areas (`RegionRules`) force Baritone's allowBreak/allowPlace off near a protected box every tick;
  `Api.setOwnBreak` changes the player's own wish, applied after leaving.

## Area clearing (`dev.baritonequeue.ClearProcess`)
Its own executor (Baritone process, priority 11): top-down passes 3 layers thick, lanes 6 apart, stops every 3
blocks, highest-then-nearest visible block first, drops picked up along the way, a time report at the end.

## Build, test, install
- `build.bat` – builds `build/libs/baritonequeue-<version>.jar` and runs the offline checks
  (`src/test/.../LangTest.java`: the language through the real parser and interpreter; `ParseFile`: every
  `scripts/*.txt` parses).
- `tests\ingame
un.bat` – the in-game tests on the "Manhatten Testing" profile, driven through the test-only
  bridge mod in `src/ingame` (see [TESTING.md](TESTING.md)).
- `install.bat` – into the "26.1.1 AFK" Modrinth profile; refuses while that profile runs. Older jars go to
  `mods-disabled`.
- Compiler: Mojang marks `hasChunkAt` and `blocksMotion` deprecated (internal use); they are used on purpose.
