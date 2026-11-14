package dev.midtown.exec;

import dev.midtown.api.Area;
import dev.midtown.api.BlockFilter;
import dev.midtown.api.EngineSelector;
import dev.midtown.api.EngineStatus;
import dev.midtown.api.GoalSpec;
import dev.midtown.api.PathEngine;
import dev.midtown.exec.MidtownCoordinator.TickAction;
import dev.midtown.plan.CalculationContext;
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

/**
 * {@link PathEngine} implementation for Midtown.
 * <p>
 * Connects {@link MidtownCoordinator} (synchronous interrupt stack, stuck/timeout/blacklist state machine),
 * {@link MidtownPlanner} (async 3D A* over {@link WorldSnapshot} + {@link CalculationContext}), and
 * {@link MidtownExecutor} (client-tick movement and rotation controller).
 */
public final class MidtownEngine implements PathEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(MidtownEngine.class);

    private final MidtownCoordinator coordinator;
    private final MidtownExecutor executor;
    private final MidtownPlanner aStarPlanner = new MidtownPlanner();
    private final Map<String, Area> protectedAreas = new ConcurrentHashMap<>();
    private final Set<Block> disallowedBreakBlocks = Collections.synchronizedSet(new LinkedHashSet<>());

    private volatile boolean useCustomPlanner;
    private volatile CompletableFuture<PlanResult> inFlightPlan;
    private volatile GoalSpec pendingGoal;
    private volatile BlockPos pendingStart;
    private volatile boolean pendingIsMine;

    private volatile boolean allowBreak = true;
    private volatile boolean allowPlace = true;
    private volatile boolean allowParkourPlace = true;
    private volatile boolean itemSaver = false;
    private volatile int itemSaverThreshold = 0;
    private volatile EngineStatus lastLoggedStatus = EngineStatus.IDLE;

    public MidtownEngine() {
        this.coordinator = new MidtownCoordinator(new EdgeTracker(), this::syncReplan);
        this.coordinator.setBlockSolidCheck(this::isWorldBlockSolid);
        this.executor = new MidtownExecutor();
        this.useCustomPlanner = false;
    }

    public MidtownEngine(Planner planner) {
        this.coordinator = new MidtownCoordinator(new EdgeTracker(), planner);
        this.coordinator.setBlockSolidCheck(this::isWorldBlockSolid);
        this.executor = new MidtownExecutor();
        this.useCustomPlanner = true;
    }

    public MidtownEngine(MidtownCoordinator coordinator, MidtownExecutor executor) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.coordinator.setBlockSolidCheck(this::isWorldBlockSolid);
        this.executor = Objects.requireNonNull(executor, "executor");
        this.useCustomPlanner = true;
    }

    private boolean isWorldBlockSolid(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;
        var state = mc.level.getBlockState(pos);
        return !state.canBeReplaced() && state.getFluidState().isEmpty();
    }

    public void setPlanner(Planner planner) {
        this.useCustomPlanner = true;
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

    @Override
    public String name() {
        return EngineSelector.ENGINE_MIDTOWN;
    }

    @Override
    public void goTo(GoalSpec goal) {
        Objects.requireNonNull(goal, "goal");
        cancelInFlightPlan();
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            coordinator.cancel();
            checkStatusTransition();
            return;
        }
        executor.releaseAllInputs(mc);
        BlockPos start = player.blockPosition();
        LOGGER.info("[MidtownEngine] goTo: start={} goal={}", start.toShortString(), goal);

        if (useCustomPlanner || mc.level == null) {
            coordinator.startGoal(goal, start);
            checkStatusTransition();
            return;
        }

        WorldSnapshot snap = WorldSnapshot.capture(mc.level, start);
        CalculationContext ctx = buildContext(player);
        this.pendingGoal = goal;
        this.pendingStart = start;
        this.pendingIsMine = false;
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
            coordinator.cancel();
            return;
        }
        executor.releaseAllInputs(mc);
        BlockPos start = player.blockPosition();
        WorldSnapshot snap = WorldSnapshot.capture(mc.level, start);
        CalculationContext ctx = buildContext(player);

        GoalSpec placeholderGoal = GoalSpec.near(start, 0);
        this.pendingGoal = placeholderGoal;
        this.pendingStart = start;
        this.pendingIsMine = true;
        coordinator.markPlanning(placeholderGoal);
        this.inFlightPlan = aStarPlanner.planMineAsync(snap, ctx, start, filter, box, ctx.timeBudgetMs());
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
        coordinator.resume(player.getX(), player.getY(), player.getZ());
    }

    @Override
    public void cancel() {
        LOGGER.info("[MidtownEngine] cancel: status={}", coordinator.status());
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

        CompletableFuture<PlanResult> future = this.inFlightPlan;
        if (future != null) {
            if (!future.isDone()) {
                executor.releaseAllInputs(mc);
                checkStatusTransition();
                return;
            }
            this.inFlightPlan = null;
            handleAsyncPlanResult(future);
        }

        TickAction action = coordinator.tick(player.getX(), player.getY(), player.getZ());
        switch (action) {
            case TickAction.MoveTo move -> executor.tick(mc, move.node());
            case TickAction.Idle _, TickAction.Done _, TickAction.Failed _ -> executor.releaseAllInputs(mc);
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

    private void handleAsyncPlanResult(CompletableFuture<PlanResult> future) {
        if (future.isCancelled()) {
            LOGGER.info("[MidtownEngine] handleAsyncPlanResult: in-flight future was cancelled");
            return;
        }
        PlanResult result;
        try {
            result = future.getNow(null);
        } catch (Throwable t) {
            LOGGER.info("[MidtownEngine] handleAsyncPlanResult: async planning exception: {}", t.getMessage());
            coordinator.markFailed("Async planning failed: " + t.getMessage());
            checkStatusTransition();
            return;
        }
        if (result == null || !result.hasPath()) {
            LOGGER.info("[MidtownEngine] handleAsyncPlanResult: no path found status={}", (result == null ? "null" : result.status()));
            coordinator.markFailed("No path found (" + (result == null ? "null" : result.status()) + ")");
            checkStatusTransition();
            return;
        }
        GoalSpec goal = pendingIsMine ? GoalSpec.exact(result.endPos()) : pendingGoal;
        BlockPos start = pendingStart != null ? pendingStart : result.start();
        List<PathNode> nodes = toPathNodes(result);
        LOGGER.info("[MidtownEngine] handleAsyncPlanResult: path found nodes={} edges={}", nodes.size(), result.edges().size());
        coordinator.startGoalWithPath(goal, start, nodes);
        checkStatusTransition();
    }

    private void cancelInFlightPlan() {
        CompletableFuture<PlanResult> future = this.inFlightPlan;
        this.inFlightPlan = null;
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
    }

    private List<PathNode> syncReplan(GoalSpec goal, BlockPos start, java.util.function.Predicate<PathNode.EdgeKey> isBlacklisted) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return new Planner.DirectStepPlanner().plan(goal, start, isBlacklisted);
        }
        WorldSnapshot snap = WorldSnapshot.capture(mc.level, start);
        CalculationContext ctx = buildContext(mc.player);
        PlanResult result = aStarPlanner.planSync(snap, ctx, start, goal);
        return result.hasPath() ? toPathNodes(result) : List.of();
    }

    private CalculationContext buildContext(LocalPlayer player) {
        CalculationContext.Builder builder = CalculationContext.fromPlayer(player, effectiveItemSaverThreshold())
                .allowBreak(allowBreak)
                .allowPlace(allowPlace)
                .allowParkourPlace(allowParkourPlace)
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
        for (Map.Entry<String, Area> entry : protectedAreas.entrySet()) {
            builder.addProtectedArea(entry.getKey(), entry.getValue());
        }
        return builder.build();
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
            protectedAreas.put(name, area);
        }
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
        for (Area area : protectedAreas.values()) {
            if (area.contains(pos)) return true;
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
            default -> null;
        };
    }
}
