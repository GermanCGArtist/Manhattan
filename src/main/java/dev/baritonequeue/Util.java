package dev.baritonequeue;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.Map;

final class Util {
    private Util() {}

    private static final java.util.List<String> pending = new java.util.ArrayList<>();

    /** A chat message. From the script's thread it is kept until the game thread shows it (flush). */
    static void msg(String s) {
        LogBuffer.append(s);
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        if (!mc.isSameThread()) {
            synchronized (pending) {
                pending.add(s);
            }
            return;
        }
        flush();
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal("[Queue] " + s));
    }

    static void flush() {
        java.util.List<String> out;
        synchronized (pending) {
            if (pending.isEmpty()) return;
            out = new java.util.ArrayList<>(pending);
            pending.clear();
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) for (String s : out) mc.player.sendSystemMessage(Component.literal("[Queue] " + s));
    }

    /** Where a // comment starts in the line (at the start or after a space), or -1. */
    static int commentStart(String line) {
        int i = line.indexOf("//");
        while (i >= 0) {
            if (i == 0 || Character.isWhitespace(line.charAt(i - 1))) return i;
            i = line.indexOf("//", i + 2);
        }
        return -1;
    }

    /** The line without its // comment. */
    static String stripComment(String line) {
        int i = commentStart(line);
        return i < 0 ? line : line.substring(0, i);
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

    /** Returns the canonical setting name if known by the active PathEngine, else null. */
    static String settingName(String word) {
        try {
            return dev.midtown.api.EngineSelector.current().getSetting(word) != null ? word : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Normalizes a legacy `#` command line. */
    static String normalize(String command) {
        String c = command.trim();
        if (c.startsWith("#")) c = c.substring(1).trim();
        return c;
    }

    /** True while the active PathEngine is busy. */
    static boolean baritoneBusy() {
        return dev.midtown.api.EngineSelector.current().isBusy();
    }

    /**
     * Right-clicking a chest while sneaking, with a block or a bucket in hand, places the block on top of it (or pours
     * the bucket) instead of opening it - and a block on top of a chest keeps it shut for good. So: stand up, and hold
     * something harmless.
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

    /** Why a chest at pos may not open: something solid on top of it (a chest, not a barrel or shulker box). */
    static String blockedText(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
        if (!(level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.ChestBlock)) return "";
        var above = pos.above();
        if (!level.getBlockState(above).isRedstoneConductor(level, above)) return "";
        return " A " + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(above).getBlock()).getPath()
                + " is on top of it (" + above.toShortString() + "): a chest does not open with a block on top. Take it away.";
    }
}
