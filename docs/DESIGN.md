# How Manhattan is built

Minecraft 26.1.1, Fabric (Mojang names, unobfuscated), Java 25, Baritone API 1.18.0. Client-only, no mixins.

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
  `ScriptCheck` (Test), `Places` (raycast up to 96 blocks), `Estimate` / `ProgressView` (time and progress).
- Capture menu: `CaptureScreen` (the menu), `Capture` (roles, sheet files per world and set, rapid aiming,
  particle outlines, full context), `CaptureSheet` (the sheet's text without the game; tested in `LangTest`).
- `Migrate` converts pre-2.0 scripts and presets once on load.

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
