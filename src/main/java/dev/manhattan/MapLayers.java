package dev.manhattan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Extra regions a running job wants on the workspace map, for example the column chunks a spatial job splits its
 * area into. A job registers a source while it runs and removes it when it ends; {@link MapView} draws every region
 * coloured by its state (the active one outlined, with its number) and the Monitor map header counts them.
 */
public final class MapLayers {
    private MapLayers() {}

    public enum State { PENDING, ACTIVE, DONE, FAILED, SKIPPED }

    /** One region in block coordinates (inclusive), with a short label such as the chunk number. */
    public record Region(int x1, int z1, int x2, int z2, State state, String label, String detail) {}

    private static final Map<String, Supplier<List<Region>>> sources = new LinkedHashMap<>();

    public static synchronized void register(String id, Supplier<List<Region>> source) {
        sources.put(id, source);
    }

    public static synchronized void unregister(String id) {
        sources.remove(id);
    }

    /** Everything to draw now (a failing source is skipped). */
    public static synchronized List<Region> regions() {
        List<Region> out = new ArrayList<>();
        for (Supplier<List<Region>> s : sources.values()) {
            try {
                List<Region> r = s.get();
                if (r != null) out.addAll(r);
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    /** "12/40 chunks done · 1 failed" for the map header, or "" when no layer is there. */
    public static String summary() {
        List<Region> rs = regions();
        if (rs.isEmpty()) return "";
        int done = 0, failed = 0;
        for (Region r : rs) {
            if (r.state() == State.DONE || r.state() == State.SKIPPED) done++;
            else if (r.state() == State.FAILED) failed++;
        }
        return done + "/" + rs.size() + " chunks done" + (failed > 0 ? " · " + failed + " failed" : "");
    }
}
