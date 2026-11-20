package dev.midtown.plan;

import dev.midtown.api.BlockFilter;

/**
 * Thread-safe, immutable read-only view of blocks in a 3D world.
 * <p>
 * Implemented by {@link WorldSnapshot} (wrapping copied Minecraft {@code LevelChunk} sections)
 * and {@link GridWorldSnapshot} (pure-Java synthetic grid for offline unit tests).
 */
public interface BlockView {

    /** Minimum valid Y coordinate (inclusive). */
    int minY();

    /** Maximum valid Y coordinate (exclusive, matching {@code LevelHeightAccessor.getMaxY()}). */
    int maxY();

    /** Returns true if {@code (x, y, z)} lies within loaded chunks/bounds and valid Y range. */
    boolean isLoaded(int x, int y, int z);

    /** Returns the {@link BlockKind} at {@code (x, y, z)}, or {@link BlockKind#AIR} if unloaded/out of Y bounds. */
    BlockKind kindAt(int x, int y, int z);

    /**
     * Estimated ticks required to break the block at {@code (x, y, z)} given {@code ctx}.
     * Returns {@code 0} if already passable/air, or {@code < 0} if unbreakable.
     */
    int breakTicks(int x, int y, int z, CalculationContext ctx);

    /** Returns true if the block at {@code (x, y, z)} matches {@code filter}. */
    default boolean matchesFilter(int x, int y, int z, BlockFilter filter) {
        return false;
    }

    default boolean isInYBounds(int y) {
        return y >= minY() && y < maxY();
    }

    default boolean isAir(int x, int y, int z) {
        return isLoaded(x, y, z) && kindAt(x, y, z) == BlockKind.AIR;
    }

    default boolean isPassable(int x, int y, int z) {
        return isLoaded(x, y, z) && kindAt(x, y, z).isPassable();
    }

    default boolean hasSolidCollision(int x, int y, int z) {
        return isLoaded(x, y, z) && kindAt(x, y, z).hasSolidCollision();
    }

    default boolean canStandOn(int x, int y, int z) {
        return isLoaded(x, y, z) && kindAt(x, y, z).canStandOn();
    }

    default boolean isReplaceable(int x, int y, int z) {
        return isLoaded(x, y, z) && kindAt(x, y, z).isReplaceable();
    }

    default boolean isWater(int x, int y, int z) {
        return isLoaded(x, y, z) && kindAt(x, y, z) == BlockKind.WATER;
    }

    default boolean isLava(int x, int y, int z) {
        return isLoaded(x, y, z) && kindAt(x, y, z) == BlockKind.LAVA;
    }

    default boolean isFallingBlock(int x, int y, int z) {
        return isLoaded(x, y, z) && kindAt(x, y, z) == BlockKind.GRAVEL_OR_SAND;
    }

    /** True if a solid non-foliage roof sits overhead within {@code maxScan} blocks starting at {@code fromY}. */
    default boolean isCaveCovered(int x, int fromY, int z, int maxScan) {
        int top = Math.min(maxY(), fromY + maxScan);
        for (int cy = fromY; cy < top; cy++) {
            if (!isLoaded(x, cy, z)) return false;
            if (hasSolidCollision(x, cy, z)) return true;
        }
        return false;
    }
}
