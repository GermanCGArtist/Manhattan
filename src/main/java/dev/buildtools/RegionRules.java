package dev.buildtools;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * The player's own allowBreak / allowPlace for the path engine. Safe areas used to switch both off everywhere while
 * you were near one; they now only reach the planner as boxes (Zones.syncEngine), so these are plain pass-throughs
 * that also give back anything an older build switched off.
 */
final class RegionRules {
    private RegionRules() {}

    private static boolean forcedBreak, forcedPlace;
    private static boolean origBreak, origPlace, origParkour;

    /** Sets allowBreak as if the player had. Returns the old value. */
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

    /**
     * Every tick. Safe areas no longer switch breaking and placing off everywhere while you are near one: the planner
     * gets each area's own box with what it denies (Zones.syncEngine) and plans around exactly that, and Guard still
     * vetoes a click into one. The global switch left a bot that was halfway up a pillar beside a safe area able to go
     * neither up nor down, so every path failed and whole areas were written off as out of reach. This only gives back
     * settings switched off by an older build in this session.
     */
    static void tick(LocalPlayer p) {
        release();
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
