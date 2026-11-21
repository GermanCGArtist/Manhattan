package dev.manhattan;

import com.google.gson.JsonObject;
import dev.manhattan.fleet.ChunkLeaseProvider;
import dev.manhattan.fleet.FleetMode;
import dev.manhattan.fleet.FleetProtocol;
import dev.manhattan.project.Project;
import dev.midtown.api.Area;
import dev.midtown.api.EngineStatus;
import dev.midtown.api.GoalSpec;
import net.minecraft.core.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * Offline unit tests for Manhattan client Fleet integration:
 * {@link FleetProtocol}, {@link Project.BasePlaces#bedPool}, and {@link ChunkRunner} with {@link ChunkLeaseProvider}.
 */
public final class FleetClientTest {
    private static int passed = 0;

    public static void main(String[] args) {
        testFleetModeParsing();
        testProtocolEnvelopeRoundTrip();
        testProjectBedPoolBackwardCompat();
        testChunkRunnerWithLeaseProvider();
        System.out.println("FleetClientTest: ALL " + passed + " tests passed.");
    }

    private static void testFleetModeParsing() {
        assertTrue(FleetMode.parse(null) == FleetMode.LOCAL, "null defaults to LOCAL");
        assertTrue(FleetMode.parse("editor") == FleetMode.EDITOR, "editor parses");
        assertTrue(FleetMode.EDITOR.canEdit() && !FleetMode.EDITOR.canWork(), "EDITOR flags");
        assertTrue(!FleetMode.WORKER.canEdit() && FleetMode.WORKER.canWork(), "WORKER flags");
        assertTrue(FleetMode.BOTH.canEdit() && FleetMode.BOTH.canWork(), "BOTH flags");
        passed++;
    }

    private static void testProtocolEnvelopeRoundTrip() {
        JsonObject body = new JsonObject();
        body.addProperty("jobId", "job_001");
        body.addProperty("chunkCount", 12);
        String encoded = FleetProtocol.encodeEnvelope("job_ack", body);
        FleetProtocol.Envelope decoded = FleetProtocol.decodeEnvelope(encoded);
        assertTrue(decoded != null, "Envelope decoded");
        assertTrue(decoded.version() == FleetProtocol.PROTOCOL_VERSION, "Version matches");
        assertTrue("job_ack".equals(decoded.type()), "Type matches");
        assertTrue("job_001".equals(decoded.body().get("jobId").getAsString()), "Body field matches");
        passed++;
    }

    private static void testProjectBedPoolBackwardCompat() {
        String legacyJson = "{\"version\":1,\"id\":\"p1\",\"name\":\"Old\",\"places\":{\"bed\":[10,64,20]}}";
        Project legacy = FleetProtocol.GSON.fromJson(legacyJson, Project.class);
        legacy.ensureInitialized();
        assertTrue(legacy.places.bed != null && legacy.places.bed[0] == 10, "Legacy bed preserved");
        assertTrue(legacy.places.bedPool != null && legacy.places.bedPool.isEmpty(), "Missing bedPool initializes empty");

        legacy.places.bedPool.add(new int[]{10, 64, 20});
        legacy.places.bedPool.add(new int[]{12, 64, 20});
        String updatedJson = FleetProtocol.GSON.toJson(legacy);
        Project reloaded = FleetProtocol.GSON.fromJson(updatedJson, Project.class);
        reloaded.ensureInitialized();
        assertTrue(reloaded.places.bedPool.size() == 2 && reloaded.places.bedPool.get(1)[0] == 12,
                "bedPool round-trips cleanly");
        passed++;
    }

    private static void testChunkRunnerWithLeaseProvider() {
        List<int[]> bounds = List.of(new int[]{0, 64, 0, 63, 70, 31}); // 2 chunks of 32x32
        List<ChunkGrid.ColumnBox> split = ChunkGrid.split(bounds, 32, 0, -1);
        assertTrue(split.size() == 2, "Expected 2 chunks");
        String key0 = ChunkRunner.chunkKey("t_001", split.get(0));

        Queue<ChunkLeaseProvider.ClaimResult> script = new ArrayDeque<>();
        script.add(ChunkLeaseProvider.ClaimResult.waiting());
        script.add(ChunkLeaseProvider.ClaimResult.granted(key0));
        script.add(ChunkLeaseProvider.ClaimResult.noWork());

        List<String> reported = new ArrayList<>();
        ChunkLeaseProvider mockProvider = new ChunkLeaseProvider() {
            @Override
            public ClaimResult pollNextChunk() {
                return script.isEmpty() ? ClaimResult.noWork() : script.poll();
            }

            @Override
            public void reportChunk(String chunkKey, String outcome, String reason) {
                reported.add(chunkKey + ":" + outcome);
            }
        };

        int[] remaining = {1};
        ChunkRunner.ChunkWorkload workload = new ChunkRunner.ChunkWorkload() {
            @Override public String actionKind() { return "clear_area"; }
            @Override public boolean usesSelectionNarrowing() { return false; }
            @Override public List<int[]> fullBounds() { return bounds; }
            @Override public Actions.Action createChunkAction(ChunkGrid.ColumnBox chunk, List<int[]> clippedBoxes) {
                return new Actions.Now(() -> {
                    remaining[0] = 0;
                    return 0.0;
                });
            }
            @Override public int countRemaining(ChunkGrid.ColumnBox chunk, List<int[]> clippedBoxes) {
                return remaining[0];
            }
        };

        ChunkRunner.Env env = new ChunkRunner.Env() {
            @Override public BlockPos playerBlockPos() { return new BlockPos(16, 64, 16); }
            @Override public boolean hasChunk(int cx, int cz) { return true; }
            @Override public int countInventoryItem(String itemKey) { return 0; }
            @Override public void goTo(GoalSpec goal) {}
            @Override public EngineStatus pathStatus() { return EngineStatus.DONE; }
            @Override public boolean isPathBusy() { return false; }
            @Override public void cancelPath() {}
            @Override public void setProtectedArea(String name, List<Area> areas) {}
            @Override public void removeProtectedArea(String name) {}
            @Override public boolean triggerChunkNeeds(Map<String, Integer> deficit, ChunkGrid.ColumnBox chunk) { return false; }
            @Override public int runnerLine() { return 1; }
            @Override public boolean runnerRunning() { return true; }
        };

        ChunkRunner runner = new ChunkRunner(workload, 32, ChunkGrid.OrderStrategy.NEAREST, env)
                .withTaskPrefix("t_001")
                .withLeaseProvider(mockProvider);

        runner.start();
        for (int i = 0; i < 20 && !runner.done; i++) {
            runner.tick();
        }
        assertTrue(runner.done && runner.error == null, "ChunkRunner should finish cleanly on NO_WORK");
        assertTrue(reported.size() == 1 && reported.get(0).equals(key0 + ":DONE"),
                "Expected exact chunk DONE report, got: " + reported);
        passed++;
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError("Assertion failed: " + msg);
    }
}
