package dev.survivalkit;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class Util {
    private Util() {}

    /** Item ids (or parts) that are technically food but should never be eaten automatically. */
    private static final List<String> BAD_FOOD = List.of(
            "rotten_flesh", "spider_eye", "pufferfish", "poisonous_potato", "chorus_fruit",
            "raw_chicken", "golden_apple", "suspicious_stew", "ominous_bottle");

    static IBaritone baritone() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    static void msg(String s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal("[SurvivalKit] " + s));
    }

    /** True while any Baritone task other than ours runs (#mine, #farm, #build, #sel ca, #goto, ...). */
    static boolean baritoneBusy() {
        IBaritone b = baritone();
        return b.getMineProcess().isActive() || b.getBuilderProcess().isActive() || b.getFarmProcess().isActive()
                || b.getExploreProcess().isActive() || b.getGetToBlockProcess().isActive()
                || b.getFollowProcess().isActive() || b.getCustomGoalProcess().isActive();
    }

    /**
     * A chest/furnace/... menu is open, or the player is dead. The player's own inventory, the pause menu and chat do
     * NOT count: everything here works through direct game calls, so the bot keeps going with those open (for example
     * when the game window sits on a second monitor and the pause menu popped up).
     */
    /** The queue's "addons off ..." line: is this feature switched off for now? (names or "all", comma separated) */
    /** The script's enable("eat", ...): this helper runs while the script runs, whatever its saved option says. */
    static boolean scriptOn(String feature, boolean saved) {
        String v = System.getProperty("baritonequeue.helpers.on");
        if (v != null) {
            for (String k : v.split(",")) {
                k = k.trim();
                if (k.equals("all") || k.equals(feature)) return true;
            }
        }
        return saved;
    }

    static boolean addonOff(String feature) {
        String v = System.getProperty("baritonequeue.addons.off");
        if (v == null) return false;
        String f = feature.toLowerCase();
        for (String k : v.split(",")) {
            k = k.trim();
            if (k.equals("all") || (!k.isEmpty() && f.contains(k))) return true;
        }
        return false;
    }

    static boolean guiOpen(LocalPlayer p) {
        return p.containerMenu != p.inventoryMenu || !p.isAlive();
    }

    private static final Map<String, Long> lastWarn = new HashMap<>();

    /** Tells the player about a problem in chat, at most once a minute per kind. */
    static void warn(String key, String text) {
        long t = System.currentTimeMillis();
        Long last = lastWarn.get(key);
        if (last != null && t - last < 60_000) return;
        lastWarn.put(key, t);
        msg("PROBLEM: " + text);
    }

    /** Features only start while a Baritone task is running, unless "always" is switched on. */
    static boolean mayAct() {
        // a running queue counts too: its own steps (chest trips, BuildTools' terminator mode, waits) are not Baritone tasks
        boolean queue = System.getProperty("baritonequeue.active") != null || System.getProperty("buildtools.busy") != null;
        return !PauseWatch.paused && (dev.survivalkit.Config.cfg.always || baritoneBusy() || queue || Recovery.waiting);
    }

    /** True while LoadChest is walking to / using its chest. */
    static boolean loadChestBusy() {
        var control = baritone().getPathingControlManager().mostRecentInControl();
        return control.isPresent() && String.valueOf(control.get().displayName()).contains("LoadChest");
    }

    private static List<BlockPos> sites = List.of();
    private static int sitesAge = 1000;

    /** Within baseRadius of the supply chest, return chest, bed or LoadChest's deposit chest. */
    static boolean nearBase(LocalPlayer p) {
        var cfg = dev.survivalkit.Config.cfg;
        if (++sitesAge > 40) {
            sitesAge = 0;
            java.util.ArrayList<BlockPos> out = new java.util.ArrayList<>();
            for (int[] a : new int[][]{cfg.supply, cfg.returnChest, cfg.bed}) if (a != null) out.add(pos(a));
            for (int[] a : cfg.extraSupplies) out.add(pos(a));
            try {
                var path = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("loadchest.json");
                if (java.nio.file.Files.exists(path)) {
                    try (var r = java.nio.file.Files.newBufferedReader(path)) {
                        var o = com.google.gson.JsonParser.parseReader(r).getAsJsonObject();
                        if (o.has("chest") && o.get("chest").isJsonArray() && o.getAsJsonArray("chest").size() >= 3) {
                            var a = o.getAsJsonArray("chest");
                            out.add(new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()));
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            sites = out;
        }
        long r2 = (long) cfg.baseRadius * cfg.baseRadius;
        BlockPos me = p.blockPosition();
        for (BlockPos s : sites) if (s.distSqr(me) <= r2) return true;
        return false;
    }

    /** Ticks until the current path segment ends, or -1 when Baritone has no path. */
    static double segmentTicks() {
        return baritone().getPathingBehavior().ticksRemainingInSegment().orElse(-1.0);
    }

    /**
     * True when the next `n` steps of Baritone's path are plain walking: same height, free space to walk into and solid
     * ground under it. Then eating while it keeps walking cannot make it mine, place or jump wrongly.
     */
    static boolean easyWalkAhead(LocalPlayer p, int n) {
        var cur = baritone().getPathingBehavior().getCurrent();
        var level = Minecraft.getInstance().level;
        if (cur == null || level == null || !p.onGround() || p.isInWater()) return false;
        var movements = cur.getPath().movements();
        int from = cur.getPosition();
        if (from >= movements.size()) return false;
        for (int i = from; i < Math.min(movements.size(), from + n); i++) {
            var m = movements.get(i);
            BlockPos src = m.getSrc(), dest = m.getDest();
            if (dest.getY() != src.getY()) return false;
            if (Math.abs(dest.getX() - src.getX()) + Math.abs(dest.getZ() - src.getZ()) > 2) return false;
            if (level.getBlockState(dest).blocksMotion() || level.getBlockState(dest.above()).blocksMotion()) return false;
            if (!level.getBlockState(dest).getFluidState().isEmpty() || !level.getBlockState(dest.above()).getFluidState().isEmpty()) return false;
            var below = level.getBlockState(dest.below());
            if (!below.isFaceSturdy(level, dest.below(), net.minecraft.core.Direction.UP)) return false;
        }
        return true;
    }

    static String pathOf(ItemStack st) {
        return BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
    }

    static boolean isFood(ItemStack st) {
        return !st.isEmpty() && st.has(DataComponents.FOOD);
    }

    /** Food that is safe to eat automatically. */
    static boolean isGoodFood(ItemStack st) {
        if (!isFood(st)) return false;
        String path = pathOf(st);
        for (String bad : BAD_FOOD) {
            if (path.contains(bad)) return false;
        }
        return true;
    }

    static int nutrition(ItemStack st) {
        FoodProperties f = st.get(DataComponents.FOOD);
        return f == null ? 0 : f.nutrition();
    }

    /**
     * Worn-out tools do not count as stock. With Baritone's item saver on (#set itemSaver true) this is exactly the
     * moment Baritone stops using the tool: damage + itemSaverThreshold >= max damage. Without it: over 80% damage.
     */
    static boolean nearlyBroken(ItemStack st) {
        if (!st.isDamageableItem()) return false;
        var settings = BaritoneAPI.getSettings();
        if (settings.itemSaver.value) {
            return st.getMaxDamage() > 1 && st.getDamageValue() + settings.itemSaverThreshold.value >= st.getMaxDamage();
        }
        return st.getDamageValue() > st.getMaxDamage() * 0.8;
    }

    /** How many worn-out tools matching the rule the player carries. */
    static int wornCount(LocalPlayer p, String key) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (matches(st, key) && st.isDamageableItem() && nearlyBroken(st)) n++;
        }
        return n;
    }

    /** Whether the player carries a worn-out tool matching the rule (it needs replacing even if enough are left). */
    static boolean hasWorn(LocalPlayer p, String key) {
        return wornCount(p, key) > 0;
    }

    static boolean debugOn = false;

    static void debug(String s) {
        if (debugOn) msg("[debug] " + s);
    }

    /** Whether the stack matches a rule key: an item id fragment, or @food. */
    static boolean matches(ItemStack st, String key) {
        if (st.isEmpty()) return false;
        return WantRule.of(key).matches(st);
    }

    /** How many usable items matching the rule the player carries (main inventory + hotbar). */
    static int countHave(LocalPlayer p, String key) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (matches(st, key) && !nearlyBroken(st)) n += st.getCount();
        }
        return n;
    }

    /** Container-menu slot index (in the player's own inventory screen) for a player inventory index 0-35. */
    static int menuSlot(int invIndex) {
        return invIndex < 9 ? 36 + invIndex : invIndex;
    }

    /** Swaps an inventory item into the given hotbar slot (0-8) using the player's inventory menu. */
    static void swapIntoHotbar(Minecraft mc, LocalPlayer p, int invIndex, int hotbar) {
        mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, menuSlot(invIndex), hotbar, ContainerInput.SWAP, p);
    }

    /** Swaps an inventory item (index 0-35) with the off-hand slot; doing it again swaps back. */
    static void swapWithOffhand(Minecraft mc, LocalPlayer p, int invIndex) {
        mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, menuSlot(invIndex), 40, ContainerInput.SWAP, p);
    }

    static BlockPos pos(int[] a) {
        return a == null ? null : new BlockPos(a[0], a[1], a[2]);
    }
}
