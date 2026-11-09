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
}
