package dev.buildtools;

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
        var engine = dev.midtown.api.EngineSelector.current();
        if (forcedBreak) {
            boolean old = origBreak;
            origBreak = v;
            return old;
        }
        boolean old = engine.allowBreak();
        engine.setAllowBreak(v);
        return old;
    }

    /** Sets allowPlace as if the player had (see setOwnBreak). Returns the old value. */
    static boolean setOwnPlace(boolean v) {
        var engine = dev.midtown.api.EngineSelector.current();
        if (forcedPlace) {
            boolean old = origPlace;
            origPlace = v;
            return old;
        }
        boolean old = engine.allowPlace();
        engine.setAllowPlace(v);
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
        var engine = dev.midtown.api.EngineSelector.current();
        boolean announce = false;

        if (noBreak && !forcedBreak && engine.allowBreak()) {
            origBreak = true;
            engine.setAllowBreak(false);
            forcedBreak = true;
            announce = true;
        } else if (noBreak && forcedBreak && engine.allowBreak()) {
            // switched on again while we are here (#set, a script's set("allowBreak", true) ...): that is the wish for
            // later; here it stays off
            origBreak = true;
            engine.setAllowBreak(false);
        } else if (!noBreak && forcedBreak) {
            if (!engine.allowBreak()) engine.setAllowBreak(origBreak);
            forcedBreak = false;
        }

        if (noPlace && !forcedPlace && (engine.allowPlace() || engine.allowParkourPlace())) {
            origPlace = engine.allowPlace();
            origParkour = engine.allowParkourPlace();
            engine.setAllowPlace(false);
            engine.setAllowParkourPlace(false);
            forcedPlace = true;
            announce = true;
        } else if (noPlace && forcedPlace && (engine.allowPlace() || engine.allowParkourPlace())) {
            if (engine.allowPlace()) origPlace = true;   // switched on again while we are here: later, not here
            if (engine.allowParkourPlace()) origParkour = true;
            engine.setAllowPlace(false);
            engine.setAllowParkourPlace(false);
        } else if (!noPlace && forcedPlace) {
            if (!engine.allowPlace()) engine.setAllowPlace(origPlace);
            if (!engine.allowParkourPlace()) engine.setAllowParkourPlace(origParkour);
            forcedPlace = false;
        }

        if (announce) {
            Util.warn("region", "Near the safe area '" + zone + "': Baritone may not "
                    + (forcedBreak && forcedPlace ? "break or place blocks" : forcedBreak ? "break blocks" : "place blocks")
                    + " here and plans around it. Allowed again when you leave. (/buildtools protect mining|bridging " + zone + " allow)");
        }
    }

    /** Gives the path engine its settings back (safe areas off, disconnected, ...). */
    static void release() {
        if (!forcedBreak && !forcedPlace) return;
        var engine = dev.midtown.api.EngineSelector.current();
        if (forcedBreak) {
            if (!engine.allowBreak()) engine.setAllowBreak(origBreak);
            forcedBreak = false;
        }
        if (forcedPlace) {
            if (!engine.allowPlace()) engine.setAllowPlace(origPlace);
            if (!engine.allowParkourPlace()) engine.setAllowParkourPlace(origParkour);
            forcedPlace = false;
        }
    }

    static void tickOrRelease(Minecraft mc) {
        if (mc.player == null) release();
        else tick(mc.player);
    }
}
