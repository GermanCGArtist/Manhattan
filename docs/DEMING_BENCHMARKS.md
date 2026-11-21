# Deming benchmarks

**Deming** (`GermanCGArtist/Deming`, `F:\workflow\Deming`) launches and supervises many isolated Minecraft clients
from one JSON spec. Manhattan uses it for benchmark scenarios that each need a fresh world and a few minutes of real
play: navigation, digging, water, schematics, survival interrupts and projects. The specs, the list of what each
covers and the run commands are in [tests/deming/README.md](../tests/deming/README.md).

These started as A/B runs of Midtown against Baritone. Baritone is gone (Midtown replaced it completely, see
[MIDTOWN_ROADMAP.md](MIDTOWN_ROADMAP.md)), so every game runs Midtown and a scenario simply passes or fails.

## How a scenario runs

`-Dmanhattan.benchmark=<scenario>` makes the test bridge (`src/ingame/java/dev/manhattan/TestBridge.java`) open a
flat creative world (`manhattan-benchmark`, seed 20261109), teleport to 100 -60 100, force-load the area around it,
build the scenario and start its script or project. When it ends, one line goes to the log:

```text
BENCHMARK_RESULT engine=midtown scenario=<name> pass=<true|false> elapsed_ms=<int> blocks_moved=<int> stuck_count=<int> interrupts_handled=<int> deaths=<int>
```

`engine` is always `midtown` (`-Dmanhattan.engine=baritone` is only an alias). Each spec instance's `log_pattern`
rule passes on `BENCHMARK_RESULT engine=midtown scenario=<name> pass=true` and fails on `pass=false` or `FATAL`;
an instance that logs neither before the spec's `timeout_secs` fails too. An unknown scenario name logs `pass=false`
at once.

## Running many at once

`python tests/deming/waves.py` runs every spec, packed into waves of up to 32 games (`--max`). The machine
(128 GB RAM, 32 threads) handles 32 games at 2.5 to 4 GB each; `--ram` (default 72000 MB) keeps the total below what
is free while other Minecraft instances run, and Deming queues the rest. Games run at below-normal priority.

## Reading the results

- `tmp/deming-waves/result-N.json`: Deming's run manifest for wave N, one entry per game with its status.
- `%LOCALAPPDATA%\deming\runs\<date>_<run id>\`: every game's `latest.log`, stdout and screenshots. Search a
  game's log for `BENCHMARK_RESULT`; `stuck_count` and `deaths` above 0 on a passing run are worth a look.
- `deming run list` / `deming run status <id>` / `deming run logs <id>` show the same from the command line.

## What counts as good

Before a release every spec passes. A scenario that fails once and passes on a second run is a flaky test, not a
pass: note it in the changelog or fix it. `elapsed_ms` is only comparable between runs on the same machine with the
same number of games running.
