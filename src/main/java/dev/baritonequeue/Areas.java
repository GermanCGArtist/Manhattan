package dev.baritonequeue;

import baritone.api.selection.ISelection;
import baritone.api.selection.ISelectionManager;
import baritone.api.utils.BetterBlockPos;

import java.util.ArrayList;
import java.util.List;

import static dev.baritonequeue.Config.cfg;

/**
 * Saved areas: the #sel selections (one or more boxes) under a name, so a restart does not mean selecting everything
 * again. The latest selection is also remembered by itself ("last") and put back when the queue starts with none.
 * A box is {x1, y1, z1, x2, y2, z2} with the smaller corner first.
 */
final class Areas {
    private Areas() {}

    private static ISelectionManager manager() {
        return Util.baritone().getSelectionManager();
    }

    static List<int[]> read() {
        List<int[]> out = new ArrayList<>();
        for (ISelection s : manager().getSelections()) {
            out.add(new int[]{s.min().x, s.min().y, s.min().z, s.max().x, s.max().y, s.max().z});
        }
        return out;
    }

    static boolean any() {
        return manager().getSelections().length > 0;
    }

    private static void apply(List<int[]> boxes) {
        ISelectionManager sm = manager();
        sm.removeAllSelections();
        for (int[] b : boxes) sm.addSelection(new BetterBlockPos(b[0], b[1], b[2]), new BetterBlockPos(b[3], b[4], b[5]));
    }

    static String describe(List<int[]> boxes) {
        long blocks = 0;
        for (int[] b : boxes) blocks += (b[3] - b[0] + 1L) * (b[4] - b[1] + 1L) * (b[5] - b[2] + 1L);
        int[] f = boxes.get(0);
        return boxes.size() + " box" + (boxes.size() == 1 ? "" : "es") + ", " + blocks + " blocks, from " + f[0] + " " + f[1] + " " + f[2]
                + " to " + f[3] + " " + f[4] + " " + f[5] + (boxes.size() > 1 ? " (first box)" : "");
    }

    /** Saves the current selection; returns an error text or null. */
    static String save(String name) {
        List<int[]> now = read();
        if (now.isEmpty()) return "There is no selection to save. Select the area first (#sel pos1 / pos2).";
        cfg.areas.put(name, now);
        Config.save();
        Util.msg("Saved area '" + name + "': " + describe(now));
        return null;
    }

    /** Puts a saved area back as the selection ("last" = the one remembered automatically); returns an error text or null. */
    static String load(String name) {
        List<int[]> boxes = name.equalsIgnoreCase("last") ? cfg.lastArea : cfg.areas.get(name);
        if (boxes == null || boxes.isEmpty()) {
            return name.equalsIgnoreCase("last") ? "No area was remembered yet." : "There is no saved area '" + name + "'. /queue area list shows them.";
        }
        apply(boxes);
        Util.msg("Area '" + name + "' selected: " + describe(boxes));
        return null;
    }

    static boolean delete(String name) {
        boolean had = cfg.areas.remove(name) != null;
        if (had) Config.save();
        return had;
    }

    private static boolean same(List<int[]> a, List<int[]> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!java.util.Arrays.equals(a.get(i), b.get(i))) return false;
        return true;
    }

    /** Every second or so: keeps the latest selection as "last". */
    static void remember() {
        List<int[]> now = read();
        if (now.isEmpty() || same(now, cfg.lastArea)) return;
        cfg.lastArea = now;
        Config.save();
    }

    private static final java.util.regex.Pattern NEEDS_SELECTION = java.util.regex.Pattern.compile(
            "(?m)^\\s*#\\s*(sel|selection|s)\\s+(ca|cleararea|set|fill|replace|r|f|s)\\b|\\b(fill_water|flood|count_blocks|water_left|protect)\\s*\\(");

    /** At script start: with no selection, a script that works on one and never picks an area itself gets the last one back. */
    static void restoreIfNeeded(String script) {
        if (any() || cfg.lastArea.isEmpty()) return;
        if (script.contains("area(") || script.contains("select(")) return;
        if (!NEEDS_SELECTION.matcher(script).find()) return;
        String err = load("last");
        if (err == null) Util.msg("(There was no selection, so the last one was put back.)");
    }
}
