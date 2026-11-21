# Midtown: status and what is left

**Midtown** (`dev.midtown`) is Manhattan's own pathfinding, terrain-traversal and movement engine. It was planned as
a replacement for `baritone-api-fabric` and has replaced it: Manhattan no longer builds against, installs or needs
Baritone. This page used to be the migration plan; it now records what was built and what is still open.

## Why Manhattan has its own engine

The script VM (`Fiber` + `Runner`) needs deterministic, preemptible movement: `on <condition> ... end` blocks
interrupt the running action (`defend()`, `eat()`, `sleep()`, `restock()`), and the built-in jobs (`fill_water`,
`clear_area`, `schem_build`, chest trips) need tight positioning with clean pause and resume. Baritone's process
stack and asynchronous `#` command queue replanned on their own, kept input held across interrupts, and tied every
Minecraft update to a Baritone release.

## How it is built

```
Script VM (Runner / Fiber / Actions)
        |  synchronous goal, instant pause / resume
        v
  PathEngine (dev.midtown.api)          EngineSelector: option("engine", ...) or -Dmanhattan.engine
   +-- MidtownEngine (dev.midtown.exec)
        +-- MidtownCoordinator   interrupt stack, stuck detection, tick lifecycle
        +-- MidtownPlanner       async 3D A* over a WorldSnapshot with typed MoveEdges (dev.midtown.plan)
        +-- MidtownExecutor      client-tick input and rotation; MineController, EdgeTracker
        +-- BodyController       one owner for movement keys, hotbar and use/attack (dev.midtown.body)
```

- Edges: walk, ascend, descend, fall, break-through, bridge, pillar and parkour (1 to 3 block gaps).
- `HazardCost` keeps paths away from lava, fire, magma, cactus, berry bushes and cliff edges; `DeathMemory` avoids
  corridors where the bot died before.
- `RuntimeBus` / `TelemetryProfiler` stream events and timings for the Monitor, incident bundles and the local MCP
  bridge (`mcp/`).
- Script functions `goto()`, `mine()`, `follow()` and `tunnel()` call Midtown directly. Old `#` lines are still
  accepted, translated to Midtown, and marked as legacy in the editor (see [SCRIPTING.md](SCRIPTING.md)).

## The migration phases

| Phase | Goal | State |
|---|---|---|
| 0 | `PathEngine` interface between the script and the engine, `BENCHMARK_RESULT` telemetry | done |
| 1 | `MidtownExecutor` and the synchronous interrupt stack | done |
| 2 | `MidtownPlanner`: 3D A* with survival move edges and hazard costs | done |
| 3 | job-aware movement for `fill_water`, `clear_area`, `schem_build` and `defend` | done |
| 4 | native script functions, the `#` shim, Baritone removed from the build | done |

The Deming benchmarks that compared both engines now run Midtown only and pass or fail
([DEMING_BENCHMARKS.md](DEMING_BENCHMARKS.md)).

## Still open

- `-Dmanhattan.engine=baritone` and `option("engine", "baritone")` are accepted as aliases for Midtown
  (`EngineSelector.ENGINE_BARITONE`), so old scripts and specs keep working. They can go once nothing uses them.
- Names from the Baritone days remain in the code: `Actions.BaritoneCommand` (runs the translated `#` lines), the
  BARITONE filter of the log panel, `colorBaritone` in the theme, and wording in `tests/ingame/run_tests.py`.
  Renaming them is cosmetic; the log filter name is also checked by an in-game test.
- The system properties between the packages (`baritonequeue.active`, `baritonequeue.step`, ... in
  [DESIGN.md](DESIGN.md)) and the asset folder `assets/baritonequeue` keep their old names.
