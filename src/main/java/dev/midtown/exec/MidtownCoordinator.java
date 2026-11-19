package dev.midtown.exec;

import dev.midtown.api.EngineStatus;
import dev.midtown.api.GoalSpec;
import dev.midtown.exec.EdgeTracker.EdgeCheckResult;
import dev.midtown.exec.PathNode.EdgeKey;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Pure-Java synchronous navigation coordinator for Midtown.
 * <p>
 * Owns:
 * <ul>
 *   <li>Active {@link GoalSpec}, planned {@link PathNode} list, and {@code pathIndex}</li>
 *   <li>Synchronous interrupt stack ({@link #pause()} / {@link #resume(double, double, double)})</li>
 *   <li>Per-edge timeout, stuck detection (20 ticks no progress), temporary edge blacklisting, and local replanning</li>
 * </ul>
 * Has zero {@code Minecraft} client dependencies so it can be unit-tested headlessly.
 */
public final class MidtownCoordinator {

    private static final Logger LOGGER = LoggerFactory.getLogger(MidtownCoordinator.class);

    public static final double DEFAULT_NODE_REACH_HORIZ = 0.40;
    public static final double DEFAULT_NODE_REACH_VERT = 0.65;
    public static final double DEFAULT_RESUME_MAX_DRIFT = 2.50;
    public static final int DEFAULT_MAX_REPLANS = 25;

    /** Snapshot pushed onto the interrupt stack when {@link #pause()} is called. */
    public record InterruptFrame(
            GoalSpec goal,
            List<PathNode> path,
            int pathIndex,
            BlockPos edgeFrom,
            BlockPos edgeTo,
            long pausedAtTick
    ) {}

    /** Outcome of {@link #resume(double, double, double)}. */
    public enum ResumeOutcome {
        /** Nothing was paused on the interrupt stack. */
        NONE,
        /** Player is still near the snapshotted edge; continuing from {@code pathIndex}. */
        CONTINUE_EDGE,
        /** Player drifted too far or edge was invalid; replanned locally from current position. */
        REPLANNED,
        /** Player drifted too far while {@code asyncReplanMode} is enabled; caller must launch an async replan. */
        NEEDS_REPLAN,
        /** Replan was required but produced no path (status set to {@link EngineStatus#FAILED}). */
        FAILED
    }

    /** Action emitted by {@link #tick(double, double, double)} for the executor. */
    public sealed interface TickAction permits TickAction.Idle, TickAction.MoveTo, TickAction.Done, TickAction.Failed, TickAction.NeedsReplan {
        record Idle() implements TickAction {}
        record MoveTo(PathNode node, boolean replannedThisTick) implements TickAction {}
        record Done() implements TickAction {}
        record Failed(String reason) implements TickAction {}
        record NeedsReplan(GoalSpec goal, BlockPos start, String reason) implements TickAction {}
    }

    private final EdgeTracker edgeTracker;
    private Planner planner;
    private Predicate<BlockPos> isBlockSolid;
    private Predicate<BlockPos> isBlockPresent;
    private final Deque<InterruptFrame> interruptStack = new ArrayDeque<>();

    private EngineStatus status = EngineStatus.IDLE;
    private GoalSpec activeGoal;
    private List<PathNode> path = List.of();
    private int pathIndex;
    private BlockPos edgeFrom;
    private boolean paused;
    private boolean asyncReplanMode;
    private long tickCounter;
    private int replanCount;
    private int maxReplans = DEFAULT_MAX_REPLANS;
    private double resumeMaxDrift = DEFAULT_RESUME_MAX_DRIFT;
    private String lastFailureReason;

    public MidtownCoordinator() {
        this(new EdgeTracker(), new Planner.DirectStepPlanner());
    }

    public MidtownCoordinator(EdgeTracker edgeTracker, Planner planner) {
        this.edgeTracker = Objects.requireNonNull(edgeTracker, "edgeTracker");
        this.planner = Objects.requireNonNull(planner, "planner");
    }

    public void setPlanner(Planner planner) {
        this.planner = Objects.requireNonNull(planner, "planner");
    }

    /** Optional world solid-block check wired by {@code MidtownEngine} for {@code PILLAR} and {@code BREAK_THROUGH}. */
    public void setBlockSolidCheck(Predicate<BlockPos> isBlockSolid) {
        this.isBlockSolid = isBlockSolid;
    }

    /** Optional world non-air block check wired by {@code MidtownEngine} for {@code BREAK_THROUGH} (including replaceable blocks). */
    public void setBlockPresentCheck(Predicate<BlockPos> isBlockPresent) {
        this.isBlockPresent = isBlockPresent;
    }

    public Planner planner() {
        return planner;
    }

    public EdgeTracker edgeTracker() {
        return edgeTracker;
    }

    /** Starts a new {@link GoalSpec} from {@code startPos}, replacing any active goal. */
    public boolean startGoal(GoalSpec goal, BlockPos startPos) {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(startPos, "startPos");
        this.activeGoal = goal;
        this.paused = false;
        this.replanCount = 0;
        this.lastFailureReason = null;

        if (isGoalSatisfied(goal, startPos.getX() + 0.5, startPos.getY(), startPos.getZ() + 0.5)) {
            this.path = List.of();
            this.pathIndex = 0;
            this.status = EngineStatus.DONE;
            edgeTracker.resetEdge();
            return true;
        }
        return planFrom(startPos);
    }

    /**
     * Starts a new {@link GoalSpec} with a precomputed path (e.g. from async {@code MidtownPlanner}).
     */
    public boolean startGoalWithPath(GoalSpec goal, BlockPos startPos, List<PathNode> plannedPath) {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(startPos, "startPos");
        this.activeGoal = goal;
        this.paused = false;
        this.replanCount = 0;
        this.lastFailureReason = null;

        if ((plannedPath == null || plannedPath.isEmpty())
                && isGoalSatisfied(goal, startPos.getX() + 0.5, startPos.getY(), startPos.getZ() + 0.5)) {
            this.path = List.of();
            this.pathIndex = 0;
            this.status = EngineStatus.DONE;
            edgeTracker.resetEdge();
            return true;
        }
        return applyPlannedPath(startPos, plannedPath);
    }

    /**
     * Suspends the active navigation task and pushes a snapshot onto the interrupt stack.
     *
     * @return the pushed {@link InterruptFrame}, or empty if no active goal is running
     */
    public Optional<InterruptFrame> pause() {
        if (activeGoal == null || paused) {
            return Optional.empty();
        }
        BlockPos to = (pathIndex >= 0 && pathIndex < path.size()) ? path.get(pathIndex).pos() : null;
        BlockPos from = edgeFrom;
        InterruptFrame frame = new InterruptFrame(
                activeGoal,
                List.copyOf(path),
                pathIndex,
                from,
                to,
                tickCounter
        );
        interruptStack.push(frame);
        paused = true;
        edgeTracker.resetEdge();
        return Optional.of(frame);
    }

    /**
     * Resumes the most recent paused task from {@link #interruptStack}.
     * Validates player position against the snapshotted edge: continues the edge if within tolerance,
     * otherwise triggers a local replan.
     */
    public ResumeOutcome resume(double playerX, double playerY, double playerZ) {
        if (interruptStack.isEmpty()) {
            paused = false;
            return ResumeOutcome.NONE;
        }
        InterruptFrame frame = interruptStack.pop();
        this.activeGoal = frame.goal();
        this.paused = false;
        this.lastFailureReason = null;

        if (isGoalSatisfied(activeGoal, playerX, playerY, playerZ)) {
            this.path = List.of();
            this.pathIndex = 0;
            this.status = EngineStatus.DONE;
            edgeTracker.resetEdge();
            return ResumeOutcome.CONTINUE_EDGE;
        }

        if (!frame.path().isEmpty()
                && frame.pathIndex() >= 0
                && frame.pathIndex() < frame.path().size()
                && isNearEdge(playerX, playerY, playerZ, frame.edgeFrom(), frame.edgeTo(), resumeMaxDrift)) {
            this.path = frame.path();
            this.pathIndex = frame.pathIndex();
            this.edgeFrom = frame.edgeFrom() != null ? frame.edgeFrom() : BlockPos.containing(playerX, playerY, playerZ);
            this.status = EngineStatus.MOVING;
            PathNode cur = path.get(pathIndex);
            edgeTracker.beginEdge(this.edgeFrom, cur.pos(), playerX, playerY, playerZ, tickCounter, cur.timeoutTicks());
            return ResumeOutcome.CONTINUE_EDGE;
        }

        BlockPos playerBlock = BlockPos.containing(playerX, playerY, playerZ);
        if (asyncReplanMode) {
            this.path = List.of();
            this.pathIndex = 0;
            this.edgeFrom = playerBlock;
            this.status = EngineStatus.PLANNING;
            edgeTracker.resetEdge();
            return ResumeOutcome.NEEDS_REPLAN;
        }
        if (planFrom(playerBlock)) {
            return ResumeOutcome.REPLANNED;
        }
        return ResumeOutcome.FAILED;
    }

    /** Convenience overload for block-aligned callers and tests. */
    public ResumeOutcome resume(BlockPos playerPos) {
        Objects.requireNonNull(playerPos, "playerPos");
        return resume(playerPos.getX() + 0.5, playerPos.getY(), playerPos.getZ() + 0.5);
    }

    /** Cancels any active and paused goals, returning to {@link EngineStatus#IDLE}. */
    public void cancel() {
        interruptStack.clear();
        activeGoal = null;
        path = List.of();
        pathIndex = 0;
        edgeFrom = null;
        paused = false;
        replanCount = 0;
        lastFailureReason = null;
        status = EngineStatus.IDLE;
        edgeTracker.resetEdge();
    }

    /**
     * Advances the coordinator state machine by one tick.
     *
     * @param playerX current player X
     * @param playerY current player Y
     * @param playerZ current player Z
     * @return the {@link TickAction} for the executor
     */
    public TickAction tick(double playerX, double playerY, double playerZ) {
        tickCounter++;
        edgeTracker.pruneExpired(tickCounter);

        if (paused || activeGoal == null || status != EngineStatus.MOVING) {
            return new TickAction.Idle();
        }

        if (canShortCircuitGoal(activeGoal, pathIndex)
                && isGoalSatisfied(activeGoal, playerX, playerY, playerZ)) {
            completeGoal();
            return new TickAction.Done();
        }

        if (path.isEmpty() || pathIndex >= path.size()) {
            return triggerReplan(playerX, playerY, playerZ, "End of path reached before goal");
        }

        boolean replanned = false;
        PathNode target = path.get(pathIndex);
        if (isNodeReached(playerX, playerY, playerZ, target)) {
            this.replanCount = 0;
            edgeFrom = target.pos();
            pathIndex++;
            if (pathIndex >= path.size()) {
                if (isGoalSatisfied(activeGoal, playerX, playerY, playerZ)) {
                    completeGoal();
                    return new TickAction.Done();
                }
                return triggerReplan(playerX, playerY, playerZ, "Reached final waypoint without satisfying goal");
            }
            target = path.get(pathIndex);
            edgeTracker.beginEdge(edgeFrom, target.pos(), playerX, playerY, playerZ, tickCounter, target.timeoutTicks());
        }

        boolean breaking = isBreakingTargetBlock(target);
        if (breaking) {
            edgeTracker.resetNoProgress();
        }

        EdgeCheckResult check = edgeTracker.update(playerX, playerY, playerZ, tickCounter);
        if (breaking) {
            edgeTracker.resetNoProgress();
            if (check == EdgeCheckResult.STUCK) {
                check = EdgeCheckResult.OK;
            }
        }
        if (check == EdgeCheckResult.STUCK || check == EdgeCheckResult.TIMED_OUT) {
            BlockPos from = edgeFrom != null ? edgeFrom : BlockPos.containing(playerX, playerY, playerZ);
            edgeTracker.blacklist(new EdgeKey(from, target.pos()), tickCounter);
            edgeTracker.resetEdge();

            String reason = check == EdgeCheckResult.STUCK
                    ? "Stuck on edge " + from.toShortString() + " -> " + target.pos().toShortString()
                    : "Timed out on edge " + from.toShortString() + " -> " + target.pos().toShortString();

            TickAction replanAction = triggerReplan(playerX, playerY, playerZ, reason);
            if (replanAction instanceof TickAction.MoveTo m) {
                return new TickAction.MoveTo(m.node(), true);
            }
            return replanAction;
        }

        return new TickAction.MoveTo(target, replanned);
    }

    private TickAction triggerReplan(double playerX, double playerY, double playerZ, String reason) {
        replanCount++;
        LOGGER.info("[MidtownCoordinator] replan #{}: {} (player {} {} {})", replanCount, reason,
                String.format("%.2f", playerX), String.format("%.2f", playerY), String.format("%.2f", playerZ));
        if (replanCount > maxReplans) {
            status = EngineStatus.FAILED;
            lastFailureReason = "Exceeded max replans (" + maxReplans + "): " + reason;
            edgeTracker.resetEdge();
            return new TickAction.Failed(lastFailureReason);
        }
        BlockPos start = BlockPos.containing(playerX, playerY, playerZ);
        if (asyncReplanMode) {
            status = EngineStatus.PLANNING;
            edgeTracker.resetEdge();
            return new TickAction.NeedsReplan(activeGoal, start, reason);
        }
        status = EngineStatus.STUCK;
        if (planFrom(start)) {
            return new TickAction.MoveTo(path.get(pathIndex), true);
        }
        lastFailureReason = reason + " (replan produced no path)";
        return new TickAction.Failed(lastFailureReason);
    }

    /**
     * Applies an asynchronously replanned path while preserving {@code replanCount},
     * or marks the coordinator {@link EngineStatus#FAILED} if {@code planned} is empty.
     */
    public boolean applyReplannedPath(BlockPos start, List<PathNode> planned) {
        Objects.requireNonNull(start, "start");
        if (activeGoal == null) {
            return false;
        }
        this.paused = false;
        this.lastFailureReason = null;
        return applyPlannedPath(start, planned);
    }

    /**
     * Requests an async replan triggered by rolling lookahead validation while {@link EngineStatus#MOVING}.
     * Increments {@code replanCount} against {@code maxReplans}; transitions {@code MOVING -> PLANNING}
     * and returns {@code true} if allowed, or marks {@link EngineStatus#FAILED} and returns {@code false}
     * if {@code maxReplans} is exceeded.
     */
    public boolean requestLookaheadReplan(String reason) {
        if (paused || activeGoal == null || status != EngineStatus.MOVING) {
            return false;
        }
        replanCount++;
        LOGGER.info("[MidtownCoordinator] lookahead replan #{}: {}", replanCount, reason);
        if (replanCount > maxReplans) {
            status = EngineStatus.FAILED;
            lastFailureReason = "Exceeded max replans (" + maxReplans + "): " + reason;
            edgeTracker.resetEdge();
            return false;
        }
        status = EngineStatus.PLANNING;
        edgeTracker.resetEdge();
        return true;
    }

    private boolean planFrom(BlockPos start) {
        status = EngineStatus.PLANNING;
        List<PathNode> planned = planner.plan(
                activeGoal,
                start,
                edge -> edgeTracker.isBlacklisted(edge, tickCounter));
        return applyPlannedPath(start, planned);
    }

    private boolean applyPlannedPath(BlockPos start, List<PathNode> planned) {
        if (planned == null || planned.isEmpty()) {
            this.path = List.of();
            this.pathIndex = 0;
            this.status = EngineStatus.FAILED;
            this.lastFailureReason = "Planner returned no path from " + start.toShortString();
            edgeTracker.resetEdge();
            return false;
        }
        this.path = List.copyOf(planned);
        this.pathIndex = 0;
        this.edgeFrom = start;
        this.status = EngineStatus.MOVING;
        PathNode first = this.path.get(0);
        edgeTracker.beginEdge(
                start,
                first.pos(),
                start.getX() + 0.5,
                start.getY(),
                start.getZ() + 0.5,
                tickCounter,
                first.timeoutTicks());
        return true;
    }

    /** Marks the coordinator as {@link EngineStatus#PLANNING} while an async plan is in flight. */
    public void markPlanning(GoalSpec goal) {
        this.activeGoal = Objects.requireNonNull(goal, "goal");
        this.paused = false;
        this.replanCount = 0;
        this.lastFailureReason = null;
        this.path = List.of();
        this.pathIndex = 0;
        this.status = EngineStatus.PLANNING;
        edgeTracker.resetEdge();
    }

    /** Marks the coordinator as {@link EngineStatus#FAILED} with a reason. */
    public void markFailed(String reason) {
        this.path = List.of();
        this.pathIndex = 0;
        this.status = EngineStatus.FAILED;
        this.lastFailureReason = reason;
        edgeTracker.resetEdge();
    }

    private void completeGoal() {
        status = EngineStatus.DONE;
        activeGoal = null;
        path = List.of();
        pathIndex = 0;
        edgeFrom = null;
        edgeTracker.resetEdge();
    }

    // ------------------------------------------------------------------ geometry & predicates

    public boolean isNodeReached(double px, double py, double pz, PathNode target) {
        if (!isNodeReached(px, py, pz, target.pos())) {
            return false;
        }
        if (target.action() == PathNode.ActionType.PILLAR) {
            if (py < target.pos().getY() - 0.15) {
                return false;
            }
            if (isBlockSolid != null && target.targetBlock() != null && !isBlockSolid.test(target.targetBlock())) {
                return false;
            }
        } else if (isBreakingTargetBlock(target)) {
            return false;
        }
        return true;
    }

    private boolean canShortCircuitGoal(GoalSpec goal, int idx) {
        if (goal instanceof GoalSpec.LineOfSight && idx < path.size()) {
            return false;
        }
        return !hasActiveBlockAction(idx);
    }

    private boolean hasActiveBlockAction(int fromIndex) {
        for (int i = fromIndex; i < path.size(); i++) {
            PathNode node = path.get(i);
            if (isBreakingTargetBlock(node)) {
                return true;
            }
            if (isBlockPresent == null
                    && isBlockSolid == null
                    && node.action() == PathNode.ActionType.BREAK_THROUGH
                    && !node.targetBlocks().isEmpty()) {
                return true;
            }
        }
        if (fromIndex >= 0 && fromIndex < path.size()) {
            PathNode cur = path.get(fromIndex);
            if (cur.action() == PathNode.ActionType.PILLAR
                    && isBlockSolid != null
                    && cur.targetBlock() != null
                    && !isBlockSolid.test(cur.targetBlock())) {
                return true;
            }
        }
        return false;
    }

    private boolean isBreakingTargetBlock(PathNode target) {
        if (target.action() != PathNode.ActionType.BREAK_THROUGH) {
            return false;
        }
        Predicate<BlockPos> check = isBlockPresent != null ? isBlockPresent : isBlockSolid;
        if (check == null) {
            return false;
        }
        for (BlockPos tb : target.targetBlocks()) {
            if (check.test(tb)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isNodeReached(double px, double py, double pz, BlockPos nodePos) {
        double dx = px - (nodePos.getX() + 0.5);
        double dz = pz - (nodePos.getZ() + 0.5);
        double dy = Math.abs(py - nodePos.getY());
        return (dx * dx + dz * dz) <= (DEFAULT_NODE_REACH_HORIZ * DEFAULT_NODE_REACH_HORIZ)
                && dy <= DEFAULT_NODE_REACH_VERT;
    }

    public static boolean isGoalSatisfied(GoalSpec goal, double px, double py, double pz) {
        return switch (goal) {
            case GoalSpec.Exact e -> {
                double dx = px - (e.pos().getX() + 0.5);
                double dz = pz - (e.pos().getZ() + 0.5);
                double dy = Math.abs(py - e.pos().getY());
                yield (dx * dx + dz * dz) <= 0.25 && dy <= 0.65;
            }
            case GoalSpec.Near n -> {
                int bx = Mth.floor(px);
                int by = (int) Math.round(py);
                int bz = Mth.floor(pz);
                long bdx = (long) bx - n.pos().getX();
                long bdy = (long) by - n.pos().getY();
                long bdz = (long) bz - n.pos().getZ();
                long blockDistSq = bdx * bdx + bdy * bdy + bdz * bdz;
                long rangeSq = (long) n.range() * n.range();
                if (blockDistSq > rangeSq) {
                    yield false;
                }
                double dx = px - (n.pos().getX() + 0.5);
                double dy = py - n.pos().getY();
                double dz = pz - (n.pos().getZ() + 0.5);
                double r = n.range() + 0.5;
                yield (dx * dx + dy * dy + dz * dz) <= r * r;
            }
            case GoalSpec.XZ xz -> {
                double dx = px - (xz.x() + 0.5);
                double dz = pz - (xz.z() + 0.5);
                yield (dx * dx + dz * dz) <= 0.36;
            }
            case GoalSpec.LineOfSight los -> {
                double dx = px - (los.target().getX() + 0.5);
                double dy = py - los.target().getY();
                double dz = pz - (los.target().getZ() + 0.5);
                yield (dx * dx + dy * dy + dz * dz) <= los.maxRange() * los.maxRange();
            }
            case GoalSpec.RunAway ra -> {
                double dx = px - (ra.from().getX() + 0.5);
                double dy = py - ra.from().getY();
                double dz = pz - (ra.from().getZ() + 0.5);
                yield (dx * dx + dy * dy + dz * dz) >= ra.distance() * ra.distance();
            }
        };
    }

    private static boolean isNearEdge(double px, double py, double pz, BlockPos from, BlockPos to, double maxDrift) {
        double maxSq = maxDrift * maxDrift;
        if (from != null && distSq(px, py, pz, from) <= maxSq) return true;
        if (to != null && distSq(px, py, pz, to) <= maxSq) return true;
        return false;
    }

    private static double distSq(double px, double py, double pz, BlockPos pos) {
        double dx = px - (pos.getX() + 0.5);
        double dy = py - pos.getY();
        double dz = pz - (pos.getZ() + 0.5);
        return dx * dx + dy * dy + dz * dz;
    }

    // ------------------------------------------------------------------ inspection & config

    public EngineStatus status() {
        return status;
    }

    public GoalSpec activeGoal() {
        return activeGoal;
    }

    public List<PathNode> path() {
        return path;
    }

    public int pathIndex() {
        return pathIndex;
    }

    public PathNode currentTarget() {
        return (pathIndex >= 0 && pathIndex < path.size()) ? path.get(pathIndex) : null;
    }

    public boolean isPaused() {
        return paused;
    }

    public boolean isAsyncReplanMode() {
        return asyncReplanMode;
    }

    public void setAsyncReplanMode(boolean asyncReplanMode) {
        this.asyncReplanMode = asyncReplanMode;
    }

    public int interruptStackSize() {
        return interruptStack.size();
    }

    public long tickCounter() {
        return tickCounter;
    }

    public int replanCount() {
        return replanCount;
    }

    public String lastFailureReason() {
        return lastFailureReason;
    }

    public int maxReplans() {
        return maxReplans;
    }

    public void setMaxReplans(int maxReplans) {
        if (maxReplans < 0) throw new IllegalArgumentException("maxReplans must be >= 0");
        this.maxReplans = maxReplans;
    }

    public double resumeMaxDrift() {
        return resumeMaxDrift;
    }

    public void setResumeMaxDrift(double resumeMaxDrift) {
        if (resumeMaxDrift <= 0) throw new IllegalArgumentException("resumeMaxDrift must be > 0");
        this.resumeMaxDrift = resumeMaxDrift;
    }
}
