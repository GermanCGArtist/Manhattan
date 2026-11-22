package dev.buildtools;

import net.minecraft.world.item.Item;

import static dev.buildtools.Config.cfg;

/** What the script (dev.manhattan) may call. Each start method returns an error text, or null when it started. */
public final class Api {
    private Api() {}

    /** Fills the water of the selection from dry ground, column by column from the bottom up. */
    public static String fillWater(Item item) {
        return BuildToolsMod.terminator.startNow(item);
    }

    /** Fills the water inside {@code explicitBoxes} without mutating global Areas selection. */
    public static String fillWater(Item item, java.util.List<int[]> explicitBoxes) {
        return BuildToolsMod.terminator.startNow(item, explicitBoxes);
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

    /** Drains water inside {@code explicitBoxes} without mutating global Areas selection. */
    public static String drainNow(java.util.List<int[]> explicitBoxes) {
        BuildToolsMod.drain.startNow(explicitBoxes);
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

    /** Fills {@code explicitBoxes} WITH water without mutating global Areas selection. */
    public static String floodNow(java.util.List<int[]> explicitBoxes) {
        BuildToolsMod.fill.startNow(explicitBoxes);
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

    /**
     * Optional filter restricting which positions may be recorded or collected as scaffolding (e.g. clear_trees cylinder zones).
     * null = no zone restriction.
     */
    public static volatile java.util.function.Predicate<net.minecraft.core.BlockPos> scaffoldAllowedZone;

    /** Marks a block as scaffolding, so collect_scaffold() takes it down (clear_trees finds pillars the recorder missed). */
    public static void addScaffold(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.Block block) {
        Scaffold.spots().put(pos.immutable(), block);
    }

    /**
     * Takes the recorded scaffolding farther than r blocks from c off the list and returns it, so collect_scaffold() only
     * works near c instead of walking to old pillars somewhere else; put it back with restoreScaffold.
     */
    public static java.util.Map<net.minecraft.core.BlockPos, net.minecraft.world.level.block.Block> stashScaffoldFarFrom(net.minecraft.core.BlockPos c, int r) {
        java.util.Map<net.minecraft.core.BlockPos, net.minecraft.world.level.block.Block> far = new java.util.LinkedHashMap<>();
        double r2 = (double) r * r;
        for (var it = Scaffold.spots().entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (e.getKey().distSqr(c) > r2) {
                far.put(e.getKey(), e.getValue());
                it.remove();
            }
        }
        return far;
    }

    /** Like stashScaffoldFarFrom, but takes off the list (and returns) every recorded spot for which {@code keep} is true. */
    public static java.util.Map<net.minecraft.core.BlockPos, net.minecraft.world.level.block.Block> stashScaffoldIf(java.util.function.Predicate<net.minecraft.core.BlockPos> keep) {
        java.util.Map<net.minecraft.core.BlockPos, net.minecraft.world.level.block.Block> kept = new java.util.LinkedHashMap<>();
        for (var it = Scaffold.spots().entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (keep.test(e.getKey())) {
                kept.put(e.getKey(), e.getValue());
                it.remove();
            }
        }
        return kept;
    }

    public static void restoreScaffold(java.util.Map<net.minecraft.core.BlockPos, net.minecraft.world.level.block.Block> stashed) {
        if (stashed != null) Scaffold.spots().putAll(stashed);
    }

    public static void forgetScaffold() {
        Scaffold.spots().clear();
    }

    public static void forgetScaffold(net.minecraft.core.BlockPos pos) {
        if (pos != null) Scaffold.forget(pos);
    }

    /** True when {@code pos} currently holds a recorded temporary scaffold block. */
    public static boolean isScaffold(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
        return level != null && pos != null && Scaffold.valid(level, pos);
    }

    /** Snapshot of all currently recorded scaffold positions. */
    public static java.util.List<net.minecraft.core.BlockPos> scaffoldSpots() {
        return java.util.List.copyOf(Scaffold.spots().keySet());
    }

    /** A safe area from two corners; returns an error text or null. */
    public static String protect(String name, int x1, int y1, int z1, int x2, int y2, int z2) {
        return Zones.addBox(x1, y1, z1, x2, y2, z2, name, false) == 1 ? null : "Could not save the safe area (see the message above).";
    }

    /** The selection becomes a safe area. */
    public static String protectSelection(String name) {
        return Zones.addSelection(name) == 1 ? null : "Could not save the safe area (is there a selection?).";
    }

    /** Replaces all protected boxes belonging to group {@code name} and syncs engines. */
    public static void protectGroup(String name, java.util.List<int[]> boxes) {
        if (name == null || name.isBlank()) return;
        Zones.putGroup(name, boxes);
    }

    public static boolean unprotect(String name) {
        if (name == null) return false;
        boolean had = cfg.zones.removeIf(z -> z.name.equalsIgnoreCase(name) || name.equalsIgnoreCase(z.group));
        if (had) {
            Zones.syncEngines();
            Config.save();
        }
        return had;
    }

    public static java.util.List<String> zones() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (var z : cfg.zones) out.add(z.describe());
        return out;
    }

    /** Immutable snapshot of a protected safe area box for client-side visualization. */
    public record ProtectedZone(String name, String group, int x1, int y1, int z1, int x2, int y2, int z2,
                                boolean allowBreak, boolean allowPlace) {}

    public static java.util.List<ProtectedZone> protectedZones() {
        java.util.List<ProtectedZone> out = new java.util.ArrayList<>();
        if (cfg != null && cfg.zones != null) {
            for (var z : cfg.zones) {
                if (z != null) {
                    out.add(new ProtectedZone(z.name, z.group, z.x1, z.y1, z.z1, z.x2, z.y2, z.z2, z.allowBreak, z.allowPlace));
                }
            }
        }
        return out;
    }

    public static boolean protectEnabled() {
        return cfg != null && cfg.protect;
    }

    public static int[] pendingCorner1() {
        return cfg != null && cfg.corner1 != null && cfg.corner1.length >= 3
                ? new int[]{cfg.corner1[0], cfg.corner1[1], cfg.corner1[2]} : null;
    }

    public static int[] pendingCorner2() {
        return cfg != null && cfg.corner2 != null && cfg.corner2.length >= 3
                ? new int[]{cfg.corner2[0], cfg.corner2[1], cfg.corner2[2]} : null;
    }

    /** Holds water and is not solid: water, seagrass, kelp, bubble column ... */
    public static boolean wet(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
        return TerminatorProcess.wet(level, pos);
    }

    /** Sets the player's own allowBreak (near a safe area it takes effect once you leave). Returns the old value. */
    public static boolean setOwnBreak(boolean v) {
        return RegionRules.setOwnBreak(v);
    }

    /** Sets the player's own allowPlace (near a safe area it takes effect once you leave). Returns the old value. */
    public static boolean setOwnPlace(boolean v) {
        return RegionRules.setOwnPlace(v);
    }

    public static boolean isEmergencyNeeded() {
        return BuildToolsMod.escape.isEmergencyNeeded();
    }

    public static boolean startEmergencyNow() {
        return BuildToolsMod.escape.startEmergencyNow();
    }

    public static boolean emergencyBusy() {
        return BuildToolsMod.escape.busy();
    }

    public static void stopEmergency() {
        BuildToolsMod.escape.onLostControl();
    }

    /** Inside a safe area (and safe areas are on). */
    public static boolean protectedAt(net.minecraft.core.BlockPos p) {
        if (!cfg.protect) return false;
        if (dev.midtown.api.EngineSelector.current().isProtectedAt(p)) return true;
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
