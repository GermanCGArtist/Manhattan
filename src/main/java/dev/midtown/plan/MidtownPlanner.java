package dev.midtown.plan;

import dev.midtown.api.Area;
import dev.midtown.api.BlockFilter;
import dev.midtown.api.GoalSpec;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Asynchronous and synchronous 3D A* path planner over a {@link BlockView} and {@link CalculationContext}.
 * <p>
 * Evaluates typed {@link MoveEdge}s (walk, ascend, descend, fall, break-through, bridge, pillar, parkour),
 * enforces {@link HazardCost} penalties/hard-blocks and {@code RegionRules} safe-area masks,
 * and respects a configurable time budget (20-50ms default) with best-so-far partial pathing.
 */
public final class MidtownPlanner {

    private static final double SQRT2 = Math.sqrt(2.0);
    private static final double HEURISTIC_WEIGHT = 1.1;

    private static final byte EDGE_WALK = 0;
    private static final byte EDGE_ASCEND = 1;
    private static final byte EDGE_DESCEND = 2;
    private static final byte EDGE_FALL = 3;
    private static final byte EDGE_BREAK = 4;
    private static final byte EDGE_BRIDGE = 5;
    private static final byte EDGE_PILLAR = 6;
    private static final byte EDGE_PARKOUR = 7;

    private static final int[][] CARDINALS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}
    };
    private static final int[][] DIAGONALS = {
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger(1);

    private final ExecutorService executor;
    private final boolean ownsExecutor;

    /** Creates a planner with a dedicated daemon worker pool for async planning. */
    public MidtownPlanner() {
        this(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "midtown-planner-" + THREAD_SEQ.getAndIncrement());
            t.setDaemon(true);
            return t;
        }), true);
    }

    /** Creates a planner backed by a caller-supplied executor service. */
    public MidtownPlanner(ExecutorService executor) {
        this(Objects.requireNonNull(executor, "executor"), false);
    }

    private MidtownPlanner(ExecutorService executor, boolean ownsExecutor) {
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
    }

    /** Shuts down the internal planner thread pool if owned by this instance. */
    public void shutdown() {
        if (ownsExecutor) {
            executor.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ Public Planning API

    /** Asynchronously plans a path to {@code goal} using {@code ctx.timeBudgetMs()}. */
    public CompletableFuture<PlanResult> planAsync(
            BlockView world,
            CalculationContext ctx,
            BlockPos start,
            GoalSpec goal) {
        return planAsync(world, ctx, start, goal, ctx.timeBudgetMs());
    }

    /** Asynchronously plans a path to {@code goal} with an explicit millisecond time budget. */
    public CompletableFuture<PlanResult> planAsync(
            BlockView world,
            CalculationContext ctx,
            BlockPos start,
            GoalSpec goal,
            long timeBudgetMs) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(goal, "goal");

        AtomicBoolean cancelled = new AtomicBoolean(false);
        CancellableFuture future = new CancellableFuture(cancelled);
        executor.execute(() -> {
            if (cancelled.get()) {
                future.complete(cancelledResult(start));
                return;
            }
            try {
                PlanResult result = runAStar(world, ctx, start, GoalAdapter.forGoal(world, goal), timeBudgetMs, cancelled);
                future.complete(result);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /** Synchronously plans a path to {@code goal} using {@code ctx.timeBudgetMs()}. */
    public PlanResult planSync(
            BlockView world,
            CalculationContext ctx,
            BlockPos start,
            GoalSpec goal) {
        return planSync(world, ctx, start, goal, ctx.timeBudgetMs());
    }

    /** Synchronously plans a path to {@code goal} with an explicit millisecond time budget. */
    public PlanResult planSync(
            BlockView world,
            CalculationContext ctx,
            BlockPos start,
            GoalSpec goal,
            long timeBudgetMs) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(goal, "goal");
        return runAStar(world, ctx, start, GoalAdapter.forGoal(world, goal), timeBudgetMs, null);
    }

    /** Asynchronously plans a path into mining reach of any block matching {@code filter}. */
    public CompletableFuture<PlanResult> planMineAsync(
            BlockView world,
            CalculationContext ctx,
            BlockPos start,
            BlockFilter filter,
            Area box,
            long timeBudgetMs) {
        return planMineAsync(world, ctx, start, filter, box, timeBudgetMs, Set.of());
    }

    /** Asynchronously plans a path into mining reach of any block matching {@code filter}, skipping {@code excludedTargets}. */
    public CompletableFuture<PlanResult> planMineAsync(
            BlockView world,
            CalculationContext ctx,
            BlockPos start,
            BlockFilter filter,
            Area box,
            long timeBudgetMs,
            Set<BlockPos> excludedTargets) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(filter, "filter");

        Set<BlockPos> excludedSnapshot = (excludedTargets == null || excludedTargets.isEmpty())
                ? Set.of()
                : Set.copyOf(excludedTargets);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        CancellableFuture future = new CancellableFuture(cancelled);
        executor.execute(() -> {
            if (cancelled.get()) {
                future.complete(cancelledResult(start));
                return;
            }
            try {
                PlanResult result = planMineSync(world, ctx, start, filter, box, timeBudgetMs, excludedSnapshot, cancelled);
                future.complete(result);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /** Synchronously plans a path into mining reach of any block matching {@code filter}. */
    public PlanResult planMineSync(
            BlockView world,
            CalculationContext ctx,
            BlockPos start,
            BlockFilter filter,
            Area box,
            long timeBudgetMs) {
        return planMineSync(world, ctx, start, filter, box, timeBudgetMs, Set.of(), null);
    }

    /** Synchronously plans a path into mining reach of any block matching {@code filter}, skipping {@code excludedTargets}. */
    public PlanResult planMineSync(
            BlockView world,
            CalculationContext ctx,
            BlockPos start,
            BlockFilter filter,
            Area box,
            long timeBudgetMs,
            Set<BlockPos> excludedTargets) {
        Set<BlockPos> excludedSnapshot = (excludedTargets == null || excludedTargets.isEmpty())
                ? Set.of()
                : Set.copyOf(excludedTargets);
        return planMineSync(world, ctx, start, filter, box, timeBudgetMs, excludedSnapshot, null);
    }

    private PlanResult planMineSync(
            BlockView world,
            CalculationContext ctx,
            BlockPos start,
            BlockFilter filter,
            Area box,
            long timeBudgetMs,
            Set<BlockPos> excludedTargets,
            AtomicBoolean cancelled) {
        long startNanos = System.nanoTime();
        List<BlockPos> targets = findMineTargets(world, ctx, start, filter, box, 48, 32, excludedTargets);
        if (targets.isEmpty()) {
            long elapsed = (System.nanoTime() - startNanos) / 1_000_000L;
            return new PlanResult(PlanResult.Status.NO_PATH, start, List.of(), List.of(start), 0.0, 0, elapsed);
        }
        return runAStar(world, ctx, start, new MineGoal(world, targets, 4.25), timeBudgetMs, cancelled);
    }

    // ------------------------------------------------------------------ 3D A* Engine

    private PlanResult runAStar(
            BlockView world,
            CalculationContext ctx,
            BlockPos start,
            GoalAdapter goal,
            long timeBudgetMs,
            AtomicBoolean cancelled) {

        long startNanos = System.nanoTime();
        long budgetNanos = Math.max(1L, timeBudgetMs) * 1_000_000L;
        long deadlineNanos = startNanos + budgetNanos;

        int sx = start.getX();
        int sy = start.getY();
        int sz = start.getZ();

        if (goal.isGoal(sx, sy, sz)) {
            long elapsed = (System.nanoTime() - startNanos) / 1_000_000L;
            return new PlanResult(PlanResult.Status.COMPLETE, start, List.of(), List.of(start), 0.0, 0, elapsed);
        }

        SearchState state = new SearchState(1024);
        int startId = state.addNode(sx, sy, sz, 0.0, goal.heuristic(sx, sy, sz, ctx), -1, (byte) 0, (byte) 0, 0L, Long.MIN_VALUE, Long.MIN_VALUE, 0.0, 0);
        state.heap.push(startId);

        int bestNodeId = startId;
        double bestH = state.hScore[startId];
        double bestG = 0.0;

        int expanded = 0;
        int maxNodes = ctx.maxNodesExpanded();
        boolean timedOut = false;
        boolean touchedUnloaded = false;

        NeighborBuffer nb = new NeighborBuffer();

        while (!state.heap.isEmpty()) {
            if (cancelled != null && cancelled.get()) {
                return cancelledResult(start);
            }
            if (expanded >= 128 && (expanded & 63) == 0 && System.nanoTime() >= deadlineNanos) {
                timedOut = true;
                break;
            }
            if (expanded >= maxNodes) {
                timedOut = true;
                break;
            }

            int curId = state.heap.popMin();
            if (state.closed[curId]) continue;
            state.closed[curId] = true;
            expanded++;

            long curPacked = state.packedPos[curId];
            int cx = BlockPos.getX(curPacked);
            int cy = BlockPos.getY(curPacked);
            int cz = BlockPos.getZ(curPacked);
            double curG = state.gScore[curId];
            int curPlaced = state.placedCount[curId];

            if (goal.isGoal(cx, cy, cz)) {
                long elapsed = (System.nanoTime() - startNanos) / 1_000_000L;
                return reconstruct(PlanResult.Status.COMPLETE, start, state, curId, expanded, elapsed);
            }

            double curH = state.hScore[curId];
            if (curH < bestH - 1e-6 || (Math.abs(curH - bestH) <= 1e-6 && curG < bestG)) {
                bestH = curH;
                bestG = curG;
                bestNodeId = curId;
            }

            nb.clear(curPlaced);
            boolean stoodOnPlaced = isPlacedSupportEdge(state.edgeType[curId], state.edgeFlag[curId]);
            generateNeighbors(world, ctx, cx, cy, cz, curPlaced, stoodOnPlaced, nb);
            if (nb.touchedUnloaded) {
                touchedUnloaded = true;
            }

            for (int i = 0; i < nb.count; i++) {
                int nx = nb.tx[i];
                int ny = nb.ty[i];
                int nz = nb.tz[i];
                double edgeCost = nb.cost[i];
                double tentativeG = curG + edgeCost;

                long nPacked = BlockPos.asLong(nx, ny, nz);
                int existingId = state.posMap.get(nPacked);
                if (existingId < 0) {
                    double h = goal.heuristic(nx, ny, nz, ctx);
                    int nextId = state.addNode(
                            nx, ny, nz,
                            tentativeG, h,
                            curId,
                            nb.edgeType[i],
                            nb.edgeFlag[i],
                            nb.aux1[i],
                            nb.aux2[i],
                            nb.aux3[i],
                            edgeCost,
                            nb.nextPlaced[i]);
                    state.heap.push(nextId);

                    if (h < bestH - 1e-6 || (Math.abs(h - bestH) <= 1e-6 && tentativeG < bestG)) {
                        bestH = h;
                        bestG = tentativeG;
                        bestNodeId = nextId;
                    }
                } else if (!state.closed[existingId] && tentativeG < state.gScore[existingId] - 1e-6) {
                    state.gScore[existingId] = tentativeG;
                    state.fScore[existingId] = tentativeG + HEURISTIC_WEIGHT * state.hScore[existingId];
                    state.parentId[existingId] = curId;
                    state.edgeType[existingId] = nb.edgeType[i];
                    state.edgeFlag[existingId] = nb.edgeFlag[i];
                    state.aux1[existingId] = nb.aux1[i];
                    state.aux2[existingId] = nb.aux2[i];
                    state.aux3[existingId] = nb.aux3[i];
                    state.edgeCost[existingId] = edgeCost;
                    state.placedCount[existingId] = nb.nextPlaced[i];
                    state.heap.decreaseKey(existingId);
                }
            }
        }

        long elapsed = (System.nanoTime() - startNanos) / 1_000_000L;
        if (timedOut) {
            return reconstruct(PlanResult.Status.PARTIAL, start, state, bestNodeId, expanded, elapsed);
        }
        if (bestNodeId != startId && touchesUnloadedTowardGoal(world, ctx, state.packedPos[bestNodeId], goal)) {
            return reconstruct(PlanResult.Status.PARTIAL, start, state, bestNodeId, expanded, elapsed);
        }
        return new PlanResult(PlanResult.Status.NO_PATH, start, List.of(), List.of(start), 0.0, expanded, elapsed);
    }

    private static boolean touchesUnloadedTowardGoal(
            BlockView world,
            CalculationContext ctx,
            long packedBest,
            GoalAdapter goal) {
        int bx = BlockPos.getX(packedBest);
        int by = BlockPos.getY(packedBest);
        int bz = BlockPos.getZ(packedBest);
        double bestH = goal.heuristic(bx, by, bz, ctx);
        for (int[] d : CARDINALS) {
            int nx = bx + d[0];
            int nz = bz + d[1];
            if (!world.isLoaded(nx, by, nz) && goal.heuristic(nx, by, nz, ctx) < bestH - 1e-6) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ MoveEdge Expansion

    private void generateNeighbors(
            BlockView world,
            CalculationContext ctx,
            int x, int y, int z,
            int placedSoFar,
            boolean stoodOnPlaced,
            NeighborBuffer out) {

        HazardCost hazards = ctx.hazardCost();
        double horizCost = ctx.horizontalTicksPerBlock();
        boolean inWater = world.isWater(x, y, z);
        boolean standableHere = stoodOnPlaced || world.canStandOn(x, y - 1, z);

        // 1. Cardinal flat moves: WalkEdge, BreakThroughEdge, BridgeEdge, DescendEdge, FallEdge, AscendEdge
        for (int[] d : CARDINALS) {
            int dx = d[0];
            int dz = d[1];
            int nx = x + dx;
            int nz = z + dz;

            if (!world.isLoaded(nx, y, nz)) {
                out.touchedUnloaded = true;
                continue;
            }

            // --- Flat (dy = 0): WalkEdge / BridgeEdge / BreakThroughEdge ---
            tryCardinalFlat(world, ctx, hazards, x, y, z, nx, nz, horizCost, inWater, standableHere, placedSoFar, out);

            // --- Step up (dy = +1): AscendEdge / BreakThroughEdge ---
            tryAscend(world, ctx, hazards, x, y, z, nx, nz, horizCost, inWater, standableHere, out);

            // --- Step down (dy = -1) or Fall (dy <= -2): DescendEdge / FallEdge / BreakThroughEdge ---
            tryDescendOrFall(world, ctx, hazards, x, y, z, nx, nz, horizCost, out);
        }

        // 2. Diagonal WalkEdge (flat only, strict 2-block corner clearance)
        for (int[] d : DIAGONALS) {
            int nx = x + d[0];
            int nz = z + d[1];
            tryDiagonalWalk(world, ctx, hazards, x, y, z, nx, nz, horizCost * CalculationContext.DEFAULT_DIAGONAL_MULTIPLIER, out);
        }

        // 3. PillarEdge: straight up (0, +1, 0)
        tryPillar(world, ctx, hazards, x, y, z, standableHere, placedSoFar, out);

        // 4. Dig straight down 1 block (BreakThroughEdge (0, -1, 0))
        tryDigDown(world, ctx, hazards, x, y, z, out);

        // 5. ParkourEdge: 1-3 block horizontal gaps
        if (ctx.allowParkour() && standableHere) {
            tryParkour(world, ctx, hazards, x, y, z, horizCost, placedSoFar, out);
        }
    }

    private void tryCardinalFlat(
            BlockView world,
            CalculationContext ctx,
            HazardCost hazards,
            int x, int y, int z,
            int nx, int nz,
            double horizCost,
            boolean inWater,
            boolean standableHere,
            int placedSoFar,
            NeighborBuffer out) {

        if (ctx.isNodeBlacklisted(nx, y, nz) || ctx.isEdgeBlacklisted(x, y, z, nx, y, nz)) {
            return;
        }
        if (!world.isInYBounds(y - 1) || !world.isInYBounds(y + 1)) return;

        boolean feetPass = world.isPassable(nx, y, nz);
        boolean headPass = world.isPassable(nx, y + 1, nz);
        boolean groundStand = world.canStandOn(nx, y - 1, nz);
        boolean targetWater = world.isWater(nx, y, nz);

        if (feetPass && headPass) {
            if (groundStand || (targetWater && !world.isLava(nx, y - 1, nz))) {
                double pen = hazards.nodePenalty(world, ctx, nx, y, nz);
                if (!Double.isInfinite(pen)) {
                    double moveCost = horizCost + (inWater || targetWater ? 4.0 : 0.0) + pen;
                    out.add(nx, y, nz, moveCost, EDGE_WALK, (byte) 0, 0L, Long.MIN_VALUE, placedSoFar);
                }
                return;
            }
            // No ground at (nx, y - 1, nz): try BridgeEdge if replaceable and we have expendable blocks.
            if (standableHere
                    && ctx.allowPlace()
                    && placedSoFar < ctx.expendableBlockCount()
                    && world.isReplaceable(nx, y - 1, nz)
                    && !ctx.isPlaceProtected(nx, y - 1, nz, x, y, z)) {
                double pen = hazards.nodePenalty(world, ctx, nx, y, nz);
                // Ignore ground penalty of the replaceable air/water we're covering, check feet/head + cactus/cliff
                double feetHeadPen = hazards.passThroughPenalty(world, nx, y, nz);
                if (!Double.isInfinite(feetHeadPen)) {
                    double bridgePenalty = Double.isInfinite(pen) ? feetHeadPen : pen;
                    double moveCost = horizCost + ctx.placeCostTicks() + bridgePenalty;
                    out.add(nx, y, nz, moveCost, EDGE_BRIDGE, (byte) 0, BlockPos.asLong(nx, y - 1, nz), Long.MIN_VALUE, placedSoFar + 1);
                }
            }
            return;
        }

        // Blocked at feet and/or head: try BreakThroughEdge (1-2 blocks) onto standable ground.
        if (ctx.allowBreak() && (groundStand || targetWater)) {
            tryBreakThrough(world, ctx, hazards, x, y, z, nx, y, nz,
                    nx, y, nz,
                    nx, y + 1, nz,
                    Integer.MIN_VALUE, 0, 0,
                    2, horizCost, placedSoFar, out);
        }
    }

    private void tryAscend(
            BlockView world,
            CalculationContext ctx,
            HazardCost hazards,
            int x, int y, int z,
            int nx, int nz,
            double horizCost,
            boolean inWater,
            boolean standableHere,
            NeighborBuffer out) {

        int ny = y + 1;
        if (!world.isInYBounds(ny + 1)) return;
        if (ctx.isNodeBlacklisted(nx, ny, nz) || ctx.isEdgeBlacklisted(x, y, z, nx, ny, nz)) return;
        if (!standableHere && !inWater) return;

        // Target ground at (nx, y, nz) must be standable (or water if swimming up in water).
        boolean groundStand = world.canStandOn(nx, y, nz);
        boolean targetWater = world.isWater(nx, ny, nz);
        if (!groundStand && !(inWater && targetWater)) return;

        boolean overPass = world.isPassable(x, y + 2, z);
        boolean feetPass = world.isPassable(nx, ny, nz);
        boolean headPass = world.isPassable(nx, ny + 1, nz);

        if (overPass && feetPass && headPass) {
            double pen = hazards.nodePenalty(world, ctx, nx, ny, nz);
            if (!Double.isInfinite(pen)) {
                double cost = horizCost + 3.5 + (inWater ? 3.0 : 0.0) + pen;
                out.add(nx, ny, nz, cost, EDGE_ASCEND, (byte) 0, 0L, Long.MIN_VALUE, out.currentPlacedFallback);
            }
            return;
        }

        if (ctx.allowBreak() && groundStand) {
            tryBreakThrough(world, ctx, hazards, x, y, z, nx, ny, nz,
                    x, y + 2, z,
                    nx, ny, nz,
                    nx, ny + 1, nz,
                    3, horizCost + 4.0, out.currentPlacedFallback, out);
        }
    }

    private void tryDescendOrFall(
            BlockView world,
            CalculationContext ctx,
            HazardCost hazards,
            int x, int y, int z,
            int nx, int nz,
            double horizCost,
            NeighborBuffer out) {

        if (!world.isInYBounds(y + 1)) return;

        // Step-off column (nx, y, nz) and (nx, y + 1, nz) must be clear (or breakable for 1-block descend).
        boolean stepFeetPass = world.isPassable(nx, y, nz);
        boolean stepHeadPass = world.isPassable(nx, y + 1, nz);

        if (!stepFeetPass || !stepHeadPass) {
            // Try BreakThroughEdge descending 1 block to (nx, y - 1, nz)
            int ny = y - 1;
            if (ctx.allowBreak()
                    && world.isInYBounds(ny - 1)
                    && world.canStandOn(nx, ny - 1, nz)
                    && !ctx.isNodeBlacklisted(nx, ny, nz)
                    && !ctx.isEdgeBlacklisted(x, y, z, nx, ny, nz)) {
                tryBreakThrough(world, ctx, hazards, x, y, z, nx, ny, nz,
                        nx, y + 1, nz,
                        nx, y, nz,
                        nx, ny, nz,
                        3, horizCost + 2.5, out.currentPlacedFallback, out);
            }
            return;
        }

        // Check step-off column hazards at (nx, y, nz) / (nx, y + 1, nz)
        double stepPen = hazards.passThroughPenalty(world, nx, y, nz);
        if (Double.isInfinite(stepPen)) return;

        // 1-block descend (dy = -1)
        int d1y = y - 1;
        if (world.isInYBounds(d1y - 1)) {
            if (world.isPassable(nx, d1y, nz)) {
                if (world.canStandOn(nx, d1y - 1, nz) || (world.isWater(nx, d1y, nz) && !world.isLava(nx, d1y - 1, nz))) {
                    if (!ctx.isNodeBlacklisted(nx, d1y, nz) && !ctx.isEdgeBlacklisted(x, y, z, nx, d1y, nz)) {
                        double pen = hazards.nodePenalty(world, ctx, nx, d1y, nz);
                        if (!Double.isInfinite(pen)) {
                            out.add(nx, d1y, nz, horizCost + 2.0 + pen, EDGE_DESCEND, (byte) 0, 0L, Long.MIN_VALUE, out.currentPlacedFallback);
                        }
                    }
                    return;
                }
            } else if (ctx.allowBreak()
                    && world.canStandOn(nx, d1y - 1, nz)
                    && !ctx.isNodeBlacklisted(nx, d1y, nz)
                    && !ctx.isEdgeBlacklisted(x, y, z, nx, d1y, nz)) {
                // Only (nx, d1y, nz) is solid while (nx, y, nz) and (nx, y+1, nz) are passable
                tryBreakThrough(world, ctx, hazards, x, y, z, nx, d1y, nz,
                        nx, d1y, nz,
                        Integer.MIN_VALUE, 0, 0,
                        Integer.MIN_VALUE, 0, 0,
                        2, horizCost + 2.5, out.currentPlacedFallback, out);
                return;
            } else {
                return;
            }
        }

        // Vertical FallEdge (dy <= -2): scan downward until solid ground or water.
        int maxScan = Math.max(ctx.maxSafeFall(), 32);
        for (int drop = 2; drop <= maxScan; drop++) {
            int ly = y - drop;
            if (!world.isInYBounds(ly - 1) || !world.isLoaded(nx, ly, nz)) {
                if (!world.isLoaded(nx, ly, nz)) out.touchedUnloaded = true;
                break;
            }
            BlockKind at = world.kindAt(nx, ly, nz);
            if (at == BlockKind.LAVA || at == BlockKind.FIRE || at == BlockKind.CACTUS) {
                break;
            }
            if (at == BlockKind.WATER) {
                if (!ctx.isNodeBlacklisted(nx, ly, nz) && !ctx.isEdgeBlacklisted(x, y, z, nx, ly, nz)) {
                    double pen = hazards.nodePenalty(world, ctx, nx, ly, nz);
                    if (!Double.isInfinite(pen)) {
                        double cost = horizCost + drop * 1.6 + pen;
                        out.add(nx, ly, nz, cost, EDGE_FALL, (byte) 1, 0L, Long.MIN_VALUE, out.currentPlacedFallback);
                    }
                }
                break;
            }
            if (!at.isPassable()) {
                break;
            }
            BlockKind below = world.kindAt(nx, ly - 1, nz);
            if (below.canStandOn()) {
                if (drop <= ctx.maxSafeFall()
                        && !ctx.isNodeBlacklisted(nx, ly, nz)
                        && !ctx.isEdgeBlacklisted(x, y, z, nx, ly, nz)) {
                    double pen = hazards.nodePenalty(world, ctx, nx, ly, nz);
                    if (!Double.isInfinite(pen)) {
                        double cost = horizCost + drop * 1.8 + pen;
                        out.add(nx, ly, nz, cost, EDGE_FALL, (byte) 0, 0L, Long.MIN_VALUE, out.currentPlacedFallback);
                    }
                }
                break;
            }
        }
    }

    private void tryDiagonalWalk(
            BlockView world,
            CalculationContext ctx,
            HazardCost hazards,
            int x, int y, int z,
            int nx, int nz,
            double diagCost,
            NeighborBuffer out) {

        if (!world.isLoaded(nx, y, nz)) {
            out.touchedUnloaded = true;
            return;
        }
        if (!world.isInYBounds(y - 1) || !world.isInYBounds(y + 1)) return;
        if (ctx.isNodeBlacklisted(nx, y, nz) || ctx.isEdgeBlacklisted(x, y, z, nx, y, nz)) return;

        if (!world.isPassable(nx, y, nz) || !world.isPassable(nx, y + 1, nz)) return;
        if (!world.canStandOn(nx, y - 1, nz) && !(world.isWater(nx, y, nz) && !world.isLava(nx, y - 1, nz))) {
            return;
        }

        // Both diagonal corner columns must be passable at feet & head and free of hard-blocked hazards.
        if (!world.isPassable(nx, y, z) || !world.isPassable(nx, y + 1, z)) return;
        if (!world.isPassable(x, y, nz) || !world.isPassable(x, y + 1, nz)) return;

        double c1 = hazards.passThroughPenalty(world, nx, y, z);
        if (Double.isInfinite(c1)) return;
        double c2 = hazards.passThroughPenalty(world, x, y, nz);
        if (Double.isInfinite(c2)) return;

        double pen = hazards.nodePenalty(world, ctx, nx, y, nz);
        if (Double.isInfinite(pen)) return;

        double cornerPenalty = (c1 + c2) * 0.25;
        out.add(nx, y, nz, diagCost + pen + cornerPenalty, EDGE_WALK, (byte) 1, 0L, Long.MIN_VALUE, out.currentPlacedFallback);
    }

    private void tryPillar(
            BlockView world,
            CalculationContext ctx,
            HazardCost hazards,
            int x, int y, int z,
            boolean standableHere,
            int placedSoFar,
            NeighborBuffer out) {

        if (!ctx.allowPlace() || placedSoFar >= ctx.expendableBlockCount()) return;
        int ny = y + 1;
        if (!world.isInYBounds(ny + 1) || !world.isInYBounds(y - 1)) return;
        if (ctx.isNodeBlacklisted(x, ny, z) || ctx.isEdgeBlacklisted(x, y, z, x, ny, z)) return;

        if (!standableHere) return;
        if (!world.isReplaceable(x, y, z)) return;
        if (!world.isPassable(x, ny, z) || !world.isPassable(x, ny + 1, z)) return;
        if (ctx.isPlaceProtected(x, y, z, x, y, z)) return;

        double pen = hazards.passThroughPenalty(world, x, ny, z);
        if (Double.isInfinite(pen)) return;

        double cost = 7.5 + ctx.placeCostTicks() + pen;
        out.add(x, ny, z, cost, EDGE_PILLAR, (byte) 0, BlockPos.asLong(x, y, z), Long.MIN_VALUE, placedSoFar + 1);
    }

    private void tryDigDown(
            BlockView world,
            CalculationContext ctx,
            HazardCost hazards,
            int x, int y, int z,
            NeighborBuffer out) {

        if (!ctx.allowBreak()) return;
        int ny = y - 1;
        if (!world.isInYBounds(ny - 1)) return;
        if (ctx.isNodeBlacklisted(x, ny, z) || ctx.isEdgeBlacklisted(x, y, z, x, ny, z)) return;
        if (world.isPassable(x, ny, z)) return;
        if (!world.canStandOn(x, ny - 1, z)) return;

        tryBreakThrough(world, ctx, hazards, x, y, z, x, ny, z,
                x, ny, z,
                Integer.MIN_VALUE, 0, 0,
                Integer.MIN_VALUE, 0, 0,
                2, 4.5, out.currentPlacedFallback, out);
    }

    private void tryParkour(
            BlockView world,
            CalculationContext ctx,
            HazardCost hazards,
            int x, int y, int z,
            double horizCost,
            int placedSoFar,
            NeighborBuffer out) {

        if (!world.isInYBounds(y + 2) || !world.isInYBounds(y - 1)) return;
        // Takeoff requires headroom at y + 2
        if (!world.isPassable(x, y + 2, z)) return;

        for (int[] d : CARDINALS) {
            int dx = d[0];
            int dz = d[1];

            for (int gap = 1; gap <= 3; gap++) {
                int dist = gap + 1;
                int tx = x + dx * dist;
                int tz = z + dz * dist;

                if (!world.isLoaded(tx, y, tz)) {
                    out.touchedUnloaded = true;
                    break;
                }

                // Verify all gap columns are clear across feet/head/jump-arc (y..y+2) and actually a gap
                boolean arcClear = true;
                boolean hasActualGap = false;
                for (int k = 1; k <= gap; k++) {
                    int gx = x + dx * k;
                    int gz = z + dz * k;
                    if (!world.isLoaded(gx, y, gz)) {
                        arcClear = false;
                        out.touchedUnloaded = true;
                        break;
                    }
                    if (!world.isPassable(gx, y, gz)
                            || !world.isPassable(gx, y + 1, gz)
                            || !world.isPassable(gx, y + 2, gz)) {
                        arcClear = false;
                        break;
                    }
                    if (Double.isInfinite(hazards.passThroughPenalty(world, gx, y, gz))) {
                        arcClear = false;
                        break;
                    }
                    if (!world.canStandOn(gx, y - 1, gz)) {
                        hasActualGap = true;
                    }
                }
                if (!arcClear) break; // Further gaps along this ray are also blocked
                if (!hasActualGap) continue;
                // A long jump over a deep drop: a miss means a big fall and climbing back up; bridge instead.
                if (gap >= 2 && gapDeeperThan(world, x, y, z, dx, dz, gap, ctx.maxSafeFall())) break;

                // Landing at same Y (gaps 1..3)
                tryParkourLanding(world, ctx, hazards, x, y, z, tx, y, tz, gap, dist * horizCost, placedSoFar, out);

                // Ascending 1 block on a 1-block parkour gap
                if (gap == 1 && world.isInYBounds(y + 3)) {
                    tryParkourLanding(world, ctx, hazards, x, y, z, tx, y + 1, tz, gap, dist * horizCost + 4.0, placedSoFar, out);
                }
            }
        }
    }

    private void tryParkourLanding(
            BlockView world,
            CalculationContext ctx,
            HazardCost hazards,
            int x, int y, int z,
            int tx, int ty, int tz,
            int gap,
            double baseCost,
            int placedSoFar,
            NeighborBuffer out) {

        if (!world.isInYBounds(ty + 2) || !world.isInYBounds(ty - 1)) return;
        if (ctx.isNodeBlacklisted(tx, ty, tz) || ctx.isEdgeBlacklisted(x, y, z, tx, ty, tz)) return;
        if (!world.isPassable(tx, ty, tz) || !world.isPassable(tx, ty + 1, tz)) return;
        if ((gap >= 2 || ty > y) && !world.isPassable(tx, ty + 2, tz)) return;

        if (world.canStandOn(tx, ty - 1, tz)) {
            double pen = hazards.nodePenalty(world, ctx, tx, ty, tz);
            if (!Double.isInfinite(pen)) {
                double cost = baseCost + ctx.parkourCostPenalty() + gap * 1.5 + pen;
                out.add(tx, ty, tz, cost, EDGE_PARKOUR, (byte) gap, 0L, Long.MIN_VALUE, placedSoFar);
            }
        } else if (ctx.allowParkourPlace()
                && ctx.allowPlace()
                && placedSoFar < ctx.expendableBlockCount()
                && world.isReplaceable(tx, ty - 1, tz)
                && hasAdjacentSolidSupport(world, tx, ty - 1, tz)
                && !ctx.isPlaceProtected(tx, ty - 1, tz, tx, ty, tz)) {
            double pen = hazards.passThroughPenalty(world, tx, ty, tz);
            if (!Double.isInfinite(pen)) {
                double cost = baseCost + ctx.parkourCostPenalty() + ctx.placeCostTicks() + gap * 1.5 + pen;
                out.add(tx, ty, tz, cost, EDGE_PARKOUR, (byte) (gap | 8), BlockPos.asLong(tx, ty - 1, tz), Long.MIN_VALUE, placedSoFar + 1);
            }
        }
    }

    /** True if under any gap column the first standable block is more than {@code maxFall} below {@code y}. */
    private static boolean gapDeeperThan(BlockView world, int x, int y, int z, int dx, int dz, int gap, int maxFall) {
        for (int k = 1; k <= gap; k++) {
            int gx = x + dx * k;
            int gz = z + dz * k;
            boolean shallow = false;
            for (int drop = 1; drop <= maxFall; drop++) {
                if (!world.isInYBounds(y - drop - 1)) break;
                if (world.isWater(gx, y - drop, gz) || world.canStandOn(gx, y - drop - 1, gz)) {
                    shallow = true;
                    break;
                }
                if (!world.isPassable(gx, y - drop, gz)) break;
            }
            if (!shallow) return true;
        }
        return false;
    }

    private static boolean hasAdjacentSolidSupport(BlockView world, int x, int y, int z) {
        return world.hasSolidCollision(x + 1, y, z)
                || world.hasSolidCollision(x - 1, y, z)
                || world.hasSolidCollision(x, y, z + 1)
                || world.hasSolidCollision(x, y, z - 1)
                || world.hasSolidCollision(x, y - 1, z);
    }

    private void tryBreakThrough(
            BlockView world,
            CalculationContext ctx,
            HazardCost hazards,
            int sx, int sy, int sz,
            int tx, int ty, int tz,
            int b1x, int b1y, int b1z,
            int b2x, int b2y, int b2z,
            int b3x, int b3y, int b3z,
            int maxBreakBlocks,
            double baseMoveCost,
            int placedSoFar,
            NeighborBuffer out) {

        if (ctx.isNodeBlacklisted(tx, ty, tz) || ctx.isEdgeBlacklisted(sx, sy, sz, tx, ty, tz)) return;

        int breakCount = 0;
        long aux1 = Long.MIN_VALUE;
        long aux2 = Long.MIN_VALUE;
        long aux3 = Long.MIN_VALUE;
        double breakCost = 0.0;

        int[][] candidates = {
                {b1x, b1y, b1z},
                {b2x, b2y, b2z},
                {b3x, b3y, b3z}
        };

        for (int[] c : candidates) {
            int bx = c[0];
            if (bx == Integer.MIN_VALUE) continue;
            int by = c[1];
            int bz = c[2];
            if (!world.isLoaded(bx, by, bz)) {
                out.touchedUnloaded = true;
                return;
            }
            if (world.isPassable(bx, by, bz)) {
                double passPen = hazards.passThroughPenalty(world, bx, by, bz);
                if (Double.isInfinite(passPen)) return;
                continue;
            }
            if (!world.kindAt(bx, by, bz).isBreakable()) return;
            if (ctx.isBreakProtected(bx, by, bz, sx, sy, sz, world)) return;
            int ticks = world.breakTicks(bx, by, bz, ctx);
            if (ticks < 0) return;

            double adjPen = hazards.adjacentBreakPenalty(world, bx, by, bz);
            if (Double.isInfinite(adjPen)) return;

            breakCount++;
            if (breakCount > maxBreakBlocks) return;

            long packed = BlockPos.asLong(bx, by, bz);
            if (breakCount == 1) aux1 = packed;
            else if (breakCount == 2) aux2 = packed;
            else aux3 = packed;

            breakCost += ticks + adjPen;
        }

        if (breakCount == 0) return;

        double targetPen = hazards.nodePenalty(world, ctx, tx, ty, tz);
        if (Double.isInfinite(targetPen)) return;

        out.add(tx, ty, tz, baseMoveCost + breakCost + targetPen, EDGE_BREAK, (byte) breakCount, aux1, aux2, aux3, placedSoFar);
    }

    // ------------------------------------------------------------------ Reconstruction

    private static PlanResult reconstruct(
            PlanResult.Status status,
            BlockPos start,
            SearchState state,
            int endId,
            int expanded,
            long elapsedMillis) {

        List<MoveEdge> edges = new ArrayList<>();
        List<BlockPos> waypoints = new ArrayList<>();

        int cur = endId;
        while (cur > 0) {
            int parent = state.parentId[cur];
            BlockPos from = BlockPos.of(state.packedPos[parent]);
            BlockPos to = BlockPos.of(state.packedPos[cur]);
            double cost = state.edgeCost[cur];
            byte flag = state.edgeFlag[cur];
            long a1 = state.aux1[cur];
            long a2 = state.aux2[cur];
            long a3 = state.aux3[cur];

            MoveEdge edge = switch (state.edgeType[cur]) {
                case EDGE_WALK -> new MoveEdge.WalkEdge(from, to, cost, flag != 0);
                case EDGE_ASCEND -> new MoveEdge.AscendEdge(from, to, cost);
                case EDGE_DESCEND -> new MoveEdge.DescendEdge(from, to, cost);
                case EDGE_FALL -> new MoveEdge.FallEdge(from, to, cost, Math.max(2, from.getY() - to.getY()), flag != 0);
                case EDGE_BREAK -> {
                    List<BlockPos> blocks;
                    if (a3 != Long.MIN_VALUE) {
                        blocks = List.of(BlockPos.of(a1), BlockPos.of(a2), BlockPos.of(a3));
                    } else if (a2 != Long.MIN_VALUE) {
                        blocks = List.of(BlockPos.of(a1), BlockPos.of(a2));
                    } else {
                        blocks = List.of(BlockPos.of(a1));
                    }
                    yield new MoveEdge.BreakThroughEdge(from, to, cost, blocks);
                }
                case EDGE_BRIDGE -> new MoveEdge.BridgeEdge(from, to, cost, BlockPos.of(a1));
                case EDGE_PILLAR -> new MoveEdge.PillarEdge(from, to, cost, BlockPos.of(a1));
                case EDGE_PARKOUR -> new MoveEdge.ParkourEdge(from, to, cost, flag & 7, (flag & 8) != 0);
                default -> throw new IllegalStateException("Unknown edgeType: " + state.edgeType[cur]);
            };
            edges.add(edge);
            waypoints.add(to);
            cur = parent;
        }
        waypoints.add(start);

        Collections.reverse(edges);
        Collections.reverse(waypoints);

        return new PlanResult(status, start, edges, waypoints, state.gScore[endId], expanded, elapsedMillis);
    }

    private static PlanResult cancelledResult(BlockPos start) {
        return new PlanResult(PlanResult.Status.CANCELLED, start, List.of(), List.of(start), 0.0, 0, 0L);
    }

    // ------------------------------------------------------------------ Mining Target Scanner

    private static List<BlockPos> findMineTargets(
            BlockView world,
            CalculationContext ctx,
            BlockPos start,
            BlockFilter filter,
            Area box,
            int defaultRadius,
            int maxCandidates,
            Set<BlockPos> excludedTargets) {

        int minX = box != null ? box.minX() : start.getX() - defaultRadius;
        int maxX = box != null ? box.maxX() : start.getX() + defaultRadius;
        int minY = Math.max(world.minY(), box != null ? box.minY() : start.getY() - 24);
        int maxY = Math.min(world.maxY() - 1, box != null ? box.maxY() : start.getY() + 24);
        int minZ = box != null ? box.minZ() : start.getZ() - defaultRadius;
        int maxZ = box != null ? box.maxZ() : start.getZ() + defaultRadius;

        if (box != null && box.volume() > 500_000L) {
            minX = Math.max(minX, start.getX() - defaultRadius);
            maxX = Math.min(maxX, start.getX() + defaultRadius);
            minY = Math.max(minY, start.getY() - 32);
            maxY = Math.min(maxY, start.getY() + 32);
            minZ = Math.max(minZ, start.getZ() - defaultRadius);
            maxZ = Math.min(maxZ, start.getZ() + defaultRadius);
        }

        boolean hasExclusions = excludedTargets != null && !excludedTargets.isEmpty();
        List<BlockPos> found = new ArrayList<>();
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    if (!world.isLoaded(x, y, z)) continue;
                    if (!world.matchesFilter(x, y, z, filter)) continue;
                    if (ctx.isBreakProtected(x, y, z, x, y, z, world)) continue;
                    if (world.breakTicks(x, y, z, ctx) < 0) continue;
                    BlockPos candidate = new BlockPos(x, y, z);
                    if (hasExclusions && excludedTargets.contains(candidate)) continue;
                    found.add(candidate);
                }
            }
        }
        found.sort(Comparator.comparingDouble(p -> p.distSqr(start)));
        if (found.size() > maxCandidates) {
            return found.subList(0, maxCandidates);
        }
        return found;
    }

    // ------------------------------------------------------------------ Goal Adapters & Raycast

    private sealed interface GoalAdapter {
        boolean isGoal(int x, int y, int z);
        double heuristic(int x, int y, int z, CalculationContext ctx);

        static GoalAdapter forGoal(BlockView world, GoalSpec goal) {
            return switch (goal) {
                case GoalSpec.Exact g -> new ExactGoal(g.pos());
                case GoalSpec.Near g -> new NearGoal(g.pos(), g.range());
                case GoalSpec.XZ g -> new XZGoal(g.x(), g.z());
                case GoalSpec.LineOfSight g -> new LineOfSightGoal(world, g.target(), g.maxRange());
                case GoalSpec.RunAway g -> new RunAwayGoal(g.from(), g.distance());
            };
        }
    }

    private record ExactGoal(BlockPos pos) implements GoalAdapter {
        @Override
        public boolean isGoal(int x, int y, int z) {
            return x == pos.getX() && y == pos.getY() && z == pos.getZ();
        }

        @Override
        public double heuristic(int x, int y, int z, CalculationContext ctx) {
            return octile3D(x, y, z, pos.getX(), pos.getY(), pos.getZ(), ctx.horizontalTicksPerBlock());
        }
    }

    private record NearGoal(BlockPos pos, int range) implements GoalAdapter {
        @Override
        public boolean isGoal(int x, int y, int z) {
            int dx = x - pos.getX();
            int dy = y - pos.getY();
            int dz = z - pos.getZ();
            return (long) dx * dx + (long) dy * dy + (long) dz * dz <= (long) range * range;
        }

        @Override
        public double heuristic(int x, int y, int z, CalculationContext ctx) {
            double dist = octile3D(x, y, z, pos.getX(), pos.getY(), pos.getZ(), ctx.horizontalTicksPerBlock());
            return Math.max(0.0, dist - range * ctx.horizontalTicksPerBlock());
        }
    }

    private record XZGoal(int gx, int gz) implements GoalAdapter {
        @Override
        public boolean isGoal(int x, int y, int z) {
            return x == gx && z == gz;
        }

        @Override
        public double heuristic(int x, int y, int z, CalculationContext ctx) {
            int dx = Math.abs(x - gx);
            int dz = Math.abs(z - gz);
            int min = Math.min(dx, dz);
            int max = Math.max(dx, dz);
            return (min * SQRT2 + (max - min)) * ctx.horizontalTicksPerBlock();
        }
    }

    private record LineOfSightGoal(BlockView world, BlockPos target, double maxRange) implements GoalAdapter {
        @Override
        public boolean isGoal(int x, int y, int z) {
            double dx = (target.getX() + 0.5) - (x + 0.5);
            double dy = (target.getY() + 0.5) - (y + 1.62);
            double dz = (target.getZ() + 0.5) - (z + 0.5);
            if (dx * dx + dy * dy + dz * dz > maxRange * maxRange) {
                return false;
            }
            return hasLineOfSight(world, x, y, z, target);
        }

        @Override
        public double heuristic(int x, int y, int z, CalculationContext ctx) {
            double dx = target.getX() - x;
            double dy = target.getY() - y;
            double dz = target.getZ() - z;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            return Math.max(0.0, dist - maxRange) * ctx.horizontalTicksPerBlock();
        }
    }

    private record RunAwayGoal(BlockPos from, double distance) implements GoalAdapter {
        @Override
        public boolean isGoal(int x, int y, int z) {
            double dx = x - from.getX();
            double dz = z - from.getZ();
            return dx * dx + dz * dz >= distance * distance;
        }

        @Override
        public double heuristic(int x, int y, int z, CalculationContext ctx) {
            double dx = x - from.getX();
            double dz = z - from.getZ();
            double dist = Math.hypot(dx, dz);
            return Math.max(0.0, distance - dist) * ctx.horizontalTicksPerBlock();
        }
    }

    private record MineGoal(BlockView world, List<BlockPos> targets, double maxRange) implements GoalAdapter {
        @Override
        public boolean isGoal(int x, int y, int z) {
            double maxSq = maxRange * maxRange;
            for (BlockPos t : targets) {
                double dx = (t.getX() + 0.5) - (x + 0.5);
                double dy = (t.getY() + 0.5) - (y + 1.62);
                double dz = (t.getZ() + 0.5) - (z + 0.5);
                if (dx * dx + dy * dy + dz * dz <= maxSq && hasLineOfSight(world, x, y, z, t)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public double heuristic(int x, int y, int z, CalculationContext ctx) {
            double best = Double.MAX_VALUE;
            double hCost = ctx.horizontalTicksPerBlock();
            for (BlockPos t : targets) {
                double d = octile3D(x, y, z, t.getX(), t.getY(), t.getZ(), hCost);
                if (d < best) best = d;
            }
            return Math.max(0.0, best - 2.0 * hCost);
        }
    }

    private static double octile3D(int x, int y, int z, int gx, int gy, int gz, double horizCost) {
        int dx = Math.abs(x - gx);
        int dz = Math.abs(z - gz);
        int min = Math.min(dx, dz);
        int max = Math.max(dx, dz);
        double horiz = (min * SQRT2 + (max - min)) * horizCost;
        int dy = gy - y;
        double vert = dy > 0 ? dy * 4.0 : (-dy) * 2.0;
        return horiz + vert;
    }

    /** 3D voxel raycast from player eye at {@code (x, y, z)} to {@code target} block center. */
    public static boolean hasLineOfSight(BlockView world, int x, int y, int z, BlockPos target) {
        double sx = x + 0.5;
        double sy = y + 1.62;
        double sz = z + 0.5;
        double ex = target.getX() + 0.5;
        double ey = target.getY() + 0.5;
        double ez = target.getZ() + 0.5;

        double dx = ex - sx;
        double dy = ey - sy;
        double dz = ez - sz;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6) return true;

        int steps = Math.max(1, (int) Math.ceil(len * 4.0));
        double stepX = dx / steps;
        double stepY = dy / steps;
        double stepZ = dz / steps;

        int tx = target.getX();
        int ty = target.getY();
        int tz = target.getZ();

        int lastVx = Integer.MIN_VALUE;
        int lastVy = Integer.MIN_VALUE;
        int lastVz = Integer.MIN_VALUE;

        for (int i = 1; i < steps; i++) {
            int vx = (int) Math.floor(sx + stepX * i);
            int vy = (int) Math.floor(sy + stepY * i);
            int vz = (int) Math.floor(sz + stepZ * i);
            if (vx == lastVx && vy == lastVy && vz == lastVz) continue;
            lastVx = vx;
            lastVy = vy;
            lastVz = vz;

            if (vx == tx && vy == ty && vz == tz) return true;
            if (vx == x && (vy == y || vy == y + 1) && vz == z) continue;
            if (!world.isLoaded(vx, vy, vz)) return false;
            if (world.hasSolidCollision(vx, vy, vz)) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ Cooperative Cancellation

    private static final class CancellableFuture extends CompletableFuture<PlanResult> {
        private final AtomicBoolean cancelled;

        CancellableFuture(AtomicBoolean cancelled) {
            this.cancelled = cancelled;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled.set(true);
            return super.cancel(mayInterruptIfRunning);
        }
    }

    // ------------------------------------------------------------------ Primitive A* Buffers

    private static final class NeighborBuffer {
        private static final int CAP = 48;
        final int[] tx = new int[CAP];
        final int[] ty = new int[CAP];
        final int[] tz = new int[CAP];
        final double[] cost = new double[CAP];
        final byte[] edgeType = new byte[CAP];
        final byte[] edgeFlag = new byte[CAP];
        final long[] aux1 = new long[CAP];
        final long[] aux2 = new long[CAP];
        final long[] aux3 = new long[CAP];
        final int[] nextPlaced = new int[CAP];
        int count;
        int currentPlacedFallback;
        boolean touchedUnloaded;

        void clear(int placedSoFar) {
            count = 0;
            currentPlacedFallback = placedSoFar;
            touchedUnloaded = false;
        }

        void add(int x, int y, int z, double c, byte type, byte flag, long a1, long a2, int placed) {
            add(x, y, z, c, type, flag, a1, a2, Long.MIN_VALUE, placed);
        }

        void add(int x, int y, int z, double c, byte type, byte flag, long a1, long a2, long a3, int placed) {
            if (count >= CAP) return;
            tx[count] = x;
            ty[count] = y;
            tz[count] = z;
            cost[count] = c;
            edgeType[count] = type;
            edgeFlag[count] = flag;
            aux1[count] = a1;
            aux2[count] = a2;
            aux3[count] = a3;
            nextPlaced[count] = placed;
            count++;
        }
    }

    private static boolean isPlacedSupportEdge(byte edgeType, byte edgeFlag) {
        return edgeType == EDGE_BRIDGE
                || edgeType == EDGE_PILLAR
                || (edgeType == EDGE_PARKOUR && (edgeFlag & 8) != 0);
    }

    private static final class SearchState {
        long[] packedPos;
        double[] gScore;
        double[] hScore;
        double[] fScore;
        int[] parentId;
        byte[] edgeType;
        byte[] edgeFlag;
        long[] aux1;
        long[] aux2;
        long[] aux3;
        double[] edgeCost;
        int[] placedCount;
        boolean[] closed;
        int size;

        final PosToNodeMap posMap;
        final NodeMinHeap heap;

        SearchState(int initialCap) {
            this.packedPos = new long[initialCap];
            this.gScore = new double[initialCap];
            this.hScore = new double[initialCap];
            this.fScore = new double[initialCap];
            this.parentId = new int[initialCap];
            this.edgeType = new byte[initialCap];
            this.edgeFlag = new byte[initialCap];
            this.aux1 = new long[initialCap];
            this.aux2 = new long[initialCap];
            this.aux3 = new long[initialCap];
            this.edgeCost = new double[initialCap];
            this.placedCount = new int[initialCap];
            this.closed = new boolean[initialCap];
            this.posMap = new PosToNodeMap(initialCap * 2);
            this.heap = new NodeMinHeap(initialCap, this);
        }

        int addNode(
                int x, int y, int z,
                double g, double h,
                int parent,
                byte type, byte flag,
                long a1, long a2, long a3,
                double eCost,
                int placed) {
            int id = size++;
            if (id >= packedPos.length) {
                grow();
            }
            long packed = BlockPos.asLong(x, y, z);
            packedPos[id] = packed;
            gScore[id] = g;
            hScore[id] = h;
            fScore[id] = g + HEURISTIC_WEIGHT * h;
            parentId[id] = parent;
            edgeType[id] = type;
            edgeFlag[id] = flag;
            aux1[id] = a1;
            aux2[id] = a2;
            aux3[id] = a3;
            edgeCost[id] = eCost;
            placedCount[id] = placed;
            closed[id] = false;
            posMap.put(packed, id);
            return id;
        }

        private void grow() {
            int n = packedPos.length << 1;
            packedPos = Arrays.copyOf(packedPos, n);
            gScore = Arrays.copyOf(gScore, n);
            hScore = Arrays.copyOf(hScore, n);
            fScore = Arrays.copyOf(fScore, n);
            parentId = Arrays.copyOf(parentId, n);
            edgeType = Arrays.copyOf(edgeType, n);
            edgeFlag = Arrays.copyOf(edgeFlag, n);
            aux1 = Arrays.copyOf(aux1, n);
            aux2 = Arrays.copyOf(aux2, n);
            aux3 = Arrays.copyOf(aux3, n);
            edgeCost = Arrays.copyOf(edgeCost, n);
            placedCount = Arrays.copyOf(placedCount, n);
            closed = Arrays.copyOf(closed, n);
            heap.ensureNodeCapacity(n);
        }
    }

    private static final class PosToNodeMap {
        private long[] keys;
        private int[] values;
        private boolean[] used;
        private int mask;
        private int size;
        private int threshold;

        PosToNodeMap(int initialCap) {
            int cap = Integer.highestOneBit(Math.max(16, initialCap) - 1) << 1;
            keys = new long[cap];
            values = new int[cap];
            used = new boolean[cap];
            mask = cap - 1;
            threshold = (int) (cap * 0.7f);
        }

        int get(long key) {
            int idx = mix(key) & mask;
            while (used[idx]) {
                if (keys[idx] == key) return values[idx];
                idx = (idx + 1) & mask;
            }
            return -1;
        }

        void put(long key, int val) {
            if (size >= threshold) rehash();
            int idx = mix(key) & mask;
            while (used[idx]) {
                if (keys[idx] == key) {
                    values[idx] = val;
                    return;
                }
                idx = (idx + 1) & mask;
            }
            keys[idx] = key;
            values[idx] = val;
            used[idx] = true;
            size++;
        }

        private void rehash() {
            long[] ok = keys;
            int[] ov = values;
            boolean[] ou = used;
            int cap = ok.length << 1;
            keys = new long[cap];
            values = new int[cap];
            used = new boolean[cap];
            mask = cap - 1;
            threshold = (int) (cap * 0.7f);
            for (int i = 0; i < ok.length; i++) {
                if (!ou[i]) continue;
                long k = ok[i];
                int idx = mix(k) & mask;
                while (used[idx]) {
                    idx = (idx + 1) & mask;
                }
                keys[idx] = k;
                values[idx] = ov[i];
                used[idx] = true;
            }
        }

        private static int mix(long x) {
            x ^= (x >>> 33);
            x *= 0xff51afd7ed558ccdL;
            x ^= (x >>> 33);
            return (int) x;
        }
    }

    private static final class NodeMinHeap {
        private final SearchState state;
        private int[] heap;
        private int[] heapPos;
        private int size;

        NodeMinHeap(int cap, SearchState state) {
            this.state = state;
            this.heap = new int[cap];
            this.heapPos = new int[cap];
            Arrays.fill(this.heapPos, -1);
        }

        void ensureNodeCapacity(int n) {
            if (n > heapPos.length) {
                int old = heapPos.length;
                heapPos = Arrays.copyOf(heapPos, n);
                Arrays.fill(heapPos, old, n, -1);
            }
        }

        boolean isEmpty() {
            return size == 0;
        }

        void push(int nodeId) {
            if (size >= heap.length) {
                heap = Arrays.copyOf(heap, heap.length << 1);
            }
            int idx = size++;
            heap[idx] = nodeId;
            heapPos[nodeId] = idx;
            siftUp(idx);
        }

        int popMin() {
            int min = heap[0];
            heapPos[min] = -1;
            size--;
            if (size > 0) {
                int last = heap[size];
                heap[0] = last;
                heapPos[last] = 0;
                siftDown(0);
            }
            return min;
        }

        void decreaseKey(int nodeId) {
            int idx = heapPos[nodeId];
            if (idx >= 0) {
                siftUp(idx);
            } else {
                push(nodeId);
            }
        }

        private void siftUp(int idx) {
            int node = heap[idx];
            while (idx > 0) {
                int parentIdx = (idx - 1) >>> 1;
                int parentNode = heap[parentIdx];
                if (!less(node, parentNode)) break;
                heap[idx] = parentNode;
                heapPos[parentNode] = idx;
                idx = parentIdx;
            }
            heap[idx] = node;
            heapPos[node] = idx;
        }

        private void siftDown(int idx) {
            int node = heap[idx];
            int half = size >>> 1;
            while (idx < half) {
                int childIdx = (idx << 1) + 1;
                int childNode = heap[childIdx];
                int rightIdx = childIdx + 1;
                if (rightIdx < size && less(heap[rightIdx], childNode)) {
                    childIdx = rightIdx;
                    childNode = heap[rightIdx];
                }
                if (!less(childNode, node)) break;
                heap[idx] = childNode;
                heapPos[childNode] = idx;
                idx = childIdx;
            }
            heap[idx] = node;
            heapPos[node] = idx;
        }

        private boolean less(int a, int b) {
            double fa = state.fScore[a];
            double fb = state.fScore[b];
            if (fa != fb) return fa < fb;
            return state.hScore[a] < state.hScore[b];
        }
    }
}
