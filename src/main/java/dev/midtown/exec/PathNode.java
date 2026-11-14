package dev.midtown.exec;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Objects;

/**
 * A single waypoint / traversal step in a planned path.
 *
 * @param pos          target feet block position
 * @param action       traversal action required to reach {@code pos}
 * @param targetBlocks optional blocks to break or place against (empty if none)
 * @param sprint       whether sprinting is permitted along this edge
 * @param sneak        whether sneaking is required along this edge
 * @param timeoutTicks maximum ticks allowed on this edge before timing out ({@code <= 0} uses coordinator default)
 */
public record PathNode(
        BlockPos pos,
        ActionType action,
        List<BlockPos> targetBlocks,
        boolean sprint,
        boolean sneak,
        int timeoutTicks
) {
    public PathNode {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(action, "action");
        targetBlocks = targetBlocks == null ? List.of() : List.copyOf(targetBlocks);
    }

    /** Backwards-compatible constructor for a single optional target block. */
    public PathNode(
            BlockPos pos,
            ActionType action,
            BlockPos targetBlock,
            boolean sprint,
            boolean sneak,
            int timeoutTicks) {
        this(pos, action, targetBlock == null ? List.of() : List.of(targetBlock), sprint, sneak, timeoutTicks);
    }

    /** First target block if present, otherwise {@code null}. */
    public BlockPos targetBlock() {
        return targetBlocks.isEmpty() ? null : targetBlocks.getFirst();
    }

    /** Convenience constructor for a standard walking waypoint. */
    public static PathNode walk(BlockPos pos) {
        return new PathNode(pos, ActionType.WALK, List.of(), true, false, 0);
    }

    /** Convenience constructor for an ascend (step-up) waypoint. */
    public static PathNode ascend(BlockPos pos) {
        return new PathNode(pos, ActionType.ASCEND, List.of(), false, false, 0);
    }

    /** Convenience constructor for a descend (step-down) waypoint. */
    public static PathNode descend(BlockPos pos) {
        return new PathNode(pos, ActionType.DESCEND, List.of(), false, false, 0);
    }

    /** Convenience constructor for a bridge/sneak waypoint. */
    public static PathNode bridge(BlockPos pos, BlockPos supportBlock) {
        return new PathNode(pos, ActionType.BRIDGE, supportBlock, false, true, 0);
    }

    /** Traversal primitive for a {@link PathNode}. */
    public enum ActionType {
        WALK,
        ASCEND,
        DESCEND,
        FALL,
        BREAK_THROUGH,
        BRIDGE,
        PILLAR,
        PARKOUR
    }

    /** Directed edge identifier between two block positions, used for temporary blacklisting. */
    public record EdgeKey(BlockPos from, BlockPos to) {
        public EdgeKey {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
        }
    }
}
