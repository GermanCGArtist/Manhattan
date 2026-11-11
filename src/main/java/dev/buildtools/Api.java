package dev.buildtools;

import net.minecraft.world.item.Item;

import static dev.buildtools.Config.cfg;

/** What the script (dev.baritonequeue) may call. Each start method returns an error text, or null when it started. */
public final class Api {
    private Api() {}

    /** Fills the water of the selection from dry ground, column by column from the bottom up. */
    public static String fillWater(Item item) {
        return BuildToolsMod.terminator.startNow(item);
    }

    public static boolean fillingWater() {
        return BuildToolsMod.terminator.busy();
    }

    public static void stopFillingWater() {
        BuildToolsMod.terminator.cancel();
    }

    /** {filled, still water, unreachable} of the last water fill. */
    public static int[] lastFill() {
        return BuildToolsMod.terminator.lastResult();
    }

    /** The last water fill stopped because the blocks ran out. */
    public static boolean fillRanOut() {
        return BuildToolsMod.terminator.ranOut();
    }

    public static String lastFillReason() {
        return BuildToolsMod.terminator.lastReason();
    }

    public static String drainNow() {
        BuildToolsMod.drain.startNow();
        return BuildToolsMod.drain.busy() ? null : "";
    }

    public static boolean draining() {
        return BuildToolsMod.drain.busy();
    }

    public static void stopDraining() {
        BuildToolsMod.drain.cancel();
    }

    /** Fills the selection WITH water, using buckets. */
    public static String floodNow() {
        BuildToolsMod.fill.startNow();
        return BuildToolsMod.fill.busy() ? null : "Could not start filling with water (see the message above).";
    }

    /** Why the last flood stopped early (its message is in chat too), or null. */
    public static String floodFailure() {
        return BuildToolsMod.fill.failure();
    }

    /** An "on" block interrupts flood(): pause it (keeps its cells and skips). */
    public static void pauseFlooding() {
        BuildToolsMod.fill.suspendRun();
    }

    /** After the "on" block: carry on; null = going, "" = nothing to carry on, else an error. */
    public static String resumeFlooding() {
        return BuildToolsMod.fill.carryOn();
    }

    public static boolean flooding() {
        return BuildToolsMod.fill.busy();
    }

    public static void stopFlooding() {
        BuildToolsMod.fill.cancel();
    }

    public static String collectScaffoldNow() {
        BuildToolsMod.scaffold.startNow();
        return BuildToolsMod.scaffold.busy() ? null : "";
    }

    public static boolean collectingScaffold() {
        return BuildToolsMod.scaffold.busy();
    }

    public static void stopCollectingScaffold() {
        BuildToolsMod.scaffold.cancel();
    }

    public static int scaffoldCount() {
        return Scaffold.count();
    }

    /**
     * Set by the schematic builder: true when a throwaway block placed at this spot is NOT part of the schematic being
     * built, so it is scaffolding and is recorded even while Baritone's builder runs. null = no schematic build.
     */
    public static volatile java.util.function.BiPredicate<net.minecraft.core.BlockPos, net.minecraft.world.level.block.Block> scaffoldOutsideSchematic;

    public static void forgetScaffold() {
        Scaffold.spots().clear();
    }

    /** A safe area from two corners; returns an error text or null. */
    public static String protect(String name, int x1, int y1, int z1, int x2, int y2, int z2) {
        return Zones.addBox(x1, y1, z1, x2, y2, z2, name, false) == 1 ? null : "Could not save the safe area (see the message above).";
    }

    /** The selection becomes a safe area. */
    public static String protectSelection(String name) {
        return Zones.addSelection(name) == 1 ? null : "Could not save the safe area (is there a selection?).";
    }

    public static boolean unprotect(String name) {
        boolean had = cfg.zones.removeIf(z -> z.name.equalsIgnoreCase(name));
        if (had) Config.save();
        return had;
    }

    public static java.util.List<String> zones() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (var z : cfg.zones) out.add(z.describe());
        return out;
    }

    /** Holds water and is not solid: water, seagrass, kelp, bubble column ... */
    public static boolean wet(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
        return TerminatorProcess.wet(level, pos);
    }

    /** Sets the player's own allowBreak (near a safe area it takes effect once you leave). Returns the old value. */
    public static boolean setOwnBreak(boolean v) {
        return RegionRules.setOwnBreak(v);
    }

    /** Inside a safe area (and safe areas are on). */
    public static boolean protectedAt(net.minecraft.core.BlockPos p) {
        if (!cfg.protect) return false;
        for (var z : cfg.zones) if (z.contains(p)) return true;
        return false;
    }

    /** A block you can stand on and click against. */
    public static boolean solid(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos p) {
        return Util.isSolid(level, p);
    }

    public static boolean busy() {
        return BuildToolsMod.scaffold.busy() || BuildToolsMod.terminator.busy() || BuildToolsMod.drain.busy() || BuildToolsMod.fill.busy();
    }

    public static Object config() {
        return cfg;
    }

    public static void save() {
        Config.save();
    }
}
