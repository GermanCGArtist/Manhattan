package dev.midtown.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates {@link RuntimeEvent}s and active-category time samples into live time-budget breakdowns,
 * bottleneck callouts, and spatial hotspots for the Monitor UI and incident bundles.
 * Pure Java with zero Minecraft dependencies.
 */
public final class TelemetryProfiler {

    public record Hotspot(RuntimeEvent.Type type, int x, int y, int z, int count, String lastMessage, long lastTs) {}

    public record Bottleneck(String severity, String title, String detail) {}

    public record Snapshot(
            long totalMs,
            Map<RuntimeEvent.Category, Long> categoryMs,
            int replanCount,
            int stuckCount,
            int edgeBlacklistCount,
            int interruptCount,
            int restockExhaustedCount,
            int deathCount,
            List<Bottleneck> bottlenecks,
            List<Hotspot> hotspots) {

        public double share(RuntimeEvent.Category cat) {
            if (totalMs <= 0L) return 0.0;
            return categoryMs.getOrDefault(cat, 0L) / (double) totalMs;
        }
    }

    private final EnumMap<RuntimeEvent.Category, Long> categoryMs = new EnumMap<>(RuntimeEvent.Category.class);
    private RuntimeEvent.Category activeCategory = RuntimeEvent.Category.SYSTEM;
    private long lastSampleMs = 0L;

    public synchronized void reset() {
        categoryMs.clear();
        activeCategory = RuntimeEvent.Category.SYSTEM;
        lastSampleMs = 0L;
    }

    /** Records elapsed time since the previous sample against the active category, then switches categories. */
    public synchronized void sample(RuntimeEvent.Category category, long nowMs) {
        if (category == null) category = RuntimeEvent.Category.SYSTEM;
        if (lastSampleMs > 0L && nowMs > lastSampleMs) {
            long dt = Math.min(nowMs - lastSampleMs, 5_000L);
            if (activeCategory != RuntimeEvent.Category.SYSTEM) {
                categoryMs.merge(activeCategory, dt, Long::sum);
            }
        }
        activeCategory = category;
        lastSampleMs = nowMs;
    }

    /** Adds an explicit duration (e.g. from a completed interrupt or replan event) to a category. */
    public synchronized void addDuration(RuntimeEvent.Category category, long durationMs) {
        if (category == null || durationMs <= 0L) return;
        categoryMs.merge(category, durationMs, Long::sum);
    }

    public synchronized Snapshot snapshot(List<RuntimeEvent> events) {
        EnumMap<RuntimeEvent.Category, Long> copy = new EnumMap<>(RuntimeEvent.Category.class);
        long total = 0L;
        for (RuntimeEvent.Category c : RuntimeEvent.Category.values()) {
            if (c == RuntimeEvent.Category.SYSTEM) continue;
            long v = categoryMs.getOrDefault(c, 0L);
            copy.put(c, v);
            total += v;
        }

        int replans = 0;
        int stucks = 0;
        int blacklists = 0;
        int interrupts = 0;
        int restockExhausted = 0;
        int deaths = 0;

        // Group spatial problem events into 4x4 block cells so nearby stuck/blacklist spots cluster cleanly
        Map<String, HotspotAcc> clusterMap = new LinkedHashMap<>();
        if (events != null) {
            for (RuntimeEvent e : events) {
                switch (e.type()) {
                    case PATH_REPLAN -> replans++;
                    case STUCK -> stucks++;
                    case EDGE_BLACKLIST -> blacklists++;
                    case INTERRUPT_ENTER -> interrupts++;
                    case RESTOCK_EXHAUSTED -> restockExhausted++;
                    case DEATH -> deaths++;
                    default -> { }
                }
                if (e.hasPos() && (e.type() == RuntimeEvent.Type.STUCK
                        || e.type() == RuntimeEvent.Type.EDGE_BLACKLIST
                        || e.type() == RuntimeEvent.Type.DEATH)) {
                    int gx = Math.floorDiv(e.x(), 4) * 4;
                    int gz = Math.floorDiv(e.z(), 4) * 4;
                    String key = e.type().name() + ":" + gx + ":" + gz;
                    HotspotAcc acc = clusterMap.computeIfAbsent(key, k -> new HotspotAcc(e.type(), e.x(), e.y(), e.z()));
                    acc.count++;
                    acc.x = e.x();
                    acc.y = e.y();
                    acc.z = e.z();
                    acc.lastMessage = e.message();
                    acc.lastTs = e.timestampMs();
                }
            }
        }

        List<Hotspot> hotspots = new ArrayList<>();
        for (HotspotAcc acc : clusterMap.values()) {
            hotspots.add(new Hotspot(acc.type, acc.x, acc.y, acc.z, acc.count, acc.lastMessage, acc.lastTs));
        }
        hotspots.sort(Comparator.comparingInt(Hotspot::count).reversed().thenComparingLong(Hotspot::lastTs).reversed());

        List<Bottleneck> bottlenecks = detectBottlenecks(
                total, copy, replans, stucks, blacklists, interrupts, restockExhausted, deaths, hotspots);

        return new Snapshot(
                total,
                Collections.unmodifiableMap(copy),
                replans,
                stucks,
                blacklists,
                interrupts,
                restockExhausted,
                deaths,
                Collections.unmodifiableList(bottlenecks),
                Collections.unmodifiableList(hotspots));
    }

    private static List<Bottleneck> detectBottlenecks(
            long totalMs,
            Map<RuntimeEvent.Category, Long> catMs,
            int replans,
            int stucks,
            int blacklists,
            int interrupts,
            int restockExhausted,
            int deaths,
            List<Hotspot> hotspots) {
        List<Bottleneck> out = new ArrayList<>();

        if (deaths > 0) {
            Hotspot deathSpot = hotspots.stream()
                    .filter(h -> h.type() == RuntimeEvent.Type.DEATH)
                    .findFirst().orElse(null);
            String loc = deathSpot != null ? " near (" + deathSpot.x() + ", " + deathSpot.y() + ", " + deathSpot.z() + ")" : "";
            out.add(new Bottleneck("ERROR", deaths + " death(s) recorded" + loc,
                    "Check combat gear, safe areas, or DeathMemory corridors."));
        }

        if (restockExhausted > 0) {
            out.add(new Bottleneck("WARN", "Restock exhausted " + restockExhausted + "×",
                    "Supply chest may be empty or unreachable; refill or adjust want()/keep()."));
        }

        Hotspot topStuck = hotspots.stream()
                .filter(h -> h.type() == RuntimeEvent.Type.STUCK || h.type() == RuntimeEvent.Type.EDGE_BLACKLIST)
                .findFirst().orElse(null);
        if (stucks >= 4 || blacklists >= 4) {
            String loc = topStuck != null
                    ? " near (" + topStuck.x() + ", " + topStuck.y() + ", " + topStuck.z() + ")"
                    : "";
            out.add(new Bottleneck("WARN", (stucks + blacklists) + " stuck/blacklist events" + loc,
                    "Terrain obstacle or tight boundary causing repeated Midtown replans."));
        }

        if (totalMs >= 10_000L) {
            double logisticsPct = catMs.getOrDefault(RuntimeEvent.Category.LOGISTICS, 0L) / (double) totalMs;
            double walkPct = catMs.getOrDefault(RuntimeEvent.Category.WALK, 0L) / (double) totalMs;
            double stuckPct = (catMs.getOrDefault(RuntimeEvent.Category.STUCK, 0L)
                    + catMs.getOrDefault(RuntimeEvent.Category.REPLAN, 0L)) / (double) totalMs;
            double survivalPct = catMs.getOrDefault(RuntimeEvent.Category.SURVIVAL, 0L) / (double) totalMs;

            if (logisticsPct >= 0.30) {
                out.add(new Bottleneck("WARN",
                        Math.round(logisticsPct * 100) + "% of time spent on restock/dump trips",
                        "Move dump/supply chests closer, raise keep() buffer, or enable batchBaseTrips."));
            }
            if (stuckPct >= 0.15) {
                out.add(new Bottleneck("WARN",
                        Math.round(stuckPct * 100) + "% of time spent replanning or stuck",
                        "Inspect terrain around stuck markers on the Map or widen pathing clearance."));
            }
            if (survivalPct >= 0.25 && interrupts >= 3) {
                out.add(new Bottleneck("INFO",
                        Math.round(survivalPct * 100) + "% of time in survival interrupts (" + interrupts + "×)",
                        "Light up work area or adjust defend/eat thresholds."));
            }
            if (walkPct >= 0.55 && logisticsPct < 0.30) {
                out.add(new Bottleneck("INFO",
                        Math.round(walkPct * 100) + "% of time spent walking",
                        "Consider smaller chunk size or nearer staging chests."));
            }
        }

        if (replans >= 25 && stucks < 4) {
            out.add(new Bottleneck("INFO", replans + " path replans this run",
                    "Frequent dynamic terrain changes or goal shifts."));
        }

        return out;
    }

    private static final class HotspotAcc {
        final RuntimeEvent.Type type;
        int x;
        int y;
        int z;
        int count;
        String lastMessage = "";
        long lastTs;

        HotspotAcc(RuntimeEvent.Type type, int x, int y, int z) {
            this.type = type;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
