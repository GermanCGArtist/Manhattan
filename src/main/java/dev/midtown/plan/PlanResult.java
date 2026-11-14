package dev.midtown.plan;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Objects;

/**
 * Result of a 3D A* path planning request from {@link MidtownPlanner}.
 */
public record PlanResult(
        Status status,
        BlockPos start,
        List<MoveEdge> edges,
        List<BlockPos> waypoints,
        double totalCost,
        int nodesExpanded,
        long elapsedMillis) {

    public enum Status {
        /** Reached a node satisfying the requested {@link dev.midtown.api.GoalSpec}. */
        COMPLETE,
        /** Time budget or node/chunk limit reached before goal; {@link #edges()} holds the best-so-far path. */
        PARTIAL,
        /** Search space exhausted with no reachable path to the goal. */
        NO_PATH,
        /** Planning request was cancelled before completion. */
        CANCELLED
    }

    public PlanResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(start, "start");
        edges = List.copyOf(edges);
        waypoints = List.copyOf(waypoints);
    }

    /** True if the goal was reached completely. */
    public boolean isComplete() {
        return status == Status.COMPLETE;
    }

    /** True if a best-so-far partial path was produced before hitting the time budget or boundary. */
    public boolean isPartial() {
        return status == Status.PARTIAL;
    }

    /** True if the plan has usable movement ({@link Status#COMPLETE} or non-empty {@link Status#PARTIAL}). */
    public boolean hasPath() {
        return status == Status.COMPLETE || (status == Status.PARTIAL && !edges.isEmpty());
    }

    /** Final destination block of the computed path (or {@link #start()} if empty). */
    public BlockPos endPos() {
        return waypoints.isEmpty() ? start : waypoints.get(waypoints.size() - 1);
    }
}
