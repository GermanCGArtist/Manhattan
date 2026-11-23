package dev.manhattan;

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
    private static volatile List<Placement> fleetEmbedded = List.of();

    static void setFleetEmbedded(
            List<dev.manhattan.project.Project.EmbeddedSchematic> embedded,
            Map<String, String> sharedFiles
    ) {
        if (embedded == null || embedded.isEmpty()) {
            fleetEmbedded = List.of();
            return;
        }
        Path cacheDir = fleetCacheDir();
        Map<String, Path> writtenByHash = new java.util.HashMap<>();
        List<Placement> loaded = new ArrayList<>();
        for (dev.manhattan.project.Project.EmbeddedSchematic es : embedded) {
            if (es == null || es.name == null || es.name.isBlank()) continue;
            es.ensureInitialized();
            String b64 = es.dataBase64;
            if ((b64 == null || b64.isBlank()) && es.fileHash != null && sharedFiles != null) {
                b64 = sharedFiles.get(es.fileHash);
            }
            if (b64 == null || b64.isBlank()) continue;
            try {
                Path outFile = (es.fileHash != null && writtenByHash.containsKey(es.fileHash))
                        ? writtenByHash.get(es.fileHash)
                        : null;
                if (outFile == null) {
                    byte[] bytes = java.util.Base64.getDecoder().decode(b64);
                    if (bytes.length == 0) continue;
                    Files.createDirectories(cacheDir);
                    String hash = (es.fileHash != null && !es.fileHash.isBlank()) ? es.fileHash : shortHash(bytes);
                    String safeName = (es.fileName != null && !es.fileName.isBlank() ? es.fileName : es.name)
                            .replaceAll("\\.litematic$", "")
                            .replaceAll("[^A-Za-z0-9._-]+", "_");
                    outFile = cacheDir.resolve(safeName + "_" + hash + ".litematic");
                    if (!Files.exists(outFile) || Files.size(outFile) != bytes.length) {
                        Files.write(outFile, bytes);
                    }
                    if (es.fileHash != null) writtenByHash.put(es.fileHash, outFile);
                }
                List<Sub> subs = new ArrayList<>();
                for (dev.manhattan.project.Project.EmbeddedSubRegion sr : es.subs) {
                    if (sr == null || sr.name == null) continue;
                    int[] sp = (sr.pos != null && sr.pos.length >= 3) ? sr.pos : new int[]{0, 0, 0};
                    subs.add(new Sub(sr.name, new BlockPos(sp[0], sp[1], sp[2]),
                            parseRotation(sr.rotation), parseMirror(sr.mirror), sr.enabled));
                }
                BlockPos origin = new BlockPos(es.origin[0], es.origin[1], es.origin[2]);
                loaded.add(new Placement(es.name, outFile, origin,
                        parseRotation(es.rotation), parseMirror(es.mirror), es.enabled, subs, "fleet-embedded"));
            } catch (Throwable t) {
                System.out.println("[Queue] schem: failed to decode embedded fleet schematic '" + es.name + "': " + t);
            }
        }
        fleetEmbedded = loaded;
        if (!loaded.isEmpty()) {
            System.out.println("[Queue] schem: loaded " + loaded.size() + " embedded Fleet placement(s) ("
                    + Math.max(1, writtenByHash.size()) + " unique file(s)).");
        }
    }

    static dev.manhattan.project.Project.EmbeddedSchematic exportEmbedded(
            Placement pl,
            Map<String, String> sharedFiles
    ) {
        if (pl == null || pl.file() == null || !Files.isRegularFile(pl.file())) return null;
        try {
            byte[] bytes = Files.readAllBytes(pl.file());
            if (bytes.length == 0) return null;
            String hash = shortHash(bytes);
            dev.manhattan.project.Project.EmbeddedSchematic es = new dev.manhattan.project.Project.EmbeddedSchematic();
            es.name = pl.name();
            es.fileName = pl.file().getFileName() != null ? pl.file().getFileName().toString() : (pl.name() + ".litematic");
            es.origin = new int[]{pl.origin().getX(), pl.origin().getY(), pl.origin().getZ()};
            es.rotation = pl.rotation() != null ? pl.rotation().name() : "NONE";
            es.mirror = pl.mirror() != null ? pl.mirror().name() : "NONE";
            es.enabled = pl.enabled();
            if (pl.subs() != null) {
                for (Sub s : pl.subs()) {
                    es.subs.add(new dev.manhattan.project.Project.EmbeddedSubRegion(
                            s.name(),
                            new int[]{s.pos().getX(), s.pos().getY(), s.pos().getZ()},
                            s.rotation() != null ? s.rotation().name() : "NONE",
                            s.mirror() != null ? s.mirror().name() : "NONE",
                            s.enabled()
                    ));
                }
            }
            if (sharedFiles != null) {
                es.fileHash = hash;
                sharedFiles.computeIfAbsent(hash, k -> java.util.Base64.getEncoder().encodeToString(bytes));
            } else {
                es.dataBase64 = java.util.Base64.getEncoder().encodeToString(bytes);
            }
            return es;
        } catch (Throwable t) {
            System.out.println("[Queue] schem: failed to embed schematic '" + pl.name() + "': " + t);
            return null;
        }
    }

    private static Path fleetCacheDir() {
        try {
            return FabricLoader.getInstance().getConfigDir().resolve("manhattan-fleet-schems");
        } catch (Throwable ignored) {
            return Path.of("tmp", "manhattan-fleet-schems");
        }
    }

    static String placementFingerprint(Placement p) {
        if (p == null) return "";
        String n = p.name() == null ? "" : p.name().toLowerCase(java.util.Locale.ROOT);
        BlockPos o = p.origin() != null ? p.origin() : BlockPos.ZERO;
        String r = p.rotation() != null ? p.rotation().name() : "NONE";
        String m = p.mirror() != null ? p.mirror().name() : "NONE";
        return n + "@" + o.getX() + "," + o.getY() + "," + o.getZ() + "|" + r + "|" + m;
    }

    private static Rotation parseRotation(String raw) {
        if (raw == null || raw.isBlank()) return Rotation.NONE;
        try { return Rotation.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException ignored) { return Rotation.NONE; }
    }

    private static Mirror parseMirror(String raw) {
        if (raw == null || raw.isBlank()) return Mirror.NONE;
        try { return Mirror.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException ignored) { return Mirror.NONE; }
    }

    private static String shortHash(byte[] data) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(12);
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(java.util.Arrays.hashCode(data));
        }
    }

    static List<Placement> list() {
        List<Placement> embedded = fleetEmbedded;
        List<Placement> local = List.of();
        String localSrc = "Litematica config file";
        try {
            if (FabricLoader.getInstance().isModLoaded("litematica")) {
                List<Placement> live = fromLitematica();
                if (live != null && !live.isEmpty()) {
                    local = live;
                    localSrc = "Litematica (live)";
                }
            }
        } catch (Throwable t) {
            System.out.println("[Queue] schem: reading Litematica directly failed (" + t + "), using its config file instead");
        }
        if (local.isEmpty()) {
            try {
                local = fromConfig();
            } catch (Throwable ignored) {
                local = List.of();
            }
        }
        if (embedded == null || embedded.isEmpty()) {
            lastSource = localSrc;
            return local;
        }
        List<Placement> merged = new ArrayList<>(embedded);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Placement p : embedded) {
            seen.add(placementFingerprint(p));
        }
        for (Placement p : local) {
            if (seen.add(placementFingerprint(p))) {
                merged.add(p);
            }
        }
        lastSource = local.isEmpty() ? "Fleet embedded" : "Fleet embedded + " + localSrc;
        return merged;
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
            if (file == null || !Files.isRegularFile(file)) {
                // Support in-memory Litematica placements (e.g. Vitruvius assemblies/arrays not yet saved to disk)
                file = materializeInMemorySchematic(p);
            }
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

    private static Path materializeInMemorySchematic(Object placementObj) {
        try {
            if (!has(placementObj, "getSchematic")) return null;
            Object schem = call(placementObj, "getSchematic");
            if (schem == null) return null;
            Object tag = null;
            if (has(schem, "writeToTag")) tag = call(schem, "writeToTag");
            else if (has(schem, "writeToNBT")) tag = call(schem, "writeToNBT");
            if (!(tag instanceof net.minecraft.nbt.CompoundTag compound)) return null;
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            net.minecraft.nbt.NbtIo.writeCompressed(compound, baos);
            byte[] bytes = baos.toByteArray();
            if (bytes.length == 0) return null;
            Path cacheDir = fleetCacheDir();
            Files.createDirectories(cacheDir);
            String name = String.valueOf(call(placementObj, "getName")).replaceAll("[^A-Za-z0-9._-]+", "_");
            Path outFile = cacheDir.resolve("live_" + name + "_" + shortHash(bytes) + ".litematic");
            if (!Files.exists(outFile) || Files.size(outFile) != bytes.length) {
                Files.write(outFile, bytes);
            }
            return outFile;
        } catch (Throwable t) {
            System.out.println("[Queue] schem: could not materialize in-memory schematic: " + t);
            return null;
        }
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
