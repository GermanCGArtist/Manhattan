package dev.midtown.exec;

import dev.midtown.exec.PathNode.EdgeKey;
import net.minecraft.core.BlockPos;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pure-Java edge progress tracker, per-edge timeout detector, stuck detector, and temporary edge blacklist.
 * <p>
 * Designed to run without a live {@code Minecraft} client so unit tests can exercise stuck/timeout/blacklist
 * transitions deterministically.
 */
public final class EdgeTracker {

    public static final int DEFAULT_STUCK_TICKS = 20;
    public static final int DEFAULT_EDGE_TIMEOUT_TICKS = 80;
    public static final int DEFAULT_BLACKLIST_TICKS = 100;
    public static final double DEFAULT_MIN_PROGRESS_DIST = 0.05;

    private int stuckThresholdTicks;
    private int defaultEdgeTimeoutTicks;
    private int defaultBlacklistTicks;
    private double minProgressDist;

    private final Map<EdgeKey, Long> blacklistedUntilTick = new ConcurrentHashMap<>();

    private EdgeKey activeEdge;
    private long edgeStartTick;
    private int edgeTimeoutTicks;
    private double bestDistSqToTarget;
    private int noProgressTicks;
    private boolean edgeActive;

    /** Result of evaluating player progress on the active edge for one tick. */
    public enum EdgeCheckResult {
        /** Making progress or still within stuck/timeout thresholds. */
        OK,
        /** No meaningful progress toward the edge target for {@code stuckThresholdTicks}. */
        STUCK,
        /** Elapsed ticks on this edge exceeded {@code edgeTimeoutTicks}. */
        TIMED_OUT
    }

    public EdgeTracker() {
        this(DEFAULT_STUCK_TICKS, DEFAULT_EDGE_TIMEOUT_TICKS, DEFAULT_BLACKLIST_TICKS, DEFAULT_MIN_PROGRESS_DIST);
    }

    public EdgeTracker(int stuckThresholdTicks, int defaultEdgeTimeoutTicks, int defaultBlacklistTicks, double minProgressDist) {
        if (stuckThresholdTicks <= 0) throw new IllegalArgumentException("stuckThresholdTicks must be > 0");
        if (defaultEdgeTimeoutTicks <= 0) throw new IllegalArgumentException("defaultEdgeTimeoutTicks must be > 0");
        if (defaultBlacklistTicks <= 0) throw new IllegalArgumentException("defaultBlacklistTicks must be > 0");
        if (minProgressDist < 0) throw new IllegalArgumentException("minProgressDist must be >= 0");
        this.stuckThresholdTicks = stuckThresholdTicks;
        this.defaultEdgeTimeoutTicks = defaultEdgeTimeoutTicks;
        this.defaultBlacklistTicks = defaultBlacklistTicks;
        this.minProgressDist = minProgressDist;
    }

    /** Begins tracking a new edge from {@code from} to {@code to}. */
    public void beginEdge(BlockPos from, BlockPos to,
                          double playerX, double playerY, double playerZ,
                          long currentTick, int customTimeoutTicks) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        this.activeEdge = new EdgeKey(from, to);
        this.edgeStartTick = currentTick;
        this.edgeTimeoutTicks = customTimeoutTicks > 0 ? customTimeoutTicks : defaultEdgeTimeoutTicks;
        this.bestDistSqToTarget = distSqToBlockCenter(playerX, playerY, playerZ, to);
        this.noProgressTicks = 0;
        this.edgeActive = true;
    }

    /** Clears the currently tracked edge without modifying the blacklist. */
    public void resetEdge() {
        this.activeEdge = null;
        this.edgeActive = false;
        this.noProgressTicks = 0;
        this.bestDistSqToTarget = Double.POSITIVE_INFINITY;
    }

    /**
     * Evaluates one tick of movement along the active edge.
     *
     * @return {@link EdgeCheckResult#OK}, {@link EdgeCheckResult#STUCK}, or {@link EdgeCheckResult#TIMED_OUT}
     */
    public EdgeCheckResult update(double playerX, double playerY, double playerZ, long currentTick) {
        if (!edgeActive || activeEdge == null) {
            return EdgeCheckResult.OK;
        }
        long elapsed = currentTick - edgeStartTick;
        if (elapsed >= edgeTimeoutTicks) {
            return EdgeCheckResult.TIMED_OUT;
        }

        double distSq = distSqToBlockCenter(playerX, playerY, playerZ, activeEdge.to());
        double bestDist = Math.sqrt(bestDistSqToTarget);
        double curDist = Math.sqrt(distSq);
        if (curDist <= bestDist - minProgressDist) {
            bestDistSqToTarget = distSq;
            noProgressTicks = 0;
        } else {
            noProgressTicks++;
            if (noProgressTicks >= stuckThresholdTicks) {
                return EdgeCheckResult.STUCK;
            }
        }
        return EdgeCheckResult.OK;
    }

    /** Resets the no-progress stuck counter (e.g., while intentionally standing still to mine a block). */
    public void resetNoProgress() {
        this.noProgressTicks = 0;
    }

    /** Temporarily blacklists {@code edge} until {@code currentTick + defaultBlacklistTicks}. */
    public void blacklist(EdgeKey edge, long currentTick) {
        blacklist(edge, currentTick, defaultBlacklistTicks);
    }

    /** Temporarily blacklists {@code edge} until {@code currentTick + durationTicks}. */
    public void blacklist(EdgeKey edge, long currentTick, int durationTicks) {
        Objects.requireNonNull(edge, "edge");
        if (durationTicks <= 0) return;
        blacklistedUntilTick.put(edge, currentTick + durationTicks);
    }

    /** Returns true if {@code (from, to)} is currently blacklisted at {@code currentTick}. */
    public boolean isBlacklisted(BlockPos from, BlockPos to, long currentTick) {
        return isBlacklisted(new EdgeKey(from, to), currentTick);
    }

    /** Returns true if {@code edge} is currently blacklisted at {@code currentTick}. */
    public boolean isBlacklisted(EdgeKey edge, long currentTick) {
        Long until = blacklistedUntilTick.get(edge);
        if (until == null) return false;
        if (currentTick >= until) {
            blacklistedUntilTick.remove(edge, until);
            return false;
        }
        return true;
    }

    /** Removes all expired blacklist entries at {@code currentTick}. */
    public void pruneExpired(long currentTick) {
        Iterator<Map.Entry<EdgeKey, Long>> it = blacklistedUntilTick.entrySet().iterator();
        while (it.hasNext()) {
            if (currentTick >= it.next().getValue()) {
                it.remove();
            }
        }
    }

    /** Clears all blacklisted edges and resets active edge tracking. */
    public void clearAll() {
        blacklistedUntilTick.clear();
        resetEdge();
    }

    public int blacklistedCount(long currentTick) {
        pruneExpired(currentTick);
        return blacklistedUntilTick.size();
    }

    public EdgeKey activeEdge() {
        return activeEdge;
    }

    public int noProgressTicks() {
        return noProgressTicks;
    }

    public int stuckThresholdTicks() {
        return stuckThresholdTicks;
    }

    public void setStuckThresholdTicks(int stuckThresholdTicks) {
        if (stuckThresholdTicks <= 0) throw new IllegalArgumentException("stuckThresholdTicks must be > 0");
        this.stuckThresholdTicks = stuckThresholdTicks;
    }

    public int defaultEdgeTimeoutTicks() {
        return defaultEdgeTimeoutTicks;
    }

    public void setDefaultEdgeTimeoutTicks(int defaultEdgeTimeoutTicks) {
        if (defaultEdgeTimeoutTicks <= 0) throw new IllegalArgumentException("defaultEdgeTimeoutTicks must be > 0");
        this.defaultEdgeTimeoutTicks = defaultEdgeTimeoutTicks;
    }

    public int defaultBlacklistTicks() {
        return defaultBlacklistTicks;
    }

    public void setDefaultBlacklistTicks(int defaultBlacklistTicks) {
        if (defaultBlacklistTicks <= 0) throw new IllegalArgumentException("defaultBlacklistTicks must be > 0");
        this.defaultBlacklistTicks = defaultBlacklistTicks;
    }

    private static double distSqToBlockCenter(double px, double py, double pz, BlockPos pos) {
        double dx = px - (pos.getX() + 0.5);
        double dy = py - pos.getY();
        double dz = pz - (pos.getZ() + 0.5);
        return dx * dx + dy * dy + dz * dz;
    }
}
