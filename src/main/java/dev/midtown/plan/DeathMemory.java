package dev.midtown.plan;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.midtown.api.Area;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Spatial death-hazard memory for {@link MidtownPlanner}.
 * <p>
 * Records recent death locations plus breadcrumbs along the approach path leading up to each death,
 * builds an immutable {@link DeathField} snapshot for off-thread A* evaluation, decays penalties with
 * time (45-minute half-life, 3-hour expiration), and escalates cost super-linearly when multiple
 * deaths cluster along the same cave or corridor.
 */
public final class DeathMemory {

    public static final long HALF_LIFE_MS = 45L * 60_000L;
    public static final long MAX_AGE_MS = 3L * 60L * 60_000L;
    public static final int DEATH_RADIUS_XZ = 24;
    public static final int DEATH_RADIUS_Y = 12;
    public static final int TRAIL_SAMPLE_DIST = 6;
    public static final int TRAIL_JUMP_RESET_DIST = 32;
    public static final int MAX_TRAIL_POINTS = 16;
    public static final int MAX_BREADCRUMBS_PER_DEATH = 9;
    public static final int MAX_BREADCRUMB_DIST = 80;
    public static final int MAX_RECORDS = 400;
    public static final double BASE_PENALTY = 50.0;
    public static final double ESCALATION_FACTOR = 0.75;
    public static final double MAX_NODE_PENALTY = 800.0;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Persisted death or breadcrumb hazard record. */
    public static final class Record {
        public String dimension;
        public int x;
        public int y;
        public int z;
        public int radiusXZ;
        public int radiusY;
        public double weight;
        public long timestampMs;
        public boolean deathSite;

        public Record() {
        }

        public Record(
                String dimension,
                int x,
                int y,
                int z,
                int radiusXZ,
                int radiusY,
                double weight,
                long timestampMs,
                boolean deathSite
        ) {
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
            this.radiusXZ = Math.max(1, radiusXZ);
            this.radiusY = Math.max(1, radiusY);
            this.weight = Math.max(0.0, weight);
            this.timestampMs = timestampMs;
            this.deathSite = deathSite;
        }
    }

    /** Result summary returned when a death is recorded. */
    public record RecordSummary(int breadcrumbs, int nearbyDeaths) {
    }

    private record TrailPoint(String dimension, int x, int y, int z) {
    }

    private static final class StoredData {
        int version = 1;
        List<Record> records = new ArrayList<>();
    }

    /** Immutable, primitive-array snapshot of active death hazard ellipsoids for fast A* evaluation. */
    public static final class DeathField {
        public static final DeathField EMPTY = new DeathField(
                0, 0, 0, 0, 0, 0,
                new int[0], new int[0], new int[0], new int[0], new int[0], new double[0]);

        private final int minX;
        private final int maxX;
        private final int minY;
        private final int maxY;
        private final int minZ;
        private final int maxZ;
        private final int[] cx;
        private final int[] cy;
        private final int[] cz;
        private final int[] rXZ;
        private final int[] rY;
        private final double[] effWeight;

        private DeathField(
                int minX,
                int maxX,
                int minY,
                int maxY,
                int minZ,
                int maxZ,
                int[] cx,
                int[] cy,
                int[] cz,
                int[] rXZ,
                int[] rY,
                double[] effWeight
        ) {
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
            this.minZ = minZ;
            this.maxZ = maxZ;
            this.cx = cx;
            this.cy = cy;
            this.cz = cz;
            this.rXZ = rXZ;
            this.rY = rY;
            this.effWeight = effWeight;
        }

        public boolean isEmpty() {
            return cx.length == 0;
        }

        public int zoneCount() {
            return cx.length;
        }

        /**
         * Returns the additive A* node penalty at {@code (x, y, z)} due to remembered deaths and approach trails.
         * Multiple nearby deaths stack super-linearly so lethal corridors are avoided strongly without becoming
         * hard-blocked when a goal itself lies inside the zone.
         */
        public double penaltyAt(int x, int y, int z) {
            if (cx.length == 0) return 0.0;
            if (x < minX || x > maxX || z < minZ || z > maxZ || y < minY || y > maxY) {
                return 0.0;
            }
            double heat = 0.0;
            for (int i = 0; i < cx.length; i++) {
                double dx = (x - cx[i]) / (double) rXZ[i];
                double dy = (y - cy[i]) / (double) rY[i];
                double dz = (z - cz[i]) / (double) rXZ[i];
                double d2 = dx * dx + dy * dy + dz * dz;
                if (d2 >= 1.0) continue;
                double d = Math.sqrt(d2);
                double falloff = d <= 0.4 ? 1.0 : (1.0 - d) / 0.6;
                heat += effWeight[i] * falloff;
            }
            if (heat <= 0.0) return 0.0;
            return Math.min(
                    MAX_NODE_PENALTY,
                    BASE_PENALTY * heat * (1.0 + ESCALATION_FACTOR * Math.max(0.0, heat - 1.0)));
        }
    }

    private final List<Record> records = new ArrayList<>();
    private final Deque<TrailPoint> trail = new ArrayDeque<>();

    /** Samples the player's current block position into a short rolling approach trail for future deaths. */
    public synchronized void recordTrailPosition(String dimension, int x, int y, int z) {
        if (dimension == null || dimension.isBlank()) return;
        TrailPoint last = trail.peekLast();
        if (last != null) {
            if (!Objects.equals(last.dimension(), dimension)) {
                trail.clear();
            } else {
                long dx = (long) x - last.x();
                long dy = (long) y - last.y();
                long dz = (long) z - last.z();
                long d2 = dx * dx + dy * dy + dz * dz;
                if (d2 > (long) TRAIL_JUMP_RESET_DIST * TRAIL_JUMP_RESET_DIST) {
                    trail.clear();
                } else if (d2 < (long) TRAIL_SAMPLE_DIST * TRAIL_SAMPLE_DIST) {
                    return;
                }
            }
        }
        trail.addLast(new TrailPoint(dimension, x, y, z));
        while (trail.size() > MAX_TRAIL_POINTS) {
            trail.removeFirst();
        }
    }

    public synchronized void clearTrail() {
        trail.clear();
    }

    /**
     * Records a death at {@code (x, y, z)} along with breadcrumbs along the recent approach trail,
     * prunes expired records, and returns how many breadcrumbs and nearby recent deaths apply.
     */
    public synchronized RecordSummary recordDeath(String dimension, int x, int y, int z, long nowMs) {
        String dim = (dimension == null || dimension.isBlank()) ? "minecraft:overworld" : dimension;
        records.removeIf(r -> r == null || nowMs - r.timestampMs > MAX_AGE_MS);
        records.add(new Record(dim, x, y, z, DEATH_RADIUS_XZ, DEATH_RADIUS_Y, 1.0, nowMs, true));

        List<TrailPoint> snapshotTrail = new ArrayList<>(trail);
        int breadcrumbs = 0;
        for (int i = snapshotTrail.size() - 1; i >= 0 && breadcrumbs < MAX_BREADCRUMBS_PER_DEATH; i--) {
            TrailPoint pt = snapshotTrail.get(i);
            if (!Objects.equals(pt.dimension(), dim)) continue;
            long dx = (long) pt.x() - x;
            long dy = (long) pt.y() - y;
            long dz = (long) pt.z() - z;
            long d2 = dx * dx + dy * dy + dz * dz;
            if (d2 < (long) TRAIL_SAMPLE_DIST * TRAIL_SAMPLE_DIST) {
                continue;
            }
            if (d2 > (long) MAX_BREADCRUMB_DIST * MAX_BREADCRUMB_DIST) {
                break;
            }
            double weight = Math.max(0.20, 0.70 - breadcrumbs * 0.06);
            int radiusXZ = Math.max(10, 16 - breadcrumbs);
            int radiusY = 8;
            records.add(new Record(dim, pt.x(), pt.y(), pt.z(), radiusXZ, radiusY, weight, nowMs, false));
            breadcrumbs++;
        }
        trail.clear();

        while (records.size() > MAX_RECORDS) {
            records.remove(0);
        }

        int nearbyDeaths = 0;
        for (Record r : records) {
            if (!r.deathSite || !Objects.equals(r.dimension, dim)) continue;
            long dx = (long) r.x - x;
            long dz = (long) r.z - z;
            if (dx * dx + dz * dz <= 40L * 40L) {
                nearbyDeaths++;
            }
        }
        return new RecordSummary(breadcrumbs, nearbyDeaths);
    }

    /** Builds an immutable {@link DeathField} snapshot for {@code dimension} at {@code nowMs}. */
    public synchronized DeathField snapshot(String dimension, long nowMs) {
        return snapshot(dimension, nowMs, null, List.of());
    }

    /**
     * Builds an immutable {@link DeathField} snapshot for {@code dimension} at {@code nowMs},
     * excluding records near {@code protectedAreas} (so base deaths never trap the bot inside its own base)
     * or covering {@code goalPos} (so destinations inside a death zone remain smoothly reachable).
     */
    public synchronized DeathField snapshot(
            String dimension,
            long nowMs,
            BlockPos goalPos,
            Collection<Area> protectedAreas
    ) {
        if (records.isEmpty()) return DeathField.EMPTY;
        List<Record> active = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        for (Record r : records) {
            if (r == null) continue;
            if (dimension != null && !Objects.equals(dimension, r.dimension)) continue;
            long age = nowMs - r.timestampMs;
            if (age > MAX_AGE_MS) continue;
            if (isNearProtected(r, protectedAreas, 16) || isNearGoal(r, goalPos)) continue;
            double decay = Math.pow(0.5, Math.max(0L, age) / (double) HALF_LIFE_MS);
            double eff = r.weight * decay;
            if (eff < 0.05) continue;
            active.add(r);
            weights.add(eff);
        }
        if (active.isEmpty()) return DeathField.EMPTY;

        int n = active.size();
        int[] cx = new int[n];
        int[] cy = new int[n];
        int[] cz = new int[n];
        int[] rXZ = new int[n];
        int[] rY = new int[n];
        double[] effWeight = new double[n];
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (int i = 0; i < n; i++) {
            Record r = active.get(i);
            int rxz = Math.max(1, r.radiusXZ);
            int ry = Math.max(1, r.radiusY);
            cx[i] = r.x;
            cy[i] = r.y;
            cz[i] = r.z;
            rXZ[i] = rxz;
            rY[i] = ry;
            effWeight[i] = weights.get(i);
            minX = Math.min(minX, r.x - rxz);
            maxX = Math.max(maxX, r.x + rxz);
            minY = Math.min(minY, r.y - ry);
            maxY = Math.max(maxY, r.y + ry);
            minZ = Math.min(minZ, r.z - rxz);
            maxZ = Math.max(maxZ, r.z + rxz);
        }
        return new DeathField(minX, maxX, minY, maxY, minZ, maxZ, cx, cy, cz, rXZ, rY, effWeight);
    }

    /** Returns unexpired primary death-site records matching {@code dimension} (or all if {@code null}). */
    public synchronized List<Record> recentDeathSites(String dimension, long nowMs) {
        List<Record> out = new ArrayList<>();
        for (Record r : records) {
            if (r == null || !r.deathSite) continue;
            if (dimension != null && !Objects.equals(dimension, r.dimension)) continue;
            if (nowMs - r.timestampMs > MAX_AGE_MS) continue;
            out.add(new Record(r.dimension, r.x, r.y, r.z, r.radiusXZ, r.radiusY, r.weight, r.timestampMs, true));
        }
        return out;
    }

    /** Clears remembered death records and trail for {@code dimension}. */
    public synchronized void clear(String dimension) {
        if (dimension == null) {
            clearAll();
            return;
        }
        records.removeIf(r -> r == null || Objects.equals(dimension, r.dimension));
        trail.removeIf(pt -> Objects.equals(dimension, pt.dimension()));
    }

    /** Clears all remembered death records and breadcrumb trail. */
    public synchronized void clearAll() {
        records.clear();
        trail.clear();
    }

    /** Atomically persists remembered death hazards to {@code file}. */
    public synchronized void save(Path file) {
        if (file == null) return;
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            StoredData data = new StoredData();
            data.records = new ArrayList<>(records);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(data, w);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
        }
    }

    /** Loads persisted death hazards from {@code file}, tolerating missing or malformed files. */
    public synchronized void load(Path file) {
        records.clear();
        trail.clear();
        if (file == null || !Files.isRegularFile(file)) return;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            StoredData data = GSON.fromJson(r, StoredData.class);
            if (data == null || data.records == null) return;
            for (Record rec : data.records) {
                if (rec == null || rec.dimension == null || rec.dimension.isBlank()) continue;
                records.add(new Record(
                        rec.dimension,
                        rec.x,
                        rec.y,
                        rec.z,
                        rec.radiusXZ,
                        rec.radiusY,
                        rec.weight,
                        rec.timestampMs,
                        rec.deathSite));
            }
        } catch (Exception ignored) {
        }
    }

    private static boolean isNearProtected(Record r, Collection<Area> protectedAreas, int margin) {
        if (protectedAreas == null || protectedAreas.isEmpty()) return false;
        for (Area area : protectedAreas) {
            if (area == null) continue;
            if (r.x >= area.minX() - margin && r.x <= area.maxX() + margin
                    && r.y >= area.minY() - margin && r.y <= area.maxY() + margin
                    && r.z >= area.minZ() - margin && r.z <= area.maxZ() + margin) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNearGoal(Record r, BlockPos goalPos) {
        if (goalPos == null) return false;
        long dx = (long) goalPos.getX() - r.x;
        long dz = (long) goalPos.getZ() - r.z;
        long maxHoriz = (long) Math.max(1, r.radiusXZ) + 8L;
        int maxVert = Math.max(1, r.radiusY) + 6;
        return dx * dx + dz * dz <= maxHoriz * maxHoriz && Math.abs(goalPos.getY() - r.y) <= maxVert;
    }
}
