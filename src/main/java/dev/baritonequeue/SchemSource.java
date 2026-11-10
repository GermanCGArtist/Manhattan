package dev.baritonequeue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

import java.io.File;
import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The user's Litematica schematic placements. Primary source: the running Litematica (found by reflection, so this mod
 * loads and works without it). If Litematica is not there or its classes look different, the same data is read from
 * Litematica's own per-world file in config/litematica/.
 */
final class SchemSource {
    private SchemSource() {}

    record Sub(String name, BlockPos pos, Rotation rotation, Mirror mirror, boolean enabled) {}

    record Placement(String name, Path file, BlockPos origin, Rotation rotation, Mirror mirror, boolean enabled, List<Sub> subs, String via) {}

    static String lastSource = "";

    static List<Placement> list() {
        try {
            if (FabricLoader.getInstance().isModLoaded("litematica")) {
                List<Placement> live = fromLitematica();
                if (live != null && !live.isEmpty()) {
                    lastSource = "Litematica (live)";
                    return live;
                }
            }
        } catch (Throwable t) {
            System.out.println("[Queue] schem: reading Litematica directly failed (" + t + "), using its config file instead");
        }
        List<Placement> file = fromConfig();
        lastSource = "Litematica config file";
        return file;
    }

    // ------------------------------------------------------------------ live, by reflection

    private static Object call(Object o, String name) throws Exception {
        for (Method m : o.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 0) return m.invoke(o);
        }
        throw new NoSuchMethodException(o.getClass().getName() + "." + name + "()");
    }

    private static boolean has(Object o, String name) {
        for (Method m : o.getClass().getMethods()) if (m.getName().equals(name) && m.getParameterCount() == 0) return true;
        return false;
    }

    private static List<Placement> fromLitematica() throws Exception {
        Class<?> dm = Class.forName("fi.dy.masa.litematica.data.DataManager");
        Object mgr = dm.getMethod("getSchematicPlacementManager").invoke(null);
        Object all = call(mgr, "getAllSchematicsPlacements");
        if (!(all instanceof Collection<?> c)) return null;
        List<Placement> out = new ArrayList<>();
        for (Object p : c) {
            Object fileObj = call(p, "getSchematicFile");
            Path file = fileObj instanceof Path pp ? pp : fileObj instanceof File ff ? ff.toPath() : null;
            if (file == null) continue;
            List<Sub> subs = new ArrayList<>();
            // newer Litematica (0.27.x): getAllSubRegionsPlacements() -> Collection; older: getSubRegionPlacements() -> Map
            Collection<?> regions;
            if (has(p, "getAllSubRegionsPlacements")) regions = (Collection<?>) call(p, "getAllSubRegionsPlacements");
            else regions = ((Map<?, ?>) call(p, "getSubRegionPlacements")).values();
            for (Object s : regions) {
                subs.add(new Sub(String.valueOf(call(s, "getName")), (BlockPos) call(s, "getPos"), (Rotation) call(s, "getRotation"),
                        (Mirror) call(s, "getMirror"), (Boolean) call(s, "isEnabled")));
            }
            out.add(new Placement(String.valueOf(call(p, "getName")), file, (BlockPos) call(p, "getOrigin"), (Rotation) call(p, "getRotation"),
                    (Mirror) call(p, "getMirror"), (Boolean) call(p, "isEnabled"), subs, "live"));
        }
        return out;
    }

    /**
     * Moves Litematica placements by an offset, the way its own "move" does (live, so it renders and saves as usual).
     * which: 1-based placement numbers as /schem list shows them, or null for every placement. Returns an error or null.
     */
    static String move(List<Integer> which, int dx, int dy, int dz) {
        if (!FabricLoader.getInstance().isModLoaded("litematica")) return "Litematica is not installed.";
        try {
            Class<?> dm = Class.forName("fi.dy.masa.litematica.data.DataManager");
            Object mgr = dm.getMethod("getSchematicPlacementManager").invoke(null);
            Object all = call(mgr, "getAllSchematicsPlacements");
            if (!(all instanceof List<?> list) || list.isEmpty()) return moveInFile(which, dx, dy, dz);
            Class<?> consumer = Class.forName("fi.dy.masa.malilib.interfaces.IStringConsumer");
            List<String> said = new ArrayList<>();
            Object feedback = java.lang.reflect.Proxy.newProxyInstance(consumer.getClassLoader(), new Class<?>[]{consumer}, (proxy, m, args) -> {
                switch (m.getName()) {
                    case "setString" -> { if (args != null && args.length == 1) said.add(String.valueOf(args[0])); return null; }
                    case "hashCode" -> { return System.identityHashCode(proxy); }
                    case "equals" -> { return proxy == args[0]; }
                    case "toString" -> { return "Queue feedback"; }
                    default -> { return null; }
                }
            });
            List<Integer> idx = new ArrayList<>();
            if (which == null) for (int i = 1; i <= list.size(); i++) idx.add(i);
            else idx.addAll(which);
            for (int n : idx) if (n < 1 || n > list.size()) return "There is no placement " + n + " (there are " + list.size() + "). /schem list shows them.";
            int moved = 0;
            for (int n : idx) {
                Object p = list.get(n - 1);
                BlockPos from = (BlockPos) call(p, "getOrigin");
                BlockPos to = from.offset(dx, dy, dz);
                Method set = p.getClass().getMethod("setOrigin", BlockPos.class, consumer);
                said.clear();
                set.invoke(p, to, feedback);
                BlockPos now = (BlockPos) call(p, "getOrigin");
                if (now.equals(to)) {
                    moved++;
                    Util.msg("Moved placement " + n + " (" + call(p, "getName") + ") from " + from.toShortString() + " to " + to.toShortString() + ".");
                } else {
                    Util.msg("Placement " + n + " (" + call(p, "getName") + ") did not move" + (said.isEmpty() ? " (is it locked?)" : ": " + String.join(" ", said)) + ".");
                }
            }
            return moved > 0 ? null : "Nothing was moved.";
        } catch (Throwable t) {
            return "Could not move the placements (" + t + ").";
        }
    }

    /** No live placements (Litematica has not loaded them): move them in its per-world file, which /schem reads then. */
    private static String moveInFile(List<Integer> which, int dx, int dy, int dz) {
        Path p = configFile();
        if (p == null) return "No Litematica placements found. Load a schematic in Litematica first.";
        try {
            JsonObject root;
            try (Reader r = Files.newBufferedReader(p)) { root = JsonParser.parseReader(r).getAsJsonObject(); }
            JsonElement pl = root.get("placements");
            if (pl == null || !pl.isJsonArray() || pl.getAsJsonArray().isEmpty()) return "No Litematica placements found. Load a schematic in Litematica first.";
            var arr = pl.getAsJsonArray();
            List<Integer> idx = new ArrayList<>();
            if (which == null) for (int i = 1; i <= arr.size(); i++) idx.add(i);
            else idx.addAll(which);
            for (int n : idx) if (n < 1 || n > arr.size()) return "There is no placement " + n + " (there are " + arr.size() + "). /schem list shows them.";
            for (int n : idx) {
                JsonObject o = arr.get(n - 1).getAsJsonObject();
                BlockPos from = pos(o.getAsJsonArray("origin"));
                BlockPos to = from.offset(dx, dy, dz);
                var a = new com.google.gson.JsonArray();
                a.add(to.getX()); a.add(to.getY()); a.add(to.getZ());
                o.add("origin", a);
                Util.msg("Moved placement " + n + " (" + o.get("name").getAsString() + ") from " + from.toShortString() + " to " + to.toShortString() + " (in Litematica's file).");
            }
            Files.writeString(p, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(root));
            return null;
        } catch (Exception ex) {
            return "Could not move the placements in " + p.getFileName() + " (" + ex + ").";
        }
    }

    // ------------------------------------------------------------------ config file

    private static Rotation rot(String s) {
        try {
            return Rotation.valueOf(s.toUpperCase());
        } catch (Exception e) {
            return Rotation.NONE;
        }
    }

    private static Mirror mir(String s) {
        try {
            return Mirror.valueOf(s.toUpperCase());
        } catch (Exception e) {
            return Mirror.NONE;
        }
    }

    private static BlockPos pos(JsonArray a) {
        return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
    }

    /** The config file of this world and dimension: by world name or server address when it matches, else the newest. */
    static Path configFile() {
        Minecraft mc = Minecraft.getInstance();
        String dim = mc.level == null ? "overworld" : mc.level.dimension().identifier().getPath();
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("litematica");
        String hint = "";
        if (mc.getCurrentServer() != null) hint = mc.getCurrentServer().ip.replace(':', '_');
        else if (mc.getSingleplayerServer() != null) {
            Path root = mc.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
            hint = root.toAbsolutePath().normalize().getFileName().toString();
        }
        File[] files = dir.toFile().listFiles((d, n) -> n.endsWith("_dim_minecraft_" + dim + ".json") || n.endsWith("_dim_" + dim + ".json"));
        if (files == null) return null;
        File best = null;
        int bestScore = -1;
        for (File f : files) {
            int score = (!hint.isEmpty() && f.getName().contains(hint)) ? 2 : 0;
            if (best == null || score > bestScore || (score == bestScore && f.lastModified() > best.lastModified())) {
                best = f;
                bestScore = score;
            }
        }
        return best == null ? null : best.toPath();
    }

    private static List<Placement> fromConfig() {
        List<Placement> out = new ArrayList<>();
        Path p = configFile();
        if (p == null) return out;
        try (Reader r = Files.newBufferedReader(p)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            JsonElement pl = root.get("placements");
            if (pl == null || !pl.isJsonArray()) return out;
            for (JsonElement e : pl.getAsJsonArray()) {
                JsonObject o = e.getAsJsonObject();
                List<Sub> subs = new ArrayList<>();
                if (o.has("placements")) {
                    for (JsonElement se : o.getAsJsonArray("placements")) {
                        JsonObject so = se.getAsJsonObject(), sp = so.getAsJsonObject("placement");
                        subs.add(new Sub(so.get("name").getAsString(), pos(sp.getAsJsonArray("pos")), rot(sp.get("rotation").getAsString()),
                                mir(sp.get("mirror").getAsString()), sp.get("enabled").getAsBoolean()));
                    }
                }
                out.add(new Placement(o.get("name").getAsString(), Path.of(o.get("schematic").getAsString()), pos(o.getAsJsonArray("origin")),
                        rot(o.get("rotation").getAsString()), mir(o.get("mirror").getAsString()), o.get("enabled").getAsBoolean(), subs, p.getFileName().toString()));
            }
        } catch (Exception ex) {
            System.out.println("[Queue] schem: cannot read " + p + ": " + ex);
        }
        return out;
    }
}
