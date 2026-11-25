# Deming benchmark specs

`TestJobSpec` files for **Deming** (`GermanCGArtist/Deming`, `crates/deming-core/src/spec.rs`). Every instance is
an isolated Fabric 26.1.1 client with `manhattan-<version>.jar` and `manhattan-testbridge-<version>.jar`, started
with `-Dmanhattan.benchmark=<scenario>`: the test bridge opens a flat creative world, sets the scenario up, runs it
and logs one `BENCHMARK_RESULT ... pass=true|false` line, which is the spec's `log_pattern` rule.

Midtown is the only path engine, so there are no A/B pairs any more (`-Dmanhattan.engine=baritone` is only an alias
for Midtown).

| Spec | Games | What it covers |
|---|---|---|
| `bench-redesign-32.json` | 32 | the broad sweep: project queue, async replans, body interrupts, navigation, pillars and parkour, eating, chunked digging and water |
| `bench-redesign-8.json` | 8 | projects (queue, script params, logistics, dusk and partial-stop gates), async replan, body interrupt |
| `bench-chunk-matrix-16.json` | 16 | every chunk scenario of the three specs below in one run |
| `bench-chunk-clear.json` | 7 | chunked `clear_area`: shapes, stragglers, deep trench, a colossal 4x4 |
| `bench-chunk-water.json` | 5 | chunked `fill_water`, flood and drain, the needs hook |
| `bench-chunk-advanced.json` | 4 | survival interrupts, protected transit, ledger resume, chunking switched off |
| `bench-pillars-floating.json` | 5 | pillaring up, bridging off a pillar, mining and clearing floating pillars |
| `bench-parkour-wall.json` | 4 | jumping gaps along a wall without placing blocks, plus pillar regressions |
| `bench-trees-zones.json` | 3 | `clear_trees` with scaffold zones in a project, queue, selection kept |
| `bench-eat-offhand.json` | 2 | eating food held in the off hand, also with a full hotbar |
| `bench-interrupt-mid-action.json` | 1 | `clear_area` + `schem_build` interrupted by `eat()` and `defend()` |
| `bench-nav-obstacle.json` | 1 | 120-block walk across walls, drops, water and lava |
| `bench-nav-pillar-bridge.json` | 1 | bridging a gap and pillaring up |
| `bench-schem-unsupported-reach.json` | 1 | a schematic with cells that need scaffolding |
| `bench-survival-marathon.json` | 1 | dig, dump, restock, sleep and defend in one loop |
| `bench-water-fill-tight.json` | 1 | `fill_water` on a tight pond with restock trips |
| `bench-fleet-clear-4.json` | 4 + server | fleet: 4 bots clear 9 chunks through manhattan-fleet-server |
| `bench-fleet-clear-8.json` | 8 + server | fleet: 8 bots clear 16 chunks |
| `bench-fleet-kick-4.json` | 4 + server | fleet: a worker is kicked mid-job and reconnects (grace, outbox resend) |
| `bench-fleet-death-4.json` | 4 + server | fleet: a worker dies mid-job (lease released, chunk redone) |
| `bench-fleet-doorway-4.json` | 4 + server | fleet: 4 bots leave a pen through one 1-wide door (yielding) |
| `bench-fleet-reload-4.json` | 4 + server | fleet: project id `default` plus a file written under the manhattan folder mid-run; no stop/start loop |

The fleet specs bring a dedicated server and need `../manhattan-fleet-server` built too; see
[docs/DEMING_BENCHMARKS.md](../../docs/DEMING_BENCHMARKS.md#fleet-scenarios-a-dedicated-server-and-several-bots).

## Running

Build first (`build.bat`), so the jars in `build/libs` exist. One spec:

```
deming run submit tests/deming/bench-nav-obstacle.json
```

All of them, up to 32 games at once (Deming's own default limit is 16):

```
python tests/deming/waves.py
```

`waves.py` packs the specs into waves of at most `--max` games (default 32), gives every game its own id, test
bridge port and account so specs can share a wave, and runs the waves one after another with a Deming config that
allows `--max` games and `--ram` MB (default 72000; games beyond it wait). Results: `tmp/deming-waves/result-N.json`
and `summary.txt`; Deming keeps every run under `%LOCALAPPDATA%\deming\runs`. `--only <text>` picks specs,
`--dry-run` only writes the wave files.

Manhattan needs Liskov (since 2.16.0): `waves.py` adds `../Liskov/liskov-ui/build/libs/liskov-<liskov_version>.jar`
(or the newest built one) to every game that lists no Liskov jar; `--liskov <jar>` picks another one.

The games are not pinned to CPUs (`cpu_cores` is set to 0 in the wave files). Deming pins `cpu_cores` to the
*lowest* logical processors, so with pinning all games of a wave share CPUs 0 to 3 and most of them time out
while still booting. `--pin` keeps the specs' values anyway.

After `mod_version` changes, `python tests/deming/waves.py --set-version` points every spec at the new jar names.

See [docs/DEMING_BENCHMARKS.md](../../docs/DEMING_BENCHMARKS.md) for the `BENCHMARK_RESULT` line and how to read
the results.
