package dev.baritonequeue;

import dev.midtown.api.Area;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pure spatial chunking geometry: splits multi-box selections into full-height {@link ColumnBox}
 * columns and orders them using serpentine (boustrophedon) or nearest-first strategies.
 */
public final class ChunkGrid {
    private ChunkGrid() {}

    public enum OrderStrategy {
        SERPENTINE,
        NEAREST;

        public static OrderStrategy parse(String s) {
            if (s != null && s.trim().equalsIgnoreCase("nearest")) {
                return NEAREST;
            }
            return SERPENTINE;
        }
    }

    /** Full-height column slice of a selection, with sequential index and grid coordinates. */
    public record ColumnBox(int index, int gridX, int gridZ, BlockPos min, BlockPos max) {
        public int minX() { return min.getX(); }
        public int minY() { return min.getY(); }
        public int minZ() { return min.getZ(); }
        public int maxX() { return max.getX(); }
        public int maxY() { return max.getY(); }
        public int maxZ() { return max.getZ(); }

        public BlockPos centerXZ(int y) {
            return new BlockPos(Math.floorDiv(minX() + maxX(), 2), y, Math.floorDiv(minZ() + maxZ(), 2));
        }

        public boolean contains(BlockPos pos) {
            if (pos == null) return false;
            return pos.getX() >= minX() && pos.getX() <= maxX()
                    && pos.getY() >= minY() && pos.getY() <= maxY()
                    && pos.getZ() >= minZ() && pos.getZ() <= maxZ();
        }

        public boolean containsXZ(int x, int z) {
            return x >= minX() && x <= maxX() && z >= minZ() && z <= maxZ();
        }

        /** 0.0 if {@code from} is inside [minX..maxX, minZ..maxZ], else squared distance to nearest XZ edge. */
        public double distanceSqXZ(BlockPos from) {
            if (from == null) return 0.0;
            int fx = from.getX();
            int fz = from.getZ();
            double dx = fx < minX() ? (minX() - fx) : (fx > maxX() ? (fx - maxX()) : 0.0);
            double dz = fz < minZ() ? (minZ() - fz) : (fz > maxZ() ? (fz - maxZ()) : 0.0);
            return dx * dx + dz * dz;
        }

        public Area toMidtownArea() {
            return new Area(minX(), minY(), minZ(), maxX(), maxY(), maxZ());
        }

        /** Intersects each {@code int[]{x1,y1,z1,x2,y2,z2}} box with this column, returning non-empty boxes. */
        public List<int[]> clip(List<int[]> boxes) {
            if (boxes == null || boxes.isEmpty()) return List.of();
            List<int[]> out = new ArrayList<>();
            for (int[] b : boxes) {
                if (b == null || b.length < 6) continue;
                int bx0 = Math.min(b[0], b[3]);
                int by0 = Math.min(b[1], b[4]);
                int bz0 = Math.min(b[2], b[5]);
                int bx1 = Math.max(b[0], b[3]);
                int by1 = Math.max(b[1], b[4]);
                int bz1 = Math.max(b[2], b[5]);
                int ix0 = Math.max(minX(), bx0);
                int iy0 = Math.max(minY(), by0);
                int iz0 = Math.max(minZ(), bz0);
                int ix1 = Math.min(maxX(), bx1);
                int iy1 = Math.min(maxY(), by1);
                int iz1 = Math.min(maxZ(), bz1);
                if (ix0 <= ix1 && iy0 <= iy1 && iz0 <= iz1) {
                    out.add(new int[]{ix0, iy0, iz0, ix1, iy1, iz1});
                }
            }
            return out;
        }

        String key() {
            return minX() + "," + minZ();
        }

        String label() {
            return minX() + " " + minZ() + " .. " + maxX() + " " + maxZ();
        }
    }

    private static int[] unionBounds(List<int[]> boxes) {
        if (boxes == null || boxes.isEmpty()) return null;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        boolean any = false;
        for (int[] b : boxes) {
            if (b == null || b.length < 6) continue;
            any = true;
            minX = Math.min(minX, Math.min(b[0], b[3]));
            minY = Math.min(minY, Math.min(b[1], b[4]));
            minZ = Math.min(minZ, Math.min(b[2], b[5]));
            maxX = Math.max(maxX, Math.max(b[0], b[3]));
            maxY = Math.max(maxY, Math.max(b[1], b[4]));
            maxZ = Math.max(maxZ, Math.max(b[2], b[5]));
        }
        return any ? new int[]{minX, minY, minZ, maxX, maxY, maxZ} : null;
    }

    /**
     * Returns {@code true} iff {@code enabled && chunkSize > 0 && boxes != null && !boxes.isEmpty()}
     * and the union width or depth exceeds {@code chunkSize}.
     */
    public static boolean shouldChunk(List<int[]> boxes, int chunkSize, boolean enabled) {
        if (!enabled || chunkSize <= 0 || boxes == null || boxes.isEmpty()) return false;
        int[] u = unionBounds(boxes);
        if (u == null) return false;
        long spanX = (long) u[3] - u[0] + 1L;
        long spanZ = (long) u[5] - u[2] + 1L;
        return spanX > chunkSize || spanZ > chunkSize;
    }

    /**
     * Computes union [minX..maxX, minY..maxY, minZ..maxZ], slices X and Z by {@code chunkSize}
     * (preserving full [minY..maxY]), keeps only columns where {@code !col.clip(boxes).isEmpty()},
     * and assigns sequential {@code index} (0..N-1) and {@code (gridX, gridZ)}.
     */
    public static List<ColumnBox> split(List<int[]> boxes, int chunkSize) {
        if (boxes == null || boxes.isEmpty()) return List.of();
        if (chunkSize <= 0) throw new IllegalArgumentException("chunkSize must be > 0: " + chunkSize);
        int[] u = unionBounds(boxes);
        if (u == null) return List.of();
        int minX = u[0], minY = u[1], minZ = u[2];
        int maxX = u[3], maxY = u[4], maxZ = u[5];

        List<ColumnBox> out = new ArrayList<>();
        int idx = 0;
        for (int gx = 0, x = minX; x <= maxX; gx++) {
            long nextX = (long) x + chunkSize - 1L;
            int x1 = (int) Math.min(maxX, nextX);
            for (int gz = 0, z = minZ; z <= maxZ; gz++) {
                long nextZ = (long) z + chunkSize - 1L;
                int z1 = (int) Math.min(maxZ, nextZ);
                ColumnBox candidate = new ColumnBox(idx, gx, gz, new BlockPos(x, minY, z), new BlockPos(x1, maxY, z1));
                if (!candidate.clip(boxes).isEmpty()) {
                    out.add(candidate);
                    idx++;
                }
                if (z1 == maxZ) break;
                z = z1 + 1;
            }
            if (x1 == maxX) break;
            x = x1 + 1;
        }
        return out;
    }

    /**
     * Orders columns in boustrophedon (snake) order along grid rows, picking the starting corner
     * closest to {@code startPos} when {@code startPos != null}, and re-indexing 0..N-1.
     */
    public static List<ColumnBox> orderSerpentine(List<ColumnBox> chunks, BlockPos startPos) {
        if (chunks == null || chunks.isEmpty()) return List.of();

        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        Map<Integer, List<ColumnBox>> rowsByZ = new TreeMap<>();
        for (ColumnBox c : chunks) {
            minX = Math.min(minX, c.minX());
            minZ = Math.min(minZ, c.minZ());
            maxX = Math.max(maxX, c.maxX());
            maxZ = Math.max(maxZ, c.maxZ());
            rowsByZ.computeIfAbsent(c.gridZ(), k -> new ArrayList<>()).add(c);
        }

        boolean startMaxX = false;
        boolean startMaxZ = false;
        if (startPos != null) {
            startMaxX = Math.abs((long) startPos.getX() - maxX) < Math.abs((long) startPos.getX() - minX);
            startMaxZ = Math.abs((long) startPos.getZ() - maxZ) < Math.abs((long) startPos.getZ() - minZ);
        }

        List<Integer> zKeys = new ArrayList<>(rowsByZ.keySet());
        if (startMaxZ) {
            Collections.reverse(zKeys);
        }

        List<ColumnBox> out = new ArrayList<>(chunks.size());
        int idx = 0;
        for (int r = 0; r < zKeys.size(); r++) {
            List<ColumnBox> row = new ArrayList<>(rowsByZ.get(zKeys.get(r)));
            boolean reverseX = (r % 2 == 0) ? startMaxX : !startMaxX;
            row.sort(reverseX
                    ? Comparator.comparingInt(ColumnBox::gridX).reversed()
                    : Comparator.comparingInt(ColumnBox::gridX));
            for (ColumnBox c : row) {
                out.add(new ColumnBox(idx++, c.gridX(), c.gridZ(), c.min(), c.max()));
            }
        }
        return out;
    }

    /**
     * Picks the next chunk from {@code candidates}. For {@link OrderStrategy#SERPENTINE}
     * (or null {@code playerPos}), returns {@code candidates.get(0)}. For {@link OrderStrategy#NEAREST},
     * returns the candidate with minimum {@link ColumnBox#distanceSqXZ(BlockPos)} (ties broken by list order).
     */
    public static ColumnBox pickNext(List<ColumnBox> candidates, BlockPos playerPos, OrderStrategy strategy) {
        if (candidates == null || candidates.isEmpty()) return null;
        if (strategy != OrderStrategy.NEAREST || playerPos == null) {
            return candidates.get(0);
        }
        ColumnBox best = candidates.get(0);
        double bestDist = best.distanceSqXZ(playerPos);
        for (int i = 1; i < candidates.size(); i++) {
            ColumnBox c = candidates.get(i);
            double d = c.distanceSqXZ(playerPos);
            if (d < bestDist) {
                best = c;
                bestDist = d;
            }
        }
        return best;
    }
}
