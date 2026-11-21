# How Manhattan is built

Minecraft 26.1.1, Fabric (Mojang names, unobfuscated), Java 25, Midtown navigation & movement engine (`dev.midtown`). Client-only, with client mixins (`baritonequeue.mixins.json`) for Dear ImGui rendering and input routing.

## Packages
- `dev.baritonequeue` – the language, the editor, runner, and native movement integration. Entry point `QueueMod`.
- `dev.midtown` – the native pathing, execution, and movement engine (A* pathfinder, cost model, movement executor, `dev.midtown.api.*`).
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
- Workspace (key J; Dear ImGui is the only UI since 2.12.0): `Shell` has two fixed layouts around the live game,
  switched from its activity bar, Ctrl+P or `/queue ui edit|monitor|close`; J opens the last one (`uiLastMode`,
  script option `ui.layout`). **Edit**: activity bar, the script editor (about 60% of the width), and on the right
  the game at 16:9 above a Now running card and Vitals/Tools/Alerts tiles. **Monitor**: a top strip (step, segmented
  bar, big percent, step estimate, Skip/Stop), Steps/Vitals/Handlers on the left, the game in the middle, Alerts and
  Log under it, Map and Inventory on the right, and a status bar. Presets, Chests, Areas, Capture (K) and Log open as
  a drawer over the left side. No DockSpace and no ini file: windows are placed every frame (`NoSavedSettings`).
- Look: `UiTheme` (colour tokens, the ImGui style, fonts Inter / JetBrains Mono / Barlow Condensed bundled under
  `assets/baritonequeue/fonts`, SIL OFL; rasterised at the UI scale and rebuilt when it changes), `Icons` (activity
  bar icons drawn with the draw list).
- Map layers: `MapLayers.register(id, supplier)` / `unregister(id)` give `MapView` regions (block x/z, state
  PENDING / ACTIVE / DONE / FAILED / SKIPPED, label, hover detail) to draw over the map; while any are there the map
  fits itself to them and its header counts them. A spatial job registers its chunks in its `start()` and removes
  them when it finishes or fails. `ItemIcons` draws item pictures from the items atlas (block items from the blocks
  atlas) with the draw list.
- Tiles: `Widgets` (Now running, top strip, step list, vitals, tools with item icons, inventory,
  alerts, handlers, compact log), `MapView` (top-down: saved areas, `#sel`, chests and captured spots, positions
  written in the script as `let name = pos(...)`, the player and heading), `PlayerView` (inventory and tool
  durability), `Alerts` (latest error, stalled step, worn tools, low health/food, full inventory), `Runner.handlerInfos()`
  (each `on` block: condition, first body line, times fired, ticks since it last finished, running now).
- Editor (`DockedPanels.renderScriptEditorPanel`): file tabs (`main` = `cfg.script`, plus `<game>/scripts/*.txt`), Run
  (with the tab's name for the job ledger), Test, Pick block, Save preset; `Syntax` colours drawn with the draw list
  over a transparent `InputTextMultiline`; `Suggest` completions at the cursor as ghost text and a list (Tab accepts,
  Up/Down/PgUp/PgDn/Enter/Esc, Ctrl+Space shows all, a click on the list is handled before ImGui so the editor keeps
  the keyboard); Ctrl+/ comments lines; `ScriptCheck` runs 400 ms after the last edit and marks lines with a squiggle
  and the message. Edits made while typing (completion, comment, pick, preset) go through the input callback
  (`pendingText`), which works in UTF-8 byte offsets (`byteToChar` / `charToByte`). The script is written to the
  config at most once a second while typing.
- `CommandPalette` (Ctrl+P): run, test, skip, stop, layouts, drawers, UI scale, Pick block, insert a preset, open a
  script tab, go to a chest or spot.
- Rendering (`ImGuiManager`): `imgui-java` 1.90.0 at `RenderSystem.flipFrame`, before the buffer swap. With the
  workspace open the window is cleared to the background colour, the game image (`mainRenderTarget`) is blitted into
  the game area letterboxed to the window's aspect ratio (`fittedViewportRect`), and then ImGui is drawn on top (so
  toasts and popups can cover the game). Input comes from `KeyboardHandlerMixin` / `MouseHandlerMixin`
  (`installCallbacks = false`). A click in the game gives it the keyboard and mouse buttons (clicks are remapped so
  screens such as chests work inside the game area); a click on the panels or Esc takes them back. There is no mouse
  look while the workspace is open; Pick block and Capture's Aim in world close it (`Shell.suspendForAiming`) and J
  brings it back as it was.
- Closed workspace (`Overlay`): drawn on the foreground draw list, never takes input (`capturesInput()` is false, the
  mixins leave grab and mouse look alone): a glass status pill while the script runs, the aiming hints for Pick block
  and Capture, and error toasts (8 s, also shown inside the game area when the workspace is open).
- Capture: the Capture drawer in `DockedPanels` (role, name, Litematica placement, Fetch looked-at, Aim in world,
  Rapid, Use #sel, the sheet, Import saved, Copy sheet, Copy full context, Show in world, stale check), `Capture` (roles,
  sheet files per world and set, rapid aiming, particle outlines, full context), `CaptureSheet` (the sheet's text
  without the game; tested in `LangTest`).
- `Migrate` converts pre-2.0 scripts and presets once on load.

## Water fill (`dev.buildtools.TerminatorProcess`)
- A wet cell is any cell with a water fluid that does not block movement (seagrass, kelp, bubble columns count).
- A cell is filled only when its column below is dry inside the selection: strictly bottom-up.
- States WORK, WALK, SWIM, SINK, UNDER, RISE. It plans over the whole selection with a time budget; for each cell
  a stand on the shore (checked with `Placer.placeableFrom`) or a dive spot. Walking uses Midtown with `GoalSpec`,
  digging only when the stand needs it; swimming and diving use its own key holds.
- Safe areas (`RegionRules`) force allowBreak/allowPlace off near a protected box every tick;
  `Api.setOwnBreak` changes the player's own wish, applied after leaving.

## Area clearing (`dev.baritonequeue.ClearProcess`)
Its own executor (longer walks go through Midtown): top-down passes 3 layers thick, lanes 6 apart, stops every 3
blocks, highest-then-nearest visible block first, drops picked up along the way, a time report at the end.

## Jobs, chunks and the ledger (`Jobs`, `Ledger`, `ChunkGrid`, `ChunkRunner`)
- `job "name" ... end` (`Ast.Job`): skipped when the ledger says DONE (its assigned variables are restored from the
  ledger); FAILED on an uncaught error; left RUNNING by stop/return/break, so the next run does it again.
- `ChunkGrid` splits a selection longer than `chunk.size` (default 32) in x or z into column boxes, ordered
  serpentine or nearest-first. `ChunkRunner` runs a `ChunkWorkload` (`fill_water`, `clear_area`, `drain`,
  `schem_build`) per chunk: walk → wait for the chunk to load → check needs → run, in three phases (PRIMARY,
  DEFERRED_RETRY, VERIFICATION), and registers the chunks with `MapLayers`.
- Ids: `<job>/<action>@<line>(<args>)#<n>/<x0>,<z0>`; outside a job the job is `script`. `Ledger` writes
  `config/manhattan-jobs/<world>_<script>.json` (temp file + move). `/queue jobs | reset [job] | retry <job>`
  (`QueueMod.ledgerChange`) call `Jobs.describe/reset/retry` on the running script's ledger, or the editor tab's
  (`Jobs.useCommandScript`); reset and retry refuse while a script runs. The ledger is never cleared by itself.

## Legacy `#` lines (`Actions.BaritoneCommand`)
Baritone is gone at runtime; `#` lines are a shim onto the current `PathEngine` (`EngineSelector`, both names map
to `MidtownEngine`): goto, mine, stop/cancel, pause/resume, set, sel pos1/pos2/set/clear/ca/replace … air, and engine
settings. Baritone-only settings (`buildInLayers`, `acceptableThrowawayItems`, `allowInventory`, `layerOrder`) are
accepted and ignored.

## Build, test, install
- `build.bat` – builds `build/libs/manhattan-<version>.jar` and runs the offline checks
  (`src/test/.../LangTest.java`: the language through the real parser and interpreter; `ParseFile`: every
  `scripts/*.txt` parses).
- `tests\ingame
un.bat` – the in-game tests on the "Manhatten Testing" profile, driven through the test-only
  bridge mod in `src/ingame` (see [TESTING.md](TESTING.md)).
- `install.bat` – into the "26.1.1 AFK" Modrinth profile; refuses while that profile runs. Older jars go to
  `mods-disabled`.
- Compiler: Mojang marks `hasChunkAt` and `blocksMotion` deprecated (internal use); they are used on purpose.
