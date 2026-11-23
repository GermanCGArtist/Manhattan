package dev.midtown.exec;

import dev.midtown.api.Area;
import dev.midtown.api.BlockFilter;
import dev.midtown.api.EngineSelector;
import dev.midtown.api.EngineStatus;
import dev.midtown.api.GoalSpec;
import dev.midtown.api.PathEngine;
import dev.midtown.exec.MidtownCoordinator.ResumeOutcome;
import dev.midtown.exec.MidtownCoordinator.TickAction;
import dev.midtown.plan.BlockKind;
import dev.midtown.plan.CalculationContext;
import dev.midtown.plan.DeathMemory;
import dev.midtown.plan.MidtownPlanner;
import dev.midtown.plan.MoveEdge;
import dev.midtown.plan.PlanResult;
import dev.midtown.plan.WorldSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * {@link PathEngine} implementation for Midtown.
 * <p>
 * Connects {@link MidtownCoordinator} (synchronous interrupt stack, stuck/timeout/blacklist state machine),
 * {@link MidtownPlanner} (async 3D A* over {@link WorldSnapshot} + {@link CalculationContext}), and
 * {@link MidtownExecutor} (client-tick movement and rotation controller).
 */
public final class MidtownEngine implements PathEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(MidtownEngine.class);
    /** {@code -Dmanhattan.midtownTrace=true}: one log line per moving tick (node, player, keys) for debugging. */
    private static final boolean TRACE = Boolean.getBoolean("manhattan.midtownTrace");

    private static final DeathMemory DEATH_MEMORY = new DeathMemory();
    private static volatile Path deathStorageDir;
    private static volatile Supplier<String> deathWorldSupplier;
    private static String loadedDeathWorld;

    private final MidtownCoordinator coordinator;
    private final MidtownExecutor executor;
    private final MidtownPlanner aStarPlanner = new MidtownPlanner();
    private final MineController mineController = new MineController();
    private final Map<String, List<Area>> protectedAreas = new ConcurrentHashMap<>();
    private final Set<Block> disallowedBreakBlocks = Collections.synchronizedSet(new LinkedHashSet<>());

    private volatile boolean useCustomPlanner;
    private final AtomicLong planGeneration = new AtomicLong();
    private volatile long activePlanGen;
    private volatile long inFlightPlanGen;
    private volatile CompletableFuture<PlanResult> inFlightPlan;
    private volatile GoalSpec pendingGoal;
    private volatile BlockPos pendingStart;
    private volatile boolean pendingIsMine;
    private volatile boolean pendingIsReplan;
    private volatile BlockFilter pendingMineFilter;
    private volatile Area pendingMineBox;
    private int lookaheadTickCounter;

    private volatile boolean allowBreak = true;
    private volatile boolean allowPlace = true;
    // Off by default: a landing block 2-4 blocks away is placed from the takeoff, which needs a free
    // face in reach; bridging the gap is slower but never jumps onto air. setting allowParkourPlace.
    private volatile boolean allowParkourPlace = false;
    private volatile boolean itemSaver = false;
    private volatile int itemSaverThreshold = 0;
    private volatile double blockBreakPenalty = CalculationContext.DEFAULT_BLOCK_BREAK_PENALTY;
    private volatile double placeCostTicks = CalculationContext.DEFAULT_PLACE_COST_TICKS;
    private volatile EngineStatus lastLoggedStatus = EngineStatus.IDLE;

    public MidtownEngine() {
        this.coordinator = new MidtownCoordinator(new EdgeTracker(), new Planner.DirectStepPlanner());
        this.coordinator.setAsyncReplanMode(true);
        this.coordinator.setBlockSolidCheck(this::isWorldBlockSolid);
        this.coordinator.setBlockPresentCheck(this::isWorldBlockPresent);
        this.executor = new MidtownExecutor();
        this.useCustomPlanner = false;
    }

    public MidtownEngine(Planner planner) {
        this.coordinator = new MidtownCoordinator(new EdgeTracker(), planner);
        this.coordinator.setBlockSolidCheck(this::isWorldBlockSolid);
        this.coordinator.setBlockPresentCheck(this::isWorldBlockPresent);
        this.executor = new MidtownExecutor();
        this.useCustomPlanner = true;
    }

    public MidtownEngine(MidtownCoordinator coordinator, MidtownExecutor executor) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.coordinator.setBlockSolidCheck(this::isWorldBlockSolid);
        this.coordinator.setBlockPresentCheck(this::isWorldBlockPresent);
        this.executor = Objects.requireNonNull(executor, "executor");
        this.useCustomPlanner = true;
    }

    private boolean isWorldBlockSolid(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;
        var state = mc.level.getBlockState(pos);
        return !state.canBeReplaced() && state.getFluidState().isEmpty();
    }

    private boolean isWorldBlockPresent(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;
        var state = mc.level.getBlockState(pos);
        return !state.isAir() && state.getFluidState().isEmpty();
    }

    public void setPlanner(Planner planner) {
        this.useCustomPlanner = true;
        coordinator.setAsyncReplanMode(false);
        coordinator.setPlanner(planner);
    }

    public MidtownCoordinator coordinator() {
        return coordinator;
    }

    public MidtownExecutor executor() {
        return executor;
    }

    public MidtownPlanner aStarPlanner() {
        return aStarPlanner;
    }

    public MineController mineController() {
        return mineController;
    }

    @Override
    public String name() {
        return EngineSelector.ENGINE_MIDTOWN;
    }

    @Override
    public void goTo(GoalSpec goal) {
        Objects.requireNonNull(goal, "goal");
        clearActiveMine();
        cancelInFlightPlan();
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            coordinator.cancel();
            checkStatusTransition();
            return;
        }
        executor.releaseAllInputs(mc);
        BlockPos start = groundedStart(mc, player, player.blockPosition());
        LOGGER.info("[MidtownEngine] goTo: start={} goal={}", start.toShortString(), goal);

        if (useCustomPlanner || mc.level == null) {
            coordinator.startGoal(goal, start);
            checkStatusTransition();
            return;
        }

        WorldSnapshot snap = WorldSnapshot.captureForGoal(mc.level, start, goal);
        CalculationContext ctx = buildContext(player, goal);
        long gen = planGeneration.incrementAndGet();
        this.activePlanGen = gen;
        this.inFlightPlanGen = gen;
        this.pendingGoal = goal;
        this.pendingStart = start;
        this.pendingIsMine = false;
        this.pendingIsReplan = false;
        this.pendingMineFilter = null;
        this.pendingMineBox = null;
        this.lookaheadTickCounter = 0;
        coordinator.markPlanning(goal);
        checkStatusTransition();
        this.inFlightPlan = aStarPlanner.planAsync(snap, ctx, start, goal);
    }

    @Override
    public void mine(BlockFilter filter, Area box) {
        Objects.requireNonNull(filter, "filter");
        cancelInFlightPlan();
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            clearActiveMine();
            coordinator.cancel();
            checkStatusTransition();
            return;
        }
        executor.releaseAllInputs(mc);
        mineController.beginMine(filter, box);
        startNextMineTarget(mc, player, filter, box);
        checkStatusTransition();
    }

    @Override
    public void pause() {
        cancelInFlightPlan();
        coordinator.pause();
        executor.releaseAllInputs(Minecraft.getInstance());
    }

    @Override
    public void resume() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            executor.releaseAllInputs(mc);
            return;
        }
        ResumeOutcome outcome = coordinator.resume(player.getX(), player.getY(), player.getZ());
        if (outcome == ResumeOutcome.NEEDS_REPLAN && !useCustomPlanner && mc.level != null) {
            GoalSpec goal = coordinator.activeGoal();
            if (goal != null) {
                executor.releaseAllInputs(mc);
                BlockPos start = groundedStart(mc, player, player.blockPosition());
                launchAsyncReplan(mc, player, goal, start, "resume drift");
            }
        }
        checkStatusTransition();
    }

    @Override
    public void cancel() {
        LOGGER.info("[MidtownEngine] cancel: status={}", coordinator.status());
        clearActiveMine();
        cancelInFlightPlan();
        coordinator.cancel();
        executor.releaseAllInputs(Minecraft.getInstance());
        checkStatusTransition();
    }

    @Override
    public EngineStatus status() {
        return coordinator.status();
    }

    @Override
    public boolean isBusy() {
        if (coordinator.isPaused()) return false;
        return PathEngine.super.isBusy() || mineController.isActive();
    }

    @Override
    public void tick() {
        checkStatusTransition();
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            cancelInFlightPlan();
            executor.releaseAllInputs(mc);
            checkStatusTransition();
            return;
        }
        if (mc.level != null && !player.isDeadOrDying()) {
            ensureDeathMemoryLoaded();
            DEATH_MEMORY.recordTrailPosition(
                    mc.level.dimension().identifier().toString(),
                    player.getBlockX(),
                    player.getBlockY(),
                    player.getBlockZ());
        }

        CompletableFuture<PlanResult> future = this.inFlightPlan;
        long futureGen = this.inFlightPlanGen;
        if (future != null) {
            if (!future.isDone()) {
                executor.releaseAllInputs(mc);
                checkStatusTransition();
                return;
            }
            this.inFlightPlan = null;
            handleAsyncPlanResult(mc, future, futureGen);
        }

        if (coordinator.status() == EngineStatus.PLANNING && !coordinator.isPaused() && this.inFlightPlan == null) {
            GoalSpec orphanGoal = coordinator.activeGoal();
            if (orphanGoal != null && !useCustomPlanner && mc.level != null) {
                BlockPos start = groundedStart(mc, player, player.blockPosition());
                launchAsyncReplan(mc, player, orphanGoal, start, "orphaned PLANNING state");
            } else {
                coordinator.markFailed("Orphaned PLANNING state with no active future");
            }
            checkStatusTransition();
            return;
        }

        if (coordinator.status() == EngineStatus.MOVING
                && this.inFlightPlan == null
                && !useCustomPlanner
                && mc.level != null
                && (++lookaheadTickCounter % 4 == 0)) {
            String invalidReason = validateLookahead(mc);
            if (invalidReason != null) {
                executor.releaseAllInputs(mc);
                if (coordinator.requestLookaheadReplan(invalidReason)) {
                    GoalSpec goal = coordinator.activeGoal();
                    if (goal != null) {
                        BlockPos start = groundedStart(mc, player, player.blockPosition());
                        launchAsyncReplan(mc, player, goal, start, "lookahead: " + invalidReason);
                    }
                }
                checkStatusTransition();
                return;
            }
        }

        TickAction action = coordinator.tick(player.getX(), player.getY(), player.getZ());
        switch (action) {
            case TickAction.MoveTo move -> {
                executor.tick(mc, move.node());
                if (TRACE) trace(mc, player, move.node());
            }
            case TickAction.NeedsReplan replan -> {
                executor.releaseAllInputs(mc);
                if (!useCustomPlanner && mc.level != null) {
                    BlockPos start = groundedStart(mc, player, replan.start());
                    launchAsyncReplan(mc, player, replan.goal(), start, replan.reason());
                } else {
                    coordinator.markFailed("Async replan unavailable: " + replan.reason());
                }
            }
            case TickAction.Idle _, TickAction.Done _, TickAction.Failed _ -> executor.releaseAllInputs(mc);
        }

        if (mineController.isActive() && this.inFlightPlan == null) {
            EngineStatus st = coordinator.status();
            if (st == EngineStatus.DONE) {
                startNextMineTarget(mc, player, mineController.filter(), mineController.box());
            } else if (st == EngineStatus.FAILED || st == EngineStatus.IDLE) {
                clearActiveMine();
            }
        }
        checkStatusTransition();
    }

    private void checkStatusTransition() {
        EngineStatus current = coordinator.status();
        if (current != lastLoggedStatus) {
            LOGGER.info("[MidtownEngine] status transition: {} -> {}", lastLoggedStatus, current);
            lastLoggedStatus = current;
        }
    }

    private void clearActiveMine() {
        mineController.clear();
        this.pendingMineFilter = null;
        this.pendingMineBox = null;
    }

    private void startNextMineTarget(Minecraft mc, LocalPlayer player, BlockFilter filter, Area box) {
        if (mc.level == null) {
            clearActiveMine();
            if (coordinator.status() != EngineStatus.DONE && coordinator.status() != EngineStatus.IDLE) {
                coordinator.cancel();
            }
            return;
        }
        BlockPos start = groundedStart(mc, player, player.blockPosition());
        if (!mineController.hasMatchingBlockInArea(
                mc, start, filter, box, this::isProtectedAt, this::isDisallowedBreakBlock)) {
            LOGGER.info("[MidtownEngine] startNextMineTarget: no matching blocks remain");
            clearActiveMine();
            if (coordinator.status() != EngineStatus.DONE && coordinator.status() != EngineStatus.IDLE) {
                coordinator.cancel();
            }
            return;
        }
        WorldSnapshot snap = WorldSnapshot.capture(mc.level, start);
        CalculationContext ctx = buildContext(player, null);
        GoalSpec placeholderGoal = GoalSpec.near(start, 0);
        long gen = planGeneration.incrementAndGet();
        this.activePlanGen = gen;
        this.inFlightPlanGen = gen;
        this.pendingGoal = placeholderGoal;
        this.pendingStart = start;
        this.pendingIsMine = true;
        this.pendingIsReplan = false;
        this.pendingMineFilter = filter;
        this.pendingMineBox = box;
        this.lookaheadTickCounter = 0;
        coordinator.markPlanning(placeholderGoal);
        this.inFlightPlan = aStarPlanner.planMineAsync(
                snap, ctx, start, filter, box, ctx.timeBudgetMs(), Set.copyOf(mineController.blacklist()));
    }

    private boolean isDisallowedBreakBlock(Block block) {
        synchronized (disallowedBreakBlocks) {
            return disallowedBreakBlocks.contains(block);
        }
    }

    private void handleAsyncPlanResult(Minecraft mc, CompletableFuture<PlanResult> future, long gen) {
        if (gen != this.activePlanGen || future.isCancelled()) {
            LOGGER.info("[MidtownEngine] handleAsyncPlanResult: ignoring stale/cancelled future (gen={} active={})",
                    gen, this.activePlanGen);
            return;
        }
        boolean isReplan = this.pendingIsReplan;
        this.pendingIsReplan = false;
        PlanResult result;
        try {
            result = future.getNow(null);
        } catch (Throwable t) {
            LOGGER.info("[MidtownEngine] handleAsyncPlanResult: async planning exception: {}", t.getMessage());
            if (pendingIsMine) {
                clearActiveMine();
            }
            coordinator.markFailed("Async planning failed: " + t.getMessage());
            checkStatusTransition();
            return;
        }
        if (result == null || !result.hasPath()) {
            LOGGER.info("[MidtownEngine] handleAsyncPlanResult: no path found status={}", (result == null ? "null" : result.status()));
            if (pendingIsMine) {
                clearActiveMine();
            }
            coordinator.markFailed("No path found (" + (result == null ? "null" : result.status()) + ")");
            checkStatusTransition();
            return;
        }
        GoalSpec goal = pendingIsMine ? GoalSpec.exact(result.endPos()) : pendingGoal;
        BlockPos start = pendingStart != null ? pendingStart : result.start();
        List<PathNode> nodes = toPathNodes(result);
        if (pendingIsMine) {
            BlockFilter mineFilter = pendingMineFilter != null ? pendingMineFilter : mineController.filter();
            Area mineBox = pendingMineBox != null ? pendingMineBox : mineController.box();
            BlockPos bestTarget = mineController.findNearestMatchingInReach(
                    mc, mineFilter, mineBox, result.endPos(), this::isProtectedAt, this::isDisallowedBreakBlock);
            if (bestTarget == null && result.endPos().equals(start)) {
                clearActiveMine();
                coordinator.markFailed("No reachable matching block");
                checkStatusTransition();
                return;
            }
            if (bestTarget != null) {
                if (mineController.recordAttemptAndCheckBlacklist(bestTarget)) {
                    if (mc.player != null) {
                        startNextMineTarget(mc, mc.player, mineFilter, mineBox);
                        checkStatusTransition();
                        return;
                    }
                }
                List<PathNode> pathWithBreak = new ArrayList<>(nodes);
                pathWithBreak.add(new PathNode(
                        result.endPos(),
                        PathNode.ActionType.BREAK_THROUGH,
                        List.of(bestTarget),
                        false,
                        false,
                        400));
                nodes = pathWithBreak;
            }
        }
        LOGGER.info("[MidtownEngine] handleAsyncPlanResult: path found replan={} status={} nodes={} edges={} expanded={} ms={} start={} path={}",
                isReplan, result.status(), nodes.size(), result.edges().size(), result.nodesExpanded(), result.elapsedMillis(),
                start.toShortString(), describePath(nodes));
        if (isReplan) {
            if (nodes.isEmpty() && goal != null
                    && MidtownCoordinator.isGoalSatisfied(goal, start.getX() + 0.5, start.getY(), start.getZ() + 0.5)) {
                coordinator.startGoalWithPath(goal, start, nodes);
            } else {
                coordinator.applyReplannedPath(start, nodes);
            }
        } else {
            coordinator.startGoalWithPath(goal, start, nodes);
        }
        checkStatusTransition();
    }

    private void launchAsyncReplan(Minecraft mc, LocalPlayer player, GoalSpec goal, BlockPos start, String reason) {
        if (mc.level == null || player == null || goal == null || start == null) {
            coordinator.markFailed("Cannot launch async replan: " + reason);
            return;
        }
        cancelInFlightPlan();
        WorldSnapshot snap = WorldSnapshot.captureForGoal(mc.level, start, goal);
        CalculationContext ctx = buildContext(player, goal);
        long gen = planGeneration.incrementAndGet();
        this.activePlanGen = gen;
        this.inFlightPlanGen = gen;
        this.pendingGoal = goal;
        this.pendingStart = start;
        this.pendingIsMine = false;
        this.pendingIsReplan = true;
        this.pendingMineFilter = null;
        this.pendingMineBox = null;
        this.lookaheadTickCounter = 0;
        LOGGER.info("[MidtownEngine] launchAsyncReplan: gen={} start={} goal={} reason={}",
                gen, start.toShortString(), goal, reason);
        this.inFlightPlan = aStarPlanner.planAsync(snap, ctx, start, goal);
    }

    /**
     * Validates the next 5 waypoints of the active path against the live {@code mc.level}.
     *
     * @return a non-null invalidation reason if an async replan should be triggered, or {@code null} if clear
     */
    private String validateLookahead(Minecraft mc) {
        if (mc.level == null) {
            return null;
        }
        List<PathNode> currentPath = coordinator.path();
        int startIdx = coordinator.pathIndex();
        if (currentPath.isEmpty() || startIdx < 0 || startIdx >= currentPath.size()) {
            return null;
        }
        int endIdx = Math.min(currentPath.size(), startIdx + 5);
        for (int i = startIdx; i < endIdx; i++) {
            PathNode node = currentPath.get(i);
            BlockPos feet = node.pos();
            if (!mc.level.hasChunkAt(feet)) {
                continue;
            }
            BlockPos head = feet.above();
            BlockPos floor = feet.below();
            BlockKind feetKind = WorldSnapshot.classify(mc.level.getBlockState(feet));
            BlockKind headKind = WorldSnapshot.classify(mc.level.getBlockState(head));
            BlockKind floorKind = WorldSnapshot.classify(mc.level.getBlockState(floor));

            switch (node.action()) {
                case WALK, ASCEND, DESCEND -> {
                    if (feetKind.hasSolidCollision() || feetKind.isHazard()) {
                        return "feet " + feetKind + " at " + feet.toShortString();
                    }
                    if (headKind.hasSolidCollision() || headKind.isHazard()) {
                        return "head " + headKind + " at " + head.toShortString();
                    }
                    if (node.action() == PathNode.ActionType.WALK
                            && !allowPlace
                            && feetKind != BlockKind.WATER
                            && feetKind != BlockKind.LADDER_OR_VINE
                            && !floorKind.canStandOn()
                            && floorKind != BlockKind.WATER
                            && floorKind != BlockKind.LADDER_OR_VINE) {
                        return "walk floor missing (" + floorKind + ") at " + floor.toShortString();
                    }
                }
                case BRIDGE -> {
                    if (feetKind.hasSolidCollision() || feetKind.isHazard()) {
                        return "bridge feet " + feetKind + " at " + feet.toShortString();
                    }
                    if (headKind.hasSolidCollision() || headKind.isHazard()) {
                        return "bridge head " + headKind + " at " + head.toShortString();
                    }
                    if (!allowPlace && !floorKind.canStandOn()) {
                        return "bridge floor missing with allowPlace=false at " + floor.toShortString();
                    }
                }
                case BREAK_THROUGH -> {
                    if (!allowBreak) {
                        for (BlockPos tb : node.targetBlocks()) {
                            if (isWorldBlockPresent(tb)) {
                                return "break disallowed at " + tb.toShortString();
                            }
                        }
                    }
                }
                case PILLAR -> {
                    if (!allowPlace && node.targetBlock() != null && !isWorldBlockSolid(node.targetBlock())) {
                        return "pillar place disallowed at " + node.targetBlock().toShortString();
                    }
                }
                default -> {}
            }

            for (BlockPos tb : node.targetBlocks()) {
                if (isProtectedAt(tb)) {
                    return "protected area blocks target at " + tb.toShortString();
                }
            }
        }
        return null;
    }

    private void cancelInFlightPlan() {
        this.activePlanGen = planGeneration.incrementAndGet();
        CompletableFuture<PlanResult> future = this.inFlightPlan;
        this.inFlightPlan = null;
        this.pendingIsReplan = false;
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
    }

    private CalculationContext buildContext(LocalPlayer player, GoalSpec goal) {
        CalculationContext.Builder builder = CalculationContext.fromPlayer(player, effectiveItemSaverThreshold())
                .allowBreak(allowBreak)
                .allowPlace(allowPlace)
                .allowParkourPlace(allowParkourPlace)
                .blockBreakPenalty(blockBreakPenalty)
                .placeCostTicks(placeCostTicks)
                .deathField(currentDeathField(goal))
                .dynamicEdgeBlacklist((fx, fy, fz, tx, ty, tz) ->
                        coordinator.edgeTracker().isBlacklisted(
                                new BlockPos(fx, fy, fz),
                                new BlockPos(tx, ty, tz),
                                coordinator.tickCounter()));

        synchronized (disallowedBreakBlocks) {
            if (!disallowedBreakBlocks.isEmpty()) {
                builder.addDisallowedBreakBlocks(disallowedBreakBlocks);
            }
        }
        for (Map.Entry<String, List<Area>> entry : protectedAreas.entrySet()) {
            for (Area area : entry.getValue()) {
                builder.addProtectedArea(entry.getKey(), area);
            }
        }
        return builder.build();
    }

    /**
     * The block the planner should start from. While the player is airborne (mid pillar jump,
     * stepping up, falling) its feet block often has nothing under it yet, so a plan from there can
     * only fall into a neighbouring column. Snap down one block onto solid ground so a stalled
     * pillar is retried in place instead of dropping off and pillaring again one block over. Only ever one block, and never while anything is under the hitbox:
     * snapping further put the start on the ground while the player still stood on a pillar edge.
     */
    static BlockPos groundedStart(Minecraft mc, LocalPlayer player, BlockPos feet) {
        if (mc.level == null || player == null || feet == null) return feet;
        // on the ground it is supported, maybe by the edge of a neighbouring block: never snap down then
        if (player.onGround() || player.isInWater() || player.isInLava() || player.onClimbable()) return feet;
        // anything under the hitbox within 0.6 (also a neighbour's edge): it lands on this level
        if (!mc.level.noCollision(player, player.getBoundingBox().move(0.0, -0.6, 0.0))) return feet;
        // a pillar jump lifts the player at most ~1.25 above its block: snap one block, onto solid ground only
        BlockPos down = feet.below();
        BlockPos support = down.below();
        if (mc.level.getBlockState(down).getCollisionShape(mc.level, down).isEmpty()
                && !mc.level.getBlockState(support).getCollisionShape(mc.level, support).isEmpty()) {
            return down;
        }
        return feet;
    }

    private static void trace(Minecraft mc, LocalPlayer p, PathNode node) {
        var o = mc.options;
        var v = p.getDeltaMovement();
        LOGGER.info("[MidtownTrace] {}@{} tb={} p={} {} {} v={} {} {} ground={} yaw={} pitch={} keys={}{}{}{}{}{}",
                node.action(), node.pos().toShortString().replace(" ", ""),
                node.targetBlock() == null ? "-" : node.targetBlock().toShortString().replace(" ", ""),
                String.format("%.2f", p.getX()), String.format("%.2f", p.getY()), String.format("%.2f", p.getZ()),
                String.format("%.2f", v.x), String.format("%.2f", v.y), String.format("%.2f", v.z),
                p.onGround(), String.format("%.0f", p.getYRot()), String.format("%.0f", p.getXRot()),
                o.keyUp.isDown() ? "W" : "", o.keyDown.isDown() ? "S" : "", o.keyLeft.isDown() ? "A" : "",
                o.keyRight.isDown() ? "D" : "", o.keyJump.isDown() ? "J" : "", o.keyShift.isDown() ? "Sh" : "");
    }

    /** Compact one-line path description for the log: {@code ACTION@x,y,z} per node (first 40). */
    static String describePath(List<PathNode> nodes) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(nodes.size(), 40);
        for (int i = 0; i < n; i++) {
            PathNode node = nodes.get(i);
            if (i > 0) sb.append(' ');
            sb.append(node.action()).append('@').append(node.pos().toShortString().replace(" ", ""));
        }
        if (nodes.size() > n) sb.append(" ...+").append(nodes.size() - n);
        return sb.toString();
    }

    /** Maps a {@link PlanResult} from {@link MidtownPlanner} to executor {@link PathNode}s. */
    public static List<PathNode> toPathNodes(PlanResult result) {
        if (result == null || result.edges().isEmpty()) {
            return List.of();
        }
        List<PathNode> nodes = new ArrayList<>(result.edges().size());
        for (MoveEdge edge : result.edges()) {
            nodes.add(toPathNode(edge));
        }
        return nodes;
    }

    private static PathNode toPathNode(MoveEdge edge) {
        return switch (edge) {
            case MoveEdge.WalkEdge w -> new PathNode(w.to(), PathNode.ActionType.WALK, List.of(), !w.diagonal(), false, 0);
            case MoveEdge.AscendEdge a -> new PathNode(a.to(), PathNode.ActionType.ASCEND, List.of(), false, false, 0);
            case MoveEdge.DescendEdge d -> new PathNode(d.to(), PathNode.ActionType.DESCEND, List.of(), false, false, 0);
            case MoveEdge.FallEdge f -> new PathNode(
                    f.to(), PathNode.ActionType.FALL, List.of(), false, false, Math.max(80, f.fallDistance() * 10 + 40));
            case MoveEdge.BreakThroughEdge b -> new PathNode(
                    b.to(), PathNode.ActionType.BREAK_THROUGH, b.blocksToBreak(), false, false, 160);
            case MoveEdge.BridgeEdge br -> new PathNode(
                    br.to(), PathNode.ActionType.BRIDGE, br.placePos(), false, true, 100);
            case MoveEdge.PillarEdge p -> new PathNode(
                    p.to(), PathNode.ActionType.PILLAR, p.placePos(), false, false, 100);
            case MoveEdge.ParkourEdge pk -> new PathNode(
                    pk.to(), PathNode.ActionType.PARKOUR, pk.needsBridgePlace() ? pk.to().below() : null, true, false, 80);
        };
    }

    // ------------------------------------------------------------------ safety & setting hooks

    @Override
    public void setAllowBreak(boolean allow) {
        this.allowBreak = allow;
    }

    @Override
    public boolean allowBreak() {
        return allowBreak;
    }

    @Override
    public void setAllowPlace(boolean allow) {
        this.allowPlace = allow;
    }

    @Override
    public boolean allowPlace() {
        return allowPlace;
    }

    @Override
    public void setAllowParkourPlace(boolean allow) {
        this.allowParkourPlace = allow;
    }

    @Override
    public boolean allowParkourPlace() {
        return allowParkourPlace;
    }

    @Override
    public void addProtectedArea(String name, Area area) {
        if (name != null && area != null) {
            protectedAreas.compute(name, (k, cur) -> {
                List<Area> next = cur == null ? new ArrayList<>() : new ArrayList<>(cur);
                next.add(area);
                return Collections.unmodifiableList(next);
            });
        }
    }

    @Override
    public void setProtectedArea(String name, List<Area> boxes) {
        if (name == null) return;
        if (boxes == null || boxes.isEmpty()) {
            protectedAreas.remove(name);
            return;
        }
        List<Area> copy = new ArrayList<>();
        for (Area a : boxes) {
            if (a != null) copy.add(a);
        }
        if (copy.isEmpty()) protectedAreas.remove(name);
        else protectedAreas.put(name, Collections.unmodifiableList(copy));
    }

    @Override
    public void removeProtectedArea(String name) {
        if (name != null) {
            protectedAreas.remove(name);
        }
    }

    @Override
    public void clearProtectedAreas() {
        protectedAreas.clear();
    }

    @Override
    public boolean isProtectedAt(BlockPos pos) {
        if (pos == null) return false;
        for (List<Area> areas : protectedAreas.values()) {
            for (Area area : areas) {
                if (area.contains(pos)) return true;
            }
        }
        return false;
    }

    @Override
    public void setDisallowedBreakBlocks(Collection<Block> blocks) {
        synchronized (disallowedBreakBlocks) {
            disallowedBreakBlocks.clear();
            if (blocks != null) {
                disallowedBreakBlocks.addAll(blocks);
            }
        }
    }

    @Override
    public boolean setSetting(String name, String value) {
        if (name == null || value == null) return false;
        return switch (name) {
            case "stuckTicks" -> {
                coordinator.edgeTracker().setStuckThresholdTicks(Integer.parseInt(value));
                yield true;
            }
            case "edgeTimeoutTicks" -> {
                coordinator.edgeTracker().setDefaultEdgeTimeoutTicks(Integer.parseInt(value));
                yield true;
            }
            case "blacklistTicks" -> {
                coordinator.edgeTracker().setDefaultBlacklistTicks(Integer.parseInt(value));
                yield true;
            }
            case "maxReplans" -> {
                coordinator.setMaxReplans(Integer.parseInt(value));
                yield true;
            }
            case "allowBreak" -> {
                setAllowBreak(Boolean.parseBoolean(value));
                yield true;
            }
            case "allowPlace" -> {
                setAllowPlace(Boolean.parseBoolean(value));
                yield true;
            }
            case "allowParkourPlace" -> {
                setAllowParkourPlace(Boolean.parseBoolean(value));
                yield true;
            }
            case "itemSaver" -> {
                this.itemSaver = Boolean.parseBoolean(value);
                executor.setItemSaverThreshold(effectiveItemSaverThreshold());
                yield true;
            }
            case "itemSaverThreshold" -> {
                int threshold = Integer.parseInt(value);
                if (threshold < 0) throw new IllegalArgumentException("itemSaverThreshold must be >= 0");
                this.itemSaverThreshold = threshold;
                executor.setItemSaverThreshold(effectiveItemSaverThreshold());
                yield true;
            }
            case "blockBreakPenalty" -> {
                double pen = Double.parseDouble(value);
                if (pen < 0) throw new IllegalArgumentException("blockBreakPenalty must be >= 0");
                this.blockBreakPenalty = pen;
                yield true;
            }
            case "placeCostTicks" -> {
                double cost = Double.parseDouble(value);
                if (cost < 0) throw new IllegalArgumentException("placeCostTicks must be >= 0");
                this.placeCostTicks = cost;
                yield true;
            }
            default -> false;
        };
    }

    public int effectiveItemSaverThreshold() {
        return itemSaver ? itemSaverThreshold : 0;
    }

    @Override
    public String getSetting(String name) {
        if (name == null) return null;
        return switch (name) {
            case "stuckTicks" -> Integer.toString(coordinator.edgeTracker().stuckThresholdTicks());
            case "edgeTimeoutTicks" -> Integer.toString(coordinator.edgeTracker().defaultEdgeTimeoutTicks());
            case "blacklistTicks" -> Integer.toString(coordinator.edgeTracker().defaultBlacklistTicks());
            case "maxReplans" -> Integer.toString(coordinator.maxReplans());
            case "allowBreak" -> Boolean.toString(allowBreak);
            case "allowPlace" -> Boolean.toString(allowPlace);
            case "allowParkourPlace" -> Boolean.toString(allowParkourPlace);
            case "itemSaver" -> Boolean.toString(itemSaver);
            case "itemSaverThreshold" -> Integer.toString(itemSaverThreshold);
            case "blockBreakPenalty" -> Double.toString(blockBreakPenalty);
            case "placeCostTicks" -> Double.toString(placeCostTicks);
            default -> null;
        };
    }

    // ------------------------------------------------------------------ Death Memory Integration

    public static synchronized void configureDeathStorage(Path dir, Supplier<String> worldSupplier) {
        deathStorageDir = dir;
        deathWorldSupplier = worldSupplier;
        loadedDeathWorld = null;
        ensureDeathMemoryLoaded();
    }

    public static synchronized DeathMemory.RecordSummary recordDeath(String dimension, BlockPos pos) {
        if (pos == null) return null;
        ensureDeathMemoryLoaded();
        DeathMemory.RecordSummary summary = DEATH_MEMORY.recordDeath(
                dimension, pos.getX(), pos.getY(), pos.getZ(), System.currentTimeMillis());
        saveDeathMemory();
        LOGGER.info("[MidtownEngine] Recorded death at {} (+{} breadcrumbs, {} nearby) in {}",
                pos.toShortString(), summary.breadcrumbs(), summary.nearbyDeaths(), dimension);
        return summary;
    }

    public static synchronized List<DeathMemory.Record> recentDeathSites(String dimension) {
        ensureDeathMemoryLoaded();
        return DEATH_MEMORY.recentDeathSites(dimension, System.currentTimeMillis());
    }

    public static synchronized void clearDeaths() {
        ensureDeathMemoryLoaded();
        DEATH_MEMORY.clearAll();
        saveDeathMemory();
    }

    private DeathMemory.DeathField currentDeathField(GoalSpec goal) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) return DeathMemory.DeathField.EMPTY;
        BlockPos goalPos = switch (goal) {
            case GoalSpec.Exact g -> g.pos();
            case GoalSpec.Near g -> g.pos();
            case GoalSpec.LineOfSight g -> g.target();
            case null, default -> null;
        };
        List<Area> protectedBoxes = new ArrayList<>();
        for (List<Area> boxes : protectedAreas.values()) {
            protectedBoxes.addAll(boxes);
        }
        synchronized (MidtownEngine.class) {
            ensureDeathMemoryLoaded();
            return DEATH_MEMORY.snapshot(
                    mc.level.dimension().identifier().toString(),
                    System.currentTimeMillis(),
                    goalPos,
                    protectedBoxes);
        }
    }

    private static synchronized void ensureDeathMemoryLoaded() {
        Path dir = deathStorageDir;
        if (dir == null) return;
        String world = resolveDeathWorld();
        if (world.equals(loadedDeathWorld)) return;
        loadedDeathWorld = world;
        DEATH_MEMORY.load(dir.resolve(world + ".json"));
    }

    private static synchronized void saveDeathMemory() {
        Path dir = deathStorageDir;
        if (dir == null) return;
        String world = loadedDeathWorld != null ? loadedDeathWorld : resolveDeathWorld();
        loadedDeathWorld = world;
        DEATH_MEMORY.save(dir.resolve(world + ".json"));
    }

    private static String resolveDeathWorld() {
        Supplier<String> sup = deathWorldSupplier;
        String raw = null;
        if (sup != null) {
            try {
                raw = sup.get();
            } catch (Throwable ignored) {
            }
        }
        if (raw == null || raw.isBlank()) return "default";
        String stem = raw.trim().replaceAll("[^A-Za-z0-9._-]+", "_");
        while (stem.startsWith(".")) stem = stem.substring(1);
        stem = stem.replaceAll("^_+|_+$", "");
        return stem.isEmpty() ? "default" : stem;
    }
}
