package dev.midtown.plan;

import dev.midtown.api.Area;
import dev.midtown.api.BlockFilter;
import dev.midtown.api.GoalSpec;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Goal termination predicates, admissible-ish 3D heuristics, voxel line-of-sight checks,
 * and mining target scanning for {@link MidtownPlanner}.
 */
public final class GoalAdapters {

    private static final double SQRT2 = Math.sqrt(2.0);

    private GoalAdapters() {}

    /** Internal A* goal evaluation contract (termination + tick-scaled heuristic). */
    public sealed interface GoalAdapter
            permits ExactGoal, NearGoal, XZGoal, LineOfSightGoal, RunAwayGoal, MineGoal {
        boolean isGoal(int x, int y, int z);
        double heuristic(int x, int y, int z, CalculationContext ctx);

        static GoalAdapter forGoal(BlockView world, GoalSpec goal) {
            return GoalAdapters.forGoal(world, goal);
        }
    }

    /** Adapts a public {@link GoalSpec} into a {@link GoalAdapter} over {@code world}. */
    public static GoalAdapter forGoal(BlockView world, GoalSpec goal) {
        return switch (goal) {
            case GoalSpec.Exact g -> new ExactGoal(g.pos());
            case GoalSpec.Near g -> new NearGoal(g.pos(), g.range());
            case GoalSpec.XZ g -> new XZGoal(g.x(), g.z());
            case GoalSpec.LineOfSight g -> new LineOfSightGoal(world, g.target(), g.maxRange());
            case GoalSpec.RunAway g -> new RunAwayGoal(g.from(), g.distance());
        };
    }

    public record ExactGoal(BlockPos pos) implements GoalAdapter {
        @Override
        public boolean isGoal(int x, int y, int z) {
            return x == pos.getX() && y == pos.getY() && z == pos.getZ();
        }

        @Override
        public double heuristic(int x, int y, int z, CalculationContext ctx) {
            return octile3D(x, y, z, pos.getX(), pos.getY(), pos.getZ(), ctx.horizontalTicksPerBlock());
        }
    }

    public record NearGoal(BlockPos pos, int range) implements GoalAdapter {
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

    public record XZGoal(int gx, int gz) implements GoalAdapter {
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

    public record LineOfSightGoal(BlockView world, BlockPos target, double maxRange) implements GoalAdapter {
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

    public record RunAwayGoal(BlockPos from, double distance) implements GoalAdapter {
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

    public record MineGoal(BlockView world, List<BlockPos> targets, double maxRange) implements GoalAdapter {
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

    /** 3D octile horizontal + asymmetric vertical tick-cost heuristic. */
    public static double octile3D(int x, int y, int z, int gx, int gy, int gz, double horizCost) {
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

    /** Scans {@code world} (optionally bounded by {@code box}) for breakable targets matching {@code filter}. */
    public static List<BlockPos> findMineTargets(
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
}
