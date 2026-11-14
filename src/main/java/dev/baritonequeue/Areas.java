package dev.baritonequeue;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;

import java.util.ArrayList;
import java.util.List;

import static dev.baritonequeue.Config.cfg;

/**
 * Saved areas: the selections (one or more boxes) under a name, so a restart does not mean selecting everything
 * again. The latest selection is also remembered by itself ("last") and put back when the queue starts with none.
 * A box is {x1, y1, z1, x2, y2, z2} with the smaller corner first.
 */
public final class Areas {
    private Areas() {}

    private static final List<int[]> selection = new ArrayList<>();
    private static BlockPos pos1, pos2;
    private static int renderTick;

    static void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null) return;
        if (++renderTick % 5 != 0) return;
        int[] budget = {600};
        BlockPos me = mc.player.blockPosition();
        if (!selection.isEmpty()) {
            for (int[] b : selection) {
                if (me.getX() < b[0] - 96 || me.getX() > b[3] + 96 || me.getZ() < b[2] - 96 || me.getZ() > b[5] + 96) continue;
                Capture.outline(mc.level, b, ParticleTypes.END_ROD, budget);
            }
        } else if (pos1 != null && pos2 == null) {
            if (Math.abs(pos1.getX() - me.getX()) <= 96 && Math.abs(pos1.getZ() - me.getZ()) <= 96) {
                Capture.outline(mc.level, new int[]{pos1.getX(), pos1.getY(), pos1.getZ(), pos1.getX(), pos1.getY(), pos1.getZ()}, ParticleTypes.END_ROD, budget);
            }
        }
    }

    public static List<int[]> read() {
        List<int[]> out = new ArrayList<>(selection.size());
        for (int[] b : selection) out.add(b.clone());
        return out;
    }

    public static boolean any() {
        return !selection.isEmpty();
    }

    static void clearSelection() {
        selection.clear();
        pos1 = null;
        pos2 = null;
    }

    static void setPos1(BlockPos p) {
        pos1 = p.immutable();
        if (pos2 != null) setSingleBox(pos1, pos2);
    }

    static void setPos2(BlockPos p) {
        pos2 = p.immutable();
        if (pos1 != null) setSingleBox(pos1, pos2);
    }

    static void setSingleBox(BlockPos a, BlockPos b) {
        selection.clear();
        addBox(a, b);
    }

    static void addBox(BlockPos a, BlockPos b) {
        selection.add(new int[]{
                Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ())
        });
    }

    static void apply(List<int[]> boxes) {
        selection.clear();
        for (int[] b : boxes) {
            selection.add(new int[]{
                    Math.min(b[0], b[3]), Math.min(b[1], b[4]), Math.min(b[2], b[5]),
                    Math.max(b[0], b[3]), Math.max(b[1], b[4]), Math.max(b[2], b[5])
            });
        }
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

    /** Names an area_box() of the running script has written to; the first box of a run replaces what was saved. */
    static final java.util.Set<String> scripted = new java.util.HashSet<>();

    /** area_box("name", corner1, corner2): the area is written in the script itself; returns an error text or null. */
    static String box(String name, int x1, int y1, int z1, int x2, int y2, int z2) {
        String n = name.trim();
        if (n.isEmpty() || n.equalsIgnoreCase("last")) return "area_box() needs a name for the area, for example \"mine_1\".";
        List<int[]> boxes = scripted.add(n) ? new ArrayList<>() : new ArrayList<>(cfg.areas.getOrDefault(n, List.of()));
        boxes.add(new int[]{Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2), Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2)});
        cfg.areas.put(n, boxes);
        Config.save();
        return null;
    }

    /** Moves a saved area (or every one: "all") by an offset; the selection moves along when it is that area. Returns an error or null. */
    static String move(String name, int dx, int dy, int dz) {
        List<String> names = new ArrayList<>();
        if (name.equalsIgnoreCase("all")) names.addAll(cfg.areas.keySet());
        else if (cfg.areas.containsKey(name)) names.add(name);
        else return "There is no saved area '" + name + "'. /queue area list shows them.";
        if (names.isEmpty()) return "There are no saved areas.";
        List<int[]> sel = read();
        boolean selMoved = false;
        for (String n : names) {
            List<int[]> boxes = cfg.areas.get(n);
            boolean isSel = !sel.isEmpty() && same(sel, boxes);
            List<int[]> out = new ArrayList<>();
            for (int[] b : boxes) out.add(new int[]{b[0] + dx, b[1] + dy, b[2] + dz, b[3] + dx, b[4] + dy, b[5] + dz});
            cfg.areas.put(n, out);
            if (isSel && !selMoved) { apply(out); selMoved = true; }
            Util.msg("Moved area '" + n + "': " + describe(out));
        }
        Config.save();
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
