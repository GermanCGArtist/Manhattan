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
        testWireContractMatchesServer();
        testOutboxResendBackoffAckAndPersistence();
        testOutboxSurvivesCorruptFile();
        testWorkerScopesToLeasedTask();
        testChestStockUnreserved();
        testLargeJobCompressionAndChunking();
        System.out.println("FleetClientTest: ALL " + passed + " tests passed.");
    }

    /** The wire contract must be byte-identical with manhattan-fleet-server's copy (when that checkout exists). */
    private static void testWireContractMatchesServer() {
        try {
            java.nio.file.Path mine = java.nio.file.Path.of("src/main/java/dev/manhattan/fleet/wire/FleetWire.java");
            java.nio.file.Path theirs = java.nio.file.Path.of(
                    "../manhattan-fleet-server/src/main/java/dev/manhattan/fleet/wire/FleetWire.java");
            assertTrue(java.nio.file.Files.exists(mine), "FleetWire.java exists");
            if (java.nio.file.Files.exists(theirs)) {
                String a = java.nio.file.Files.readString(mine).replace("\r\n", "\n");
                String b = java.nio.file.Files.readString(theirs).replace("\r\n", "\n");
                assertTrue(a.equals(b), "FleetWire.java differs between Manhattan and manhattan-fleet-server; copy it over");
            } else {
                System.out.println("  (no ../manhattan-fleet-server checkout: wire contract comparison skipped)");
            }
            assertTrue(FleetProtocol.PROTOCOL_VERSION == dev.manhattan.fleet.wire.FleetWire.PROTOCOL_VERSION,
                    "client protocol version comes from the wire contract");
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        passed++;
    }

    private static java.nio.file.Path tmp(String prefix) {
        try {
            return TestDirs.create(prefix);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void testOutboxResendBackoffAckAndPersistence() {
        java.nio.file.Path dir = tmp("fleet-outbox");
        java.nio.file.Path file = dir.resolve("outbox.json");
        dev.manhattan.fleet.FleetOutbox box = new dev.manhattan.fleet.FleetOutbox(file);
        JsonObject body = new JsonObject();
        body.addProperty("jobId", "job_001");
        String id = box.add("srv", "chunk_report", body, 1_000L);
        assertTrue(body.get("msgId").getAsString().equals(id), "msgId is put into the body");
        assertTrue(box.due("other", 1_000L).isEmpty(), "entries belong to their server");
        assertTrue(box.due("srv", 1_000L).size() == 1, "due at once");
        assertTrue(box.due("srv", 2_000L).isEmpty(), "not again before the backoff");
        assertTrue(box.due("srv", 4_001L).size() == 1, "resent after 3 s");
        assertTrue(box.due("srv", 4_001L + 5_999L).isEmpty(), "second backoff is 6 s");
        assertTrue(box.due("srv", 4_001L + 6_000L).size() == 1, "resent after 6 s");

        dev.manhattan.fleet.FleetOutbox reloaded = new dev.manhattan.fleet.FleetOutbox(file);
        assertTrue(reloaded.pending("srv") == 1, "unacknowledged report survives a restart of the game");
        reloaded.rearm("srv", 50_000L);
        assertTrue(reloaded.due("srv", 50_000L).size() == 1, "rearm makes it due at once after reconnecting");
        assertTrue(reloaded.ack(id), "ack removes it");
        assertTrue(new dev.manhattan.fleet.FleetOutbox(file).size() == 0, "ack is saved");

        dev.manhattan.fleet.FleetOutbox old = new dev.manhattan.fleet.FleetOutbox(file);
        old.add("srv", "chunk_report", new JsonObject(), 0L);
        assertTrue(old.due("srv", dev.manhattan.fleet.FleetOutbox.MAX_AGE_MS + 1).isEmpty() && old.size() == 0,
                "very old reports are dropped");
        passed++;
    }

    private static void testOutboxSurvivesCorruptFile() {
        java.nio.file.Path dir = tmp("fleet-outbox-corrupt");
        java.nio.file.Path file = dir.resolve("outbox.json");
        try {
            java.nio.file.Files.writeString(file, "{ not json");
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        dev.manhattan.fleet.FleetOutbox box = new dev.manhattan.fleet.FleetOutbox(file);
        assertTrue(box.size() == 0, "corrupt outbox starts empty");
        box.add("srv", "chunk_report", new JsonObject(), 1L);
        assertTrue(new dev.manhattan.fleet.FleetOutbox(file).size() == 1, "and works again");
        passed++;
    }

    private static void testWorkerScopesToLeasedTask() {
        Project p = new Project("p", "P", "w");
        p.ensureInitialized();
        dev.manhattan.project.ProjectTask a = new dev.manhattan.project.ProjectTask("t_a", "A",
                dev.manhattan.project.ProjectTask.Kind.CLEAR_AREA);
        dev.manhattan.project.ProjectTask b = new dev.manhattan.project.ProjectTask("t_b", "B",
                dev.manhattan.project.ProjectTask.Kind.CLEAR_AREA);
        p.tasks.add(a);
        p.tasks.add(b);
        dev.manhattan.fleet.FleetWorker.scopeToLeasedTask(p, "t_b/16,32");
        assertTrue(!a.enabled && b.enabled, "only the leased task runs");
        dev.manhattan.fleet.FleetWorker.scopeToLeasedTask(p, "t_unknown/0,0");
        assertTrue(!a.enabled && b.enabled, "an unknown task id changes nothing");
        passed++;
    }

    private static void testChestStockUnreserved() {
        FleetProtocol.ChestSync cs = new FleetProtocol.ChestSync();
        assertTrue(cs.unreserved("minecraft:dirt", "me") == -1, "never looked at: unknown");
        cs.stockAtMs = 1L;
        cs.stock.put("minecraft:dirt", 64);
        FleetProtocol.Reservation r = new FleetProtocol.Reservation();
        r.botUuid = "other";
        r.item = "minecraft:dirt";
        r.count = 40;
        r.expiresAtMs = System.currentTimeMillis() + 60_000L;
        cs.reservations.add(r);
        assertTrue(cs.unreserved("minecraft:dirt", "me") == 24, "others' reservations are subtracted");
        assertTrue(cs.unreserved("minecraft:dirt", "other") == 64, "own reservation is not");
        r.expiresAtMs = 0L;
        assertTrue(cs.unreserved("minecraft:dirt", "me") == 64, "lapsed reservations do not count");
        passed++;
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

    private static void testLargeJobCompressionAndChunking() {
        final int mcC2sLimit = 32_767;
        try {
            // 1. Small envelopes pass through untouched as a single frame.
            String small = FleetProtocol.encodeEnvelope(dev.manhattan.fleet.wire.FleetWire.HEARTBEAT, new JsonObject());
            List<String> smallFrames = dev.manhattan.fleet.wire.FleetWire.frameForWire(small);
            assertTrue(smallFrames.size() == 1 && smallFrames.get(0).equals(small),
                    "Small envelope must not be wrapped");

            // 2. Large JSON project (~150 KB with 2000 chunks) compresses heavily and every frame stays < 32 KB.
            StringBuilder sb = new StringBuilder(160_000);
            sb.append("{\"v\":2,\"type\":\"submit_job\",\"body\":{\"projectId\":\"mega\",\"chunks\":[");
            for (int i = 0; i < 2000; i++) {
                if (i > 0) sb.append(',');
                sb.append("{\"chunkKey\":\"t_001/").append(i * 16).append(",0\",\"cx\":").append(i)
                        .append(",\"cz\":0,\"centerX\":").append(i * 16 + 8).append(",\"centerZ\":8}");
            }
            sb.append("]}}");
            String bigJson = sb.toString();
            assertTrue(bigJson.length() > 100_000, "Large project fixture should exceed 100 KB");

            List<String> bigFrames = dev.manhattan.fleet.wire.FleetWire.frameForWire(bigJson);
            assertTrue(bigFrames.size() <= 2, "150 KB repetitive JSON should compress into 1-2 frames, got " + bigFrames.size());
            dev.manhattan.fleet.wire.FleetWire.PacketReassembler reassembler =
                    new dev.manhattan.fleet.wire.FleetWire.PacketReassembler();
            String reassembled = null;
            for (String frame : bigFrames) {
                int wireBytes = frame.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                assertTrue(wireBytes < mcC2sLimit, "Every frame must be < 32,767 bytes, got " + wireBytes);
                FleetProtocol.Envelope env = FleetProtocol.decodeEnvelope(frame);
                assertTrue(env != null && dev.manhattan.fleet.wire.FleetWire.PACKET_CHUNK.equals(env.type()),
                        "Oversized envelope must be wrapped in packet_chunk");
                JsonObject b = env.body();
                reassembled = reassembler.accept("u1",
                        b.get("id").getAsString(), b.get("i").getAsInt(), b.get("n").getAsInt(),
                        b.get("gz").getAsBoolean(), b.get("d").getAsString());
            }
            assertTrue(bigJson.equals(reassembled), "Compressed JSON job must round-trip identically");

            // 3. Large incompressible payload (~120 KB binary schematic) splits into multiple < 32 KB frames
            //    and round-trips even when chunks arrive out of order.
            byte[] randomBytes = new byte[90_000];
            new java.util.Random(12345).nextBytes(randomBytes);
            String b64 = java.util.Base64.getEncoder().encodeToString(randomBytes);
            String hugeSchem = "{\"v\":2,\"type\":\"submit_job\",\"body\":{\"projectJson\":\"" + b64 + "\"}}";
            List<String> schemFrames = dev.manhattan.fleet.wire.FleetWire.frameForWire(hugeSchem);
            assertTrue(schemFrames.size() >= 5, "90 KB incompressible payload should span multiple frames");
            String schemOut = null;
            for (int i = schemFrames.size() - 1; i >= 0; i--) {
                String frame = schemFrames.get(i);
                int wireBytes = frame.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                assertTrue(wireBytes < mcC2sLimit, "Every schematic frame must be < 32,767 bytes, got " + wireBytes);
                FleetProtocol.Envelope env = FleetProtocol.decodeEnvelope(frame);
                JsonObject b = env.body();
                schemOut = reassembler.accept("u2",
                        b.get("id").getAsString(), b.get("i").getAsInt(), b.get("n").getAsInt(),
                        b.get("gz").getAsBoolean(), b.get("d").getAsString());
            }
            assertTrue(hugeSchem.equals(schemOut), "Out-of-order schematic frames must round-trip identically");
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        passed++;
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError("Assertion failed: " + msg);
    }
}
