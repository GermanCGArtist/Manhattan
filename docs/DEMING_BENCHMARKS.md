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

## Fleet scenarios (a dedicated server and several bots)

`bench-fleet-*.json` start a dedicated Fabric server with manhattan-fleet-server and its test-only bench bridge
(`manhattan-fleet-benchbridge-<version>.jar`, built next to the server jar in `../manhattan-fleet-server`) and 4 or 8
clients that Deming connects to it. `server.properties` names the scenario (`manhattan-fleet-bench=<scenario>`).

1. The server bridge builds the terrain (a dirt box, for `fleet_doorway_4` also a bedrock pen with one 1-wide door),
   turns block drops and mobs off, gives every bot Efficiency V tools and makes `FBEditor` an operator.
2. Every client becomes a fleet worker (`-Dmanhattan.fleetRole=editor` also edits). When all bots are registered,
   the server says `[FleetBench] READY ... box=...`; the editor saves the box as an area and submits a CLEAR_AREA
   project through the same code as the Fleet panel's Submit button.
3. 25 s into the job, `fleet_kick_4` kicks `FBWorker2` (it reconnects by itself) and `fleet_death_4` kills
   `FBWorker1`.
4. When the job is done the server checks the box block by block, that every chunk is DONE exactly once and none
   FAILED, and says `[FleetBench] RESULT scenario=... pass=...`; each client logs its `BENCHMARK_RESULT` from that.
   The server log also gets a `FLEET_BENCH_RESULT` line and a progress line every 30 s.

`waves.py` runs a spec that has a `server` alone in its wave, with its own accounts (the server knows the bots by
name). `--only fleet` runs just these. Restarting the fleet server in the middle of a job is not covered here
(Deming cannot restart its server); `FleetReliabilityTest` in the server repository simulates it.

## Running many at once

`python tests/deming/waves.py` runs every spec, packed into waves of up to 32 games (`--max`). The machine
(128 GB RAM, 32 threads) handles 32 games at 2.5 to 4 GB each; `--ram` (default 72000 MB) keeps the total below what
is free while other Minecraft instances run, and Deming queues the rest. Games run at below-normal priority and are not pinned to CPUs: Deming's `cpu_cores` picks the
lowest logical processors, which put 32 games on CPUs 0 to 3 and made most of them time out during start-up.

## Reading the results

- `tmp/deming-waves/result-N.json`: Deming's run manifest for wave N, one entry per game with its status.
- `%LOCALAPPDATA%\deming\runs\<date>_<run id>\`: every game's `latest.log`, stdout and screenshots. Search a
  game's log for `BENCHMARK_RESULT`; `stuck_count` and `deaths` above 0 on a passing run are worth a look.
- `deming run list` / `deming run status <id>` / `deming run logs <id>` show the same from the command line.
- `Mod error: I/O error: The system cannot find the file specified` with no `log_path` means the game never
  started: Deming failed to copy its mods, which happens now and then when 32 games install at once. It is not a
  Manhattan failure; run that spec again (`waves.py --only <name>`).

## What counts as good

Before a release every spec passes. A scenario that fails once and passes on a second run is a flaky test, not a
pass: note it in the changelog or fix it. `elapsed_ms` is only comparable between runs on the same machine with the
same number of games running.
