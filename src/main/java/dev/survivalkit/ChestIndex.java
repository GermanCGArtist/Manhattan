package dev.survivalkit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What is in which chest. Every time a chest, barrel or shulker box is open (by a script chest action, the restock,
 * a dump or the player), its contents are noted, and what a script takes out or puts in is counted. The restock
 * uses it to pick the supply chest to visit first, /survival find tells where something is, and take/take_upto/put
 * with a list of chests walk only to the chests that hold the item.
 *
 * One file per server or single-player world: config/manhattan-chests/(sp-worldname | mp-serverip).json,
 * {"version": 2, "world": key, "entries": {"dim|x,y,z": Entry}}; every entry has "time" (when it was seen, ms).
 * The index is a memory, not a live view: it is as old as the last visit. After the refresh interval (30 min,
 * {@link #refreshMinutes}) an entry is stale and the chest is opened again once.
 */
final class ChestIndex {
    private ChestIndex() {}

    static final class Entry {
        String dim;
        int x, y, z;
        long time;
        int food;   // number of food items (for the @food rule)
        Map<String, Integer> items = new LinkedHashMap<>();

        BlockPos pos() {
            return new BlockPos(x, y, z);
        }

        int count(String fragment) {
            int n = 0;
            for (var e : items.entrySet()) {
                if (e.getKey().contains(fragment)) n += e.getValue();
            }
            return n;
        }

        /** How many items a want-rule is about (enchantments are not indexed, so those count by item only). */
        /** How many of exactly this item id ("minecraft:stone"). */
        int countExact(String id) {
            Integer n = items.get(id);
            return n == null ? 0 : n;
        }

        int countRule(String key) {
            String item = WantRule.itemOf(key);
            if (item.equals("@food")) return food;
            int n = 0;
            for (var e : items.entrySet()) {
                boolean hit = item.contains(":") ? e.getKey().equals(item) : e.getKey().substring(e.getKey().indexOf(':') + 1).contains(item);
                if (hit) n += e.getValue();
            }
            return n;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final double DEFAULT_REFRESH = 30;
    private static Map<String, Entry> map = new LinkedHashMap<>();
    private static String world;   // key of the server or world the map belongs to (null: none joined yet)
    private static boolean dirty;
    private static int openTicks, check;
    private static String lastHash = "";

    /** Minutes after which an entry is stale and its chest is opened again (0 = always). Changed by the script. */
    static volatile double refreshMinutes = DEFAULT_REFRESH;

    /** Set by code that opens a chest on purpose (the restock, the chest action), so the right chest is recorded. */
    static BlockPos hint;

    /** A script chest action is working: it records the chest itself, the per-tick look stays out of its way. */
    static volatile boolean external;

    static void resetRefresh() {
        refreshMinutes = DEFAULT_REFRESH;
    }

    private static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve("manhattan-chests");
    }

    private static Path path(String key) {
        return dir().resolve(key.replaceAll("[^A-Za-z0-9._-]", "_") + ".json");
    }

    /** The server or single-player world the player is in. */
    static String worldKey(Minecraft mc) {
        try {
            var sp = mc.getSingleplayerServer();
            if (sp != null) return "sp-" + sp.getWorldData().getLevelName();
            var s = mc.getCurrentServer();
            if (s != null) return "mp-" + s.ip.toLowerCase();
        } catch (Throwable ignored) {}
        return "local";
    }

    /** Switches to the file of the world the player is in (saving the old one), when it changed. */
    private static void ensureWorld(Minecraft mc) {
        if (mc == null || mc.level == null) return;
        String k = worldKey(mc);
        if (k.equals(world)) return;
        if (world != null) save();
        world = k;
        map = new LinkedHashMap<>();
        dirty = false;
        lastHash = "";
        try {
            Path p = path(k);
            if (!Files.exists(p)) return;
            try (Reader r = Files.newBufferedReader(p)) {
                JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
                Map<String, Entry> m = GSON.fromJson(root.get("entries"), new TypeToken<LinkedHashMap<String, Entry>>() {}.getType());
                if (m != null) map = m;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static void save() {
        if (!dirty || world == null) return;
        dirty = false;
        try {
            Files.createDirectories(dir());
            JsonObject root = new JsonObject();
            root.addProperty("version", 2);
            root.addProperty("world", world);
            root.add("entries", GSON.toJsonTree(map));
            Path p = path(world), tmp = p.resolveSibling(p.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp)) {
                GSON.toJson(root, w);
            }
            Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            dirty = true;
            e.printStackTrace();
        }
    }

    private static String dim(Minecraft mc) {
        return mc.level.dimension().identifier().toString();
    }

    private static String key(String dim, BlockPos p) {
        return dim + "|" + p.getX() + "," + p.getY() + "," + p.getZ();
    }

    static Entry at(Minecraft mc, BlockPos p) {
        ensureWorld(mc);
        return mc.level == null ? null : map.get(key(dim(mc), p));
    }

    static boolean isStorage(Block b) {
        return b instanceof ChestBlock || b instanceof BarrelBlock || b instanceof ShulkerBoxBlock;
    }

    /** Whether the entry is young enough to be trusted without opening the chest again. */
    static boolean fresh(Entry e) {
        double min = refreshMinutes;
        return e != null && min > 0 && System.currentTimeMillis() - e.time < min * 60000.0;
    }

    /** The chest the open menu belongs to: the hint, else the block looked at, else the nearest chest. */
    private static BlockPos whichChest(Minecraft mc, LocalPlayer p) {
        if (hint != null && isStorage(mc.level.getBlockState(hint).getBlock())) return hint;
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                && isStorage(mc.level.getBlockState(hit.getBlockPos()).getBlock())) return hit.getBlockPos();
        BlockPos me = p.blockPosition(), best = null;
        double bd = 1e9;
        for (BlockPos q : BlockPos.betweenClosed(me.offset(-6, -4, -6), me.offset(6, 4, 6))) {
            if (!isStorage(mc.level.getBlockState(q).getBlock())) continue;
            double d = q.distSqr(me);
            if (d < bd) { bd = d; best = q.immutable(); }
        }
        return best;
    }

    /** Every tick: while a chest is open, note what is in it. */
    static void tick(Minecraft mc, LocalPlayer p) {
        ensureWorld(mc);
        if (external) { openTicks = 0; lastHash = ""; return; }
        if (!(p.containerMenu instanceof ChestMenu || p.containerMenu instanceof ShulkerBoxMenu)) {
            if (openTicks > 0) { openTicks = 0; lastHash = ""; hint = null; save(); }
            else if (dirty) save();
            return;
        }
        openTicks++;
        if (openTicks < 8 || ++check % 5 != 0) return;   // contents arrive a moment after opening
        BlockPos pos = whichChest(mc, p);
        if (pos == null) return;
        record(mc, p, pos, true);
    }

    /** Notes what the open container holds as the contents of the chest at pos (and of its other half). */
    static void recordOpen(Minecraft mc, BlockPos pos) {
        if (mc == null || mc.player == null || mc.level == null || pos == null) return;
        if (mc.player.containerMenu == mc.player.inventoryMenu) return;
        ensureWorld(mc);
        record(mc, mc.player, pos, false);
    }

    private static void record(Minecraft mc, LocalPlayer p, BlockPos pos, boolean dedupe) {
        AbstractContainerMenu menu = p.containerMenu;
        Map<String, Integer> items = new LinkedHashMap<>();
        int food = 0;
        for (Slot s : menu.slots) {
            if (s.container == p.getInventory()) continue;
            ItemStack st = s.getItem();
            if (st.isEmpty()) continue;
            items.merge(BuiltInRegistries.ITEM.getKey(st.getItem()).toString(), st.getCount(), Integer::sum);
            if (Util.isGoodFood(st)) food += st.getCount();
        }
        String hash = pos + ":" + items + ":" + food;
        if (dedupe) {
            if (hash.equals(lastHash)) return;
            lastHash = hash;
        }
        put(dim(mc), pos, items, food);
        BlockState st = mc.level.getBlockState(pos);
        if (st.getBlock() instanceof ChestBlock && st.hasProperty(ChestBlock.TYPE) && st.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            put(dim(mc), pos.relative(ChestBlock.getConnectedDirection(st)), items, food);   // a double chest: both halves hold the same
        }
    }

    private static void put(String dim, BlockPos pos, Map<String, Integer> items, int food) {
        Entry e = new Entry();
        e.dim = dim;
        e.x = pos.getX();
        e.y = pos.getY();
        e.z = pos.getZ();
        e.time = System.currentTimeMillis();
        e.items = new LinkedHashMap<>(items);
        e.food = food;
        map.put(key(e.dim, pos), e);
        dirty = true;
    }

    /** The chest is gone (or never was one): drop it from the index. */
    static void forgetAt(Minecraft mc, BlockPos pos) {
        ensureWorld(mc);
        if (mc.level != null && map.remove(key(dim(mc), pos)) != null) {
            dirty = true;
            save();
        }
    }

    /** Entries of the current dimension, nearest first. */
    static List<Entry> nearest(Minecraft mc, BlockPos from) {
        ensureWorld(mc);
        List<Entry> out = new ArrayList<>();
        if (mc.level == null) return out;
        String d = dim(mc);
        for (Entry e : map.values()) if (e.dim.equals(d)) out.add(e);
        out.sort((a, b) -> Double.compare(a.pos().distSqr(from), b.pos().distSqr(from)));
        return out;
    }

    /** All entries (current dimension if in a world, nearest first; else all entries in map order). */
    static List<Entry> all(Minecraft mc) {
        ensureWorld(mc);
        if (mc != null && mc.level != null && mc.player != null) {
            return nearest(mc, mc.player.blockPosition());
        }
        return new ArrayList<>(map.values());
    }

    static void putEntry(String dim, int x, int y, int z, Map<String, Integer> items, int food) {
        try { ensureWorld(Minecraft.getInstance()); } catch (Throwable ignored) {}
        Entry e = new Entry();
        e.dim = dim == null || dim.isEmpty() ? "minecraft:overworld" : dim;
        e.x = x;
        e.y = y;
        e.z = z;
        e.time = System.currentTimeMillis();
        e.items = new LinkedHashMap<>(items);
        e.food = food;
        map.put(key(e.dim, new BlockPos(x, y, z)), e);
        dirty = true;
    }

    static void forget() {
        map.clear();
        dirty = true;
        save();
    }

    static String age(long time) {
        long min = (System.currentTimeMillis() - time) / 60000;
        if (min < 1) return "just now";
        if (min < 60) return min + " min ago";
        if (min < 60 * 24) return (min / 60) + " h ago";
        return (min / 1440) + " days ago";
    }
}
