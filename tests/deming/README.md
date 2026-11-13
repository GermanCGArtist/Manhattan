# Deming Benchmark Specs (`Midtown` vs. `Baritone`)

These `TestJobSpec` JSON files are formatted for **Deming** (`GermanCGArtist/Deming`, `crates/deming-core/src/spec.rs`).

Each spec launches two isolated Fabric `26.1.1` client instances in parallel:
- `baritone`: `-Dmanhattan.engine=baritone`
- `midtown`: `-Dmanhattan.engine=midtown`

## Prerequisites

1. Build Manhattan (`build.bat` / `./gradlew build`) so `build/libs/baritonequeue.jar` and `build/libs/manhattan-testbridge.jar` exist (update the `local_path` entries in the JSONs if your jar filenames include a version suffix).
2. Ensure `baritone-api-fabric-1.18.0.jar` is present at `libs/baritone-api-fabric-1.18.0.jar` for the `baritone` instance.
3. Have Deming installed (`cargo build --release` in `GermanCGArtist/Deming`).

## Running a Benchmark

```bash
deming run tests/deming/bench-nav-obstacle.json
deming run tests/deming/bench-interrupt-mid-action.json
deming run tests/deming/bench-survival-marathon.json
```

See [docs/DEMING_BENCHMARKS.md](../../docs/DEMING_BENCHMARKS.md) for the `BENCHMARK_RESULT` log format and scoring rubric.
