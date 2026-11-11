package dev.baritonequeue;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.Map;

final class Util {
    private Util() {}

    static IBaritone baritone() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    private static final java.util.List<String> pending = new java.util.ArrayList<>();

    /** A chat message. From the script's thread it is kept until the game thread shows it (flush). */
    static void msg(String s) {
        Minecraft mc = Minecraft.getInstance();
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

    /** The canonical name when the word is a Baritone setting (typed like a command: buildInLayers false), else null. */
    static String settingName(String word) {
        try {
            var s = BaritoneAPI.getSettings().byLowerName.get(word.toLowerCase());
            return s == null ? null : s.getName();
        } catch (Throwable t) {
            return null;
        }
    }

    /** "buildInLayers false" is Baritone's shortcut for "set buildInLayers false"; the command manager does not know it. */
    static String normalize(String command) {
        String c = command.trim();
        int sp = c.indexOf(' ');
        String first = sp < 0 ? c : c.substring(0, sp);
        String prefix = BaritoneAPI.getSettings().prefix.value;
        if (first.startsWith(prefix)) first = first.substring(prefix.length());
        try {
            if (baritone().getCommandManager().getCommand(first.toLowerCase()) != null) return c;
        } catch (Throwable ignored) {
        }
        String name = settingName(first);
        return name == null ? c : "set " + name + (sp < 0 ? "" : c.substring(sp));
    }

    /** True while any Baritone task runs (#mine, #farm, #build, #sel ca, #goto, ...). */
    static boolean baritoneBusy() {
        IBaritone b = baritone();
        return b.getMineProcess().isActive() || b.getBuilderProcess().isActive() || b.getFarmProcess().isActive()
                || b.getExploreProcess().isActive() || b.getGetToBlockProcess().isActive()
                || b.getFollowProcess().isActive() || b.getCustomGoalProcess().isActive();
    }

    /**
     * Right-clicking a chest while sneaking, with a block or a bucket in hand, places the block on top of it (or pours
     * the bucket) instead of opening it - and a block on top of a chest keeps it shut for good. On the live canal a
     * stone brick ended up on the tools chest that way and every restock after it failed. So: stand up, and hold
     * something harmless.
     */
    static void readyToOpen(net.minecraft.client.player.LocalPlayer p) {
        baritone().getInputOverrideHandler().setInputForceState(baritone.api.utils.input.Input.SNEAK, false);
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
