package dev.buildtools;

import baritone.api.selection.ISelection;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.buildtools.Config.cfg;

/** Creating and removing safe areas (the commands behind /buildtools protect). */
final class Zones {
    private Zones() {}

    private static void put(Config.Zone zone) {
        cfg.zones.removeIf(z -> z.name.equalsIgnoreCase(zone.name));   // same name = replace
        cfg.zones.add(zone);
    }

    private static String freeName() {
        for (int i = 1; ; i++) {
            String name = "area" + i;
            if (cfg.zones.stream().noneMatch(z -> z.name.equalsIgnoreCase(name))) return name;
        }
    }

    /** Every #sel selection becomes a safe area. */
    static int addSelection(String name) {
        ISelection[] selections = Util.baritone().getSelectionManager().getSelections();
        if (selections.length == 0) {
            Util.msg("Select the area first with #sel pos1 and #sel pos2, then run this again.");
            return 0;
        }
        for (int i = 0; i < selections.length; i++) {
            ISelection s = selections[i];
            String n = name == null ? freeName() : selections.length == 1 ? name : name + (i + 1);
            put(new Config.Zone(n, new BlockPos(s.min().x, s.min().y, s.min().z), new BlockPos(s.max().x, s.max().y, s.max().z)));
            Util.msg("Safe area '" + n + "' saved.");
        }
        Config.save();
        return 1;
    }

    private static boolean overlaps(int[] b, Config.Zone z) {
        return b[0] <= z.x2 && b[3] >= z.x1 && b[1] <= z.y2 && b[4] >= z.y1 && b[2] <= z.z2 && b[5] >= z.z1;
    }

    /** The box without the zone: up to six boxes. Boxes are {x1, y1, z1, x2, y2, z2}. */
    private static java.util.List<int[]> subtract(int[] b, Config.Zone z) {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        if (!overlaps(b, z)) {
            out.add(b);
            return out;
        }
        int ix1 = Math.max(b[0], z.x1), ix2 = Math.min(b[3], z.x2);
        int iy1 = Math.max(b[1], z.y1), iy2 = Math.min(b[4], z.y2);
        int iz1 = Math.max(b[2], z.z1), iz2 = Math.min(b[5], z.z2);
        if (b[0] < ix1) out.add(new int[]{b[0], b[1], b[2], ix1 - 1, b[4], b[5]});
        if (b[3] > ix2) out.add(new int[]{ix2 + 1, b[1], b[2], b[3], b[4], b[5]});
        if (b[1] < iy1) out.add(new int[]{ix1, b[1], b[2], ix2, iy1 - 1, b[5]});
        if (b[4] > iy2) out.add(new int[]{ix1, iy2 + 1, b[2], ix2, b[4], b[5]});
        if (b[2] < iz1) out.add(new int[]{ix1, iy1, b[2], ix2, iy2, iz1 - 1});
        if (b[5] > iz2) out.add(new int[]{ix1, iy1, iz2 + 1, ix2, iy2, b[5]});
        return out;
    }

    /**
     * Cuts every #sel selection around the safe areas, so "#sel ca" / "#sel replace" never target blocks inside them.
     * Returns true when something was cut. Called about once a second, so it is done before you type the command.
     */
    static boolean trimSelections() {
        var manager = Util.baritone().getSelectionManager();
        ISelection[] selections = manager.getSelections();
        if (selections.length == 0 || cfg.zones.isEmpty()) return false;

        boolean changed = false;
        java.util.List<int[]> result = new java.util.ArrayList<>();
        String zoneName = "";
        for (ISelection s : selections) {
            java.util.List<int[]> boxes = new java.util.ArrayList<>();
            boxes.add(new int[]{s.min().x, s.min().y, s.min().z, s.max().x, s.max().y, s.max().z});
            for (Config.Zone z : cfg.zones) {
                java.util.List<int[]> next = new java.util.ArrayList<>();
                for (int[] b : boxes) {
                    if (overlaps(b, z)) { changed = true; zoneName = z.name; }
                    next.addAll(subtract(b, z));
                }
                boxes = next;
            }
            result.addAll(boxes);
        }
        if (!changed) return false;

        manager.removeAllSelections();
        for (int[] b : result) {
            manager.addSelection(new baritone.api.utils.BetterBlockPos(b[0], b[1], b[2]), new baritone.api.utils.BetterBlockPos(b[3], b[4], b[5]));
        }
        Util.msg(result.isEmpty()
                ? "Your selection lay completely inside the safe area '" + zoneName + "', so it was removed."
                : "Your selection overlapped the safe area '" + zoneName + "': I cut it around it (" + result.size()
                + " box" + (result.size() == 1 ? "" : "es") + " now), so Baritone leaves the area alone. "
                + "(/buildtools protect trim off to stop this)");
        return true;
    }

    /** Sets corner 1 or 2 to the block you look at (or the one you stand in). Independent of #sel. */
    static int setCorner(int which) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;
        BlockPos p = mc.player.blockPosition();
        if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit
                && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            p = hit.getBlockPos();
        }
        int[] c = {p.getX(), p.getY(), p.getZ()};
        if (which == 1) cfg.corner1 = c; else cfg.corner2 = c;
        Config.save();
        Util.msg("Safe-area corner " + which + " set to " + p.toShortString() + ". "
                + (cfg.corner1 != null && cfg.corner2 != null ? "Both corners set: /buildtools protect save [name]." : "Now set the other corner."));
        return 1;
    }

    /** Turns the two hand-set corners into a safe area. */
    static int saveCorners(String name) {
        if (cfg.corner1 == null || cfg.corner2 == null) {
            Util.msg("Set both corners first (/buildtools protect pos1 and pos2).");
            return 0;
        }
        return addBox(cfg.corner1[0], cfg.corner1[1], cfg.corner1[2], cfg.corner2[0], cfg.corner2[1], cfg.corner2[2], name, true);
    }

    static int addBox(int x1, int y1, int z1, int x2, int y2, int z2, String name, boolean clearCorners) {
        String n = name == null ? freeName() : name;
        put(new Config.Zone(n, new BlockPos(x1, y1, z1), new BlockPos(x2, y2, z2)));
        if (clearCorners) { cfg.corner1 = null; cfg.corner2 = null; }
        Config.save();
        Util.msg("Safe area '" + n + "' saved (" + x1 + " " + y1 + " " + z1 + " to " + x2 + " " + y2 + " " + z2 + ").");
        return 1;
    }

    /** A cube around the player. */
    static int addAroundPlayer(int radius, String name) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;
        BlockPos c = mc.player.blockPosition();
        String n = name == null ? freeName() : name;
        put(new Config.Zone(n, c.offset(-radius, -radius, -radius), c.offset(radius, radius, radius)));
        Config.save();
        Util.msg("Safe area '" + n + "' saved: " + radius + " blocks around you.");
        return 1;
    }

    /** The saved chests and bed of LoadChest / SurvivalKit, each with a margin around it. */
    static int addSites(int radius) {
        int added = 0;
        added += site("survivalkit.json", "supply", "supply", radius);
        added += site("survivalkit.json", "returnChest", "return", radius);
        added += site("survivalkit.json", "bed", "bed", radius);
        added += site("loadchest.json", "chest", "deposit", radius);
        if (added == 0) {
            Util.msg("No saved chest or bed found. Set them first (/survival supply set, /survival bed set, /loadchest set).");
            return 0;
        }
        Config.save();
        return 1;
    }

    private static int site(String file, String key, String name, int radius) {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(file);
        if (!Files.exists(path)) return 0;
        try (Reader r = Files.newBufferedReader(path)) {
            JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
            if (!o.has(key) || !o.get(key).isJsonArray()) return 0;
            JsonArray a = o.getAsJsonArray(key);
            if (a.size() < 3) return 0;
            BlockPos p = new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
            put(new Config.Zone(name, p.offset(-radius, -radius, -radius), p.offset(radius, radius, radius)));
            Util.msg("Safe area '" + name + "' saved: " + radius + " blocks around " + p.toShortString() + ".");
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    static int remove(String name) {
        if (cfg.zones.removeIf(z -> z.name.equalsIgnoreCase(name))) {
            Config.save();
            Util.msg("Safe area '" + name + "' removed.");
            return 1;
        }
        Util.msg("No safe area called '" + name + "'. /buildtools protect list shows them.");
        return 0;
    }
}
