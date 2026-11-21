package dev.manhattan;

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
 * Pure spatial chunking geometry: splits multi-box selections into {@link ColumnBox}es and orders them using
 * serpentine (boustrophedon) or nearest-first strategies.
 *
 * Boxes that lie more than a gap apart (in any direction) are separate regions, each chunked on its own, so the
 * bot never crosses the empty space between them just to come back. A region can also be cut into bands in Y
 * (top band first), so a deep hole is dug out band by band instead of in one go.
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

    /**
     * A slice of a selection, with sequential index and grid coordinates. gridY counts bands from the bottom,
     * ySlices is the number of bands of its region (1 = full height), cluster numbers the separate regions.
     */
    public record ColumnBox(int index, int gridX, int gridZ, BlockPos min, BlockPos max, int gridY, int ySlices, int cluster) {
        /** A full-height column of the only region. */
        public ColumnBox(int index, int gridX, int gridZ, BlockPos min, BlockPos max) {
            this(index, gridX, gridZ, min, max, 0, 1, 0);
        }

        ColumnBox withIndex(int i) {
            return new ColumnBox(i, gridX, gridZ, min, max, gridY, ySlices, cluster);
        }

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
            return minX() + "," + minZ() + (ySlices > 1 ? "," + minY() : "");
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
        return shouldChunk(boxes, chunkSize, 0, -1, enabled);
    }

    /** As above, and also true when the selection is taller than {@code chunkSizeY} (> 0) or falls apart into regions. */
    public static boolean shouldChunk(List<int[]> boxes, int chunkSize, int chunkSizeY, int gap, boolean enabled) {
        if (!enabled || chunkSize <= 0 || boxes == null || boxes.isEmpty()) return false;
        int[] u = unionBounds(boxes);
        if (u == null) return false;
        long spanX = (long) u[3] - u[0] + 1L;
        long spanZ = (long) u[5] - u[2] + 1L;
        long spanY = (long) u[4] - u[1] + 1L;
        return spanX > chunkSize || spanZ > chunkSize || (chunkSizeY > 0 && spanY > chunkSizeY)
                || (gap >= 0 && clusters(boxes, gap).size() > 1);
    }

    private static int[] norm(int[] b) {
        return new int[]{Math.min(b[0], b[3]), Math.min(b[1], b[4]), Math.min(b[2], b[5]),
                Math.max(b[0], b[3]), Math.max(b[1], b[4]), Math.max(b[2], b[5])};
    }

    /** The empty blocks between two boxes along one axis (0 = they touch or overlap). */
    private static int axisGap(int aMin, int aMax, int bMin, int bMax) {
        return Math.max(0, Math.max(bMin - aMax, aMin - bMax) - 1);
    }

    /**
     * Groups the boxes into regions: two boxes belong together when the empty space between them is at most {@code gap}
     * blocks in every direction (chains count). A negative gap keeps everything in one region. The regions are
     * ordered by their minimum corner, so the result does not depend on the order of the boxes.
     */
    public static List<List<int[]>> clusters(List<int[]> boxes, int gap) {
        List<int[]> bs = new ArrayList<>();
        for (int[] b : boxes) if (b != null && b.length >= 6) bs.add(norm(b));
        if (bs.isEmpty()) return List.of();
        if (gap < 0 || bs.size() == 1) return List.of(bs);
        int n = bs.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (int i = 0; i < n; i++) {
            int[] a = bs.get(i);
            for (int j = i + 1; j < n; j++) {
                int[] b = bs.get(j);
                if (axisGap(a[0], a[3], b[0], b[3]) <= gap && axisGap(a[1], a[4], b[1], b[4]) <= gap
                        && axisGap(a[2], a[5], b[2], b[5]) <= gap) {
                    int ra = find(parent, i), rb = find(parent, j);
                    if (ra != rb) parent[Math.max(ra, rb)] = Math.min(ra, rb);
                }
            }
        }
        Map<Integer, List<int[]>> groups = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(bs.get(i));
        List<List<int[]>> out = new ArrayList<>(groups.values());
        out.sort(Comparator.<List<int[]>>comparingInt(g -> unionBounds(g)[0])
                .thenComparingInt(g -> unionBounds(g)[2]).thenComparingInt(g -> unionBounds(g)[1]));
        return out;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    /** Full-height columns of the whole selection (one region, no bands in Y). */
    public static List<ColumnBox> split(List<int[]> boxes, int chunkSize) {
        return split(boxes, chunkSize, 0, -1);
    }

    /**
     * Cuts every region (see {@link #clusters}) into slices of {@code chunkSize} in X and Z and, when
     * {@code chunkSizeY > 0}, of that height in Y; keeps only slices that hold part of the region and numbers them
     * 0..N-1 with their grid position. {@code gap < 0} keeps the whole selection as one region.
     */
    public static List<ColumnBox> split(List<int[]> boxes, int chunkSize, int chunkSizeY, int gap) {
        if (boxes == null || boxes.isEmpty()) return List.of();
        if (chunkSize <= 0) throw new IllegalArgumentException("chunkSize must be > 0: " + chunkSize);
        List<List<int[]>> regions = clusters(boxes, gap);
        List<ColumnBox> out = new ArrayList<>();
        int idx = 0;
        for (int ci = 0; ci < regions.size(); ci++) {
            List<int[]> region = regions.get(ci);
            int[] u = unionBounds(region);
            if (u == null) continue;
            int minX = u[0], minY = u[1], minZ = u[2];
            int maxX = u[3], maxY = u[4], maxZ = u[5];
            long spanY = (long) maxY - minY + 1L;
            int ySlices = chunkSizeY > 0 ? (int) ((spanY + chunkSizeY - 1) / chunkSizeY) : 1;
            for (int gy = 0; gy < ySlices; gy++) {
                int y0 = chunkSizeY > 0 ? (int) Math.min(maxY, (long) minY + (long) gy * chunkSizeY) : minY;
                int y1 = chunkSizeY > 0 ? (int) Math.min(maxY, (long) y0 + chunkSizeY - 1L) : maxY;
                for (int gx = 0, x = minX; x <= maxX; gx++) {
                    long nextX = (long) x + chunkSize - 1L;
                    int x1 = (int) Math.min(maxX, nextX);
                    for (int gz = 0, z = minZ; z <= maxZ; gz++) {
                        long nextZ = (long) z + chunkSize - 1L;
                        int z1 = (int) Math.min(maxZ, nextZ);
                        ColumnBox candidate = new ColumnBox(idx, gx, gz, new BlockPos(x, y0, z), new BlockPos(x1, y1, z1),
                                gy, ySlices, ci);
                        if (!candidate.clip(region).isEmpty()) {
                            out.add(candidate);
                            idx++;
                        }
                        if (z1 == maxZ) break;
                        z = z1 + 1;
                    }
                    if (x1 == maxX) break;
                    x = x1 + 1;
                }
            }
        }
        return out;
    }

    /**
     * Orders columns in boustrophedon (snake) order along grid rows, picking the starting corner
     * closest to {@code startPos} when {@code startPos != null}, and re-indexing 0..N-1.
     */
    public static List<ColumnBox> orderSerpentine(List<ColumnBox> chunks, BlockPos startPos) {
        if (chunks == null || chunks.isEmpty()) return List.of();

        // regions one after the other (the nearest first, then the nearest to where the last one ended), inside a
        // region the bands from the top down, inside a band the rows back and forth
        Map<Integer, List<ColumnBox>> byRegion = new TreeMap<>();
        for (ColumnBox c : chunks) byRegion.computeIfAbsent(c.cluster(), k -> new ArrayList<>()).add(c);
        List<List<ColumnBox>> left = new ArrayList<>(byRegion.values());

        List<ColumnBox> out = new ArrayList<>(chunks.size());
        BlockPos from = startPos;
        while (!left.isEmpty()) {
            List<ColumnBox> next = left.get(0);
            if (from != null && left.size() > 1) {
                double best = Double.MAX_VALUE;
                for (List<ColumnBox> region : left) {
                    double d = Double.MAX_VALUE;
                    for (ColumnBox c : region) d = Math.min(d, c.distanceSqXZ(from));
                    if (d < best) { best = d; next = region; }
                }
            }
            left.remove(next);
            Map<Integer, List<ColumnBox>> bands = new TreeMap<>(Comparator.reverseOrder());
            for (ColumnBox c : next) bands.computeIfAbsent(c.gridY(), k -> new ArrayList<>()).add(c);
            for (List<ColumnBox> band : bands.values()) {
                List<ColumnBox> ordered = orderBand(band, from);
                out.addAll(ordered);
                if (!ordered.isEmpty()) from = ordered.get(ordered.size() - 1).centerXZ(0);
            }
        }
        List<ColumnBox> numbered = new ArrayList<>(out.size());
        for (int i = 0; i < out.size(); i++) numbered.add(out.get(i).withIndex(i));
        return numbered;
    }

    /** One band of one region in boustrophedon order along grid rows, starting at the corner nearest {@code startPos}. */
    private static List<ColumnBox> orderBand(List<ColumnBox> chunks, BlockPos startPos) {
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
        for (int r = 0; r < zKeys.size(); r++) {
            List<ColumnBox> row = new ArrayList<>(rowsByZ.get(zKeys.get(r)));
            boolean reverseX = (r % 2 == 0) ? startMaxX : !startMaxX;
            row.sort(reverseX
                    ? Comparator.comparingInt(ColumnBox::gridX).reversed()
                    : Comparator.comparingInt(ColumnBox::gridX));
            out.addAll(row);
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
        // never skip ahead to a lower band (a hole is dug from the top) or another region before this one is done
        int region = Integer.MAX_VALUE, band = Integer.MIN_VALUE;
        for (ColumnBox c : candidates) region = Math.min(region, c.cluster());
        for (ColumnBox c : candidates) if (c.cluster() == region) band = Math.max(band, c.gridY());
        ColumnBox best = null;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < candidates.size(); i++) {
            ColumnBox c = candidates.get(i);
            if (c.cluster() != region || c.gridY() != band) continue;
            double d = c.distanceSqXZ(playerPos);
            if (best == null) { best = c; bestDist = d; continue; }
            if (d < bestDist) {
                best = c;
                bestDist = d;
            }
        }
        return best;
    }
}
