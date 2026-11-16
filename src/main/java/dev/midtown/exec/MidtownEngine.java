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
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
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
    private final Map<String, List<Area>> protectedAreas = new ConcurrentHashMap<>();
    private final Set<Block> disallowedBreakBlocks = Collections.synchronizedSet(new LinkedHashSet<>());

    private volatile boolean useCustomPlanner;
    private volatile CompletableFuture<PlanResult> inFlightPlan;
    private volatile GoalSpec pendingGoal;
    private volatile BlockPos pendingStart;
    private volatile boolean pendingIsMine;
    private volatile BlockFilter pendingMineFilter;
    private volatile Area pendingMineBox;
    private volatile BlockFilter activeMineFilter;
    private volatile Area activeMineBox;
    private final Set<BlockPos> activeMineBlacklist = new HashSet<>();
    private volatile BlockPos lastMineTarget = null;
    private volatile int lastMineTargetAttempts = 0;

    private volatile boolean allowBreak = true;
    private volatile boolean allowPlace = true;
    private volatile boolean allowParkourPlace = true;
    private volatile boolean itemSaver = false;
    private volatile int itemSaverThreshold = 0;
    private volatile EngineStatus lastLoggedStatus = EngineStatus.IDLE;

    public MidtownEngine() {
        this.coordinator = new MidtownCoordinator(new EdgeTracker(), this::syncReplan);
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

        WorldSnapshot snap = WorldSnapshot.capture(mc.level, start);
        CalculationContext ctx = buildContext(player);
        this.pendingGoal = goal;
        this.pendingStart = start;
        this.pendingIsMine = false;
        this.pendingMineFilter = null;
        this.pendingMineBox = null;
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
        this.activeMineBlacklist.clear();
        this.lastMineTarget = null;
        this.lastMineTargetAttempts = 0;
        this.activeMineFilter = filter;
        this.activeMineBox = box;
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
        coordinator.resume(player.getX(), player.getY(), player.getZ());
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
        return PathEngine.super.isBusy() || (activeMineFilter != null && !coordinator.isPaused());
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
            handleAsyncPlanResult(mc, future);
        }

        TickAction action = coordinator.tick(player.getX(), player.getY(), player.getZ());
        switch (action) {
            case TickAction.MoveTo move -> executor.tick(mc, move.node());
            case TickAction.Idle _, TickAction.Done _, TickAction.Failed _ -> executor.releaseAllInputs(mc);
        }

        BlockFilter mineFilter = this.activeMineFilter;
        if (mineFilter != null && this.inFlightPlan == null) {
            EngineStatus st = coordinator.status();
            if (st == EngineStatus.DONE) {
                startNextMineTarget(mc, player, mineFilter, this.activeMineBox);
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
        this.activeMineFilter = null;
        this.activeMineBox = null;
        this.pendingMineFilter = null;
        this.pendingMineBox = null;
        this.activeMineBlacklist.clear();
        this.lastMineTarget = null;
        this.lastMineTargetAttempts = 0;
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
        if (!hasMatchingBlockInArea(mc, start, filter, box)) {
            LOGGER.info("[MidtownEngine] startNextMineTarget: no matching blocks remain");
            clearActiveMine();
            if (coordinator.status() != EngineStatus.DONE && coordinator.status() != EngineStatus.IDLE) {
                coordinator.cancel();
            }
            return;
        }
        WorldSnapshot snap = WorldSnapshot.capture(mc.level, start);
        CalculationContext ctx = buildContext(player);
        GoalSpec placeholderGoal = GoalSpec.near(start, 0);
        this.pendingGoal = placeholderGoal;
        this.pendingStart = start;
        this.pendingIsMine = true;
        this.pendingMineFilter = filter;
        this.pendingMineBox = box;
        coordinator.markPlanning(placeholderGoal);
        this.inFlightPlan = aStarPlanner.planMineAsync(
                snap, ctx, start, filter, box, ctx.timeBudgetMs(), activeMineBlacklist);
    }

    private boolean hasMatchingBlockInArea(Minecraft mc, BlockPos start, BlockFilter filter, Area box) {
        if (mc.level == null || filter == null) return false;
        int defaultRadius = 48;
        int minX = box != null ? box.minX() : start.getX() - defaultRadius;
        int maxX = box != null ? box.maxX() : start.getX() + defaultRadius;
        int minY = Math.max(mc.level.getMinY(), box != null ? box.minY() : start.getY() - 24);
        int maxY = Math.min(mc.level.getMaxY() - 1, box != null ? box.maxY() : start.getY() + 24);
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

        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    mp.set(x, y, z);
                    if (activeMineBlacklist.contains(mp)) continue;
                    if (!mc.level.hasChunkAt(mp)) continue;
                    var state = mc.level.getBlockState(mp);
                    if (state.isAir() || !state.getFluidState().isEmpty()) continue;
                    if (!filter.matches(state)) continue;
                    if (isProtectedAt(mp)) continue;
                    if (state.getDestroySpeed(mc.level, mp) < 0.0f) continue;
                    if (isDisallowedBreakBlock(state.getBlock())) continue;
                    return true;
                }
            }
        }
        return false;
    }

    private BlockPos findNearestMatchingInReach(Minecraft mc, BlockFilter filter, Area box, BlockPos endPos) {
        if (mc.level == null || filter == null || endPos == null) return null;
        double eyeX = endPos.getX() + 0.5;
        double eyeY = endPos.getY() + 1.62;
        double eyeZ = endPos.getZ() + 0.5;
        Vec3 eye = new Vec3(eyeX, eyeY, eyeZ);

        int minX = endPos.getX() - 5;
        int maxX = endPos.getX() + 5;
        int minY = Math.max(mc.level.getMinY(), endPos.getY() - 4);
        int maxY = Math.min(mc.level.getMaxY() - 1, endPos.getY() + 6);
        int minZ = endPos.getZ() - 5;
        int maxZ = endPos.getZ() + 5;

        if (box != null) {
            minX = Math.max(minX, box.minX());
            maxX = Math.min(maxX, box.maxX());
            minY = Math.max(minY, box.minY());
            maxY = Math.min(maxY, box.maxY());
            minZ = Math.max(minZ, box.minZ());
            maxZ = Math.min(maxZ, box.maxZ());
        }

        BlockPos bestLos = null;
        double bestLosDistSq = Double.MAX_VALUE;
        BlockPos bestAny = null;
        double bestAnyDistSq = Double.MAX_VALUE;
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    mp.set(x, y, z);
                    if (activeMineBlacklist.contains(mp)) continue;
                    if (!mc.level.hasChunkAt(mp)) continue;
                    var state = mc.level.getBlockState(mp);
                    if (state.isAir() || !state.getFluidState().isEmpty()) continue;
                    if (!filter.matches(state)) continue;
                    if (isProtectedAt(mp)) continue;
                    if (state.getDestroySpeed(mc.level, mp) < 0.0f) continue;
                    if (isDisallowedBreakBlock(state.getBlock())) continue;

                    double dx = (x + 0.5) - eyeX;
                    double dy = (y + 0.5) - eyeY;
                    double dz = (z + 0.5) - eyeZ;
                    double distSq = dx * dx + dy * dy + dz * dz;
                    if (distSq > 20.25) continue;

                    if (distSq < bestAnyDistSq) {
                        bestAnyDistSq = distSq;
                        bestAny = mp.immutable();
                    }
                    if (distSq < bestLosDistSq && hasClearSight(mc, eye, mp)) {
                        bestLosDistSq = distSq;
                        bestLos = mp.immutable();
                    }
                }
            }
        }
        return bestLos != null ? bestLos : bestAny;
    }

    private static boolean hasClearSight(Minecraft mc, Vec3 eye, BlockPos target) {
        if (mc.level == null || mc.player == null) return true;
        var hit = mc.level.clip(new ClipContext(
                eye, Vec3.atCenterOf(target), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.MISS
                || (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target));
    }

    private boolean isDisallowedBreakBlock(Block block) {
        synchronized (disallowedBreakBlocks) {
            return disallowedBreakBlocks.contains(block);
        }
    }

    private void handleAsyncPlanResult(Minecraft mc, CompletableFuture<PlanResult> future) {
        if (future.isCancelled()) {
            LOGGER.info("[MidtownEngine] handleAsyncPlanResult: in-flight future was cancelled");
            return;
        }
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
            BlockFilter mineFilter = pendingMineFilter != null ? pendingMineFilter : activeMineFilter;
            Area mineBox = pendingMineBox != null ? pendingMineBox : activeMineBox;
            BlockPos bestTarget = findNearestMatchingInReach(mc, mineFilter, mineBox, result.endPos());
            if (bestTarget == null && result.endPos().equals(start)) {
                clearActiveMine();
                coordinator.markFailed("No reachable matching block");
                checkStatusTransition();
                return;
            }
            if (bestTarget != null) {
                if (bestTarget.equals(lastMineTarget)) {
                    lastMineTargetAttempts++;
                } else {
                    lastMineTarget = bestTarget;
                    lastMineTargetAttempts = 1;
                }
                if (lastMineTargetAttempts > 2) {
                    activeMineBlacklist.add(bestTarget);
                    lastMineTarget = null;
                    lastMineTargetAttempts = 0;
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
        LOGGER.info("[MidtownEngine] handleAsyncPlanResult: path found status={} nodes={} edges={} start={} path={}",
                result.status(), nodes.size(), result.edges().size(), start.toShortString(), describePath(nodes));
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
        start = groundedStart(mc, mc.player, start);
        WorldSnapshot snap = WorldSnapshot.capture(mc.level, start);
        CalculationContext ctx = buildContext(mc.player);
        PlanResult result = aStarPlanner.planSync(snap, ctx, start, goal);
        List<PathNode> nodes = result.hasPath() ? toPathNodes(result) : List.of();
        LOGGER.info("[MidtownEngine] syncReplan: start={} onGround={} status={} path={}",
                start.toShortString(), mc.player.onGround(), result.status(), describePath(nodes));
        return nodes;
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
     * only fall into a neighbouring column. Snap down to the first standable block of this column
     * (at most 3 below) so a stalled pillar is retried in place instead of dropping off and
     * pillaring again one block over.
     */
    static BlockPos groundedStart(Minecraft mc, LocalPlayer player, BlockPos feet) {
        if (mc.level == null || player == null || feet == null) return feet;
        if (player.isInWater() || player.isInLava() || player.onClimbable()) return feet;
        if (!mc.level.getBlockState(feet.below()).getCollisionShape(mc.level, feet.below()).isEmpty()) {
            return feet;
        }
        BlockPos.MutableBlockPos p = feet.mutable();
        for (int d = 1; d <= 3; d++) {
            p.setY(feet.getY() - d);
            if (!mc.level.getBlockState(p).getCollisionShape(mc.level, p).isEmpty()) {
                return feet;
            }
            BlockPos below = p.below();
            if (!mc.level.getBlockState(below).getCollisionShape(mc.level, below).isEmpty()) {
                return p.immutable();
            }
        }
        return feet;
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
