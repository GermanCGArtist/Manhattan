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
}
