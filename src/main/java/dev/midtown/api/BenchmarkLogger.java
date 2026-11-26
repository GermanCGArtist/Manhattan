package dev.midtown.api;

import java.util.Objects;

/**
 * Emits structured {@code BENCHMARK_RESULT} telemetry lines to {@code latest.log} (via stdout)
 * matching the Deming telemetry contract in {@code docs/DEMING_BENCHMARKS.md}.
 */
public final class BenchmarkLogger {

    public static final String PREFIX = "BENCHMARK_RESULT";

    /** The last line emitted (the test suite's checker reads the runner's verdict and metrics from it). */
    public static volatile String lastLine;

    private BenchmarkLogger() {}

    /** Structured benchmark outcome payload. */
    public record Result(
            String engine,
            String scenario,
            boolean pass,
            long elapsedMs,
            int blocksMoved,
            int stuckCount,
            int interruptsHandled,
            int deaths) {

        public Result {
            engine = EngineSelector.normalizeAndValidate(engine);
            Objects.requireNonNull(scenario, "scenario");
            if (scenario.isBlank()) throw new IllegalArgumentException("scenario must not be blank");
            if (elapsedMs < 0) throw new IllegalArgumentException("elapsedMs must be >= 0");
            if (blocksMoved < 0) throw new IllegalArgumentException("blocksMoved must be >= 0");
            if (stuckCount < 0) throw new IllegalArgumentException("stuckCount must be >= 0");
            if (interruptsHandled < 0) throw new IllegalArgumentException("interruptsHandled must be >= 0");
            if (deaths < 0) throw new IllegalArgumentException("deaths must be >= 0");
        }
    }

    /** Formats a {@link Result} as the exact single-line {@code BENCHMARK_RESULT} log string. */
    public static String format(Result r) {
        Objects.requireNonNull(r, "result");
        return PREFIX
                + " engine=" + r.engine()
                + " scenario=" + sanitizeScenario(r.scenario())
                + " pass=" + r.pass()
                + " elapsed_ms=" + r.elapsedMs()
                + " blocks_moved=" + r.blocksMoved()
                + " stuck_count=" + r.stuckCount()
                + " interrupts_handled=" + r.interruptsHandled()
                + " deaths=" + r.deaths();
    }

    /** Formats a benchmark outcome as the exact single-line {@code BENCHMARK_RESULT} log string. */
    public static String format(
            String engine,
            String scenario,
            boolean pass,
            long elapsedMs,
            int blocksMoved,
            int stuckCount,
            int interruptsHandled,
            int deaths) {
        return format(new Result(engine, scenario, pass, elapsedMs, blocksMoved, stuckCount, interruptsHandled, deaths));
    }

    /** Emits the formatted {@code BENCHMARK_RESULT} line to {@code latest.log} (stdout) and returns it. */
    public static String emit(Result result) {
        String line = format(result);
        System.out.println(line);
        lastLine = line;
        return line;
    }

    /** Emits the formatted {@code BENCHMARK_RESULT} line to {@code latest.log} (stdout) and returns it. */
    public static String emit(
            String engine,
            String scenario,
            boolean pass,
            long elapsedMs,
            int blocksMoved,
            int stuckCount,
            int interruptsHandled,
            int deaths) {
        return emit(new Result(engine, scenario, pass, elapsedMs, blocksMoved, stuckCount, interruptsHandled, deaths));
    }

    private static String sanitizeScenario(String scenario) {
        return scenario.trim().replaceAll("\\s+", "_");
    }
}
