package dev.survivalkit;

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

    static void msg(String s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal("[SurvivalKit] " + s));
    }

    /** True while any engine task other than ours runs. */
    static boolean baritoneBusy() {
        return dev.midtown.api.EngineSelector.current().isBusy();
    }

    /** The script's enable("eat", ...): this helper runs while the script runs, whatever its saved option says. */
    static boolean scriptOn(String feature, boolean saved) {
        return dev.midtown.api.RuntimeBus.isHelperOn(feature, saved);
    }

    static boolean addonOff(String feature) {
        String f = feature.toLowerCase();
        // After a death the script is paused: eating and fighting must still happen to get safe again.
        if (Recovery.waiting && (f.contains("defence") || f.contains("eat"))) return false;
        if (f.contains("defence") && DefenceProcess.forced) return false;
        if (f.contains("eat") && EatProcess.forced) return false;
        return dev.midtown.api.RuntimeBus.isAddonOff(feature);
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

    /** Features only start while a task is running, unless "always" is switched on. */
    static boolean mayAct() {
        boolean queue = dev.midtown.api.RuntimeBus.isScriptActive() || dev.midtown.api.RuntimeBus.isBuildToolsBusy();
        return !PauseWatch.isPaused() && (dev.survivalkit.Config.cfg.always || baritoneBusy() || queue || Recovery.waiting);
    }

    /** True while LoadChest is walking to / using its chest. */
    static boolean loadChestBusy() {
        return dev.midtown.api.RuntimeBus.isLoadChestBusy();
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
            if (cfg.extraBeds != null) for (int[] a : cfg.extraBeds) if (a != null && a.length >= 3) out.add(pos(a));
            if (cfg.extraSupplies != null) for (int[] a : cfg.extraSupplies) if (a != null && a.length >= 3) out.add(pos(a));
            if (cfg.extraReturnChests != null) for (int[] a : cfg.extraReturnChests) if (a != null && a.length >= 3) out.add(pos(a));
            if (cfg.supplyAreas != null) {
                for (int[] b : cfg.supplyAreas) {
                    if (b != null && b.length >= 6) out.add(new BlockPos((b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2));
                }
            }
            if (cfg.returnAreas != null) {
                for (int[] b : cfg.returnAreas) {
                    if (b != null && b.length >= 6) out.add(new BlockPos((b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2));
                }
            }
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

    /** Ticks until the current path segment ends, or -1 when no path. */
    static double segmentTicks() {
        return -1.0;
    }

    /**
     * True when the next `n` steps of the path are plain walking.
     */
    static boolean easyWalkAhead(LocalPlayer p, int n) {
        return false;
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
     * Worn-out tools do not count as stock: over 80% damage or 5 uses left.
     */
    static boolean nearlyBroken(ItemStack st) {
        if (!st.isDamageableItem()) return false;
        return st.getMaxDamage() > 1 && (st.getMaxDamage() - st.getDamageValue() <= 5 || st.getDamageValue() > st.getMaxDamage() * 0.8);
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

    /**
     * Release sneak and hold harmless item before opening chest.
     */
    static void readyToOpen(net.minecraft.client.player.LocalPlayer p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options != null) mc.options.keyShift.setDown(false);
        p.setShiftKeyDown(false);
        var inv = p.getInventory();
        if (!placesSomething(inv.getItem(inv.getSelectedSlot()))) return;
        for (int i = 0; i < 9; i++) {
            if (!placesSomething(inv.getItem(i))) { inv.setSelectedSlot(i); return; }
        }
    }

    private static boolean placesSomething(net.minecraft.world.item.ItemStack s) {
        var it = s.getItem();
        return it instanceof net.minecraft.world.item.BlockItem || it instanceof net.minecraft.world.item.BucketItem
                || it instanceof net.minecraft.world.item.SpawnEggItem || it instanceof net.minecraft.world.item.FlintAndSteelItem;
    }

    /** Why a chest at pos may not open: something solid on top of it. */
    static String blockedText(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
        if (!(level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.ChestBlock)) return "";
        var above = pos.above();
        if (!level.getBlockState(above).isRedstoneConductor(level, above)) return "";
        return " A " + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(above).getBlock()).getPath()
                + " is on top of it (" + above.toShortString() + "): a chest does not open with a block on top. Take it away.";
    }

    private static final int MAX_AREA_SCAN_VOLUME = 65_536;

    /**
     * Collects all unique storage container positions ({@code ChestBlock}, {@code BarrelBlock}, {@code ShulkerBoxBlock})
     * from explicit point coordinates plus live loaded blocks inside 6-element area boxes {@code [x1,y1,z1,x2,y2,z2]}.
     * Double chests are deduplicated to one canonical half so bots never visit both halves of the same chest.
     */
    static java.util.List<BlockPos> scanStorageAreas(
            net.minecraft.world.level.Level level,
            java.util.List<int[]> explicitPoints,
            java.util.List<int[]> areas
    ) {
        java.util.LinkedHashSet<BlockPos> out = new java.util.LinkedHashSet<>();
        java.util.HashSet<BlockPos> seenDoubleHalves = new java.util.HashSet<>();
        if (explicitPoints != null) {
            for (int[] p : explicitPoints) {
                if (p == null || p.length < 3) continue;
                BlockPos bp = new BlockPos(p[0], p[1], p[2]);
                addStorageIfCanonical(level, bp, out, seenDoubleHalves, true);
            }
        }
        if (level != null && areas != null) {
            BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
            for (int[] box : areas) {
                if (box == null || box.length < 6) continue;
                int x1 = Math.min(box[0], box[3]), x2 = Math.max(box[0], box[3]);
                int y1 = Math.min(box[1], box[4]), y2 = Math.max(box[1], box[4]);
                int z1 = Math.min(box[2], box[5]), z2 = Math.max(box[2], box[5]);
                long vol = (long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
                if (vol <= 0 || vol > MAX_AREA_SCAN_VOLUME) continue;
                for (int y = y1; y <= y2; y++) {
                    for (int z = z1; z <= z2; z++) {
                        for (int x = x1; x <= x2; x++) {
                            mp.set(x, y, z);
                            if (!level.hasChunkAt(mp)) continue;
                            var st = level.getBlockState(mp);
                            var b = st.getBlock();
                            if (b instanceof net.minecraft.world.level.block.ChestBlock
                                    || b instanceof net.minecraft.world.level.block.BarrelBlock
                                    || b instanceof net.minecraft.world.level.block.ShulkerBoxBlock) {
                                addStorageIfCanonical(level, mp.immutable(), out, seenDoubleHalves, false);
                            }
                        }
                    }
                }
            }
        }
        return new java.util.ArrayList<>(out);
    }

    private static void addStorageIfCanonical(
            net.minecraft.world.level.Level level,
            BlockPos bp,
            java.util.LinkedHashSet<BlockPos> out,
            java.util.HashSet<BlockPos> seenDoubleHalves,
            boolean keepEvenIfUnloaded
    ) {
        if (seenDoubleHalves.contains(bp) || out.contains(bp)) return;
        if (level != null && level.hasChunkAt(bp)) {
            var st = level.getBlockState(bp);
            if (st.getBlock() instanceof net.minecraft.world.level.block.ChestBlock
                    && st.hasProperty(net.minecraft.world.level.block.ChestBlock.TYPE)) {
                var type = st.getValue(net.minecraft.world.level.block.ChestBlock.TYPE);
                if (type != net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
                    BlockPos other = bp.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(st));
                    if (out.contains(other) || seenDoubleHalves.contains(other)) {
                        seenDoubleHalves.add(bp);
                        return;
                    }
                    // Keep this half and mark the connected half as seen so we never visit both halves.
                    seenDoubleHalves.add(other);
                }
            }
            out.add(bp);
        } else if (keepEvenIfUnloaded) {
            out.add(bp);
        }
    }
}
