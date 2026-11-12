package dev.survivalkit;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

import static dev.survivalkit.Config.cfg;

/** What the script (dev.baritonequeue) may call. Each start method returns an error text, or null when it started. */
public final class Api {
    private Api() {}

    public static String sleepNow() {
        if (cfg.bed == null) return "No bed saved. Set it in the script: option(\"survival.bed\", pos(x, y, z)) - or look at the bed and /survival bed set.";
        SurvivalKitMod.sleep.startNow();
        return SurvivalKitMod.sleep.busy() ? null : "";   // not night, or the bed is not loaded: the chat says which
    }

    public static boolean sleeping() {
        return SurvivalKitMod.sleep.busy();
    }

    public static void stopSleeping() {
        SurvivalKitMod.sleep.onLostControl();
    }

    public static String restockNow() {
        if (cfg.supply == null) return "No supply chest saved. Set it in the script: option(\"survival.supply\", pos(x, y, z)).";
        if (cfg.want.isEmpty()) return "The want-list is empty. Add rules in the script: want(\"bread\", 16).";
        SurvivalKitMod.restock.startNow();
        return SurvivalKitMod.restock.busy() ? null : "";
    }

    public static boolean restocking() {
        return SurvivalKitMod.restock.busy();
    }

    public static void stopRestocking() {
        SurvivalKitMod.restock.onLostControl();
    }

    /** Eat now if the food bar is not full (eating must be switched on). */
    public static String eatNow() {
        var p = Minecraft.getInstance().player;
        if (p == null) return "Not in a world.";
        if (!p.getFoodData().needsFood()) return "";
        EatProcess.forced = true;
        return null;
    }

    public static boolean eating() {
        return SurvivalKitMod.eat.busy() || EatProcess.forced;
    }

    public static boolean fighting() {
        return SurvivalKitMod.defence.busy() || DefenceProcess.forced;
    }

    /** Fight every hostile mob nearby until none is left. "" = nothing to fight. */
    public static String defendNow() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return "Not in a world.";
        if (DefenceProcess.threats(mc, mc.player, 7) == 0) return "";
        DefenceProcess.forced = true;
        return null;
    }

    public static void stopDefending() {
        DefenceProcess.forced = false;
        SurvivalKitMod.defence.onLostControl();
    }

    public static int threats(double range) {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null || mc.level == null ? 0 : DefenceProcess.threats(mc, mc.player, range);
    }

    public static boolean restockNeeded() {
        var p = Minecraft.getInstance().player;
        return p != null && cfg.supply != null && RestockProcess.needed(p);
    }

    public static boolean bedTime() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.level != null && SleepProcess.bedTime(mc, mc.player);
    }

    public static boolean busy() {
        return SurvivalKitMod.sleep.busy() || SurvivalKitMod.restock.busy();
    }

    public static Object config() {
        return cfg;
    }

    public static void save() {
        Config.save();
    }

    /** want(item, n): carry n of it, restock when below half. 0 removes the rule. */
    public static void want(String item, int count) {
        String k = item.toLowerCase().equals("food") ? "@food" : item.toLowerCase();
        if (count <= 0) cfg.want.remove(k);
        else cfg.want.put(k, count);
        Config.save();
    }

    public static java.util.Map<String, Integer> wantList() {
        return cfg.want;
    }

    /** Whether the stack fits a want-list rule ("diamond_shovel", "@food", "diamond_pickaxe efficiency:5"). */
    public static boolean wantMatches(net.minecraft.world.item.ItemStack st, String rule) {
        return WantRule.of(rule).matches(st);
    }

    /** Whether a tool is at the item-saver limit (restock hands it in). */
    public static boolean worn(net.minecraft.world.item.ItemStack st) {
        return Util.nearlyBroken(st);
    }

    /** A work item rule (/survival workitem ...); returns an error text or null. */
    public static String workItem(String rule) {
        return WorkItems.add(rule);
    }

    /** Chests the index knows that hold the item: {x, y, z, count}, nearest first. */
    public static List<int[]> findItem(String what) {
        Minecraft mc = Minecraft.getInstance();
        List<int[]> out = new ArrayList<>();
        if (mc.player == null) return out;
        for (var e : ChestIndex.nearest(mc, mc.player.blockPosition())) {
            int n = what.equals("food") || what.equals("@food") ? e.food : e.count(what);
            if (n > 0) out.add(new int[]{e.x, e.y, e.z, n});
        }
        return out;
    }

    /** What the index remembers of the chest at that place: item id -> count; null if never seen. */
    public static java.util.Map<String, Integer> chestContents(BlockPos at) {
        var e = ChestIndex.at(Minecraft.getInstance(), at);
        return e == null ? null : e.items;
    }

    /** What the index knows of one chest about one item: known = there is an entry; fresh = young enough to trust. */
    public record ChestInfo(boolean known, boolean fresh, double ageMinutes, int count) {}

    /** The index entry of the chest at that place for an item id such as "minecraft:stone". */
    public static ChestInfo chestInfo(BlockPos at, String itemId) {
        Minecraft mc = Minecraft.getInstance();
        var e = ChestIndex.at(mc, at);
        if (e == null) return new ChestInfo(false, false, Double.MAX_VALUE, 0);
        return new ChestInfo(true, ChestIndex.fresh(e), (System.currentTimeMillis() - e.time) / 60000.0, e.countExact(itemId));
    }

    /** Notes what the open container holds as the contents of the chest at that place (call while it is open). */
    public static void noteOpenChest(BlockPos at) {
        ChestIndex.recordOpen(Minecraft.getInstance(), at);
    }

    /** Opens/closes of the chest at that place are about to happen: record the right chest for the player's own look. */
    public static void chestHint(BlockPos at) {
        ChestIndex.hint = at;
    }

    /** A script chest action starts (true) or has ended (false): it records the chests it opens itself. */
    public static void chestBusy(boolean on) {
        ChestIndex.external = on;
    }

    /** The chest is gone: drop it from the index. */
    public static void forgetChest(BlockPos at) {
        ChestIndex.forgetAt(Minecraft.getInstance(), at);
    }

    public static void saveChestIndex() {
        ChestIndex.save();
    }

    /** Minutes after which an indexed chest is opened again to refresh it (default 30; 0 = every time). */
    public static double indexRefresh() {
        return ChestIndex.refreshMinutes;
    }

    public static void setIndexRefresh(double minutes) {
        ChestIndex.refreshMinutes = Math.max(0, minutes);
    }

    public static void resetIndexRefresh() {
        ChestIndex.resetRefresh();
    }

    /** The file name part that identifies the server or world the index belongs to (for tests). */
    public static String indexWorld() {
        return ChestIndex.worldKey(Minecraft.getInstance());
    }

    /** Snapshot of one indexed chest for ChestIndexView (UI & tests). */
    public record IndexedChest(String dim, int x, int y, int z, long time, String age, int food, java.util.Map<String, Integer> items) {}

    /** All indexed chests for the current world/dimension (nearest first when in-game). */
    public static List<IndexedChest> indexedChests() {
        Minecraft mc = null;
        try { mc = Minecraft.getInstance(); } catch (Throwable ignored) {}
        List<IndexedChest> out = new ArrayList<>();
        for (ChestIndex.Entry e : ChestIndex.all(mc)) {
            out.add(new IndexedChest(e.dim, e.x, e.y, e.z, e.time, ChestIndex.age(e.time), e.food, new java.util.LinkedHashMap<>(e.items)));
        }
        return out;
    }

    /** Records or updates a chest in ChestIndex (used by tests and manual indexing). */
    public static void recordChest(String dim, int x, int y, int z, java.util.Map<String, Integer> items, int food) {
        ChestIndex.putEntry(dim, x, y, z, items, food);
    }

    /** Clears all indexed chests in memory (and saves if in-game). */
    public static void clearChestIndex() {
        try { ChestIndex.forget(); } catch (Throwable ignored) {}
    }
}
