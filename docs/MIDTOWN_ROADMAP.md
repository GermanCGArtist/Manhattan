# Midtown: De-Baritoning Roadmap for Manhattan

**Midtown** (`dev.midtown`) is the planned custom pathfinding, terrain-traversal, and movement-execution engine to replace `baritone-api-fabric` in Manhattan (`baritonequeue`).

## Why Replace Baritone?

Manhattan's script VM (`Fiber` + `Runner`) is built around **deterministic, preemptible execution**:
- `on <condition> ... end` handlers interrupt the active action every 10 ticks (`defend()`, `eat()`, `sleep()`, `restock()`).
- Built-in task helpers (`TerminatorProcess`, `ClearProcess`, `SchemProcess`, `ChestProcess`) need tight, predictable positioning and clean pause/resume semantics.

Baritone's `BaritoneProcess` priority stack and async `#` command queue are black boxes that can replan unexpectedly, hold dangling input states across interrupts, and tie Manhattan's release schedule to `baritone-api-fabric` updates for new Minecraft versions (e.g. `26.1.1`).

Because Manhattan targets **private SMPs and singleplayer** (no strict Grim/NCP anti-cheat) and can replace `#` commands with native script functions (`goto()`, `mine()`), a purpose-built engine in `dev.midtown` is the cleanest long-term path.

---

## Target Architecture

```
Manhattan Script VM (Runner / Fiber / Actions)
        │
        ▼  (synchronous goal, instant pause/resume)
  PathEngine (interface)
   ├── BaritoneEngine   (legacy adapter, temporary A/B fallback)
   └── MidtownEngine    (dev.midtown)
        ├── MidtownCoordinator  (interrupt stack, stuck detection, tick lifecycle)
        ├── MidtownPlanner      (async 3D A* over WorldSnapshot + typed MoveEdges)
        └── MidtownExecutor     (client-tick input & rotation controller)
```

### Engine Selection
During the transition, both engines sit behind `PathEngine` and can be toggled for A/B testing in Deming or in-game:
- JVM property: `-Dmanhattan.engine=baritone|midtown`
- Script/config option: `option("engine", "midtown")`

---

## Phased Migration Roadmap

### Phase 0 — `PathEngine` Abstraction & Telemetry
**Goal:** Decouple Manhattan from direct `BaritoneAPI` calls without changing behavior.
1. Define `dev.midtown.api.PathEngine` with:
   - `goTo(GoalSpec goal)` (exact block, near-block, XZ, or line-of-sight reach)
   - `mine(BlockFilter filter, Area box)`
   - `pause()` / `resume()` / `cancel()`
   - `status()` (`IDLE`, `PLANNING`, `MOVING`, `STUCK`, `DONE`, `FAILED`)
2. Wrap existing Baritone calls in `BaritoneEngine` across:
   - `dev.baritonequeue.Actions`, `ClearProcess`, `SchemProcess`, `ChestProcess`
   - `dev.buildtools.TerminatorProcess`, `DrainProcess`, `FillProcess`, `ScaffoldProcess`, `EscapeProcess`, `RegionRules`
   - `dev.survivalkit.RestockProcess`, `DefenceProcess`, `SleepProcess`, `EatProcess`, `Recovery`
3. Add structured `BENCHMARK_RESULT` logging for Deming A/B runs.

**Exit Criteria:** All existing `tests/ingame/run.bat` tests pass with `-Dmanhattan.engine=baritone` through `PathEngine`.

---

### Phase 1 — `MidtownExecutor` & Synchronous Interrupt Stack
**Goal:** Own local movement and make `on <condition> ... end` interrupts 100% deterministic.
1. Implement `MidtownExecutor` on `ClientTickEvents.END_CLIENT_TICK`:
   - Direct `LocalPlayer` / `Input` vector driving and smooth yaw/pitch interpolation.
   - Explicit release of all movement/attack/use/sneak states on `pause()` and `cancel()`.
2. Implement `MidtownCoordinator` interrupt stack:
   - When `on mobs > 0` or `on food < 14` fires, `pause()` snapshots the active `GoalSpec` and current path index.
   - When the handler finishes (`end`), `resume()` validates player position and either continues the current edge or triggers a fast local replan.
3. Implement per-edge timeout and stuck detection (e.g., no progress for 20 ticks -> blacklist edge temporarily and replan).

**Exit Criteria:** `smoke`, `eat`, `defend`, and short-range chest/bed navigation pass on `-Dmanhattan.engine=midtown`.

---

### Phase 2 — `MidtownPlanner` (3D A* + Survival `MoveEdge`s)
**Goal:** Full survival terrain traversal parity with Baritone.
1. **`WorldSnapshot` & `CalculationContext`:**
   - Lock-free / snapshot view of loaded `LevelChunk`s so A* runs off the game thread with a strict time budget (20–50ms best-so-far partial pathing).
   - Precomputed tool break times, inventory expendable block count, and `RegionRules` safe-area masks.
2. **Typed `MoveEdge`s:**
   - `WalkEdge`: flat and diagonal movement with 2-block headroom checks.
   - `AscendEdge` / `DescendEdge`: 1-block step-up (`jump` + `forward`) and safe step-down.
   - `FallEdge`: safe drops up to `maxSafeFall` (or into water).
   - `BreakThroughEdge`: dig 1–2 blocking blocks; cost includes estimated break ticks + penalty for blocks adjacent to lava/water/gravel.
   - `BridgeEdge` / `PillarEdge`: place expendable blocks across gaps or upward; gated on inventory supply.
   - `ParkourEdge`: 1–3 block horizontal gap jumps (configurable).
3. **Hazard Avoidance:** High cost / hard block on lava, fire, magma, cactus, berry bushes, and unprotected cliff edges.

**Exit Criteria:** Midtown matches or beats Baritone on `nav_flat_obstacle`, `nav_pillar_bridge`, and `interrupt_mid_action` in Deming.

---

### Phase 3 — Task-Native Co-Optimization
**Goal:** Outperform Baritone on Manhattan's specialized helpers.
1. **`TerminatorProcess` (`fill_water`):** Request "stand within 4.5 blocks with line-of-sight to target cell" directly from `MidtownExecutor`, eliminating Baritone `GoalNear` oscillation.
2. **`ClearProcess` (`clear_area`):** Integrate top-down lane clearing directly with `MidtownPlanner` so dig-and-step happens in one continuous loop.
3. **`SchemProcess` (`schem_build`):** Co-optimize placement order with reachability and scaffolding (`collect_scaffold`), avoiding self-trapping.
4. **`DefenceProcess` (`defend`):** Native combat strafing, spacing, and target pursuit without Baritone process handoff lag.

**Exit Criteria:** Midtown beats Baritone on `water_fill_tight`, `schem_unsupported_reach`, and `survival_marathon` in Deming.

---

### Phase 4 — Native Script Primitives & De-Baritoning
**Goal:** Remove the `baritone-api-fabric` dependency completely.
1. Add native Manhattan builtins: `goto(pos)`, `mine(block, ...)`, `follow(...)`, `tunnel(...)`.
2. Optionally keep a lightweight `#` shim (`#goto`, `#mine`) that translates legacy script lines to `MidtownEngine`, or emit a deprecation warning.
3. Remove `baritone-api-fabric` from `build.gradle`, `libs/`, `docs/DESIGN.md`, and `tests/ingame/mc.py`.

**Exit Criteria:** Clean `build.bat` and full `tests/ingame/run.bat` + Deming suite pass with zero Baritone jars installed.
