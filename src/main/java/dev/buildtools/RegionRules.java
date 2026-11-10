package dev.buildtools;

import baritone.api.BaritoneAPI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import static dev.buildtools.Config.cfg;

/**
 * Position rules for the safe areas. While you are inside a safe area, or close enough to reach into it, Baritone's
 * own settings "allowBreak" / "allowPlace" (and parkour placing) are switched off for whatever that area denies, and
 * switched back when you leave. Because it is Baritone's planner that sees the setting, it walks around instead of
 * trying to break or place: nothing stalls. (The veto in Guard stays as the last line of defence.)
 * Your own values are remembered and restored, even if you change the setting yourself in between.
 */
final class RegionRules {
    private RegionRules() {}

    private static boolean forcedBreak, forcedPlace;
    private static boolean origBreak, origPlace, origParkour;

    /** Sets allowBreak as if the player had; near a safe area it only takes effect once you leave it. Returns the old value. */
    static boolean setOwnBreak(boolean v) {
        var s = BaritoneAPI.getSettings();
        if (forcedBreak) {
            boolean old = origBreak;
            origBreak = v;
            return old;
        }
        boolean old = s.allowBreak.value;
        s.allowBreak.value = v;
        return old;
    }

    /** Sets allowPlace as if the player had (see setOwnBreak). Returns the old value. */
    static boolean setOwnPlace(boolean v) {
        var s = BaritoneAPI.getSettings();
        if (forcedPlace) {
            boolean old = origPlace;
            origPlace = v;
            return old;
        }
        boolean old = s.allowPlace.value;
        s.allowPlace.value = v;
        return old;
    }

    /** Every few ticks. */
    static void tick(LocalPlayer p) {
        boolean noBreak = false, noPlace = false;
        String zone = "";
        if (cfg.protect) {
            BlockPos me = p.blockPosition();
            for (Config.Zone z : cfg.zones) {
                boolean in = z.contains(me);
                if (!in && !(z.reach && z.within(me, cfg.reachBlocks))) continue;
                if (!z.allowBreak) noBreak = true;
                if (!z.allowPlace) noPlace = true;
                if ((!z.allowBreak || !z.allowPlace) && zone.isEmpty()) zone = z.name;
            }
        }
        var s = BaritoneAPI.getSettings();
        boolean announce = false;

        if (noBreak && !forcedBreak && s.allowBreak.value) {
            origBreak = true;
            s.allowBreak.value = false;
            forcedBreak = true;
            announce = true;
        } else if (noBreak && forcedBreak && s.allowBreak.value) {
            // switched on again while we are here (#set, a script's set("allowBreak", true) ...): that is the wish for
            // later; here it stays off
            origBreak = true;
            s.allowBreak.value = false;
        } else if (!noBreak && forcedBreak) {
            if (!s.allowBreak.value) s.allowBreak.value = origBreak;
            forcedBreak = false;
        }

        if (noPlace && !forcedPlace && (s.allowPlace.value || s.allowParkourPlace.value)) {
            origPlace = s.allowPlace.value;
            origParkour = s.allowParkourPlace.value;
            s.allowPlace.value = false;
            s.allowParkourPlace.value = false;
            forcedPlace = true;
            announce = true;
        } else if (noPlace && forcedPlace && (s.allowPlace.value || s.allowParkourPlace.value)) {
            if (s.allowPlace.value) origPlace = true;   // switched on again while we are here: later, not here
            if (s.allowParkourPlace.value) origParkour = true;
            s.allowPlace.value = false;
            s.allowParkourPlace.value = false;
        } else if (!noPlace && forcedPlace) {
            if (!s.allowPlace.value) s.allowPlace.value = origPlace;
            if (!s.allowParkourPlace.value) s.allowParkourPlace.value = origParkour;
            forcedPlace = false;
        }

        if (announce) {
            Util.warn("region", "Near the safe area '" + zone + "': Baritone may not "
                    + (forcedBreak && forcedPlace ? "break or place blocks" : forcedBreak ? "break blocks" : "place blocks")
                    + " here and plans around it. Allowed again when you leave. (/buildtools protect mining|bridging " + zone + " allow)");
        }
    }

    /** Gives Baritone its settings back (safe areas off, disconnected, ...). */
    static void release() {
        if (!forcedBreak && !forcedPlace) return;
        var s = BaritoneAPI.getSettings();
        if (forcedBreak) {
            if (!s.allowBreak.value) s.allowBreak.value = origBreak;
            forcedBreak = false;
        }
        if (forcedPlace) {
            if (!s.allowPlace.value) s.allowPlace.value = origPlace;
            if (!s.allowParkourPlace.value) s.allowParkourPlace.value = origParkour;
            forcedPlace = false;
        }
    }

    static void tickOrRelease(Minecraft mc) {
        if (mc.player == null) release();
        else tick(mc.player);
    }
}
