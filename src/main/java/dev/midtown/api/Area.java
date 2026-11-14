package dev.midtown.api;

import net.minecraft.core.BlockPos;

/**
 * Axis-aligned inclusive 3D block bounding box.
 */
public record Area(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public Area {
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException(
                    "Inverted Area bounds: (" + minX + "," + minY + "," + minZ + ") > ("
                            + maxX + "," + maxY + "," + maxZ + ")");
        }
    }

    /** Creates a normalized {@link Area} from two arbitrary corner coordinates. */
    public static Area of(int x1, int y1, int z1, int x2, int y2, int z2) {
        return new Area(
                Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    /** Creates a normalized {@link Area} from two arbitrary corner {@link BlockPos} positions. */
    public static Area of(BlockPos a, BlockPos b) {
        return of(a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ());
    }

    /** Returns true if the given block coordinates lie inside this area (inclusive). */
    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    /** Returns true if {@code pos} lies inside this area (inclusive). */
    public boolean contains(BlockPos pos) {
        return contains(pos.getX(), pos.getY(), pos.getZ());
    }

    /** Returns true if {@code pos} is within {@code margin} blocks of this area on all axes. */
    public boolean within(BlockPos pos, int margin) {
        return pos.getX() >= minX - margin && pos.getX() <= maxX + margin
                && pos.getY() >= minY - margin && pos.getY() <= maxY + margin
                && pos.getZ() >= minZ - margin && pos.getZ() <= maxZ + margin;
    }

    /** Total number of blocks enclosed by this area. */
    public long volume() {
        return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
    }

    public BlockPos minPos() {
        return new BlockPos(minX, minY, minZ);
    }

    public BlockPos maxPos() {
        return new BlockPos(maxX, maxY, maxZ);
    }
}
