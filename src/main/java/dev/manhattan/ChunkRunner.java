package dev.manhattan;

import dev.midtown.api.Area;
import dev.midtown.api.EngineSelector;
import dev.midtown.api.EngineStatus;
import dev.midtown.api.GoalSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Spatial chunking action runner executing any {@link ChunkWorkload} across a 3-phase pipeline
 * ({@link Phase#PRIMARY}, {@link Phase#DEFERRED_RETRY}, {@link Phase#VERIFICATION}) with a 4-step
 * per-chunk lifecycle (walk -> wait chunk load -> check needs -> run chunk).
 */
public class ChunkRunner extends Actions.Action {
    public static final String PROTECTED_AREA_NAME = "manhattan_completed_chunks";
    static final int WALK_TIMEOUT_TICKS = 200;
    static final int MAX_WALK_TICKS = 20 * 180;
    static final int CHUNK_LOAD_TIMEOUT_TICKS = 60;
    static final int MAX_DEFERRED_PASSES = 2;

    public enum Phase {
        PRIMARY,
        DEFERRED_RETRY,
        VERIFICATION,
        DONE
    }

    public enum Step {
        PICK_CHUNK,
        WALK_TO_CHUNK,
        WAIT_CHUNK_LOAD,
        CHECK_NEEDS,
        RUN_CHUNK
    }

    /** Workload contract implemented by spatial job adapters (clear_area, fill_water, flood, drain, schem_build). */
    public interface ChunkWorkload {
        String actionKind();
        List<int[]> fullBounds();
        Actions.Action createChunkAction(ChunkGrid.ColumnBox chunk, List<int[]> clippedBoxes);
        int countRemaining(ChunkGrid.ColumnBox chunk, List<int[]> clippedBoxes);

        default Map<String, Integer> requiredItems(ChunkGrid.ColumnBox chunk, List<int[]> clippedBoxes) {
            return Map.of();
        }

        default boolean usesSelectionNarrowing() {
            return true;
        }

        /** True: a chunk whose blocks are all loaded and that has nothing left to do is ticked off without walking there. */
        default boolean skipWhenEmpty() {
            return false;
        }

        default void onChunkStart(ChunkGrid.ColumnBox chunk, List<int[]> clippedBoxes) {}

        default void onChunkEnd(ChunkGrid.ColumnBox chunk) {}
    }

    /** Environment seam allowing deterministic offline unit testing without a live Minecraft client. */
    interface Env {
        BlockPos playerBlockPos();
        boolean hasChunk(int cx, int cz);
        int countInventoryItem(String itemKey);
        void goTo(GoalSpec goal);
        EngineStatus pathStatus();
        boolean isPathBusy();
        void cancelPath();
        void setProtectedArea(String name, List<Area> areas);
        void removeProtectedArea(String name);
        boolean triggerChunkNeeds(Map<String, Integer> deficit, ChunkGrid.ColumnBox chunk);
        int runnerLine();
        boolean runnerRunning();
    }

    private static final class GameEnv implements Env {
        @Override
        public BlockPos playerBlockPos() {
            Minecraft mc = Minecraft.getInstance();
            return mc != null && mc.player != null ? mc.player.blockPosition() : null;
        }

        @Override
        public boolean hasChunk(int cx, int cz) {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.level == null) return true;
            return mc.level.getChunkSource().hasChunk(cx, cz);
        }

        @Override
        public int countInventoryItem(String itemKey) {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null || itemKey == null || itemKey.isBlank()) return 0;
            String normalized = itemKey.trim().toLowerCase(Locale.ROOT);
            String fullKey = normalized.contains(":") ? normalized : "minecraft:" + normalized;
            Inventory inv = mc.player.getInventory();
            int total = 0;
            int size = inv.getContainerSize();
            for (int i = 0; i < size; i++) {
                ItemStack stack = inv.getItem(i);
                if (stack.isEmpty()) continue;
                String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                if (id.equals(fullKey)) {
                    total += stack.getCount();
                }
            }
            return total;
        }

        @Override
        public void goTo(GoalSpec goal) {
            EngineSelector.current().goTo(goal);
        }

        @Override
        public EngineStatus pathStatus() {
            return EngineSelector.current().status();
        }

        @Override
        public boolean isPathBusy() {
            return EngineSelector.current().isBusy();
        }

        @Override
        public void cancelPath() {
            EngineSelector.current().cancel();
        }

        @Override
        public void setProtectedArea(String name, List<Area> areas) {
            EngineSelector.current().setProtectedArea(name, areas);
        }

        @Override
        public void removeProtectedArea(String name) {
            EngineSelector.current().removeProtectedArea(name);
        }

        @Override
        public boolean triggerChunkNeeds(Map<String, Integer> deficit, ChunkGrid.ColumnBox chunk) {
            return QueueMod.runner != null && QueueMod.runner.triggerChunkNeeds(deficit, chunk);
        }

        @Override
        public int runnerLine() {
            return QueueMod.runner != null && QueueMod.runner.running() ? QueueMod.runner.line() : 0;
        }

        @Override
        public boolean runnerRunning() {
            return QueueMod.runner != null && QueueMod.runner.running();
        }
    }

    private final ChunkWorkload workload;
    private final int chunkSize;
    private final ChunkGrid.OrderStrategy strategy;
    private final Env env;
    private int chunkSizeY;   // > 0: bands of this height in Y, top first
    private int gap = -1;     // >= 0: boxes further apart than this are separate regions
    private String taskIdPrefix;
    private Map<String, Integer> latestDeficit = Map.of();

    private List<int[]> fullBounds = List.of();
    private List<int[]> savedSelection;
    private List<ChunkGrid.ColumnBox> chunks = List.of();
    private final Map<Integer, List<int[]>> clippedByChunk = new HashMap<>();
    private final Map<Integer, Jobs.Node> nodeByChunk = new HashMap<>();
    private final Set<Integer> skippedByUser = new HashSet<>();
    private final Set<Integer> verifiedOnce = new HashSet<>();
    private Jobs.Node actionNode;
    private String signature;

    private Phase phase = Phase.PRIMARY;
    private Step step = Step.PICK_CHUNK;
    private int retryPass = 0;
    private final List<ChunkGrid.ColumnBox> primaryQueue = new ArrayList<>();
    private final List<ChunkGrid.ColumnBox> retryQueue = new ArrayList<>();
    private final List<ChunkGrid.ColumnBox> verifyQueue = new ArrayList<>();

    private ChunkGrid.ColumnBox currentChunk;
    private List<int[]> currentClipped;
    private Actions.Action activeChunkAction;
    private boolean activeActionSuspended;
    private int walkTicks;
    private int walkStallTicks;
    private double walkBestDistSq;
    private int waitLoadTicks;
    private boolean walkIssued;
    private boolean needsCheckedForAttempt;
    private dev.manhattan.fleet.ChunkLeaseProvider leaseProvider;
    private final Map<String, ChunkGrid.ColumnBox> chunkByKey = new LinkedHashMap<>();
    private String currentLeaseKey;

    public ChunkRunner(ChunkWorkload workload) {
        this(workload, Config.cfg.chunkSize, ChunkGrid.OrderStrategy.parse(Config.cfg.chunkOrder));
    }

    public ChunkRunner(ChunkWorkload workload, int chunkSize, ChunkGrid.OrderStrategy strategy) {
        this(workload, chunkSize, strategy, new GameEnv());
    }

    ChunkRunner(ChunkWorkload workload, int chunkSize, ChunkGrid.OrderStrategy strategy, Env env) {
        this.workload = Objects.requireNonNull(workload, "workload");
        this.chunkSize = Math.max(1, chunkSize);
        this.strategy = strategy == null ? ChunkGrid.OrderStrategy.SERPENTINE : strategy;
        this.env = Objects.requireNonNull(env, "env");
    }

    /** Also cut the selection into bands in Y and split it into regions at gaps (see {@link ChunkGrid#split}). */
    public ChunkRunner withGeometry(int chunkSizeY, int gap) {
        this.chunkSizeY = Math.max(0, chunkSizeY);
        this.gap = gap;
        return this;
    }

    /**
     * Uses a stable task id prefix (e.g. {@code "t_001"}) for Jobs/Ledger chunk keys
     * ({@code "<taskIdPrefix>/<x0>,<z0>[,<y0>]"}) instead of line-numbered {@code @line} ids.
     */
    public ChunkRunner withTaskPrefix(String taskIdPrefix) {
        this.taskIdPrefix = (taskIdPrefix == null || taskIdPrefix.isBlank()) ? null : taskIdPrefix.trim();
        return this;
    }

    /** Attaches a Fleet Server lease provider so chunks are claimed/reported remotely instead of locally. */
    public ChunkRunner withLeaseProvider(dev.manhattan.fleet.ChunkLeaseProvider provider) {
        this.leaseProvider = provider;
        return this;
    }

    /** Canonical chunk key shared between local Ledger and Fleet Server leases. */
    public static String chunkKey(String taskPrefix, ChunkGrid.ColumnBox c) {
        String base = c.minX() + "," + c.minZ() + (c.ySlices() > 1 ? "," + c.minY() : "");
        return (taskPrefix != null && !taskPrefix.isBlank()) ? taskPrefix.trim() + "/" + base : base;
    }

    public Map<String, Integer> latestDeficit() {
        return latestDeficit;
    }

    public int remainingWork() {
        if (done && result instanceof Double d) {
            return Math.max(0, (int) Math.round(d));
        }
        return (int) Math.round(computeTotalRemaining());
    }

    Phase phase() { return phase; }
    Step step() { return step; }
    int retryPass() { return retryPass; }
    List<ChunkGrid.ColumnBox> chunks() { return Collections.unmodifiableList(chunks); }
    ChunkGrid.ColumnBox currentChunk() { return currentChunk; }

    @Override
    void start() {
        fullBounds = copyBoxes(workload.fullBounds());
        if (workload.usesSelectionNarrowing()) {
            savedSelection = copyBoxes(Areas.read());
        } else {
            savedSelection = null;
        }

        List<ChunkGrid.ColumnBox> raw = ChunkGrid.split(fullBounds, chunkSize, chunkSizeY, gap);
        if (raw.isEmpty()) {
            finishCleanly(0.0);
            return;
        }

        chunks = strategy == ChunkGrid.OrderStrategy.SERPENTINE
                ? ChunkGrid.orderSerpentine(raw, env.playerBlockPos())
                : raw;

        clippedByChunk.clear();
        nodeByChunk.clear();
        chunkByKey.clear();
        currentLeaseKey = null;
        skippedByUser.clear();
        verifiedOnce.clear();
        primaryQueue.clear();
        retryQueue.clear();
        verifyQueue.clear();
        retryPass = 0;
        currentChunk = null;
        currentClipped = null;
        activeChunkAction = null;
        activeActionSuspended = false;

        signature = buildSignature(workload.actionKind(), chunkSize, chunkSizeY, gap, fullBounds);
        actionNode = taskIdPrefix != null
                ? Jobs.taskAction(taskIdPrefix, workload.actionKind() + " [" + taskIdPrefix + "]")
                : Jobs.action(sanitizeIdPart(workload.actionKind()), env.runnerLine(), "", env.runnerRunning());
        actionNode.state = Ledger.State.RUNNING;

        Ledger ledger = Jobs.ledger();
        for (ChunkGrid.ColumnBox c : chunks) {
            clippedByChunk.put(c.index(), c.clip(fullBounds));
            chunkByKey.put(chunkKey(taskIdPrefix, c), c);
            Jobs.Node node = Jobs.chunk(actionNode, c);
            nodeByChunk.put(c.index(), node);

            Ledger.Entry entry = ledger.get(node.id, signature);
            if (entry != null && entry.state == Ledger.State.DONE) {
                node.state = Ledger.State.DONE;
                node.tries = entry.tries;
                node.left = 0;
                node.reason = entry.reason;
                if (Jobs.SKIPPED.equals(entry.reason)) {
                    skippedByUser.add(c.index());
                }
            } else {
                node.state = Ledger.State.PENDING;
                if (entry != null) {
                    node.tries = entry.tries;
                }
                primaryQueue.add(c);
            }
        }

        syncProtectedCompletedAreas(null);
        phase = Phase.PRIMARY;
        step = Step.PICK_CHUNK;
        MapLayers.register("chunks", this::mapRegions);
    }

    @Override
    void tick() {
        if (done) return;

        switch (step) {
            case PICK_CHUNK -> tickPickChunk();
            case WALK_TO_CHUNK -> tickWalkToChunk();
            case WAIT_CHUNK_LOAD -> tickWaitChunkLoad();
            case CHECK_NEEDS -> tickCheckNeeds();
            case RUN_CHUNK -> tickRunChunk();
        }
    }

    private void tickPickChunk() {
        if (leaseProvider != null) {
            dev.manhattan.fleet.ChunkLeaseProvider.ClaimResult claim = leaseProvider.pollNextChunk();
            if (claim == null || claim.status() == dev.manhattan.fleet.ChunkLeaseProvider.ClaimStatus.WAITING) {
                return;
            }
            if (claim.status() == dev.manhattan.fleet.ChunkLeaseProvider.ClaimStatus.NO_WORK) {
                finishCleanly(computeTotalRemaining());
                return;
            }
            ChunkGrid.ColumnBox next = chunkByKey.get(claim.chunkKey());
            if (next == null) {
                // If the leased chunk belongs to a different task in the same Fleet Project,
                // finish this task cleanly without marking the peer/other-task chunk as FAILED.
                if (taskIdPrefix != null && claim.chunkKey() != null && !claim.chunkKey().startsWith(taskIdPrefix + "/")) {
                    finishCleanly(0.0);
                    return;
                }
                leaseProvider.reportChunk(claim.chunkKey(), "FAILED", "Unknown chunk key");
                return;
            }
            currentLeaseKey = claim.chunkKey();
            if (nothingToDo(next)) {
                tickOffEmpty(next);
                leaseProvider.reportChunk(currentLeaseKey, "DONE", "already empty");
                currentLeaseKey = null;
                return;
            }
            beginChunk(next, false);
            return;
        }

        if (phase == Phase.PRIMARY) {
            if (!primaryQueue.isEmpty()) {
                ChunkGrid.ColumnBox next = ChunkGrid.pickNext(primaryQueue, env.playerBlockPos(), strategy);
                primaryQueue.remove(next);
                if (nothingToDo(next)) {
                    tickOffEmpty(next);
                    return;
                }
                beginChunk(next, false);
                return;
            }
            if (startNextRetryPass()) {
                return;
            }
            enterVerificationPhase();
            return;
        }

        if (phase == Phase.DEFERRED_RETRY) {
            if (!retryQueue.isEmpty()) {
                ChunkGrid.ColumnBox next = ChunkGrid.pickNext(retryQueue, env.playerBlockPos(), strategy);
                retryQueue.remove(next);
                beginChunk(next, false);
                return;
            }
            if (startNextRetryPass()) {
                return;
            }
            enterVerificationPhase();
            return;
        }

        if (phase == Phase.VERIFICATION) {
            while (!verifyQueue.isEmpty()) {
                ChunkGrid.ColumnBox next = verifyQueue.remove(0);
                if (verifiedOnce.contains(next.index())) continue;
                List<int[]> clipped = clippedByChunk.getOrDefault(next.index(), List.of());
                int rem = workload.countRemaining(next, clipped);
                if (rem > 0) {
                    verifiedOnce.add(next.index());
                    beginChunk(next, true);
                    return;
                }
            }
            finishCleanly(computeTotalRemaining());
        }
    }

    /** A chunk of solid-free air (a cave, an earlier dig) or already finished: no reason to walk there. */
    private boolean nothingToDo(ChunkGrid.ColumnBox chunk) {
        if (!workload.skipWhenEmpty() || !allMcChunksLoaded(chunk)) return false;
        return workload.countRemaining(chunk, clippedByChunk.getOrDefault(chunk.index(), List.of())) == 0;
    }

    private void tickOffEmpty(ChunkGrid.ColumnBox chunk) {
        Jobs.Node node = nodeByChunk.get(chunk.index());
        if (node != null) {
            Jobs.set(node, Ledger.State.DONE, node.tries, 0, null, signature);
        }
        if (actionNode != null) {
            Jobs.settle(actionNode);
        }
        syncProtectedCompletedAreas(null);
    }

    private boolean startNextRetryPass() {
        if (retryPass >= MAX_DEFERRED_PASSES) return false;
        List<ChunkGrid.ColumnBox> deferred = new ArrayList<>();
        for (ChunkGrid.ColumnBox c : chunks) {
            Jobs.Node node = nodeByChunk.get(c.index());
            if (node != null && node.state == Ledger.State.DEFERRED) {
                deferred.add(c);
            }
        }
        if (deferred.isEmpty()) return false;
        retryPass++;
        retryQueue.clear();
        retryQueue.addAll(deferred);
        phase = Phase.DEFERRED_RETRY;
        step = Step.PICK_CHUNK;
        return true;
    }

    private void enterVerificationPhase() {
        phase = Phase.VERIFICATION;
        verifyQueue.clear();
        for (ChunkGrid.ColumnBox c : chunks) {
            Jobs.Node node = nodeByChunk.get(c.index());
            if (node != null && node.state == Ledger.State.DONE && !skippedByUser.contains(c.index())) {
                verifyQueue.add(c);
            }
        }
        step = Step.PICK_CHUNK;
        tickPickChunk();
    }

    private void beginChunk(ChunkGrid.ColumnBox chunk, boolean excludeFromProtection) {
        currentChunk = chunk;
        currentClipped = clippedByChunk.getOrDefault(chunk.index(), List.of());
        activeChunkAction = null;
        activeActionSuspended = false;
        walkTicks = 0;
        walkStallTicks = 0;
        walkBestDistSq = Double.MAX_VALUE;
        waitLoadTicks = 0;
        walkIssued = false;
        needsCheckedForAttempt = false;
        this.estimate = new Estimate();

        Jobs.Node node = nodeByChunk.get(chunk.index());
        if (node != null) {
            Jobs.set(node, Ledger.State.RUNNING, node.tries, node.left, null, signature);
        }
        if (excludeFromProtection) {
            syncProtectedCompletedAreas(chunk.index());
        }

        BlockPos playerPos = env.playerBlockPos();
        boolean far = playerPos != null && chunk.distanceSqXZ(playerPos) > 64.0;
        boolean unloaded = !allMcChunksLoaded(chunk);

        if (far || unloaded) {
            issueWalkToChunk(chunk);
            step = Step.WALK_TO_CHUNK;
        } else {
            step = Step.WAIT_CHUNK_LOAD;
            tickWaitChunkLoad();
        }
    }

    private void issueWalkToChunk(ChunkGrid.ColumnBox chunk) {
        BlockPos walkTarget = chunk.centerXZ(chunk.minY());
        BlockPos playerPos = env.playerBlockPos();
        walkBestDistSq = playerPos != null ? chunk.distanceSqXZ(playerPos) : Double.MAX_VALUE;
        walkStallTicks = 0;
        env.goTo(GoalSpec.xz(walkTarget.getX(), walkTarget.getZ()));
        walkIssued = true;
    }

    private void tickWalkToChunk() {
        walkTicks++;
        BlockPos playerPos = env.playerBlockPos();
        if (playerPos != null && currentChunk != null) {
            double distSq = currentChunk.distanceSqXZ(playerPos);
            if (distSq <= 64.0 && allMcChunksLoaded(currentChunk)) {
                env.cancelPath();
                step = Step.WAIT_CHUNK_LOAD;
                tickWaitChunkLoad();
                return;
            }
            if (distSq < walkBestDistSq - 1.0) {
                walkBestDistSq = distSq;
                walkStallTicks = 0;
            } else {
                walkStallTicks++;
            }
        } else {
            walkStallTicks++;
        }
        if (walkStallTicks >= WALK_TIMEOUT_TICKS || walkTicks >= MAX_WALK_TICKS) {
            env.cancelPath();
            step = Step.WAIT_CHUNK_LOAD;
            return;
        }
        EngineStatus st = env.pathStatus();
        if (st == EngineStatus.DONE || st == EngineStatus.FAILED || (walkTicks >= 2 && !env.isPathBusy())) {
            step = Step.WAIT_CHUNK_LOAD;
            tickWaitChunkLoad();
        }
    }

    private void tickWaitChunkLoad() {
        if (allMcChunksLoaded(currentChunk) || waitLoadTicks >= CHUNK_LOAD_TIMEOUT_TICKS) {
            step = Step.CHECK_NEEDS;
            tickCheckNeeds();
            return;
        }
        waitLoadTicks++;
    }

    private void tickCheckNeeds() {
        step = Step.RUN_CHUNK;
        if (!needsCheckedForAttempt) {
            needsCheckedForAttempt = true;
            Map<String, Integer> required = workload.requiredItems(currentChunk, currentClipped);
            Map<String, Integer> deficit = computeDeficit(required);
            latestDeficit = deficit.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(deficit));
            if (!deficit.isEmpty() && env.triggerChunkNeeds(deficit, currentChunk)) {
                return; // Wait for the "on chunk_needs" handler to finish and resume() us into RUN_CHUNK.
            }
        }
        tickRunChunk();
    }

    private Map<String, Integer> computeDeficit(Map<String, Integer> required) {
        if (required == null || required.isEmpty()) return Map.of();
        Map<String, Integer> deficit = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : required.entrySet()) {
            String item = e.getKey();
            int need = e.getValue() == null ? 0 : e.getValue();
            if (item == null || need <= 0) continue;
            int have = env.countInventoryItem(item);
            if (need > have) {
                deficit.put(item, need - have);
            }
        }
        return deficit;
    }

    private void tickRunChunk() {
        if (activeChunkAction == null) {
            if (workload.usesSelectionNarrowing()) {
                Areas.apply(currentClipped);
            }
            workload.onChunkStart(currentChunk, currentClipped);
            activeChunkAction = workload.createChunkAction(currentChunk, currentClipped);
            if (activeChunkAction == null) {
                completeCurrentChunk(null);
                return;
            }
            this.estimate = activeChunkAction.estimate;
            if (!activeChunkAction.done) {
                activeChunkAction.start();
            }
            if (activeChunkAction.done) {
                completeCurrentChunk(activeChunkAction.error);
            }
            return;
        }

        if (activeActionSuspended) {
            activeActionSuspended = false;
            if (workload.usesSelectionNarrowing() && currentClipped != null) {
                Areas.apply(currentClipped);
            }
            workload.onChunkStart(currentChunk, currentClipped);
            activeChunkAction.resume();
        }
        if (!activeChunkAction.done) {
            activeChunkAction.ticks++;
            activeChunkAction.tick();
        }
        if (activeChunkAction.done) {
            completeCurrentChunk(activeChunkAction.error);
        }
    }

    private void completeCurrentChunk(String actionError) {
        ChunkGrid.ColumnBox finishedChunk = currentChunk;
        List<int[]> finishedClipped = currentClipped;

        activeChunkAction = null;
        activeActionSuspended = false;
        this.estimate = new Estimate();
        workload.onChunkEnd(finishedChunk);
        if (workload.usesSelectionNarrowing() && savedSelection != null) {
            Areas.apply(savedSelection);
        }

        Jobs.Node node = nodeByChunk.get(finishedChunk.index());
        String leaseKey = currentLeaseKey;
        currentLeaseKey = null;
        if (actionError != null) {
            if (node != null) {
                Jobs.set(node, Ledger.State.FAILED, node.tries + 1, node.left, actionError, signature);
            }
            if (leaseProvider != null && leaseKey != null) {
                leaseProvider.reportChunk(leaseKey, "FAILED", actionError);
            }
            currentChunk = null;
            currentClipped = null;
            cleanupAndFail(actionError);
            return;
        }

        int rem = workload.countRemaining(finishedChunk, finishedClipped);
        if (node != null) {
            if (rem == 0) {
                Jobs.set(node, Ledger.State.DONE, node.tries + 1, 0, null, signature);
            } else {
                String reason = phase == Phase.VERIFICATION
                        ? rem + " left after verification"
                        : rem + " left";
                Jobs.set(node, Ledger.State.DEFERRED, node.tries + 1, rem, reason, signature);
            }
        }
        if (leaseProvider != null && leaseKey != null) {
            if (rem == 0 && !allMcChunksLoaded(finishedChunk)) {
                leaseProvider.reportChunk(leaseKey, "DEFERRED", "chunk not loaded");
            } else if (rem == 0) {
                leaseProvider.reportChunk(leaseKey, "DONE", "");
            } else {
                leaseProvider.reportChunk(leaseKey, "DEFERRED", rem + " left");
            }
        }
        if (actionNode != null) {
            Jobs.settle(actionNode);
        }
        syncProtectedCompletedAreas(null);

        currentChunk = null;
        currentClipped = null;
        latestDeficit = Map.of();
        step = Step.PICK_CHUNK;
    }

    private void syncProtectedCompletedAreas(Integer excludedChunkIndex) {
        List<Area> completedAreas = new ArrayList<>();
        for (ChunkGrid.ColumnBox c : chunks) {
            if (excludedChunkIndex != null && c.index() == excludedChunkIndex) continue;
            Jobs.Node node = nodeByChunk.get(c.index());
            if (node != null && node.state == Ledger.State.DONE) {
                completedAreas.add(c.toMidtownArea());
            }
        }
        if (completedAreas.isEmpty()) {
            env.removeProtectedArea(PROTECTED_AREA_NAME);
        } else {
            env.setProtectedArea(PROTECTED_AREA_NAME, completedAreas);
        }
    }

    private boolean allMcChunksLoaded(ChunkGrid.ColumnBox chunk) {
        if (chunk == null) return true;
        int minCX = Math.floorDiv(chunk.minX(), 16);
        int maxCX = Math.floorDiv(chunk.maxX(), 16);
        int minCZ = Math.floorDiv(chunk.minZ(), 16);
        int maxCZ = Math.floorDiv(chunk.maxZ(), 16);
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                if (!env.hasChunk(cx, cz)) return false;
            }
        }
        return true;
    }

    private double computeTotalRemaining() {
        long total = 0;
        for (ChunkGrid.ColumnBox c : chunks) {
            Jobs.Node node = nodeByChunk.get(c.index());
            if (node != null && node.state != Ledger.State.DONE) {
                total += Math.max(0, node.left);
            }
        }
        return (double) total;
    }

    private void finishCleanly(double resultValue) {
        phase = Phase.DONE;
        latestDeficit = Map.of();
        MapLayers.unregister("chunks");
        env.removeProtectedArea(PROTECTED_AREA_NAME);
        if (workload.usesSelectionNarrowing() && savedSelection != null) {
            Areas.apply(savedSelection);
        }
        if (actionNode != null) {
            Jobs.settle(actionNode);
        }
        finish(resultValue);
    }

    private void cleanupAndFail(String why) {
        phase = Phase.DONE;
        latestDeficit = Map.of();
        MapLayers.unregister("chunks");
        env.removeProtectedArea(PROTECTED_AREA_NAME);
        if (workload.usesSelectionNarrowing() && savedSelection != null) {
            Areas.apply(savedSelection);
        }
        if (actionNode != null) {
            Jobs.settle(actionNode);
        }
        fail(why);
    }

    @Override
    void suspend() {
        if (activeChunkAction != null) {
            activeChunkAction.suspend();
            activeActionSuspended = true;
        }
        if (step == Step.WALK_TO_CHUNK && walkIssued) {
            env.cancelPath();
        }
        if (workload.usesSelectionNarrowing() && savedSelection != null) {
            Areas.apply(savedSelection);
        }
    }

    @Override
    void resume() {
        done = false;
        MapLayers.register("chunks", this::mapRegions);
        if (currentChunk == null) return;
        BlockPos playerPos = env.playerBlockPos();
        boolean far = playerPos != null && currentChunk.distanceSqXZ(playerPos) > 64.0;
        boolean unloaded = !allMcChunksLoaded(currentChunk);
        if (step == Step.WALK_TO_CHUNK || far || unloaded) {
            walkTicks = 0;
            waitLoadTicks = 0;
            step = Step.WALK_TO_CHUNK;
            issueWalkToChunk(currentChunk);
            return;
        }
        if (step == Step.RUN_CHUNK) {
            if (workload.usesSelectionNarrowing() && currentClipped != null) {
                Areas.apply(currentClipped);
            }
            if (activeChunkAction != null && activeActionSuspended) {
                activeActionSuspended = false;
                workload.onChunkStart(currentChunk, currentClipped);
                activeChunkAction.resume();
            }
        }
    }

    @Override
    void cancel() {
        MapLayers.unregister("chunks");
        latestDeficit = Map.of();
        activeActionSuspended = false;
        if (activeChunkAction != null) {
            activeChunkAction.cancel();
            activeChunkAction = null;
        }
        if (currentChunk != null) {
            workload.onChunkEnd(currentChunk);
            currentChunk = null;
            currentClipped = null;
        }
        env.cancelPath();
        env.removeProtectedArea(PROTECTED_AREA_NAME);
        if (workload.usesSelectionNarrowing() && savedSelection != null) {
            Areas.apply(savedSelection);
        }
        if (actionNode != null) {
            Jobs.settle(actionNode);
        }
    }

    public List<MapLayers.Region> mapRegions() {
        if (chunks.isEmpty()) return List.of();
        int totalChunks = chunks.size();
        List<MapLayers.Region> out = new ArrayList<>(totalChunks);
        for (ChunkGrid.ColumnBox chunk : chunks) {
            Jobs.Node node = nodeByChunk.get(chunk.index());
            MapLayers.State state;
            if (currentChunk != null && currentChunk.index() == chunk.index()) {
                state = MapLayers.State.ACTIVE;
            } else if (node != null) {
                state = switch (node.state) {
                    case DONE -> skippedByUser.contains(chunk.index())
                            ? MapLayers.State.SKIPPED
                            : MapLayers.State.DONE;
                    case FAILED -> MapLayers.State.FAILED;
                    case RUNNING -> MapLayers.State.ACTIVE;
                    case PENDING, DEFERRED -> MapLayers.State.PENDING;
                };
            } else {
                state = MapLayers.State.PENDING;
            }
            String detail = "chunk " + (chunk.index() + 1) + "/" + totalChunks
                    + " (" + phase.name().toLowerCase(Locale.ROOT)
                    + (state == MapLayers.State.ACTIVE ? ", " + step.name().toLowerCase(Locale.ROOT) : "")
                    + ")";
            out.add(new MapLayers.Region(
                    chunk.minX(),
                    chunk.minZ(),
                    chunk.maxX(),
                    chunk.maxZ(),
                    state,
                    String.valueOf(chunk.index() + 1),
                    detail
            ));
        }
        return out;
    }

    @Override
    boolean stalled() {
        return activeChunkAction != null && activeChunkAction.stalled();
    }

    private static List<int[]> copyBoxes(List<int[]> boxes) {
        if (boxes == null || boxes.isEmpty()) return List.of();
        List<int[]> out = new ArrayList<>(boxes.size());
        for (int[] b : boxes) {
            if (b != null && b.length >= 6) {
                out.add(new int[]{
                        Math.min(b[0], b[3]), Math.min(b[1], b[4]), Math.min(b[2], b[5]),
                        Math.max(b[0], b[3]), Math.max(b[1], b[4]), Math.max(b[2], b[5])
                });
            }
        }
        return out;
    }

    private static String buildSignature(String kind, int chunkSize, int chunkSizeY, int gap, List<int[]> bounds) {
        StringBuilder sb = new StringBuilder();
        sb.append(sanitizeIdPart(kind)).append(':').append(chunkSize);
        if (chunkSizeY > 0 || gap >= 0) sb.append('y').append(chunkSizeY).append('g').append(gap);
        for (int[] b : bounds) {
            sb.append(':').append(b[0]).append(',').append(b[1]).append(',').append(b[2])
              .append('-').append(b[3]).append(',').append(b[4]).append(',').append(b[5]);
        }
        return sb.toString();
    }

    private static String sanitizeIdPart(String s) {
        if (s == null || s.isBlank()) return "chunked";
        return s.trim().replace('/', '-');
    }
}
