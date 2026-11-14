package dev.baritonequeue;

import java.util.ArrayList;
import java.util.List;

import static dev.baritonequeue.Config.cfg;

/**
 * View-model over saved areas ({@link Config#areas}, {@link Config#lastArea}, and active Baritone selection)
 * for the Stage 3 Areas panel.
 */
public final class AreasView {
    public record AreaEntry(String name, int boxCount, long totalBlocks, String summary, boolean isLast, String owner, String role) {
        public AreaEntry(String name, int boxCount, long totalBlocks, String summary, boolean isLast) {
            this(name, boxCount, totalBlocks, summary, isLast, "manual", "custom");
        }

        public String scriptCall() {
            return "area(\"" + name + "\")";
        }

        public String tag() {
            if ("heavymatica".equalsIgnoreCase(owner)) return "[H]";
            if ("script".equalsIgnoreCase(owner)) return "[S]";
            return "[M]";
        }
    }

    private AreasView() {}

    public static List<AreaEntry> list() {
        List<AreaEntry> out = new ArrayList<>();
        if (cfg != null && cfg.areas != null) {
            cfg.areas.forEach((name, boxes) -> {
                if (boxes != null && !boxes.isEmpty()) {
                    out.add(toEntry(name, boxes, false));
                }
            });
        }
        if (cfg != null && cfg.lastArea != null && !cfg.lastArea.isEmpty()) {
            out.add(toEntry("last", cfg.lastArea, true));
        }
        return out;
    }

    private static AreaEntry toEntry(String name, List<int[]> boxes, boolean isLast) {
        long blocks = 0;
        for (int[] b : boxes) {
            blocks += (b[3] - b[0] + 1L) * (b[4] - b[1] + 1L) * (b[5] - b[2] + 1L);
        }
        Config.AreaMeta m = isLast ? new Config.AreaMeta() : Areas.meta(name);
        String owner = m != null && m.owner != null ? m.owner : "manual";
        String role = m != null && m.role != null ? m.role : "custom";
        return new AreaEntry(name, boxes.size(), blocks, Areas.describe(boxes), isLast, owner, role);
    }

    public static String currentSelectionSummary() {
        try {
            List<int[]> sel = Areas.read();
            if (sel.isEmpty()) return "none (#sel pos1 / pos2)";
            return Areas.describe(sel);
        } catch (Throwable t) {
            return "none";
        }
    }

    public static String select(String name) {
        return Areas.load(name);
    }

    public static String saveCurrent(String name) {
        if (name == null || name.isBlank()) return "Enter an area name first.";
        return Areas.save(name.trim());
    }

    public static boolean delete(String name) {
        return Areas.delete(name);
    }
}
