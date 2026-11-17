package dev.baritonequeue;

import dev.midtown.api.Area;
import dev.midtown.api.EngineStatus;
import dev.midtown.api.GoalSpec;
import net.minecraft.core.BlockPos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Offline unit tests for {@link ChunkRunner}, {@link Config}/{@link Modules} chunk options, and {@link Jobs}. */
public class ChunkRunnerTest {
    private static int passes = 0;
    private static int fails = 0;

    private static void check(String name, boolean cond) {
        if (cond) {
            passes++;
        } else {
            fails++;
            System.out.println("FAIL " + name);
        }
    }

    private static void eq(String name, Object got, Object expected) {
        if (java.util.Objects.equals(got, expected)) {
            passes++;
        } else {
            fails++;
            System.out.println("FAIL " + name + "\n  expected: " + expected + "\n  got:      " + got);
        }
    }

    private static final class FakeEnv implements ChunkRunner.Env {
        BlockPos playerPos = new BlockPos(0, 60, 0);
        final Set<Long> unloadedChunks = new HashSet<>();
        final Map<String, Integer> inventory = new HashMap<>();
        EngineStatus status = EngineStatus.IDLE;
        boolean busy = false;
        boolean autoFinishWalk = true;
        int goToCalls = 0;
        GoalSpec lastGoal;
        int cancelCalls = 0;
        final Map<String, List<Area>> protectedAreas = new LinkedHashMap<>();
        final List<List<Area>> protectionSnapshots = new ArrayList<>();
        int needsCalls = 0;
        Map<String, Integer> lastNeedsDeficit;
        ChunkGrid.ColumnBox lastNeedsChunk;
        Runnable onNeedsCallback;
        boolean returnNeedsFired = true;

        private static long chunkKey(int cx, int cz) {
            return (((long) cx) << 32) ^ (cz & 0xffffffffL);
        }

        @Override public BlockPos playerBlockPos() { return playerPos; }
        @Override public boolean hasChunk(int cx, int cz) { return !unloadedChunks.contains(chunkKey(cx, cz)); }
        @Override public int countInventoryItem(String itemKey) { return inventory.getOrDefault(itemKey, 0); }

        @Override
        public void goTo(GoalSpec goal) {
            goToCalls++;
            lastGoal = goal;
            if (autoFinishWalk) {
                if (goal instanceof GoalSpec.Near n) {
                    playerPos = n.pos();
                } else if (goal instanceof GoalSpec.Exact e) {
                    playerPos = e.pos();
                } else if (goal instanceof GoalSpec.XZ xz) {
                    int y = playerPos != null ? playerPos.getY() : 60;
                    playerPos = new BlockPos(xz.x(), y, xz.z());
                }
                status = EngineStatus.DONE;
                busy = false;
            } else {
                status = EngineStatus.MOVING;
                busy = true;
            }
        }

        @Override public EngineStatus pathStatus() { return status; }
        @Override public boolean isPathBusy() { return busy; }
        @Override public void cancelPath() { cancelCalls++; busy = false; status = EngineStatus.IDLE; }

        @Override
        public void setProtectedArea(String name, List<Area> areas) {
            List<Area> copy = List.copyOf(areas);
            protectedAreas.put(name, copy);
            protectionSnapshots.add(copy);
        }

        @Override
        public void removeProtectedArea(String name) {
            protectedAreas.remove(name);
        }

        @Override
        public boolean triggerChunkNeeds(Map<String, Integer> deficit, ChunkGrid.ColumnBox chunk) {
            needsCalls++;
            lastNeedsDeficit = deficit;
            lastNeedsChunk = chunk;
            if (onNeedsCallback != null) onNeedsCallback.run();
            return returnNeedsFired;
        }

        @Override public int runnerLine() { return 1; }
        @Override public boolean runnerRunning() { return true; }
    }

    private static Path freshLedger(String name) throws Exception {
        Path dir = Files.createTempDirectory("manhattan-chunk-test");
        Path file = dir.resolve(name + ".json");
        Jobs.useLedger(Ledger.load(file));
        Jobs.startRun("test");
        return file;
    }

    private static void runToCompletion(ChunkRunner runner, int maxTicks) {
        runner.start();
        int t = 0;
        while (!runner.done && t++ < maxTicks) {
            runner.ticks++;
            runner.tick();
        }
    }

    public static void main(String[] args) throws Exception {
        testPrimarySerpentineAndSelectionRestoration();
        testNearestStrategyOrder();
        testDeferredRetryPassesAndCap();
        testVerificationReRunsDamagedChunkAndProtectionLifecycle();
        testWalkAndChunkLoadTimeouts();
        testCheckNeedsAndSuspendResume();
        testCancelRestoresSelectionAndClearsProtection();
        testLedgerResumeSkipsDoneChunks();
        testConfigAndModulesOptions();
        testBandsAndEmptyChunks();
        testMapLayersRegistrationAndState();

        System.out.println("ChunkRunnerTest: " + passes + " passed, " + fails + " failed.");
        if (fails > 0) System.exit(1);
    }

    private static void testPrimarySerpentineAndSelectionRestoration() throws Exception {
        freshLedger("primary");
        Areas.setSingleBox(new BlockPos(0, 60, 0), new BlockPos(23, 63, 23));
        FakeEnv env = new FakeEnv();
        env.playerPos = new BlockPos(1, 60, 1);

        List<String> executedOrder = new ArrayList<>();
        List<String> jobsSnapshots = new ArrayList<>();
        List<int[]> fullBounds = List.of(new int[]{0, 60, 0, 23, 63, 23});

        ChunkRunner runner = new ChunkRunner(new ChunkRunner.ChunkWorkload() {
            @Override public String actionKind() { return "clear_area"; }
            @Override public List<int[]> fullBounds() { return fullBounds; }

            @Override
            public Actions.Action createChunkAction(ChunkGrid.ColumnBox chunk, List<int[]> clippedBoxes) {
                // Verify selection narrowing happened before createChunkAction
                List<int[]> sel = Areas.read();
                eq("narrowed selection size for " + chunk.index(), sel.size(), 1);
                check("narrowed selection matches chunk " + chunk.index(),
                        Arrays.equals(sel.get(0), clippedBoxes.get(0)));
                executedOrder.add(chunk.gridX() + "," + chunk.gridZ());
                String ct = Jobs.chunkText();
                if (ct != null) jobsSnapshots.add(ct);
                check("activeChunks populated while running", !Jobs.activeChunks().isEmpty());
                return new Actions.Now(() -> 0.0);
            }

            @Override
            public int countRemaining(ChunkGrid.ColumnBox chunk, List<int[]> clippedBoxes) {
                return 0;
            }
        }, 12, ChunkGrid.OrderStrategy.SERPENTINE, env);

        runToCompletion(runner, 500);
        check("runner finished", runner.done);
        eq("runner error null", runner.error, null);
        eq("runner result 0", runner.result, 0.0);
        eq("2x2 serpentine order", executedOrder, List.of("0,0", "1,0", "1,1", "0,1"));
        eq("Jobs.chunkText progression", jobsSnapshots, List.of("chunk 1/4", "chunk 2/4", "chunk 3/4", "chunk 4/4"));
        eq("Jobs.chunkText cleared on done", Jobs.chunkText(), null);
        check("activeChunks empty when done", Jobs.activeChunks().isEmpty());
        check("completed protection cleared at end", !env.protectedAreas.containsKey(ChunkRunner.PROTECTED_AREA_NAME));
        List<int[]> restored = Areas.read();
        eq("selection restored count", restored.size(), 1);
        check("selection restored to full bounds", Arrays.equals(restored.get(0), new int[]{0, 60, 0, 23, 63, 23}));
    }

    private static void testNearestStrategyOrder() throws Exception {
        freshLedger("nearest");
        FakeEnv env = new FakeEnv();
        env.playerPos = new BlockPos(22, 60, 22); // Inside chunk (1,1)

        List<String> executedOrder = new ArrayList<>();
        List<int[]> bounds = List.of(new int[]{0, 60, 0, 23, 63, 23});

        ChunkRunner runner = new ChunkRunner(new ChunkRunner.ChunkWorkload() {
            @Override public String actionKind() { return "clear_area"; }
            @Override public List<int[]> fullBounds() { return bounds; }
            @Override public boolean usesSelectionNarrowing() { return false; }

            @Override
            public Actions.Action createChunkAction(ChunkGrid.ColumnBox chunk, List<int[]> clippedBoxes) {
                executedOrder.add(chunk.gridX() + "," + chunk.gridZ());
                env.playerPos = chunk.centerXZ(60);
                return new Actions.Now(() -> 0.0);
            }

            @Override
            public int countRemaining(ChunkGrid.ColumnBox chunk, List<int[]> clippedBoxes) {
                return 0;
            }
        }, 12, ChunkGrid.OrderStrategy.NEAREST, env);

        runToCompletion(runner, 500);
        check("nearest finished", runner.done);
        eq("nearest starts at containing chunk (1,1)", executedOrder.get(0), "1,1");
        eq("nearest visited all 4 chunks", executedOrder.size(), 4);
    }

    private static void testDeferredRetryPassesAndCap() throws Exception {
        freshLedger("deferred");
        FakeEnv env = new FakeEnv();
        env.playerPos = new BlockPos(5, 60, 5);
        List<int[]> bounds = List.of(new int[]{0, 60, 0, 23, 63, 11}); // 2 chunks

        Map<Integer, Integer> runs = new HashMap<>();
        ChunkRunner runner = new ChunkRunner(new ChunkRunner.ChunkWorkload() {
            @Override public String actionKind() { return "fill_water"; }
            @Override public List<int[]> fullBounds() { return bounds; }
            @Override public boolean usesSelectionNarrowing() { return false; }

            @Override
            public Actions.Action createChunkAction(ChunkGrid.ColumnBox chunk, List<int[]> clippedBoxes) {
                runs.merge(chunk.index(), 1, Integer::sum);
                return new Actions.Now(() -> 0.0);
            }

            @Override
            public int countRemaining(ChunkGrid.ColumnBox chunk, List<int[]> clippedBoxes) {
                int r = runs.getOrDefault(chunk.index(), 0);
                if (chunk.index() == 0) return 0; // finishes on try 1
                // Chunk 1: clears on try 3 (Phase 2 pass 2)
                return r >= 3 ? 0 : (3 - r) * 2;
            }
        }, 12, ChunkGrid.OrderStrategy.SERPENTINE, env);

        runToCompletion(runner, 500);
        check("deferred resolved finished", runner.done);
        eq("chunk 0 ran once", runs.get(0), 1);
        eq("chunk 1 ran 3 times (1 primary + 2 retry)", runs.get(1), 3);
        eq("result 0 when resolved in pass 2", runner.result, 0.0);

        // Now test cap at 2 retry passes when chunk 1 never clears
        freshLedger("deferred-cap");
        Map<Integer, Integer> cappedRuns = new HashMap<>();
        ChunkRunner capped = new ChunkRunner(new ChunkRunner.ChunkWorkload() {
            @Override public String actionKind() { return "clear_area"; }
            @Override public List<int[]> fullBounds() { return bounds; }
            @Override public boolean usesSelectionNarrowing() { return false; }

            @Override
            public Actions.Action createChunkAction(ChunkGrid.ColumnBox chunk, List<int[]> clippedBoxes) {
                cappedRuns.merge(chunk.index(), 1, Integer::sum);
                return new Actions.Now(() -> 0.0);
            }

            @Override
            public int countRemaining(ChunkGrid.ColumnBox chunk, List<int[]> clippedBoxes) {
                return chunk.index() == 0 ? 0 : 4;
            }
        }, 12, ChunkGrid.OrderStrategy.SERPENTINE, env);

        runToCompletion(capped, 500);
        check("capped runner finished", capped.done);
        eq("capped chunk 1 ran 3 times max", cappedRuns.get(1), 3);
        eq("capped result reports remaining 4.0", capped.result, 4.0);
        eq("retryPass capped at 2", capped.retryPass(), 2);
    }

    private static void testVerificationReRunsDamagedChunkAndProtectionLifecycle() throws Exception {
        freshLedger("verify");
        FakeEnv env = new FakeEnv();
        env.playerPos = new BlockPos(5, 60, 5);
        List<int[]> bounds = List.of(new int[]{0, 60, 0, 23, 63, 11}); // 2 chunks: index 0 and 1

        Map<Integer, Integer> runs = new HashMap<>();
        boolean[] damagedChunk0 = {true};
        List<Area> protectionDuringChunk0Verify = new ArrayList<>();

        ChunkRunner runner = new ChunkRunner(new ChunkRunner.ChunkWorkload() {
            @Override public String actionKind() { return "schem_build"; }
            @Override public List<int[]> fullBounds() { return bounds; }
            @Override public boolean usesSelectionNarrowing() { return false; }

            @Override
            public Actions.Action createChunkAction(ChunkGrid.ColumnBox chunk, List<int[]> clippedBoxes) {
                int count = runs.merge(chunk.index(), 1, Integer::sum);
                if (chunk.index() == 0 && count == 2) {
                    // During verification re-run of chunk 0, chunk 0 must be removed from protection while chunk 1 remains protected
                    List<Area> cur = env.protectedAreas.getOrDefault(ChunkRunner.PROTECTED_AREA_NAME, List.of());
                    protectionDuringChunk0Verify.addAll(cur);
                    damagedChunk0[0] = false; // repaired
                }
                return new Actions.Now(() -> 0.0);
            }

            @Override
            public int countRemaining(ChunkGrid.ColumnBox chunk, List<int[]> clippedBoxes) {
                if (chunk.index() == 0 && runs.getOrDefault(0, 0) == 1 && runs.getOrDefault(1, 0) >= 1 && damagedChunk0[0]) {
                    return 3; // damaged after phase 1 completes
                }
                return 0;
            }
        }, 12, ChunkGrid.OrderStrategy.SERPENTINE, env);

        runToCompletion(runner, 500);
        check("verify finished", runner.done);
        eq("chunk 0 re-ran once in verification", runs.get(0), 2);
        eq("chunk 1 ran only once", runs.get(1), 1);
        eq("during verification re-run only chunk 1 was protected",
                protectionDuringChunk0Verify,
                List.of(new Area(12, 60, 0, 23, 63, 11)));
        check("protected area cleared on finish", !env.protectedAreas.containsKey(ChunkRunner.PROTECTED_AREA_NAME));
    }

    private static void testWalkAndChunkLoadTimeouts() throws Exception {
        freshLedger("timeouts");
        FakeEnv env = new FakeEnv();
        env.autoFinishWalk = false;
        env.playerPos = new BlockPos(100, 60, 100); // > 8 blocks from [0..11, 0..11]
        // Mark MC chunk (0,0) unloaded forever to exercise both 200-tick walk timeout and 60-tick load timeout
        env.unloadedChunks.add((((long) 0) << 32) ^ 0L);

        AtomicInteger ran = new AtomicInteger();
        ChunkRunner runner = new ChunkRunner(new ChunkRunner.ChunkWorkload() {
            @Override public String actionKind() { return "drain"; }
            @Override public List<int[]> fullBounds() { return List.of(new int[]{0, 60, 0, 11, 63, 11}); }
            @Override public boolean usesSelectionNarrowing() { return false; }

            @Override
            public Actions.Action createChunkAction(ChunkGrid.ColumnBox chunk, List<int[]> clippedBoxes) {
                ran.incrementAndGet();
                return new Actions.Now(() -> 0.0);
            }

            @Override public int countRemaining(ChunkGrid.ColumnBox chunk, List<int[]> clippedBoxes) { return 0; }
        }, 12, ChunkGrid.OrderStrategy.SERPENTINE, env);

        runner.start();
        // Tick 1: picks chunk, sees far & unloaded -> issues goTo, enters WALK_TO_CHUNK
        runner.tick();
        eq("issued goTo", env.goToCalls, 1);
        check("goal is XZ(5, 5)", env.lastGoal instanceof GoalSpec.XZ xz && xz.x() == 5 && xz.z() == 5);
        eq("step is WALK_TO_CHUNK", runner.step(), ChunkRunner.Step.WALK_TO_CHUNK);

        // Advance 200 walk ticks while engine stays MOVING
        for (int i = 0; i < 200; i++) {
            runner.tick();
        }
        check("walk timed out and canceled path", env.cancelCalls >= 1);
        eq("entered WAIT_CHUNK_LOAD after walk timeout", runner.step(), ChunkRunner.Step.WAIT_CHUNK_LOAD);

        // Advance 60 chunk-load ticks + completion
        for (int i = 0; i < 65 && !runner.done; i++) {
            runner.tick();
        }
        check("proceeded to RUN_CHUNK after 60-tick chunk load timeout", ran.get() == 1 && runner.done);
    }

    private static void testCheckNeedsAndSuspendResume() throws Exception {
        freshLedger("needs");
        Areas.setSingleBox(new BlockPos(0, 60, 0), new BlockPos(23, 63, 11));
        FakeEnv env = new FakeEnv();
        env.playerPos = new BlockPos(5, 60, 5);
        env.inventory.put("dirt", 10);

        AtomicInteger childSuspends = new AtomicInteger();
        AtomicInteger childResumes = new AtomicInteger();
        AtomicInteger childTicks = new AtomicInteger();

        ChunkRunner[] holder = new ChunkRunner[1];
        // Simulate Runner suspending ChunkRunner when on chunk_needs fires, then restocking & resuming
        env.onNeedsCallback = () -> {
            holder[0].suspend();
            // While suspended, full selection must be restored
            check("selection restored during chunk_needs suspend",
                    Arrays.equals(Areas.read().get(0), new int[]{0, 60, 0, 23, 63, 11}));
            env.inventory.put("dirt", 64);
            holder[0].resume();
        };

        ChunkRunner runner = new ChunkRunner(new ChunkRunner.ChunkWorkload() {
            @Override public String actionKind() { return "fill_water"; }
            @Override public List<int[]> fullBounds() { return List.of(new int[]{0, 60, 0, 23, 63, 11}); }

            @Override
            public Map<String, Integer> requiredItems(ChunkGrid.ColumnBox chunk, List<int[]> clippedBoxes) {
                return chunk.index() == 0 ? Map.of("dirt", 64) : Map.of();
            }

            @Override
            public Actions.Action createChunkAction(ChunkGrid.ColumnBox chunk, List<int[]> clippedBoxes) {
                return new Actions.Action() {
                    @Override void start() {}
                    @Override void tick() {
                        if (childTicks.incrementAndGet() >= 2) finish(0.0);
                    }
                    @Override void suspend() { childSuspends.incrementAndGet(); }
                    @Override void resume() { childResumes.incrementAndGet(); }
                };
            }

            @Override public int countRemaining(ChunkGrid.ColumnBox chunk, List<int[]> clippedBoxes) { return 0; }
        }, 12, ChunkGrid.OrderStrategy.SERPENTINE, env);
        holder[0] = runner;

        runner.start();
        // Tick 1 picks chunk 0 -> WAIT_CHUNK_LOAD -> CHECK_NEEDS -> fires triggerChunkNeeds once -> returns before RUN_CHUNK
        runner.tick();
        eq("chunk_needs fired once", env.needsCalls, 1);
        eq("chunk_needs deficit dirt=54", env.lastNeedsDeficit, Map.of("dirt", 54));

        // Tick 2 enters RUN_CHUNK and starts child action (narrowed selection)
        runner.tick();
        check("narrowed selection applied in RUN_CHUNK",
                Arrays.equals(Areas.read().get(0), new int[]{0, 60, 0, 11, 63, 11}));

        // Mid-chunk interrupt suspend/resume
        runner.suspend();
        eq("child suspended", childSuspends.get(), 1);
        check("selection restored during mid-chunk suspend",
                Arrays.equals(Areas.read().get(0), new int[]{0, 60, 0, 23, 63, 11}));

        runner.resume();
        eq("child resumed", childResumes.get(), 1);
        check("narrowed selection re-applied on resume",
                Arrays.equals(Areas.read().get(0), new int[]{0, 60, 0, 11, 63, 11}));

        int guard = 0;
        while (!runner.done && guard++ < 200) runner.tick();
        check("finished after resume", runner.done);
        eq("chunk_needs still fired only once for chunk 0", env.needsCalls, 1);
    }

    private static void testCancelRestoresSelectionAndClearsProtection() throws Exception {
        freshLedger("cancel");
        Areas.setSingleBox(new BlockPos(0, 60, 0), new BlockPos(23, 63, 11));
        FakeEnv env = new FakeEnv();
        env.playerPos = new BlockPos(5, 60, 5);
        boolean[] childCanceled = {false};
        boolean[] chunkEnded = {false};

        ChunkRunner runner = new ChunkRunner(new ChunkRunner.ChunkWorkload() {
            @Override public String actionKind() { return "clear_area"; }
            @Override public List<int[]> fullBounds() { return List.of(new int[]{0, 60, 0, 23, 63, 11}); }
            @Override public void onChunkEnd(ChunkGrid.ColumnBox chunk) { chunkEnded[0] = true; }

            @Override
            public Actions.Action createChunkAction(ChunkGrid.ColumnBox chunk, List<int[]> clippedBoxes) {
                return new Actions.Action() {
                    @Override void start() {}
                    @Override void tick() {}
                    @Override void cancel() { childCanceled[0] = true; }
                };
            }

            @Override public int countRemaining(ChunkGrid.ColumnBox chunk, List<int[]> clippedBoxes) { return 0; }
        }, 12, ChunkGrid.OrderStrategy.SERPENTINE, env);

        runner.start();
        runner.tick();
        check("selection narrowed while running", Arrays.equals(Areas.read().get(0), new int[]{0, 60, 0, 11, 63, 11}));
        runner.cancel();
        check("child action canceled", childCanceled[0]);
        check("onChunkEnd called on cancel", chunkEnded[0]);
        check("selection restored on cancel", Arrays.equals(Areas.read().get(0), new int[]{0, 60, 0, 23, 63, 11}));
        check("protection cleared on cancel", !env.protectedAreas.containsKey(ChunkRunner.PROTECTED_AREA_NAME));
    }

    private static void testLedgerResumeSkipsDoneChunks() throws Exception {
        Path ledgerFile = freshLedger("resume");
        FakeEnv env = new FakeEnv();
        env.playerPos = new BlockPos(5, 60, 5);
        List<int[]> bounds = List.of(new int[]{0, 60, 0, 23, 63, 11}); // 2 chunks

        // First run: chunk 0 completes, chunk 1 stays deferred
        Map<Integer, Integer> firstRuns = new HashMap<>();
        ChunkRunner first = new ChunkRunner(new ChunkRunner.ChunkWorkload() {
            @Override public String actionKind() { return "clear_area"; }
            @Override public List<int[]> fullBounds() { return bounds; }
            @Override public boolean usesSelectionNarrowing() { return false; }

            @Override
            public Actions.Action createChunkAction(ChunkGrid.ColumnBox chunk, List<int[]> clippedBoxes) {
                firstRuns.merge(chunk.index(), 1, Integer::sum);
                return new Actions.Now(() -> 0.0);
            }

            @Override
            public int countRemaining(ChunkGrid.ColumnBox chunk, List<int[]> clippedBoxes) {
                return chunk.index() == 0 ? 0 : 2;
            }
        }, 12, ChunkGrid.OrderStrategy.SERPENTINE, env);
        runToCompletion(first, 200);
        eq("first run chunk 0 ran once", firstRuns.get(0), 1);

        // Reload ledger from disk and start a new run: chunk 0 must be skipped in Phase 1
        Jobs.useLedger(Ledger.load(ledgerFile));
        Jobs.startRun("test");
        Map<Integer, Integer> secondRuns = new HashMap<>();
        ChunkRunner second = new ChunkRunner(new ChunkRunner.ChunkWorkload() {
            @Override public String actionKind() { return "clear_area"; }
            @Override public List<int[]> fullBounds() { return bounds; }
            @Override public boolean usesSelectionNarrowing() { return false; }

            @Override
            public Actions.Action createChunkAction(ChunkGrid.ColumnBox chunk, List<int[]> clippedBoxes) {
                secondRuns.merge(chunk.index(), 1, Integer::sum);
                return new Actions.Now(() -> 0.0);
            }

            @Override
            public int countRemaining(ChunkGrid.ColumnBox chunk, List<int[]> clippedBoxes) {
                return 0;
            }
        }, 12, ChunkGrid.OrderStrategy.SERPENTINE, env);
        runToCompletion(second, 200);
        eq("resumed run skipped already-DONE chunk 0", secondRuns.getOrDefault(0, 0), 0);
        eq("resumed run executed chunk 1", secondRuns.get(1), 1);
    }

    /** A hole 24 tall in 12-high bands: top band first, and a band that is already empty is not walked to. */
    private static void testBandsAndEmptyChunks() throws Exception {
        freshLedger("bands");
        Areas.setSingleBox(new BlockPos(0, 40, 0), new BlockPos(9, 63, 9));
        FakeEnv env = new FakeEnv();
        env.playerPos = new BlockPos(5, 64, 5);
        List<String> ran = new ArrayList<>();
        ChunkRunner runner = new ChunkRunner(new ChunkRunner.ChunkWorkload() {
            @Override public String actionKind() { return "clear_area"; }
            @Override public boolean skipWhenEmpty() { return true; }
            @Override public List<int[]> fullBounds() { return List.of(new int[]{0, 40, 0, 9, 63, 9}); }
            @Override public Actions.Action createChunkAction(ChunkGrid.ColumnBox chunk, List<int[]> clippedBoxes) {
                ran.add(chunk.minY() + ".." + chunk.maxY());
                return new Actions.Now(() -> 0.0);
            }
            @Override public int countRemaining(ChunkGrid.ColumnBox chunk, List<int[]> clippedBoxes) {
                return chunk.minY() == 52 ? 0 : (ran.contains(chunk.minY() + ".." + chunk.maxY()) ? 0 : 5);   // the top band is air already
            }
        }, 32, ChunkGrid.OrderStrategy.SERPENTINE, env).withGeometry(12, -1);
        runToCompletion(runner, 500);
        check("bands runner finished", runner.done);
        eq("the empty top band is skipped, only the lower band is dug", ran, List.of("40..51"));
        eq("three bands existed", runner.chunks().size(), 2);
    }

    private static void testConfigAndModulesOptions() {
        boolean oldEnabled = Config.cfg.chunkEnabled;
        int oldSize = Config.cfg.chunkSize, oldSizeY = Config.cfg.chunkSizeY, oldGap = Config.cfg.chunkGap;
        String oldOrder = Config.cfg.chunkOrder;
        try {
            Modules.set("chunk.enabled", false, 1);
            eq("chunk.enabled false", Config.cfg.chunkEnabled, false);
            eq("Modules.get chunk.enabled", Modules.get("chunk.enabled", 1), false);

            Modules.set("chunk.size", 500.0, 1);
            eq("chunk.size clamped high to 128", Config.cfg.chunkSize, 128);
            Modules.set("chunk.size", 2.0, 1);
            eq("chunk.size clamped low to 8", Config.cfg.chunkSize, 8);
            Modules.set("chunk.size", 24.0, 1);
            eq("Modules.get chunk.size", Modules.get("chunk.size", 1), 24.0);

            Modules.set("chunk.sizeY", 2.0, 1);
            eq("chunk.sizeY clamped low to 4", Config.cfg.chunkSizeY, 4);
            Modules.set("chunk.sizeY", 0.0, 1);
            eq("chunk.sizeY 0 = off", Config.cfg.chunkSizeY, 0);
            Modules.set("chunk.gap", -5.0, 1);
            eq("chunk.gap below 0 = off", Config.cfg.chunkGap, -1);
            Modules.set("chunk.gap", 12.0, 1);
            eq("chunk.gap set", Config.cfg.chunkGap, 12);

            Modules.set("chunk.order", "NEAREST", 1);
            eq("chunk.order normalized lowercase", Config.cfg.chunkOrder, "nearest");
            eq("Modules.get chunk.order", Modules.get("chunk.order", 1), "nearest");

            Map<String, String> all = Modules.all();
            eq("Modules.all chunk.enabled", all.get("chunk.enabled"), "false");
            eq("Modules.all chunk.size", all.get("chunk.size"), "24");
            eq("Modules.all chunk.order", all.get("chunk.order"), "nearest");
        } finally {
            Config.cfg.chunkEnabled = oldEnabled;
            Config.cfg.chunkSize = oldSize;
            Config.cfg.chunkSizeY = oldSizeY;
            Config.cfg.chunkGap = oldGap;
            Config.cfg.chunkOrder = oldOrder;
        }
    }

    private static void testMapLayersRegistrationAndState() throws Exception {
        freshLedger("maplayers");
        MapLayers.unregister("chunks");
        Areas.setSingleBox(new BlockPos(0, 60, 0), new BlockPos(23, 63, 11));
        FakeEnv env = new FakeEnv();
        env.playerPos = new BlockPos(5, 60, 5);

        ChunkRunner runner = new ChunkRunner(new ChunkRunner.ChunkWorkload() {
            @Override public String actionKind() { return "clear_area"; }
            @Override public List<int[]> fullBounds() { return List.of(new int[]{0, 60, 0, 23, 63, 11}); }

            @Override
            public Actions.Action createChunkAction(ChunkGrid.ColumnBox chunk, List<int[]> clippedBoxes) {
                return new Actions.Action() {
                    int ticksLeft = 2;
                    @Override void start() {}
                    @Override void tick() { if (--ticksLeft <= 0) finish(0.0); }
                };
            }

            @Override public int countRemaining(ChunkGrid.ColumnBox chunk, List<int[]> clippedBoxes) { return 0; }
        }, 12, ChunkGrid.OrderStrategy.SERPENTINE, env);

        runner.start();
        List<MapLayers.Region> initial = MapLayers.regions();
        eq("mapRegions registered on start", initial.size(), 2);
        eq("chunk 0 initially pending", initial.get(0).state(), MapLayers.State.PENDING);
        eq("chunk 0 label is 1", initial.get(0).label(), "1");
        check("chunk 0 detail mentions chunk 1/2", initial.get(0).detail().contains("chunk 1/2"));

        runner.tick(); // starts chunk 0
        List<MapLayers.Region> active = MapLayers.regions();
        eq("chunk 0 active while executing", active.get(0).state(), MapLayers.State.ACTIVE);
        eq("chunk 1 still pending", active.get(1).state(), MapLayers.State.PENDING);

        runner.suspend();
        runner.resume();
        eq("mapRegions still registered after resume", MapLayers.regions().size(), 2);

        runner.tick();
        runner.tick(); // completes chunk 0
        List<MapLayers.Region> afterFirst = MapLayers.regions();
        eq("chunk 0 done after completion", afterFirst.get(0).state(), MapLayers.State.DONE);

        int guard = 0;
        while (!runner.done && guard++ < 100) {
            runner.ticks++;
            runner.tick();
        }
        check("runner finished", runner.done);
        eq("mapRegions unregistered on finish", MapLayers.regions().size(), 0);
    }
}
