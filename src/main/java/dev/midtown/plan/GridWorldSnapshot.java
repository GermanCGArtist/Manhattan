package dev.midtown.plan;

import dev.midtown.api.Area;
import dev.midtown.api.BlockFilter;
import net.minecraft.core.BlockPos;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Pure-Java immutable 3D grid implementing {@link BlockView}.
 * <p>
 * Designed so offline unit tests can exercise {@link MidtownPlanner}, {@link MoveEdge}s,
 * {@link HazardCost}, and {@link CalculationContext} without booting Minecraft.
 */
public final class GridWorldSnapshot implements BlockView {

    private final int minX;
    private final int minY;
    private final int minZ;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final BlockKind[] kinds;
    private final int[] customBreakTicks;
    private final Map<Long, String> blockIds;

    private GridWorldSnapshot(
            int minX, int minY, int minZ,
            int sizeX, int sizeY, int sizeZ,
            BlockKind[] kinds,
            int[] customBreakTicks,
            Map<Long, String> blockIds) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.kinds = kinds;
        this.customBreakTicks = customBreakTicks;
        this.blockIds = blockIds;
    }

    /** Creates a builder for an inclusive box {@code [minX..maxX, minY..maxY, minZ..maxZ]}. */
    public static Builder builder(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return new Builder(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** Creates a builder covering {@code area} (inclusive). */
    public static Builder builder(Area area) {
        Objects.requireNonNull(area, "area");
        return new Builder(area.minX(), area.minY(), area.minZ(), area.maxX(), area.maxY(), area.maxZ());
    }

    @Override
    public int minY() {
        return minY;
    }

    @Override
    public int maxY() {
        return minY + sizeY;
    }

    public int minX() {
        return minX;
    }

    public int maxXInclusive() {
        return minX + sizeX - 1;
    }

    public int minZ() {
        return minZ;
    }

    public int maxZInclusive() {
        return minZ + sizeZ - 1;
    }

    @Override
    public boolean isLoaded(int x, int y, int z) {
        return x >= minX && x < minX + sizeX
                && y >= minY && y < minY + sizeY
                && z >= minZ && z < minZ + sizeZ;
    }

    @Override
    public BlockKind kindAt(int x, int y, int z) {
        if (!isLoaded(x, y, z)) return BlockKind.AIR;
        return kinds[index(x, y, z)];
    }

    @Override
    public int breakTicks(int x, int y, int z, CalculationContext ctx) {
        if (!isLoaded(x, y, z)) return -1;
        int idx = index(x, y, z);
        int custom = customBreakTicks[idx];
        if (custom != Integer.MIN_VALUE) {
            return custom;
        }
        return ctx.defaultBreakTicks(kinds[idx]);
    }

    @Override
    public boolean matchesFilter(int x, int y, int z, BlockFilter filter) {
        if (filter == null || !isLoaded(x, y, z)) return false;
        String id = blockIds.get(BlockPos.asLong(x, y, z));
        if (id == null) return false;
        for (String candidate : filter.blockIds()) {
            if (candidate.equals(id) || candidate.equals("minecraft:" + id)
                    || ("minecraft:" + candidate).equals(id)) {
                return true;
            }
        }
        return false;
    }

    private int index(int x, int y, int z) {
        return ((y - minY) * sizeZ + (z - minZ)) * sizeX + (x - minX);
    }

    /** Mutable builder for constructing an immutable {@link GridWorldSnapshot}. */
    public static final class Builder {
        private final int minX;
        private final int minY;
        private final int minZ;
        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;
        private final BlockKind[] kinds;
        private final int[] customBreakTicks;
        private final Map<Long, String> blockIds = new HashMap<>();

        private Builder(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            if (minX > maxX || minY > maxY || minZ > maxZ) {
                throw new IllegalArgumentException("Inverted GridWorldSnapshot bounds");
            }
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.sizeX = maxX - minX + 1;
            this.sizeY = maxY - minY + 1;
            this.sizeZ = maxZ - minZ + 1;
            long total = (long) sizeX * sizeY * sizeZ;
            if (total > 4_000_000L) {
                throw new IllegalArgumentException("GridWorldSnapshot too large: " + total + " blocks");
            }
            this.kinds = new BlockKind[(int) total];
            Arrays.fill(this.kinds, BlockKind.AIR);
            this.customBreakTicks = new int[(int) total];
            Arrays.fill(this.customBreakTicks, Integer.MIN_VALUE);
        }

        public Builder set(int x, int y, int z, BlockKind kind) {
            Objects.requireNonNull(kind, "kind");
            checkBounds(x, y, z);
            int idx = index(x, y, z);
            kinds[idx] = kind;
            customBreakTicks[idx] = Integer.MIN_VALUE;
            return this;
        }

        public Builder set(int x, int y, int z, BlockKind kind, int breakTicks) {
            Objects.requireNonNull(kind, "kind");
            checkBounds(x, y, z);
            int idx = index(x, y, z);
            kinds[idx] = kind;
            customBreakTicks[idx] = breakTicks;
            return this;
        }

        public Builder setBlockId(int x, int y, int z, String blockId) {
            checkBounds(x, y, z);
            if (blockId == null) {
                blockIds.remove(BlockPos.asLong(x, y, z));
            } else {
                blockIds.put(BlockPos.asLong(x, y, z), blockId);
            }
            return this;
        }

        public Builder fillFloor(int y, BlockKind kind) {
            return fillBox(minX, y, minZ, minX + sizeX - 1, y, minZ + sizeZ - 1, kind);
        }

        public Builder fillBox(int x1, int y1, int z1, int x2, int y2, int z2, BlockKind kind) {
            Objects.requireNonNull(kind, "kind");
            int lx = Math.max(minX, Math.min(x1, x2));
            int hx = Math.min(minX + sizeX - 1, Math.max(x1, x2));
            int ly = Math.max(minY, Math.min(y1, y2));
            int hy = Math.min(minY + sizeY - 1, Math.max(y1, y2));
            int lz = Math.max(minZ, Math.min(z1, z2));
            int hz = Math.min(minZ + sizeZ - 1, Math.max(z1, z2));
            for (int y = ly; y <= hy; y++) {
                for (int z = lz; z <= hz; z++) {
                    for (int x = lx; x <= hx; x++) {
                        int idx = index(x, y, z);
                        kinds[idx] = kind;
                        customBreakTicks[idx] = Integer.MIN_VALUE;
                    }
                }
            }
            return this;
        }

        public GridWorldSnapshot build() {
            return new GridWorldSnapshot(
                    minX, minY, minZ,
                    sizeX, sizeY, sizeZ,
                    kinds.clone(),
                    customBreakTicks.clone(),
                    Map.copyOf(blockIds));
        }

        private void checkBounds(int x, int y, int z) {
            if (x < minX || x >= minX + sizeX
                    || y < minY || y >= minY + sizeY
                    || z < minZ || z >= minZ + sizeZ) {
                throw new IndexOutOfBoundsException(
                        "Position (" + x + "," + y + "," + z + ") outside grid bounds");
            }
        }

        private int index(int x, int y, int z) {
            return ((y - minY) * sizeZ + (z - minZ)) * sizeX + (x - minX);
        }
    }
}
