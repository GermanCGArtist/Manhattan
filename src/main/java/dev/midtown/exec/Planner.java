package dev.midtown.exec;

import dev.midtown.api.GoalSpec;
import dev.midtown.exec.PathNode.EdgeKey;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Pluggable path planner interface used by {@link MidtownEngine} and {@link MidtownCoordinator}.
 * <p>
 * Phase 1 ships with {@link DirectStepPlanner} for local step-by-step navigation; Phase 2 plugs in the
 * 3D A* {@code MidtownPlanner}.
 */
@FunctionalInterface
public interface Planner {

    /**
     * Computes a sequence of {@link PathNode}s from {@code start} toward {@code goal}.
     *
     * @param goal          target goal specification
     * @param start         starting block position (player feet)
     * @param isBlacklisted predicate returning true if a directed edge is temporarily blacklisted
     * @return non-null list of waypoints (empty if already at goal or no path found)
     */
    List<PathNode> plan(GoalSpec goal, BlockPos start, Predicate<EdgeKey> isBlacklisted);

    /**
     * Simple local step-by-step fallback planner for Phase 1.
     * Steps 1 block at a time along the cardinal/diagonal direction toward the goal target,
     * avoiding blacklisted edges when an alternate step is available.
     */
    final class DirectStepPlanner implements Planner {

        private static final int MAX_STEPS = 256;

        @Override
        public List<PathNode> plan(GoalSpec goal, BlockPos start, Predicate<EdgeKey> isBlacklisted) {
            BlockPos target = resolveTarget(goal, start);
            if (target == null || start.equals(target)) {
                return List.of();
            }

            List<PathNode> nodes = new ArrayList<>();
            BlockPos cur = start;
            for (int step = 0; step < MAX_STEPS && !cur.equals(target); step++) {
                int dx = Integer.compare(target.getX(), cur.getX());
                int dy = Integer.compare(target.getY(), cur.getY());
                int dz = Integer.compare(target.getZ(), cur.getZ());

                BlockPos next = pickNext(cur, dx, dy, dz, isBlacklisted);
                if (next == null) {
                    break;
                }
                PathNode.ActionType action = dy > 0 ? PathNode.ActionType.ASCEND
                        : (dy < 0 ? PathNode.ActionType.DESCEND : PathNode.ActionType.WALK);
                nodes.add(new PathNode(next, action, List.of(), dy == 0, false, 0));
                cur = next;
            }
            return nodes;
        }

        private static BlockPos pickNext(BlockPos cur, int dx, int dy, int dz, Predicate<EdgeKey> isBlacklisted) {
            BlockPos primary = cur.offset(dx, dy, dz);
            if (isBlacklisted == null || !isBlacklisted.test(new EdgeKey(cur, primary))) {
                return primary;
            }
            // Try axis-aligned alternatives if diagonal or primary is blacklisted
            if (dx != 0) {
                BlockPos altX = cur.offset(dx, dy, 0);
                if (!isBlacklisted.test(new EdgeKey(cur, altX))) return altX;
            }
            if (dz != 0) {
                BlockPos altZ = cur.offset(0, dy, dz);
                if (!isBlacklisted.test(new EdgeKey(cur, altZ))) return altZ;
            }
            // Sidestep perpendicular to primary direction
            int perpX = dz != 0 ? 1 : 0;
            int perpZ = dx != 0 ? 1 : 0;
            BlockPos sideA = cur.offset(perpX, 0, perpZ);
            if (!sideA.equals(cur) && !isBlacklisted.test(new EdgeKey(cur, sideA))) return sideA;
            BlockPos sideB = cur.offset(-perpX, 0, -perpZ);
            if (!sideB.equals(cur) && !isBlacklisted.test(new EdgeKey(cur, sideB))) return sideB;
            return null;
        }

        private static BlockPos resolveTarget(GoalSpec goal, BlockPos start) {
            return switch (goal) {
                case GoalSpec.Exact e -> e.pos();
                case GoalSpec.Near n -> n.pos();
                case GoalSpec.XZ xz -> new BlockPos(xz.x(), start.getY(), xz.z());
                case GoalSpec.LineOfSight los -> los.target();
                case GoalSpec.RunAway ra -> {
                    int dx = start.getX() - ra.from().getX();
                    int dz = start.getZ() - ra.from().getZ();
                    double len = Math.hypot(dx, dz);
                    int stepX = len < 1e-3 ? 1 : (int) Math.round((dx / len) * ra.distance());
                    int stepZ = len < 1e-3 ? 0 : (int) Math.round((dz / len) * ra.distance());
                    yield start.offset(stepX, 0, stepZ);
                }
            };
        }
    }
}
