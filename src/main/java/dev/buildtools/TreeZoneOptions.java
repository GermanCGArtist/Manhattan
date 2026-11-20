package dev.buildtools;

/** The tree-zone settings of the buildtools config, for clear_trees (the Config class itself is package-private). */
public final class TreeZoneOptions {
    private static volatile boolean autoSweepSuppressed;

    private TreeZoneOptions() {}

    /** Diameter of the zone around a tree in blocks (at least 3). */
    public static int diameter() {
        return Math.max(3, Config.cfg.treeZoneDiameter);
    }

    /** Sets the tree-zone diameter (clamped to at least 3) and returns the previous value. */
    public static int setDiameter(int d) {
        int was = diameter();
        Config.cfg.treeZoneDiameter = Math.max(3, d);
        return was;
    }

    public static boolean show() {
        return Config.cfg.showTreeZones;
    }

    /** Sets whether tree zones are drawn in the world and returns the previous value. */
    public static boolean setShow(boolean show) {
        boolean was = Config.cfg.showTreeZones;
        Config.cfg.showTreeZones = show;
        return was;
    }

    /** Sets the automatic scaffolding sweep on or off and returns what it was (clear_trees turns it off: it would take down scaffolding anywhere). */
    public static boolean setAutoSweep(boolean on) {
        boolean was = Config.cfg.scaffoldAuto;
        Config.cfg.scaffoldAuto = on;
        return was;
    }

    /** Hard runtime suppression of automatic scaffolding sweeps (even if enable("scaffold") / enable("all") is active). */
    public static boolean setAutoSweepSuppressed(boolean suppressed) {
        boolean was = autoSweepSuppressed;
        autoSweepSuppressed = suppressed;
        return was;
    }

    public static boolean isAutoSweepSuppressed() {
        return autoSweepSuppressed;
    }
}
