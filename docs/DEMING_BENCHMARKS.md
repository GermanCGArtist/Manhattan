# Deming Head-to-Head Benchmark Plan: Midtown vs. Baritone

This document defines how to use **Deming** (`GermanCGArtist/Deming`) to run deterministic, parallel A/B benchmarks comparing **Baritone** (`-Dmanhattan.engine=baritone`) against **Midtown** (`-Dmanhattan.engine=midtown`).

## Why Deming?

Manhattan's built-in `tests/ingame/run.bat` runs functional pass/fail checks sequentially or across workers for a single mod build. **Deming** lets us:
- Launch two isolated Fabric `26.1.1` client instances in parallel from a single `TestJobSpec` JSON (`baritone` vs. `midtown`).
- Pin identical RAM, CPU cores, JVM flags, and world seeds.
- Evaluate outcomes automatically via `TestCompletionRule::log_pattern` and archive logs/screenshots for comparison.

---

## Telemetry Contract

Both engines must emit one structured line per benchmark run to `latest.log` upon completion or failure:

```text
BENCHMARK_RESULT engine=<baritone|midtown> scenario=<name> pass=<true|false> elapsed_ms=<int> blocks_moved=<int> stuck_count=<int> interrupts_handled=<int> deaths=<int>
```

Deming's `log_pattern` rule checks:
- `success_regex`: `BENCHMARK_RESULT engine=(baritone|midtown) scenario=<name> pass=true`
- `failure_regex`: `BENCHMARK_RESULT engine=(baritone|midtown) scenario=<name> pass=false|FATAL|Exception in thread`

---

## Benchmark Suites

| Suite ID | Spec File | What It Tests | Primary Winner Metric |
|---|---|---|---|
| `nav_flat_obstacle` | `tests/deming/bench-nav-obstacle.json` | 120-block traversal across 2-high walls, 3-block drops, water streams, and lava pools | `elapsed_ms`, `stuck_count`, `deaths` |
| `nav_pillar_bridge` | *(Phase 2)* | 40-block void gap bridging + 12-block vertical pillaring with limited blocks | `pass`, `elapsed_ms`, blocks placed |
| `interrupt_mid_action` | `tests/deming/bench-interrupt-mid-action.json` | `clear_area()` + `schem_build()` interrupted 3x by `on food < 14 -> eat()` and `on mobs > 0 -> defend()` | `interrupts_handled`, zero soft-locks, `elapsed_ms` |
| `water_fill_tight` | *(Phase 3)* | `fill_water("dirt", chest)` on a 2-deep pond with `restock()` trips | `elapsed_ms`, `stuck_count` |
| `schem_unsupported_reach` | *(Phase 3)* | Building a schematic with unreachable cells and scaffolding cleanup | `pass`, blocks placed accurately |
| `survival_marathon` | `tests/deming/bench-survival-marathon.json` | 5-minute combined `clear_area` + `dump` + `restock` + `sleep` + `defend` loop | `pass`, `deaths`, `stuck_count`, `elapsed_ms` |

---

## Scoring Rubric (When Does Midtown Win?)

Midtown is ready to replace Baritone as the default engine when, across 5 repeated Deming runs per suite:
1. **Reliability:** `pass=true` rate is **100%** on all 6 suites (equal to or higher than Baritone) with `deaths == 0`.
2. **Interrupt Safety:** `interrupt_mid_action` completes with `stuck_count == 0` and clean resume latency (< 250ms from handler `end` to movement resume).
3. **Speed:** `elapsed_ms` is within **±10%** of Baritone on general traversal (`nav_flat_obstacle`) and **faster** than Baritone on task-coupled suites (`interrupt_mid_action`, `water_fill_tight`, `survival_marathon`).
