package dev.midtown;

import dev.manhattan.IncidentBundle;
import dev.manhattan.project.Project;
import dev.manhattan.project.ProjectTask;
import dev.midtown.api.RuntimeBus;
import dev.midtown.api.RuntimeEvent;
import dev.midtown.api.TelemetryProfiler;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Offline unit tests for Milestone 1 & 2:
 * {@link RuntimeEvent}, {@link RuntimeBus} telemetry ring buffer + JSONL stream,
 * {@link TelemetryProfiler}, and {@link IncidentBundle}.
 */
public final class TelemetryIncidentTest {
    private static int passed = 0;

    public static void main(String[] args) throws Exception {
        testRuntimeEventJsonSerialization();
        testRingBufferAndJsonlStream();
        testProfilerTimeBudgetAndBottlenecks();
        testIncidentBundleHeadlessWrite();
        System.out.println("TelemetryIncidentTest: " + passed + " checks passed.");
    }

    private static void testRuntimeEventJsonSerialization() {
        RuntimeEvent ev = new RuntimeEvent(
                7L, 1_700_000_000_000L,
                RuntimeEvent.Type.STUCK, RuntimeEvent.Category.STUCK,
                "MidtownCoordinator", "Stuck on \"edge\"\nline2",
                10, 64, -20, true, 125L,
                Map.of("goal", "chest"));
        String json = ev.toJsonLine();
        assertTrue(json.contains("\"seq\":7"), "seq serialized");
        assertTrue(json.contains("\"type\":\"STUCK\""), "type serialized");
        assertTrue(json.contains("\"pos\":[10,64,-20]"), "pos serialized");
        assertTrue(json.contains("\"durationMs\":125"), "durationMs serialized");
        assertTrue(json.contains("Stuck on \\\"edge\\\"\\nline2"), "message escaped");
        assertTrue(json.contains("\"meta\":{\"goal\":\"chest\"}"), "meta serialized");
    }

    private static void testRingBufferAndJsonlStream() throws Exception {
        RuntimeBus.resetAll();
        Path tmp = Files.createTempDirectory("manhattan-tel-test");
        try {
            Path jsonl = RuntimeBus.startRunTelemetry(tmp, "test run/01");
            assertTrue(jsonl != null && Files.exists(jsonl), "JSONL file created");
            assertTrue("test_run_01".equals(RuntimeBus.activeRunId()), "runId sanitized");

            RuntimeEvent first = RuntimeBus.emit(RuntimeEvent.Type.RUN_START, "Test", "hello");
            RuntimeBus.requestHalt("safe area hit");
            RuntimeBus.stopRunTelemetry();

            List<RuntimeEvent> since = RuntimeBus.eventsSince(first.seq());
            assertTrue(since.size() == 1 && since.get(0).type() == RuntimeEvent.Type.HALT,
                    "requestHalt emits HALT event");

            List<String> lines = Files.readAllLines(jsonl, StandardCharsets.UTF_8);
            assertTrue(lines.size() == 2, "JSONL has 2 lines, got " + lines.size());
            assertTrue(lines.get(0).contains("\"RUN_START\""), "line 0 is RUN_START");
            assertTrue(lines.get(1).contains("\"HALT\"") && lines.get(1).contains("safe area hit"),
                    "line 1 is HALT");

            // Ring buffer bounding check (> 512 events)
            RuntimeBus.clearEvents();
            for (int i = 0; i < 600; i++) {
                RuntimeBus.emit(RuntimeEvent.Type.PATH_REPLAN, "Test", "replan " + i);
            }
            List<RuntimeEvent> all = RuntimeBus.recentEvents(1000);
            assertTrue(all.size() == 512, "ring buffer capped at 512, got " + all.size());
            assertTrue("replan 599".equals(all.get(all.size() - 1).message()), "newest event kept");
        } finally {
            RuntimeBus.resetAll();
            deleteTree(tmp);
        }
    }

    private static void testProfilerTimeBudgetAndBottlenecks() {
        RuntimeBus.resetAll();
        long t = 10_000L;
        RuntimeBus.sampleCategoryAt(RuntimeEvent.Category.WORK, t);
        t += 5_000L;
        RuntimeBus.sampleCategoryAt(RuntimeEvent.Category.LOGISTICS, t);
        t += 5_000L;
        RuntimeBus.sampleCategoryAt(RuntimeEvent.Category.LOGISTICS, t);
        t += 2_000L;
        RuntimeBus.sampleCategoryAt(RuntimeEvent.Category.STUCK, t);
        t += 3_000L;
        RuntimeBus.sampleCategoryAt(RuntimeEvent.Category.WORK, t);

        for (int i = 0; i < 5; i++) {
            RuntimeBus.emitAt(RuntimeEvent.Type.STUCK, RuntimeEvent.Category.STUCK,
                    "MidtownCoordinator", "stuck #" + i, 100 + (i % 2), 64, -50);
        }
        RuntimeBus.emit(RuntimeEvent.Type.RESTOCK_EXHAUSTED, RuntimeEvent.Category.LOGISTICS,
                "GameProjectRuntime", "empty chest");
        RuntimeBus.emitAt(RuntimeEvent.Type.DEATH, RuntimeEvent.Category.SURVIVAL,
                "Recovery", "creeper", 120, 64, -40);

        TelemetryProfiler.Snapshot snap = RuntimeBus.telemetrySnapshot();
        assertTrue(snap.totalMs() == 15_000L, "total sampled ms == 15000, got " + snap.totalMs());
        assertTrue(snap.stuckCount() == 5, "stuckCount == 5");
        assertTrue(snap.deathCount() == 1, "deathCount == 1");
        assertTrue(snap.restockExhaustedCount() == 1, "restockExhaustedCount == 1");
        assertTrue(snap.hotspots().size() == 2, "clustered into 2 hotspots (stuck + death), got " + snap.hotspots().size());
        assertTrue(snap.bottlenecks().size() >= 4, "bottlenecks detected (death, restock, stuck, logistics, stuck%)");
        RuntimeBus.resetAll();
    }

    private static void testIncidentBundleHeadlessWrite() throws Exception {
        RuntimeBus.resetAll();
        IncidentBundle.clearLast();
        Path tmp = Files.createTempDirectory("manhattan-incident-test");
        try {
            Project proj = new Project("farm", "Farm Build", "test_world");
            proj.places.bed = new int[]{10, 64, 10};
            ProjectTask task = new ProjectTask();
            task.id = "t1";
            task.kind = ProjectTask.Kind.CLEAR_AREA;
            task.areaName = "pond";
            proj.tasks.add(task);

            RuntimeBus.emitAt(RuntimeEvent.Type.STUCK, RuntimeEvent.Category.STUCK,
                    "MidtownCoordinator", "blocked edge", 12, 64, 14);
            IncidentBundle.Bundle bundle = IncidentBundle.buildHeadless(
                    "test_world",
                    "queue_failed",
                    "chunk failed",
                    proj,
                    RuntimeBus.recentEvents(50),
                    RuntimeBus.telemetrySnapshot());

            Path file = IncidentBundle.writeBundle(tmp, bundle);
            assertTrue(Files.isRegularFile(file), "incident file written");
            assertTrue(file.equals(IncidentBundle.lastIncidentPath()), "lastIncidentPath updated");
            String text = Files.readString(file, StandardCharsets.UTF_8);
            assertTrue(text.contains("\"triggerReason\": \"queue_failed\""), "triggerReason in JSON");
            assertTrue(text.contains("\"id\": \"farm\""), "activeProject serialized");
            assertTrue(text.contains("\"blocked edge\""), "recentEvents included");
        } finally {
            RuntimeBus.resetAll();
            IncidentBundle.clearLast();
            deleteTree(tmp);
        }
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("FAILED: " + msg);
        }
        passed++;
    }

    private static void deleteTree(Path dir) throws Exception {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        }
    }
}
