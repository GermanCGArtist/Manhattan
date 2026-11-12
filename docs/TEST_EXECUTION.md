# Manhattan Test Execution Guide

This document summarizes how the offline and in-game test suites are executed, how parallel workers are configured, and the full inventory of tests available in the repository.

---

## 1. Offline Tests (`build.bat`)

Run from the repository (or worktree) root—no Minecraft installation required:

```powershell
.\build.bat
```

This runs Gradle `build`, which compiles `main`, `ingame` (`manhattan-testbridge`), and `test`, and executes:

* **`:langTest`** (`src/test/java/dev/baritonequeue/LangTest.java`): Runs the scripting language through the real `Lexer`, `Parser`, and `Interp` (69 test cases).
* **`:parseScripts`** (`src/test/java/dev/baritonequeue/ParseFile.java`): Parses every script in `scripts/*.txt` and validates built-in presets.

Individual Gradle tasks can also be run directly:

```powershell
.\gradlew.bat langTest
.\gradlew.bat parseScripts
```

---

## 2. Parallel In-Game Tests (`tests\ingame\run.bat`)

The in-game harness builds the mod + `manhattan-testbridge`, installs them into the `"Manhatten Testing"` Modrinth profile, launches headless/unfocused offline Minecraft instances (`ManhattanTest`), runs scenarios in a flat test world, and reports `PASS`/`FAIL`.

### Recommended Commands

```powershell
# Run the full 40-test suite using up to 8 parallel Minecraft instances (recommended max)
.\tests\ingame\run.bat -j 8

# Run specific tests only
.\tests\ingame\run.bat fill_water eat defend

# Keep a single instance open after running, or attach to an already-running instance
.\tests\ingame\run.bat --keep-open
.\tests\ingame\run.bat --attach

# Override per-test timeout (default: 300s in run_tests.py)
.\tests\ingame\run.bat -j 8 --test-timeout 420
```

### Parallelism & Worker Layout
* **`MAX_WORKERS = 8`** in `tests/ingame/run_tests.py` (bridge ports `25701`–`25708`, ~3.5 GB RAM per client). Passing `-j 16` can cause socket/startup contention on some workers; **`-j 8` is the recommended upper bound** unless `MAX_WORKERS` / port allocation is expanded.
* Each worker `N` runs in an isolated directory under `tmp\ingame\wN\` (its own `config/`, `saves/`, `logs/`, `options.txt`) with its stdout/harness log in `tmp\ingame\wN.log`.
* The shared queue lives in `tmp\ingame\queue\`:
  * `tests.txt`: Ordered list of queued test names (slowest tests scheduled first).
  * `claimed-<test>/`: Claim lock directories created as workers pick up tests.
  * `results-<N>.json`: Per-worker JSON array of `[test_name, passed_bool, message, duration_sec, worker_id]`.

---

## 3. Full In-Game Test Inventory (40 Tests)

Defined in `tests/ingame/run_tests.py`, using scenario scripts in `tests/ingame/scenarios/`:

### Core & UI (5)
* `smoke` — Basic script execution, variables/lists/print, and `QueueScreen` editor open
* `imgui_spike` — F9 Dear ImGui runner window, `RunnerView`, input isolation, resize, Stop button
* `viewport` — Docked `ui.mode = docked` top-right viewport geometry, focus gating, remapped mouse clicks, F8 fullscreen
* `docked_shell` — 2×2 `DockSpace`, layout reset, `config/manhattan-imgui.ini` persistence, `ProgressModel` telemetry
* `panels` — Stage 3 panels (`Script Editor`, `Presets`, `Chests`, `Areas`, `Capture`, `Log`)

### Survival & World (10)
* `eat` — `on food < 14 ... eat()` interrupt
* `defend` — `on mobs > 0 ... defend()` interrupt
* `dump` — `dump()` inventory filtering with `want(...)`
* `hotbar_tools` — Tool placement in hotbar with want-list preservation
* `air` — Air guard breaking out of a submerged stone box
* `fill_water` — Bottom-up `fill_water("dirt")` verification
* `clear_resume` — `clear_area()` interrupted by `eat()` and resuming without restarting
* `clear_wet` — `clear_area()` skipping blocks adjacent to water
* `trees_small` — Small tree clearing
* `trees_forest` — Multi-tree forest clearing (`clear_trees()`)

### Schematics (5)
* `schem_exact` — Exact Litematica schematic placement
* `schem_fix_wrong` — Fixing misplaced blocks in a schematic
* `schem_fix` — Repairing missing/damaged schematic blocks
* `schem_add` — Incremental schematic building
* `schem_list` — Schematic listing/management

### Canal Suite (20)
* `canal_layers`
* `canal_pillar_scaffold`
* `canal_scaffold_schem`
* `canal_water_pond`
* `canal_water_river`
* `canal_flood_bucket`
* `canal_flood_two_water`
* `canal_flood_stack`
* `canal_flood_pond`
* `canal_flood_none`
* `canal_flood_wide`
* `canal_smoke`
* `canal_schem_two`
* `canal_schem_needed`
* `canal_survival_dump`
* `canal_survival_restock`
* `canal_tools_schem`
* `canal_schem_water`
* `canal_move`
* `canal_flood_resume`
