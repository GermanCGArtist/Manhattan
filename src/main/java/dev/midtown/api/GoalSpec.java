package dev.midtown.api;

import net.minecraft.core.BlockPos;

import java.util.Objects;

/**
 * Target specification for {@link PathEngine#goTo(GoalSpec)}.
 */
public sealed interface GoalSpec
        permits GoalSpec.Exact, GoalSpec.Near, GoalSpec.XZ, GoalSpec.LineOfSight, GoalSpec.RunAway {

    /** Stand on the exact block position. */
    record Exact(BlockPos pos) implements GoalSpec {
        public Exact {
            Objects.requireNonNull(pos, "pos");
        }
    }

    /** Stand within {@code range} blocks (Chebyshev/Euclidean per engine) of {@code pos}. */
    record Near(BlockPos pos, int range) implements GoalSpec {
        public Near {
            Objects.requireNonNull(pos, "pos");
            if (range < 0) throw new IllegalArgumentException("range must be >= 0: " + range);
        }
    }

    /** Reach the horizontal (X, Z) column regardless of Y. */
    record XZ(int x, int z) implements GoalSpec {}

    /** Stand within {@code maxRange} blocks of {@code target} with clear line of sight. */
    record LineOfSight(BlockPos target, double maxRange) implements GoalSpec {
        public LineOfSight {
            Objects.requireNonNull(target, "target");
            if (maxRange <= 0) throw new IllegalArgumentException("maxRange must be > 0: " + maxRange);
        }
    }

    /** Move at least {@code distance} blocks away from {@code from}. */
    record RunAway(BlockPos from, double distance) implements GoalSpec {
        public RunAway {
            Objects.requireNonNull(from, "from");
            if (distance <= 0) throw new IllegalArgumentException("distance must be > 0: " + distance);
        }
    }

    static Exact exact(BlockPos pos) {
        return new Exact(pos);
    }

    static Near near(BlockPos pos, int range) {
        return new Near(pos, range);
    }

    static XZ xz(int x, int z) {
        return new XZ(x, z);
    }

    static LineOfSight lineOfSight(BlockPos target, double maxRange) {
        return new LineOfSight(target, maxRange);
    }

    static RunAway runAway(BlockPos from, double distance) {
        return new RunAway(from, distance);
    }
}
