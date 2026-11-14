package dev.baritonequeue;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;

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
    /** The saved area the selection was last loaded from; null after any other change to the selection. */
    private static String loadedName;
    private static final java.util.List<java.util.function.BiConsumer<String, String>> listeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    static void tick(Minecraft mc) {
        // Particle preview replaced with 3D wireframe box in renderWireframe()
    }

    public static void renderWireframe() {
        List<int[]> boxes;
        BlockPos p1;
        synchronized (selection) {
            if (selection.isEmpty() && (pos1 == null || pos2 != null)) return;
            boxes = new ArrayList<>(selection);
            p1 = pos1;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.levelRenderer == null) return;
        BlockPos me = mc.player != null ? mc.player.blockPosition() : null;
        try (var ignored = mc.levelRenderer.collectPerFrameGizmos()) {
            GizmoStyle style = GizmoStyle.stroke(0xFF00E5FF, 2.0f);
            if (!boxes.isEmpty()) {
                for (int[] b : boxes) {
                    if (me != null && (me.getX() < b[0] - 96 || me.getX() > b[3] + 96 || me.getZ() < b[2] - 96 || me.getZ() > b[5] + 96)) continue;
                    Gizmos.cuboid(new AABB(b[0], b[1], b[2], b[3] + 1, b[4] + 1, b[5] + 1).inflate(0.002), style);
                }
            } else if (p1 != null) {
                if (me == null || (Math.abs(p1.getX() - me.getX()) <= 96 && Math.abs(p1.getZ() - me.getZ()) <= 96)) {
                    Gizmos.cuboid(new AABB(p1).inflate(0.002), style);
                }
            }
        }
    }

    public static List<int[]> read() {
        synchronized (selection) {
            List<int[]> out = new ArrayList<>(selection.size());
            for (int[] b : selection) out.add(b.clone());
            return out;
        }
    }

    public static boolean any() {
        synchronized (selection) {
            return !selection.isEmpty();
        }
    }

    static void clearSelection() {
        synchronized (selection) {
            selection.clear();
            pos1 = null;
            pos2 = null;
            loadedName = null;
        }
    }

    static void setPos1(BlockPos p) {
        synchronized (selection) {
            pos1 = p.immutable();
            loadedName = null;
            if (pos2 != null) setSingleBox(pos1, pos2);
        }
    }

    static void setPos2(BlockPos p) {
        synchronized (selection) {
            pos2 = p.immutable();
            loadedName = null;
            if (pos1 != null) setSingleBox(pos1, pos2);
        }
    }

    static void setSingleBox(BlockPos a, BlockPos b) {
        synchronized (selection) {
            selection.clear();
            loadedName = null;
            addBox(a, b);
        }
    }

    static void addBox(BlockPos a, BlockPos b) {
        synchronized (selection) {
            loadedName = null;
            selection.add(new int[]{
                    Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                    Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ())
            });
        }
    }

    static void apply(List<int[]> boxes) {
        synchronized (selection) {
            loadedName = null;
            applyBoxes(boxes);
        }
    }

    private static void applyBoxes(List<int[]> boxes) {
        synchronized (selection) {
            selection.clear();
            for (int[] b : boxes) {
                selection.add(new int[]{
                        Math.min(b[0], b[3]), Math.min(b[1], b[4]), Math.min(b[2], b[5]),
                        Math.max(b[0], b[3]), Math.max(b[1], b[4]), Math.max(b[2], b[5])
                });
            }
        }
    }

    static String describe(List<int[]> boxes) {
        long blocks = 0;
        for (int[] b : boxes) blocks += (b[3] - b[0] + 1L) * (b[4] - b[1] + 1L) * (b[5] - b[2] + 1L);
        int[] f = boxes.get(0);
        return boxes.size() + " box" + (boxes.size() == 1 ? "" : "es") + ", " + blocks + " blocks, from " + f[0] + " " + f[1] + " " + f[2]
                + " to " + f[3] + " " + f[4] + " " + f[5] + (boxes.size() > 1 ? " (first box)" : "");
    }

    public static Config.AreaMeta meta(String name) {
        if (name == null || cfg.areaMeta == null) return new Config.AreaMeta();
        Config.AreaMeta m = cfg.areaMeta.get(name);
        return m != null ? m : new Config.AreaMeta();
    }

    public static String ownerOf(String name) {
        String owner = meta(name).owner;
        return owner == null || owner.isBlank() ? "manual" : owner;
    }

    public static String roleOf(String name) {
        String role = meta(name).role;
        return role == null || role.isBlank() ? "custom" : role;
    }

    public static long updatedOf(String name) {
        return meta(name).updated;
    }

    public static List<int[]> getBoxes(String name) {
        if (name == null || cfg.areas == null) return null;
        List<int[]> boxes = cfg.areas.get(name);
        if (boxes == null) return null;
        List<int[]> copy = new ArrayList<>(boxes.size());
        for (int[] b : boxes) copy.add(b.clone());
        return copy;
    }

    public static java.util.Set<String> savedNames() {
        if (cfg.areas == null) return java.util.Set.of();
        return java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(cfg.areas.keySet()));
    }

    public static boolean inUse(String name) {
        return name != null && RunnerView.running() && name.equalsIgnoreCase(loadedName);
    }

    public static void addMetaListener(java.util.function.BiConsumer<String, String> listener) {
        if (listener != null) listeners.add(listener);
    }

    private static void notifyListeners(String name, String owner) {
        for (var l : listeners) l.accept(name, owner);
    }

    private static void writeMeta(String name, String owner, String role) {
        if (cfg.areaMeta == null) cfg.areaMeta = new java.util.LinkedHashMap<>();
        Config.AreaMeta prev = cfg.areaMeta.get(name);
        Config.AreaMeta next = new Config.AreaMeta();
        next.owner = owner != null ? owner : (prev != null && prev.owner != null ? prev.owner : "manual");
        next.role = role != null ? role : (prev != null && prev.role != null ? prev.role : "custom");
        next.updated = System.currentTimeMillis();
        cfg.areaMeta.put(name, next);
    }

    /** Saves the current selection; returns an error text or null. */
    static String save(String name) {
        List<int[]> now = read();
        if (now.isEmpty()) return "There is no selection to save. Select the area first (#sel pos1 / pos2).";
        cfg.areas.put(name, now);
        writeMeta(name, "manual", null);
        Config.save();
        notifyListeners(name, "manual");
        Util.msg("Saved area '" + name + "': " + describe(now));
        return null;
    }

    /** Puts a saved area back as the selection ("last" = the one remembered automatically); returns an error text or null. */
    public static String load(String name) {
        List<int[]> boxes = name.equalsIgnoreCase("last") ? cfg.lastArea : cfg.areas.get(name);
        if (boxes == null || boxes.isEmpty()) {
            return name.equalsIgnoreCase("last") ? "No area was remembered yet." : "There is no saved area '" + name + "'. /queue area list shows them.";
        }
        synchronized (selection) {
            applyBoxes(boxes);
            loadedName = name.equalsIgnoreCase("last") ? null : name;
        }
        Util.msg("Area '" + name + "' selected: " + describe(boxes));
        return null;
    }

    /** Names an area_box() of the running script has written to; the first box of a run replaces what was saved. */
    static final java.util.Set<String> scripted = new java.util.HashSet<>();

    /** area_box("name", corner1, corner2): the area is written in the script itself; returns an error text or null. */
    static String box(String name, int x1, int y1, int z1, int x2, int y2, int z2) {
        String n = name.trim();
        if (n.isEmpty() || n.equalsIgnoreCase("last")) return "area_box() needs a name for the area, for example \"mine_1\".";
        boolean first = scripted.add(n);
        if (first && "heavymatica".equals(ownerOf(n))) {
            Util.msg("area_box(\"" + n + "\") replaces the area Heavymatica sent. It will show as out of sync there.");
        }
        List<int[]> boxes = first ? new ArrayList<>() : new ArrayList<>(cfg.areas.getOrDefault(n, List.of()));
        boxes.add(new int[]{Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2), Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2)});
        cfg.areas.put(n, boxes);
        writeMeta(n, "script", null);
        Config.save();
        notifyListeners(n, "script");
        return null;
    }

    /** Saves an area owned by Heavymatica (used by {@link dev.baritonequeue.api.ManhattanAreas}). */
    public static void putOwned(String name, List<int[]> normalizedBoxes, String role) {
        List<int[]> copy = new ArrayList<>(normalizedBoxes.size());
        for (int[] b : normalizedBoxes) copy.add(b.clone());
        cfg.areas.put(name, copy);
        writeMeta(name, "heavymatica", role);
        Config.save();
        notifyListeners(name, "heavymatica");
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
            writeMeta(n, null, null);
            if (isSel && !selMoved) { applyBoxes(out); selMoved = true; }
            notifyListeners(n, ownerOf(n));
            Util.msg("Moved area '" + n + "': " + describe(out));
        }
        Config.save();
        return null;
    }

    public static boolean delete(String name) {
        if (name == null) return false;
        boolean wasProtect = "protect".equalsIgnoreCase(roleOf(name));
        boolean had = cfg.areas.remove(name) != null;
        if (cfg.areaMeta != null) cfg.areaMeta.remove(name);
        if (had) {
            if (wasProtect) {
                dev.buildtools.Api.unprotect(name);
            }
            Config.save();
            notifyListeners(name, null);
        }
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
