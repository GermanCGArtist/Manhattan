package dev.midtown.plan;

import dev.midtown.api.Area;
import dev.midtown.api.BlockFilter;
import dev.midtown.api.GoalSpec;
import dev.midtown.plan.EdgeGenerator.NeighborBuffer;
import dev.midtown.plan.GoalAdapters.GoalAdapter;
import dev.midtown.plan.GoalAdapters.MineGoal;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
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
 * Coordinates {@link AStarStorage}, {@link EdgeGenerator}, and {@link GoalAdapters},
 * enforcing {@link HazardCost} penalties/hard-blocks, {@code RegionRules} safe-area masks,
 * and configurable time budgets (20-50ms default) with best-so-far partial pathing.
 */
public final class MidtownPlanner {

    /** Nodes searched before the time budget can end a search (see {@link #runAStar}). */
    static final int MIN_EXPANDED = 4096;

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
                PlanResult result = runAStar(world, ctx, start, GoalAdapters.forGoal(world, goal), timeBudgetMs, cancelled);
                if (isColdPartial(result) && !cancelled.get()) {
                    result = better(result, runAStar(world, ctx, start, GoalAdapters.forGoal(world, goal), timeBudgetMs, cancelled));
                }
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
        return runAStar(world, ctx, start, GoalAdapters.forGoal(world, goal), timeBudgetMs, null);
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
                if (isColdPartial(result) && !cancelled.get()) {
                    result = better(result, planMineSync(world, ctx, start, filter, box, timeBudgetMs, excludedSnapshot, cancelled));
                }
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

    /** 3D voxel raycast from player eye at {@code (x, y, z)} to {@code target} block center. */
    public static boolean hasLineOfSight(BlockView world, int x, int y, int z, BlockPos target) {
        return GoalAdapters.hasLineOfSight(world, x, y, z, target);
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
        List<BlockPos> targets = GoalAdapters.findMineTargets(world, ctx, start, filter, box, 48, 32, excludedTargets);
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
        // Cold JVM warmup guard: budget only stops search after MIN_EXPANDED nodes, up to hard limit.
        long hardDeadlineNanos = startNanos + Math.max(budgetNanos * 8, 250_000_000L);

        int sx = start.getX();
        int sy = start.getY();
        int sz = start.getZ();

        if (goal.isGoal(sx, sy, sz)) {
            long elapsed = (System.nanoTime() - startNanos) / 1_000_000L;
            return new PlanResult(PlanResult.Status.COMPLETE, start, List.of(), List.of(start), 0.0, 0, elapsed);
        }

        AStarStorage state = new AStarStorage(1024);
        int startId = state.addNode(sx, sy, sz, 0.0, goal.heuristic(sx, sy, sz, ctx), -1, (byte) 0, (byte) 0, 0L, Long.MIN_VALUE, Long.MIN_VALUE, 0.0, 0);
        state.push(startId);

        int bestNodeId = startId;
        double bestScore = state.hScore(startId);
        int bestProgressId = startId;
        double bestProgressH = state.hScore(startId);
        double bestProgressG = 0.0;
        int bestAnyId = startId;
        double bestAnyScore = Double.POSITIVE_INFINITY;

        int expanded = 0;
        int maxNodes = ctx.maxNodesExpanded();
        boolean timedOut = false;

        NeighborBuffer nb = new NeighborBuffer();

        while (!state.isHeapEmpty()) {
            if (cancelled != null && cancelled.get()) {
                return cancelledResult(start);
            }
            if (expanded >= 128 && (expanded & 63) == 0
                    && (expanded >= MIN_EXPANDED ? System.nanoTime() >= deadlineNanos : System.nanoTime() >= hardDeadlineNanos)) {
                timedOut = true;
                break;
            }
            if (expanded >= maxNodes) {
                timedOut = true;
                break;
            }

            int curId = state.popMin();
            if (state.isClosed(curId)) continue;
            state.markClosed(curId);
            expanded++;

            long curPacked = state.packedPos(curId);
            int cx = BlockPos.getX(curPacked);
            int cy = BlockPos.getY(curPacked);
            int cz = BlockPos.getZ(curPacked);
            double curG = state.gScore(curId);
            int curPlaced = state.placedCount(curId);

            if (goal.isGoal(cx, cy, cz)) {
                long elapsed = (System.nanoTime() - startNanos) / 1_000_000L;
                return reconstruct(PlanResult.Status.COMPLETE, start, state, curId, expanded, elapsed);
            }

            double curH = state.hScore(curId);
            if (curId != startId) {
                double curScore = curH + 0.15 * curG;
                if (curScore < bestScore - 1e-6) {
                    bestScore = curScore;
                    bestNodeId = curId;
                }
                if (curScore < bestAnyScore - 1e-6) {
                    bestAnyScore = curScore;
                    bestAnyId = curId;
                }
                if (curH < bestProgressH - 1e-6 || (Math.abs(curH - bestProgressH) <= 1e-6 && curG < bestProgressG)) {
                    bestProgressH = curH;
                    bestProgressG = curG;
                    bestProgressId = curId;
                }
            }

            nb.clear(curPlaced);
            boolean stoodOnPlaced = EdgeGenerator.isPlacedSupportEdge(state.edgeType(curId), state.edgeFlag(curId));
            EdgeGenerator.generateNeighbors(world, ctx, cx, cy, cz, curPlaced, stoodOnPlaced, nb);

            for (int i = 0; i < nb.count; i++) {
                int nx = nb.tx[i];
                int ny = nb.ty[i];
                int nz = nb.tz[i];
                double edgeCost = nb.cost[i];
                double tentativeG = curG + edgeCost;

                long nPacked = BlockPos.asLong(nx, ny, nz);
                int existingId = state.getNodeId(nPacked);
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
                    state.push(nextId);
                } else if (!state.isClosed(existingId) && tentativeG < state.gScore(existingId) - 1e-6) {
                    state.updateNode(
                            existingId,
                            tentativeG,
                            curId,
                            nb.edgeType[i],
                            nb.edgeFlag[i],
                            nb.aux1[i],
                            nb.aux2[i],
                            nb.aux3[i],
                            edgeCost,
                            nb.nextPlaced[i]);
                }
            }
        }

        long elapsed = (System.nanoTime() - startNanos) / 1_000_000L;
        int chosenBest = bestNodeId != startId
                ? bestNodeId
                : (bestProgressId != startId ? bestProgressId : bestAnyId);
        if (timedOut) {
            return reconstruct(PlanResult.Status.PARTIAL, start, state, chosenBest, expanded, elapsed);
        }
        if (chosenBest != startId && touchesUnloadedTowardGoal(world, ctx, state.packedPos(chosenBest), goal)) {
            return reconstruct(PlanResult.Status.PARTIAL, start, state, chosenBest, expanded, elapsed);
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
        for (int[] d : EdgeGenerator.CARDINALS) {
            int nx = bx + d[0];
            int nz = bz + d[1];
            if (!world.isLoaded(nx, by, nz) && goal.heuristic(nx, by, nz, ctx) < bestH - 1e-6) {
                return true;
            }
        }
        return false;
    }

    private static PlanResult reconstruct(
            PlanResult.Status status,
            BlockPos start,
            AStarStorage state,
            int endId,
            int expanded,
            long elapsedMillis) {

        List<MoveEdge> edges = new ArrayList<>();
        List<BlockPos> waypoints = new ArrayList<>();

        int cur = endId;
        while (cur > 0) {
            int parent = state.parentId(cur);
            BlockPos from = BlockPos.of(state.packedPos(parent));
            BlockPos to = BlockPos.of(state.packedPos(cur));
            double cost = state.edgeCost(cur);
            byte flag = state.edgeFlag(cur);
            long a1 = state.aux1(cur);
            long a2 = state.aux2(cur);
            long a3 = state.aux3(cur);

            MoveEdge edge = switch (state.edgeType(cur)) {
                case EdgeGenerator.EDGE_WALK -> new MoveEdge.WalkEdge(from, to, cost, flag != 0);
                case EdgeGenerator.EDGE_ASCEND -> new MoveEdge.AscendEdge(from, to, cost);
                case EdgeGenerator.EDGE_DESCEND -> new MoveEdge.DescendEdge(from, to, cost);
                case EdgeGenerator.EDGE_FALL -> new MoveEdge.FallEdge(from, to, cost, Math.max(2, from.getY() - to.getY()), flag != 0);
                case EdgeGenerator.EDGE_BREAK -> {
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
                case EdgeGenerator.EDGE_BRIDGE -> new MoveEdge.BridgeEdge(from, to, cost, BlockPos.of(a1));
                case EdgeGenerator.EDGE_PILLAR -> new MoveEdge.PillarEdge(from, to, cost, BlockPos.of(a1));
                case EdgeGenerator.EDGE_PARKOUR -> new MoveEdge.ParkourEdge(from, to, cost, flag & 7, (flag & 8) != 0);
                default -> throw new IllegalStateException("Unknown edgeType: " + state.edgeType(cur));
            };
            edges.add(edge);
            waypoints.add(to);
            cur = parent;
        }
        waypoints.add(start);

        Collections.reverse(edges);
        Collections.reverse(waypoints);

        return new PlanResult(status, start, edges, waypoints, state.gScore(endId), expanded, elapsedMillis);
    }

    /**
     * A partial result from a search that stopped after only a few hundred nodes: the first plan
     * after joining runs on cold (not yet JIT-compiled) code and its partial path can commit to a
     * bad route. The async planners search once more; the second run is warm.
     */
    private static boolean isColdPartial(PlanResult r) {
        return r != null && r.status() == PlanResult.Status.PARTIAL && r.nodesExpanded() < MIN_EXPANDED;
    }

    private static PlanResult better(PlanResult first, PlanResult second) {
        if (second == null || !second.hasPath()) return first;
        if (second.status() == PlanResult.Status.COMPLETE || second.nodesExpanded() > first.nodesExpanded()) return second;
        return first;
    }

    private static PlanResult cancelledResult(BlockPos start) {
        return new PlanResult(PlanResult.Status.CANCELLED, start, List.of(), List.of(start), 0.0, 0, 0L);
    }

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
}
