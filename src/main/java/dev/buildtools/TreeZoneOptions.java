package dev.buildtools;

/** The tree-zone settings of the buildtools config, for clear_trees (the Config class itself is package-private). */
public final class TreeZoneOptions {
    private TreeZoneOptions() {}

    /** Diameter of the zone around a tree in blocks (at least 3). */
    public static int diameter() {
        return Math.max(3, Config.cfg.treeZoneDiameter);
    }

    public static boolean show() {
        return Config.cfg.showTreeZones;
    }

    /** Sets the automatic scaffolding sweep on or off and returns what it was (clear_trees turns it off: it would take down scaffolding anywhere). */
    public static boolean setAutoSweep(boolean on) {
        boolean was = Config.cfg.scaffoldAuto;
        Config.cfg.scaffoldAuto = on;
        return was;
    }
}
