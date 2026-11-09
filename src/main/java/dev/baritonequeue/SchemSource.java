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
            Object sp = call(p, "getSubRegionPlacements");
            if (sp instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    Object s = e.getValue();
                    subs.add(new Sub(String.valueOf(e.getKey()), (BlockPos) call(s, "getPos"), (Rotation) call(s, "getRotation"),
                            (Mirror) call(s, "getMirror"), (Boolean) call(s, "isEnabled")));
                }
            }
            out.add(new Placement(String.valueOf(call(p, "getName")), file, (BlockPos) call(p, "getOrigin"), (Rotation) call(p, "getRotation"),
                    (Mirror) call(p, "getMirror"), (Boolean) call(p, "isEnabled"), subs, "live"));
        }
        return out;
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
