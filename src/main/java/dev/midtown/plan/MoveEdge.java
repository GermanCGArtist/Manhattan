package dev.midtown.plan;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Objects;

/**
 * Typed, sealed hierarchy of survival traversal edges produced by {@link MidtownPlanner}.
 * <p>
 * Every edge moves the player's feet from {@link #from()} to {@link #to()} with an estimated
 * tick {@link #cost()}.
 */
public sealed interface MoveEdge permits
        MoveEdge.WalkEdge,
        MoveEdge.AscendEdge,
        MoveEdge.DescendEdge,
        MoveEdge.FallEdge,
        MoveEdge.BreakThroughEdge,
        MoveEdge.BridgeEdge,
        MoveEdge.PillarEdge,
        MoveEdge.ParkourEdge {

    BlockPos from();

    BlockPos to();

    double cost();

    /** Deterministic 64-bit key identifying this directed {@code from -> to} transition for blacklisting. */
    default long edgeKey() {
        BlockPos f = from();
        BlockPos t = to();
        return CalculationContext.packEdgeKey(
                f.getX(), f.getY(), f.getZ(),
                t.getX(), t.getY(), t.getZ());
    }

    /** Blocks that must be broken before/during this edge (empty unless {@link BreakThroughEdge}). */
    default List<BlockPos> blocksToBreak() {
        return List.of();
    }

    /** Expendable block placement target, or {@code null} if this edge places no block. */
    default BlockPos blockToPlace() {
        return null;
    }

    /** Number of expendable inventory blocks consumed by executing this edge. */
    default int blocksConsumed() {
        return blockToPlace() != null ? 1 : 0;
    }

    /** Flat cardinal or diagonal step with 2-block headroom. */
    record WalkEdge(BlockPos from, BlockPos to, double cost, boolean diagonal) implements MoveEdge {
        public WalkEdge {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
        }
    }

    /** 1-block step up (jump + forward), checking overhead jump clearance. */
    record AscendEdge(BlockPos from, BlockPos to, double cost) implements MoveEdge {
        public AscendEdge {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
        }
    }

    /** 1-block step down, checking step-off headroom and safe landing. */
    record DescendEdge(BlockPos from, BlockPos to, double cost) implements MoveEdge {
        public DescendEdge {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
        }
    }

    /** Vertical drop of 2+ blocks up to {@code maxSafeFall}, or any drop into water. */
    record FallEdge(
            BlockPos from,
            BlockPos to,
            double cost,
            int fallDistance,
            boolean waterLanding) implements MoveEdge {
        public FallEdge {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            if (fallDistance < 2) {
                throw new IllegalArgumentException("FallEdge fallDistance must be >= 2: " + fallDistance);
            }
        }
    }

    /** Digging through 1-3 blocking blocks (1-2 flat, up to 3 for stair ascend/descend); cost includes break ticks + adjacency penalties. */
    record BreakThroughEdge(
            BlockPos from,
            BlockPos to,
            double cost,
            List<BlockPos> blocksToBreak) implements MoveEdge {
        public BreakThroughEdge {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(blocksToBreak, "blocksToBreak");
            if (blocksToBreak.isEmpty() || blocksToBreak.size() > 3) {
                throw new IllegalArgumentException(
                        "BreakThroughEdge must break 1-3 blocks, got: " + blocksToBreak.size());
            }
            blocksToBreak = List.copyOf(blocksToBreak);
        }
    }

    /** Horizontal bridge placement at {@code placePos} (beneath {@code to}), gated on expendable blocks. */
    record BridgeEdge(
            BlockPos from,
            BlockPos to,
            double cost,
            BlockPos placePos) implements MoveEdge {
        public BridgeEdge {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(placePos, "placePos");
        }

        @Override
        public BlockPos blockToPlace() {
            return placePos;
        }
    }

    /** Vertical 1-block jump-place pillar at {@code placePos} ({@code from}), gated on expendable blocks. */
    record PillarEdge(
            BlockPos from,
            BlockPos to,
            double cost,
            BlockPos placePos) implements MoveEdge {
        public PillarEdge {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(placePos, "placePos");
        }

        @Override
        public BlockPos blockToPlace() {
            return placePos;
        }
    }

    /** Horizontal 1-3 block gap jump (optionally landing with a parkour-place block). */
    record ParkourEdge(
            BlockPos from,
            BlockPos to,
            double cost,
            int gapDistance,
            boolean needsBridgePlace) implements MoveEdge {
        public ParkourEdge {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            if (gapDistance < 1 || gapDistance > 3) {
                throw new IllegalArgumentException("ParkourEdge gapDistance must be 1-3: " + gapDistance);
            }
        }

        @Override
        public BlockPos blockToPlace() {
            return needsBridgePlace ? to.below() : null;
        }
    }
}
