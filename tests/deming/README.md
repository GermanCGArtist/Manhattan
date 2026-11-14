# Deming Benchmark Specs (`Midtown` vs. `Baritone`)

These `TestJobSpec` JSON files are formatted for **Deming** (`GermanCGArtist/Deming`, `crates/deming-core/src/spec.rs`).

Each spec launches two isolated Fabric `26.1.1` client instances in parallel:
- `baritone`: `-Dmanhattan.engine=baritone`
- `midtown`: `-Dmanhattan.engine=midtown`

## Prerequisites

1. Build Manhattan (`build.bat` / `./gradlew build`) so `build/libs/baritonequeue-2.11.3.jar` and `build/libs/manhattan-testbridge-2.11.3.jar` exist (matching `mod_version` in `gradle.properties`).
2. Ensure `baritone-api-fabric-1.18.0.jar` is present at `libs/baritone-api-fabric-1.18.0.jar` for the legacy `baritone` instance (the `midtown` instance runs with zero Baritone jars installed).
3. Have Deming installed (`cargo build --release` in `GermanCGArtist/Deming`).

## Running a Benchmark

```bash
deming run submit tests/deming/bench-nav-obstacle.json
deming run submit tests/deming/bench-interrupt-mid-action.json
deming run submit tests/deming/bench-nav-pillar-bridge.json
deming run submit tests/deming/bench-water-fill-tight.json
deming run submit tests/deming/bench-schem-unsupported-reach.json
deming run submit tests/deming/bench-survival-marathon.json
```

See [docs/DEMING_BENCHMARKS.md](../../docs/DEMING_BENCHMARKS.md) for the `BENCHMARK_RESULT` log format and scoring rubric.
