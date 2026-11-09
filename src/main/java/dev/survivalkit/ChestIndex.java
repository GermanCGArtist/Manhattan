package dev.survivalkit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
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
 * What is in which chest. Every time a chest or barrel is open, its contents are noted (config/chestindex.json), so
 * the restock knows which supply chest to visit first, and /survival find tells where something is.
 * The index is a memory, not a live view: it is as old as the last visit.
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
    private static Map<String, Entry> map = new LinkedHashMap<>();
    private static boolean loaded, dirty;
    private static int openTicks, check;
    private static String lastHash = "";

    /** Set by code that opens a chest on purpose (the restock), so the right chest is recorded. */
    static BlockPos hint;

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("chestindex.json");
    }

    private static void load() {
        loaded = true;
        try {
            Path p = path();
            if (!Files.exists(p)) return;
            try (Reader r = Files.newBufferedReader(p)) {
                Map<String, Entry> m = GSON.fromJson(r, new TypeToken<LinkedHashMap<String, Entry>>() {}.getType());
                if (m != null) map = m;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static void save() {
        if (!dirty) return;
        dirty = false;
        try (Writer w = Files.newBufferedWriter(path())) {
            GSON.toJson(map, w);
        } catch (Exception e) {
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
        if (!loaded) load();
        return mc.level == null ? null : map.get(key(dim(mc), p));
    }

    static boolean isStorage(Block b) {
        return b instanceof ChestBlock || b instanceof BarrelBlock;
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
        if (!loaded) load();
        if (!(p.containerMenu instanceof ChestMenu menu)) {
            if (openTicks > 0) { openTicks = 0; lastHash = ""; hint = null; save(); }
            return;
        }
        openTicks++;
        if (openTicks < 8 || ++check % 5 != 0) return;   // contents arrive a moment after opening
        BlockPos pos = whichChest(mc, p);
        if (pos == null) return;

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
        if (hash.equals(lastHash)) return;
        lastHash = hash;

        Entry e = new Entry();
        e.dim = dim(mc);
        e.x = pos.getX();
        e.y = pos.getY();
        e.z = pos.getZ();
        e.time = System.currentTimeMillis();
        e.items = items;
        e.food = food;
        map.put(key(e.dim, pos), e);
        dirty = true;
    }

    /** Entries of the current dimension, nearest first. */
    static List<Entry> nearest(Minecraft mc, BlockPos from) {
        if (!loaded) load();
        List<Entry> out = new ArrayList<>();
        if (mc.level == null) return out;
        String d = dim(mc);
        for (Entry e : map.values()) if (e.dim.equals(d)) out.add(e);
        out.sort((a, b) -> Double.compare(a.pos().distSqr(from), b.pos().distSqr(from)));
        return out;
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
