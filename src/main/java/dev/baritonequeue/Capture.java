package dev.baritonequeue;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.baritonequeue.Config.cfg;

/**
 * Capture sheets: chests, beds, areas and schematics taken from the world into a text that is plain script, to paste
 * into a script or hand to an LLM. One sheet per world and set, kept as a file (manhattan-context/<world>_<set>.txt),
 * so an LLM with file access can read it directly. The menu is {@link CaptureScreen}; aiming in the world uses the
 * script editor's key (J), and with rapid capture on every press adds a line until K brings the menu back.
 */
final class Capture {
    private Capture() {}

    enum Kind { POINT, AREA, SCHEMATIC }

    /** The roles. The id is the first word of the line's comment, so the sheet can be read back. */
    enum Type {
        DUMP("dump", "dump", "dump_chest", Kind.POINT, "dump() puts the inventory in here"),
        SUPPLY("supply", "supply / tools", "tool_chest", Kind.POINT, "restock() takes tools and food from here"),
        RETURN("return", "return chest", "return_chest", Kind.POINT, "worn-out tools go here"),
        MATERIAL("material", "material chest", "material_chest", Kind.POINT, "blocks for fill_water / building"),
        SCAFFOLDING("scaffolding", "scaffolding chest", "scaffold_chest", Kind.POINT, "blocks to bridge and climb with"),
        BED("bed", "bed", "bed", Kind.POINT, "sleep() uses it"),
        MINE("mine", "mine area", "mine", Kind.AREA, "clear_area() mines it out"),
        FILL("fill", "fill area", "fill", Kind.AREA, "fill_water() fills its water"),
        BUILD("build", "building block area", "build", Kind.AREA, "where blocks are built"),
        SAFE("safe", "safe area", "protect", Kind.AREA, "Baritone never breaks or places blocks here"),
        SPOT("spot", "other spot (named)", "spot", Kind.POINT, ""),
        AREA("area", "other area (named)", "area", Kind.AREA, ""),
        SCHEMATIC("schematic", "schematic", "schematic", Kind.SCHEMATIC, "build() builds it here");

        final String id, label, base, hint;
        final Kind kind;

        Type(String id, String label, String base, Kind kind, String hint) {
            this.id = id;
            this.label = label;
            this.base = base;
            this.kind = kind;
            this.hint = hint;
        }

        boolean chest() {
            return this == DUMP || this == SUPPLY || this == RETURN || this == MATERIAL || this == SCAFFOLDING;
        }

        static Type of(String id) {
            for (Type t : values()) if (t.id.equals(id)) return t;
            return DUMP;
        }
    }

    // ------------------------------------------------------------------ state

    /** The menu is closed and the player aims; J captures, K goes back to the menu. */
    static boolean aiming;
    /** The first corner of the box being captured (null: none yet). */
    static Values.Pos corner1;
    /** The name typed in the menu ("" = automatic) and the schematic file picked there. */
    static String name = "";
    static String schematic = null;
    /** Captured since aiming started (for the hint). */
    static int captured;

    private static String text;          // the open sheet
    private static Path textFile;        // where it came from
    private static long textStamp;
    private static List<CaptureSheet.Entry> entries = List.of();
    private static String entriesOf;

    static Type type() {
        return Type.of(cfg.captureType);
    }

    // ------------------------------------------------------------------ files

    static Path dir() {
        String d = cfg.captureDir == null ? "" : cfg.captureDir.trim();
        return d.isEmpty() ? Minecraft.getInstance().gameDirectory.toPath().resolve("manhattan-context") : Path.of(d);
    }

    /** The world (singleplayer save name or server address) as a file name. */
    static String world() {
        Minecraft mc = Minecraft.getInstance();
        String w;
        if (mc.getSingleplayerServer() != null) w = mc.getSingleplayerServer().getWorldData().getLevelName();
        else if (mc.getCurrentServer() != null) w = mc.getCurrentServer().ip;
        else w = "no-world";
        return w.replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    static Path file() {
        return dir().resolve(world() + "_" + cfg.captureSet + ".txt");
    }

    static Path contextFile() {
        return dir().resolve(world() + "_" + cfg.captureSet + ".context.txt");
    }

    /** The sets of this world that have a file. */
    static List<String> sets() {
        List<String> out = new ArrayList<>();
        String prefix = world() + "_";
        try (var s = Files.list(dir())) {
            s.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith(prefix) && n.endsWith(".txt") && !n.endsWith(".context.txt"))
                    .map(n -> n.substring(prefix.length(), n.length() - 4))
                    .sorted().forEach(out::add);
        } catch (IOException ignored) {
            // no folder yet
        }
        if (!out.contains(cfg.captureSet)) out.add(0, cfg.captureSet);
        return out;
    }

    /** The sheet, read again when the file changed (an LLM or an editor may have written it). */
    private static long lastLook;

    static String text() {
        long now = System.currentTimeMillis();
        if (text != null && now - lastLook < 1000) return text;   // look at the file about once a second
        lastLook = now;
        Path f = file();
        long stamp = -1;
        try {
            if (Files.exists(f)) stamp = Files.getLastModifiedTime(f).toMillis();
        } catch (IOException ignored) {}
        if (text == null || !f.equals(textFile) || (stamp > 0 && stamp != textStamp)) {
            textFile = f;
            textStamp = stamp;
            try {
                text = stamp > 0 ? Files.readString(f, StandardCharsets.UTF_8).replace("\r\n", "\n") : CaptureSheet.header(world(), cfg.captureSet);
            } catch (IOException e) {
                text = CaptureSheet.header(world(), cfg.captureSet);
            }
        }
        return text;
    }

    static void setText(String t) {
        if (t.equals(text)) return;
        text = t;
        save();
    }

    static void save() {
        if (text == null) return;
        try {
            Files.createDirectories(dir());
            Path f = file();
            Files.writeString(f, text.replace("\n", System.lineSeparator()), StandardCharsets.UTF_8);
            textFile = f;
            textStamp = Files.getLastModifiedTime(f).toMillis();
        } catch (IOException e) {
            Util.warn("capture-save", "Could not write the capture sheet to " + file() + ": " + e.getMessage());
        }
    }

    /** Switches to another set (made when new). */
    static String openSet(String set) {
        String s = CaptureSheet.cleanName(set);
        if (s.isEmpty()) return "A set name is letters, digits and _ (for example main, outpost).";
        save();
        cfg.captureSet = s;
        Config.save();
        text = null;
        lastLook = 0;
        text();
        return "Set '" + s + "' open (" + file() + ").";
    }

    static List<CaptureSheet.Entry> entries() {
        String t = text();
        if (!t.equals(entriesOf)) {
            entriesOf = t;
            entries = CaptureSheet.entries(t);
        }
        return entries;
    }

    // ------------------------------------------------------------------ capturing

    private static String nameFor(Type t, String fallback, Set<String> taken) {
        String n = CaptureSheet.cleanName(name);
        if (!n.isEmpty()) return t.kind == Kind.AREA ? n : CaptureSheet.unique(n, taken, false);
        return CaptureSheet.unique(fallback, taken, t.kind == Kind.AREA);
    }

    /** What the chest held when last open: "holds: 1200 dirt, 3 diamond_pickaxe", or "". */
    private static String holds(BlockPos at) {
        Map<String, Integer> m = dev.survivalkit.Api.chestContents(at);
        if (m == null || m.isEmpty()) return "";
        List<Map.Entry<String, Integer>> l = new ArrayList<>(m.entrySet());
        l.sort((a, b) -> b.getValue() - a.getValue());
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < l.size() && i < 6; i++) parts.add(l.get(i).getValue() + " " + l.get(i).getKey().replace("minecraft:", ""));
        return "holds: " + String.join(", ", parts) + (l.size() > 6 ? " ..." : "");
    }

    private static String mostItem(BlockPos at) {
        Map<String, Integer> m = dev.survivalkit.Api.chestContents(at);
        if (m == null) return null;
        String best = null;
        int n = 0;
        for (var e : m.entrySet()) if (e.getValue() > n) { n = e.getValue(); best = e.getKey().replace("minecraft:", ""); }
        return best;
    }

    /** Takes the block for the chosen role; returns what happened (for the chat or the menu). */
    static String capture(Places.Target t) {
        if (t == null) return "Look at a block first.";
        Type ty = type();
        String sheet = text();
        Set<String> taken = CaptureSheet.names(sheet);
        Values.Pos p = t.pos();
        switch (ty.kind) {
            case SCHEMATIC -> {
                if (schematic == null) return "Pick a schematic file in the menu first.";
                String base = CaptureSheet.cleanName(schematic.replaceAll("\\.[A-Za-z]+$", ""));
                String n = nameFor(ty, base.isEmpty() ? "schematic" : base, taken);
                Values.Pos o = t.front();
                setText(CaptureSheet.add(sheet, CaptureSheet.SCHEMATICS, "let " + n + " = schematic(\"" + schematic + "\", "
                        + CaptureSheet.pos(o.x(), o.y(), o.z()) + ")   // schematic: build(" + n + ") builds it with its lowest corner here"));
                captured++;
                return "Schematic " + schematic + " as '" + n + "', lowest corner at " + o + ".";
            }
            case AREA -> {
                if (corner1 == null) {
                    corner1 = p;
                    return "Corner 1 at " + p + ". Now the opposite corner.";
                }
                Values.Pos a = corner1;
                corner1 = null;
                return addBox(ty, a.x(), a.y(), a.z(), p.x(), p.y(), p.z());
            }
            default -> {
                String block = t.name();
                String base = ty.base;
                if (ty == Type.MATERIAL) {
                    String item = mostItem(p.block());
                    if (item != null) base = CaptureSheet.cleanName(item) + "_chest";
                }
                String n = nameFor(ty, base, taken);
                StringBuilder c = new StringBuilder("   // ").append(ty.id);
                if (!ty.hint.isEmpty()) c.append(": ").append(ty.hint);
                c.append("; ").append(block);
                String h = holds(p.block());
                if (!h.isEmpty()) c.append("; ").append(h);
                setText(CaptureSheet.add(sheet, CaptureSheet.PLACES, "let " + n + " = " + CaptureSheet.pos(p.x(), p.y(), p.z()) + c));
                captured++;
                String warn = "";
                if (ty.chest() && !container(p.block())) warn = " (that is a " + block + ", not a chest)";
                if (ty == Type.BED && !block.endsWith("_bed")) warn = " (that is a " + block + ", not a bed)";
                return "Captured " + n + " at " + p + warn + ".";
            }
        }
    }

    private static String addBox(Type ty, int x1, int y1, int z1, int x2, int y2, int z2) {
        String sheet = text();
        String n = nameFor(ty, ty.base, CaptureSheet.names(sheet));
        int[] b = {Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2), Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2)};
        String fn = ty == Type.SAFE ? "protect" : "area_box";
        String comment = "   // " + ty.id + (ty.hint.isEmpty() ? "" : ": " + ty.hint) + "; " + CaptureSheet.box(b);
        setText(CaptureSheet.add(sheet, CaptureSheet.AREAS, fn + "(\"" + n + "\", " + CaptureSheet.pos(b[0], b[1], b[2]) + ", "
                + CaptureSheet.pos(b[3], b[4], b[5]) + ")" + comment));
        captured++;
        return "Captured " + (ty == Type.SAFE ? "safe area" : "area") + " '" + n + "': " + CaptureSheet.box(b) + ".";
    }

    /** The current Baritone selection (#sel) as boxes of the chosen area role. */
    static String useSelection() {
        Type ty = type();
        if (ty.kind != Kind.AREA) return "Choose an area role first (mine, fill, building block, safe or other area).";
        List<int[]> boxes = Areas.read();
        if (boxes.isEmpty()) return "There is no selection (#sel pos1 / pos2).";
        String keep = name;
        String last = "";
        try {
            if (CaptureSheet.cleanName(name).isEmpty() && boxes.size() > 1) {   // several boxes: one area of that many boxes
                name = CaptureSheet.unique(ty.base, CaptureSheet.names(text()), true);
            }
            for (int[] b : boxes) last = addBox(ty, b[0], b[1], b[2], b[3], b[4], b[5]);
        } finally {
            name = keep;
        }
        return boxes.size() == 1 ? last : "Captured the selection's " + boxes.size() + " boxes.";
    }

    /** The chests and areas saved in the game (/queue chest set, /queue area save) that the sheet does not have yet. */
    static String importSaved() {
        int n = 0;
        String sheet = text();
        Set<String> taken = CaptureSheet.names(sheet);
        for (var e : cfg.chests.entrySet()) {
            int[] c = e.getValue();
            String nm = CaptureSheet.cleanName(e.getKey());
            if (taken.contains(nm)) continue;
            sheet = CaptureSheet.add(sheet, CaptureSheet.PLACES, "let " + nm + " = " + CaptureSheet.pos(c[0], c[1], c[2])
                    + "   // spot: saved chest (also chest(\"" + e.getKey() + "\"))" + (holds(new BlockPos(c[0], c[1], c[2])).isEmpty() ? "" : "; " + holds(new BlockPos(c[0], c[1], c[2]))));
            taken.add(nm);
            n++;
        }
        for (var e : cfg.areas.entrySet()) {
            if (taken.contains(e.getKey())) continue;
            for (int[] b : e.getValue()) {
                sheet = CaptureSheet.add(sheet, CaptureSheet.AREAS, "area_box(\"" + e.getKey() + "\", " + CaptureSheet.pos(b[0], b[1], b[2]) + ", "
                        + CaptureSheet.pos(b[3], b[4], b[5]) + ")   // area: saved area; " + CaptureSheet.box(b));
            }
            taken.add(e.getKey());
            n++;
        }
        setText(sheet);
        return n == 0 ? "Nothing new: the sheet has every saved chest and area." : "Imported " + n + " saved chest" + (n == 1 ? "" : "s") + " and areas.";
    }

    // ------------------------------------------------------------------ schematics

    static Path schematicsDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("schematics");
    }

    static List<String> schematics() {
        List<String> out = new ArrayList<>();
        try (var s = Files.list(schematicsDir())) {
            s.map(p -> p.getFileName().toString())
                    .filter(n -> n.matches("(?i).*\\.(schem|schematic|litematic)$"))
                    .sorted(String.CASE_INSENSITIVE_ORDER).forEach(out::add);
        } catch (IOException ignored) {}
        return out;
    }

    /** The file name in the schematics folder ("house" finds house.schem); null when there is none. */
    static String schematicFile(String name) {
        Path d = schematicsDir();
        if (Files.isRegularFile(d.resolve(name))) return name;
        for (String ext : new String[]{".schem", ".litematic", ".schematic"}) if (Files.isRegularFile(d.resolve(name + ext))) return name + ext;
        return null;
    }

    // ------------------------------------------------------------------ checks

    private static boolean container(BlockPos at) {
        Level level = Minecraft.getInstance().level;
        return level != null && level.getBlockEntity(at) instanceof Container;
    }

    /** Lines whose block is no longer what the role needs (only where the chunk is loaded). */
    static List<String> stale() {
        List<String> out = new ArrayList<>();
        Level level = Minecraft.getInstance().level;
        if (level == null) return out;
        for (CaptureSheet.Entry e : entries()) {
            if (!e.active() || e.pos() == null || isFine(level, e)) continue;
            BlockPos at = new BlockPos(e.pos()[0], e.pos()[1], e.pos()[2]);
            out.add(e.name() + " (line " + e.line() + ") is now " + BuiltInRegistries.BLOCK.getKey(level.getBlockState(at).getBlock()).getPath());
        }
        return out;
    }

    private static boolean isFine(Level level, CaptureSheet.Entry e) {
        BlockPos at = new BlockPos(e.pos()[0], e.pos()[1], e.pos()[2]);
        if (!level.hasChunkAt(at)) return true;
        Type t = Type.of(e.role());
        if (!e.role().equals(t.id)) return true;   // not a role (an imported line, a hand-written one)
        if (t.chest()) return level.getBlockEntity(at) instanceof Container;
        if (t == Type.BED) return level.getBlockState(at).getBlock() instanceof BedBlock;
        return true;
    }

    // ------------------------------------------------------------------ outlines in the world

    private static int pulse;

    /** Every client tick: particles on the captured places and boxes (and the box being captured while aiming). */
    static void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            aiming = false;
            corner1 = null;
            return;
        }
        if (++pulse % 10 != 0) return;
        if (aiming && corner1 != null) {
            Places.Target t = Places.target();
            if (t != null) {
                Values.Pos p = t.pos();
                outline(mc.level, new int[]{Math.min(corner1.x(), p.x()), Math.min(corner1.y(), p.y()), Math.min(corner1.z(), p.z()),
                        Math.max(corner1.x(), p.x()), Math.max(corner1.y(), p.y()), Math.max(corner1.z(), p.z())}, ParticleTypes.ELECTRIC_SPARK, new int[]{600});
            }
        }
        boolean menu = mc.screen instanceof CaptureScreen;
        if (!cfg.captureShow || !(menu || aiming || mc.screen == null)) return;
        if (!menu && !aiming && pulse % 40 != 0) return;   // in normal play only every 2 s
        int[] budget = {1500};
        var me = mc.player.blockPosition();
        for (CaptureSheet.Entry e : entries()) {
            if (!e.active()) continue;
            if (e.box() != null) {
                int[] b = e.box();
                if (me.getX() < b[0] - 96 || me.getX() > b[3] + 96 || me.getZ() < b[2] - 96 || me.getZ() > b[5] + 96) continue;
                outline(mc.level, b, e.role().equals("safe") ? ParticleTypes.SMALL_FLAME : ParticleTypes.END_ROD, budget);
            } else {
                int[] p = e.pos();
                if (Math.abs(p[0] - me.getX()) > 96 || Math.abs(p[2] - me.getZ()) > 96) continue;
                ParticleOptions kind = isFine(mc.level, e) ? ParticleTypes.HAPPY_VILLAGER : ParticleTypes.FLAME;
                for (double dx = 0.1; dx < 1; dx += 0.8) {
                    for (double dz = 0.1; dz < 1; dz += 0.8) mc.level.addParticle(kind, p[0] + dx, p[1] + 1.05, p[2] + dz, 0, 0, 0);
                }
                mc.level.addParticle(kind, p[0] + 0.5, p[1] + 1.4, p[2] + 0.5, 0, 0, 0);
            }
        }
    }

    /** The 12 edges of a box, at most about 40 particles an edge. */
    private static void outline(Level level, int[] b, ParticleOptions kind, int[] budget) {
        double x0 = b[0], y0 = b[1], z0 = b[2], x1 = b[3] + 1, y1 = b[4] + 1, z1 = b[5] + 1;
        double[][] edges = {
                {x0, y0, z0, x1, y0, z0}, {x0, y1, z0, x1, y1, z0}, {x0, y0, z1, x1, y0, z1}, {x0, y1, z1, x1, y1, z1},
                {x0, y0, z0, x0, y1, z0}, {x1, y0, z0, x1, y1, z0}, {x0, y0, z1, x0, y1, z1}, {x1, y0, z1, x1, y1, z1},
                {x0, y0, z0, x0, y0, z1}, {x1, y0, z0, x1, y0, z1}, {x0, y1, z0, x0, y1, z1}, {x1, y1, z0, x1, y1, z1}};
        for (double[] e : edges) {
            double len = Math.max(Math.abs(e[3] - e[0]), Math.max(Math.abs(e[4] - e[1]), Math.abs(e[5] - e[2])));
            int steps = (int) Math.min(40, Math.max(1, len));
            for (int i = 0; i <= steps && budget[0] > 0; i++, budget[0]--) {
                double f = (double) i / steps;
                level.addParticle(kind, e[0] + (e[3] - e[0]) * f, e[1] + (e[4] - e[1]) * f, e[2] + (e[5] - e[2]) * f, 0, 0, 0);
            }
        }
    }

    // ------------------------------------------------------------------ full context for an LLM

    private static final long MAX_COUNT = 3_000_000;

    /** The sheet plus what an LLM needs to write the script: the world, the inventory, the area contents, the functions. */
    static String fullContext() {
        Minecraft mc = Minecraft.getInstance();
        StringBuilder sb = new StringBuilder();
        String version = FabricLoader.getInstance().getModContainer("baritonequeue")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
        sb.append("// === Manhattan context (").append(java.time.LocalDateTime.now().withNano(0).toString().replace('T', ' ')).append(") ===\n");
        sb.append("// Mod Manhattan ").append(version).append(" (Minecraft script language for Baritone). The script language is described in\n");
        sb.append("// docs/SCRIPTING.md of the mod; the built-in functions are listed at the end of this text.\n");
        if (mc.player != null && mc.level != null) {
            var p = mc.player.blockPosition();
            long t = mc.level.getOverworldClockTime() % 24000L;
            sb.append("// world: ").append(world()).append(", dimension ").append(mc.level.dimension().identifier().getPath())
                    .append(", player at ").append(p.getX()).append(' ').append(p.getY()).append(' ').append(p.getZ())
                    .append(", time of day ").append(t).append(t >= 12542 && t < 23460 ? " (night)" : " (day)")
                    .append(", health ").append((int) mc.player.getHealth()).append(", food ").append(mc.player.getFoodData().getFoodLevel()).append('\n');
            Map<String, Integer> inv = new LinkedHashMap<>();
            for (int k = 0; k < 36; k++) {
                ItemStack st = mc.player.getInventory().getItem(k);
                if (st.isEmpty()) continue;
                String id = BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
                if (st.isDamageableItem()) id += " (" + (st.getMaxDamage() - st.getDamageValue()) + "/" + st.getMaxDamage() + ")";
                inv.merge(id, st.getCount(), Integer::sum);
            }
            List<String> parts = new ArrayList<>();
            inv.forEach((k, v) -> parts.add(v + " " + k));
            sb.append("// inventory: ").append(parts.isEmpty() ? "empty" : String.join(", ", parts)).append('\n');
        }
        Map<String, Integer> want = dev.survivalkit.Api.wantList();
        if (!want.isEmpty()) {
            List<String> parts = new ArrayList<>();
            want.forEach((k, v) -> parts.add(k + " " + v));
            sb.append("// want-list (restock keeps this with you): ").append(String.join(", ", parts)).append('\n');
        }
        if (!cfg.areas.isEmpty()) sb.append("// areas saved in the game (area(\"name\")): ").append(String.join(", ", cfg.areas.keySet())).append('\n');
        if (!cfg.chests.isEmpty()) sb.append("// chests saved in the game (chest(\"name\")): ").append(String.join(", ", cfg.chests.keySet())).append('\n');
        List<String> stats = areaStats();
        if (!stats.isEmpty()) {
            sb.append("// what is in the areas of the sheet (loaded chunks only):\n");
            for (String s : stats) sb.append("//   ").append(s).append('\n');
        }
        List<String> stale = stale();
        if (!stale.isEmpty()) {
            sb.append("// check these, the block is not what the line says:\n");
            for (String s : stale) sb.append("//   ").append(s).append('\n');
        }
        List<String> files = schematics();
        if (!files.isEmpty()) sb.append("// schematics folder: ").append(String.join(", ", files)).append('\n');
        sb.append("\n").append(text().strip()).append("\n\n");
        sb.append("// --- built-in functions (name(args): what it does)\n");
        for (Builtins.Builtin b : Builtins.all().values()) {
            sb.append("// ").append(b.signature).append(": ").append(b.desc).append('\n');
        }
        String out = sb.toString();
        try {
            Files.createDirectories(dir());
            Files.writeString(contextFile(), out.replace("\n", System.lineSeparator()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Util.warn("capture-context", "Could not write " + contextFile() + ": " + e.getMessage());
        }
        return out;
    }

    /** Per area name: blocks, air, water cells and the most common blocks. */
    private static List<String> areaStats() {
        List<String> out = new ArrayList<>();
        Level level = Minecraft.getInstance().level;
        if (level == null) return out;
        Map<String, List<int[]>> byName = new LinkedHashMap<>();
        for (CaptureSheet.Entry e : entries()) if (e.active() && e.box() != null) byName.computeIfAbsent(e.name(), k -> new ArrayList<>()).add(e.box());
        long budget = MAX_COUNT;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (var en : byName.entrySet()) {
            long cells = 0, air = 0, wet = 0, unloaded = 0;
            Map<String, Integer> kinds = new LinkedHashMap<>();
            boolean tooBig = false;
            for (int[] b : en.getValue()) {
                long size = (long) (b[3] - b[0] + 1) * (b[4] - b[1] + 1) * (b[5] - b[2] + 1);
                cells += size;
                if (size > budget) { tooBig = true; continue; }
                budget -= size;
                for (int x = b[0]; x <= b[3]; x++) {
                    for (int z = b[2]; z <= b[5]; z++) {
                        m.set(x, b[1], z);
                        if (!level.hasChunkAt(m)) { unloaded += b[4] - b[1] + 1; continue; }
                        for (int y = b[1]; y <= b[4]; y++) {
                            m.setY(y);
                            BlockState s = level.getBlockState(m);
                            if (s.isAir()) { air++; continue; }
                            if (s.getFluidState().is(FluidTags.WATER) && !s.blocksMotion()) { wet++; continue; }
                            kinds.merge(BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath(), 1, Integer::sum);
                        }
                    }
                }
            }
            List<Map.Entry<String, Integer>> l = new ArrayList<>(kinds.entrySet());
            l.sort((a, b) -> b.getValue() - a.getValue());
            List<String> top = new ArrayList<>();
            for (int i = 0; i < l.size() && i < 6; i++) top.add(l.get(i).getValue() + " " + l.get(i).getKey());
            out.add(en.getKey() + ": " + cells + " cells" + (tooBig ? " (too big to count all of it)" : "") + ", " + air + " air, " + wet + " water cells"
                    + (unloaded > 0 ? ", " + unloaded + " not loaded" : "") + (top.isEmpty() ? "" : "; blocks: " + String.join(", ", top)));
        }
        return out;
    }
}
