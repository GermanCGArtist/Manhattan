# Manhattan test suite (design)

Status: **design agreed 2026-11-26; first version built the same night** (25 families, generator, checker,
runner and report in `tests/suite/`; fleet cases and the endurance suite are not built yet). This is the plan for a large generated test suite run through
Deming. The existing levels (`build.bat`, `tests\ingame\run.bat`, `tests\deming\waves.py`) stay as they are; see
[TESTING.md](TESTING.md) and [DEMING_BENCHMARKS.md](DEMING_BENCHMARKS.md).

Goal: find where Manhattan is slow, fragile or wrong across many situations, and see whether a change made it
better or worse. Not a per-commit gate.

## Running it

```
python tests/suite/generate.py --smoke                  # one case per family -> tmp/suite/cases
python tests/suite/run.py -j 25                         # smoke gate
python tests/suite/generate.py                          # about 2500 cases
python tests/suite/run.py -j 32                         # full run, 3-4 h
python tests/suite/report.py tmp/suite/runs/<run> tests/suite/baseline/summary.json   # compare
python tests/suite/generate.py --list                   # families and parameters
```

Build first (`build.bat`). A run folder `tmp/suite/runs/<time>/` holds `report.md`, `summary.json`,
`results.jsonl`, the cases it used, the logs of passing games and the whole game folder (world, log, telemetry) of
every failing one. Copy a run's `summary.json` to `tests/suite/baseline/` to make it the baseline.

How a case runs: `-Dmanhattan.benchmark=suite -Dmanhattan.suite.case=<case.json>` (and
`-Dmanhattan.suite.seed=N` for real terrain). The test bridge (`SuiteCase.java`) runs the setup commands on the
server, starts the script, and when it ends checks the world and logs
`SUITE_RESULT id=... pass=... script_pass=... checks=... elapsed_ms=... reason=...`.

## Overview

| | suite | endurance |
|---|---|---|
| size | about 2500 cases | a handful of runs |
| length of a case | under 10 min | the equivalent of about 24 h of play |
| started | on demand | on demand, on its own |
| where | `tests/suite/` in this repository | `tests/suite/endurance/` |

## Cases are generated, not written

About 25 **families**, each a parameterised scenario. A generator expands each family's parameters into cases.

- Areas covered: navigation and parkour, digging and clearing, schematic building, survival, fleet, and families
  that **combine** them (e.g. a schematic build with water in the way, hunger, a mob and a nearly empty inventory).
- Expansion: **pairwise** combinations of each family's parameters (every pair of values appears at least once),
  plus a hand-picked list of hard combinations. A full cross product would be millions of cases.
- A case is a family name + a parameter set + a case seed. Its id is stable (`family/hash`), so results can be
  compared across runs.
- The generator writes Deming wave files at run time; generated specs are not committed. What is committed: the
  family definitions, the generator, the checker and the baseline summary.

## Terrain

- **Flat built setups**: the current benchmark world (`manhattan-benchmark`, seed 20261109), with the family
  building its setup at 100 -60 100. Exact checks.
- **Real world seeds**: a fixed list of seeds with caves, mountains, oceans, rivers and villages, with fixed
  coordinates for each. Looser checks (reached the target, area cleared, nothing broken outside it).

## Pass check

One shared **checker** instead of a condition per case. Each family declares the end state it expects:

- blocks match the schematic / the area is empty / the cells are dry, compared block by block;
- the player is alive and at the target, items are in the named chest, the inventory holds what the want-list says;
- nothing outside the allowed region changed (where the family names one).

Every case also fails on a death (unless the family allows deaths), on being stuck longer than the family's limit,
or on running past its timeout. The result line extends today's `BENCHMARK_RESULT` with the case id and the
checker's verdict.

## Metrics

Per case: pass, elapsed, blocks moved, stuck count, interrupts handled, deaths, plus the checker's reason on a
failure. The **baseline** is a small per-case results JSON committed in `tests/suite/baseline/`. Raw logs,
screenshots and snapshots stay under `%LOCALAPPDATA%\deming` and only the last 5 runs are kept.

**Regressions** are judged per family, not per case (single cases are too noisy): a family's median elapsed or
its stuck rate moving by more than its usual spread across runs is flagged.

## Flaky cases

A failed case is rerun once. A fail followed by a pass is reported as **flaky**, in its own list, never as a pass.

## Reproducing failures

Minecraft is not fully deterministic (mob AI, random ticks, chunk load timing), so a rerun may pass. Therefore:

- world seed, case seed and game rules are fixed; random ticks, mob spawning, weather and the daylight cycle are off
  unless the family turns them on;
- on a failure the world is saved as a snapshot and the bot's trace (plans, steps, interrupts) is written next to
  the logs;
- the report gives a one-line command to rerun a case on its own.

## Report

After a run: an HTML/markdown report with pass, fail and flaky counts per family, regressions against the baseline,
and failures **grouped** by family, parameter values and log signature, each with its repro command. Nobody opens
issues automatically; the report is read and issues are opened by hand.

## Running

1. **Smoke gate**: one case per family (about 25 games, one wave, about 10 min). The full run starts only if every
   one of them passes. This catches generator and checker mistakes before they spoil a whole run.
2. **Full run**: all cases in waves. Throughput (games at once, rendering, tick rate) is set from the measurements
   below.
3. Report and, when wanted, update the baseline.

Only the current Minecraft release that Manhattan's main targets is tested. The version is a run parameter, so
another one can be added later.

### Fleet cases

Fleet cases need a dedicated server, and today each one has a server to itself in its own wave. The fleet bench
bridge will be extended so that **one server runs many fleet cases** in separate regions one after another. Fleet
should then be about 10 % of the suite without turning the run into a series of small waves.

## Endurance suite

Long runs catch slow memory leaks, state slowly going wrong and failures that only show after many jobs. Their
length is counted in **ticks and jobs**, not hours: "24 h" means about 1.7 million ticks or a fixed number of jobs
back to back.

To finish sooner than real time:

- a raised tick rate (`/tick rate`), as far as the client-side bot still keeps up (to be measured);
- no idle time: nights skipped, short walks between jobs, fast tools;
- a snapshot of the world and the bot's state every hour, so a failure is replayed from the last snapshot;
- several endurance runs side by side.

Planner and executor logic can also run for very long in the offline `midtownTest` harness without Minecraft.

## Throughput: measure first

Each game today needs 2.5 to 4 GB and runs both a client and its own built-in server. Before fixing the number of
games per wave:

1. one game with default and with lean settings (render distance 2, low fps cap, small window): RAM and CPU;
2. 32, 48 and 64 lean games at once: RAM, CPU, pass rate and how much elapsed times spread;
3. raised tick rates: does elapsed time drop and do results stay stable.

The chosen setting is the fastest one where timings stay stable. 128 games at once do not fit in 128 GB.
A fully headless client (graphics stubbed out) would also need Manhattan to skip Dear ImGui in test mode; the UI
tests stay in the rendered `tests\ingame` harness.

Results of the measurements go in the section below.

## Measurements

2026-11-26, `tests/suite/measure.py`, scenario `chunk_clear_2x2_easy`, other programs using about 50 GB at the time.

| run | physical RAM per game (peak) | committed per game | CPU per game while working | notes |
|---|---|---|---|---|
| 1 game, default settings | 1.63 GB | 2.6 GB | 0.5 core | |
| 1 game, lean (render distance 2, 10 fps, 320x240) | 1.35 GB | 2.3 GB | 0.5 core | heap 2 GB is enough |
| 32 lean games at once | 1.49 GB (45.6 GB total) | 2.25 GB | 0.67 core (21 of 32 busy) | CPU at 100 % while they boot |

- **Lean settings save about 17 % RAM**, not more: most of a game's memory is the JVM, the client and its own
  built-in server, not rendering.
- **32 at once costs 6–10 % speed** while working (a 4-chunk clear took 74–95 s instead of 71 s), but **start-up
  is the bottleneck**: 32 games started together took 2 min 14 s to reach the title screen (25 s alone).
  `run.py` therefore streams cases through one Deming job (a new game starts whenever one ends) instead of starting
  waves together.
- **48 or 64 at once would saturate the CPU** (0.67 core each while working, more while booting) and spoil the
  timings, and 128 do not fit in RAM. 32 is the setting.
- **`/tick rate` does not speed up the bot.** The benchmark world already runs at 50 ticks/s, but Manhattan's
  bot runs on the client's tick loop, which stays at 20/s (1429 action ticks in 71 s). Faster-than-real-time runs
  would need the client timer sped up in the test bridge; not done yet.

### Found while measuring

- **Benchmarks passed on timeout.** `timeout(n)` stops an action and the script goes on, and a script that
  finished counted as `pass=true`. `nav_flat_obstacle` and `chunk_clear_2x2_easy` both "passed" after exactly their
  timeout with the work unfinished (78 of 80 blocks walked; 3 of 4 chunks cleared). The runner now logs
  `pass=false` when any action was stopped by `timeout(...)`, so these benchmarks fail until they finish in time.
- **The benchmark world's ground is not classic flat** (and has village buildings); every scenario has to rebuild
  its ground, which the generated cases do.
