package dev.buildtools;

import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.IBuilderProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.schematic.AbstractSchematic;
import baritone.api.selection.ISelection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import baritone.api.pathing.goals.GoalBlock;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Replaces the air in the #sel selection with water source blocks, using buckets.
 *
 *  1. SEAL  - water would run out through every open side, so first close them: Baritone's own builder places
 *             throwaway blocks on the open cells just outside the selection (it knows how to bridge and scaffold).
 *  2. FILL  - layer by layer from the bottom: put a source on every second cell (Minecraft turns a cell between two
 *             sources into a source by itself), wait for that to spread, then place the remaining gaps directly.
 *             Water is infinite, so the bucket is refilled from any source that regenerates (two source
 *             neighbours) - or from a pond within 16 blocks for the first bucket.
 */
final class FillProcess extends Feature {
    private enum State { IDLE, SEALING, FILLING }

    private static final int MAX_CELLS = 30_000;

    private State state = State.IDLE;
    private Boolean breakBefore;   // Baritone's allowBreak while a fill runs (restored afterwards)

    /**
     * While a fill runs Baritone only walks and swims, it never digs: a step cut into the trench wall on the way to a
     * standing spot lets the water out (our own placer still breaks its temporary blocks itself).
     */
    private void setState(State next) {
        if (state == State.IDLE && next != State.IDLE) {
            breakBefore = RegionRules.setOwnBreak(false);
        } else if (state != State.IDLE && next == State.IDLE && breakBefore != null) {
            RegionRules.setOwnBreak(breakBefore);
            breakBefore = null;
        }
        if (next == State.IDLE) placeHigh(false);
        state = next;
    }

    /**
     * Baritone may place blocks while filling to climb out of the trench, but not from the rim up: on the live canal,
     * walking to cells it could not reach, it pillared dirt up to 36 blocks high out of the water, and a bridge over the
     * trench at rim height puts dirt into the top layer.
     */
    private void placeHigh(boolean high) {
        if (high && placeBefore == null) {
            placeBefore = RegionRules.setOwnPlace(false);
        } else if (!high && placeBefore != null) {
            RegionRules.setOwnPlace(placeBefore);
            placeBefore = null;
        }
    }
    private Boolean placeBefore;   // Baritone's allowPlace while it is switched off up high (restored afterwards)
    private int topY;              // the highest cell to fill
    private final Set<BlockPos> targets = new HashSet<>();     // cells that must become water sources
    private final Set<BlockPos> skipped = new HashSet<>();     // cells we could not reach
    private List<BlockPos> seal = new ArrayList<>();
    private int sealAttempts, pausedTicks;
    private String failure;     // why the last fill stopped (null while it runs or after a success)

    private final Placer placer = new Placer();
    private BlockPos cur;
    private Placer.Mode curMode;
    private int waitTicks, pass, layerY, noPlaceable, budget, placed, scooped, minX, minZ;
    private boolean refilling;   // empty-handed: scooping up buckets at a source before walking back to the cells
    private int flowWaits;       // ticks spent waiting for a new source to form between two placed sources

    // A cell with nothing solid next to it (the middle of an upper layer: water below, air around) cannot take a bucket.
    // Then a throwaway block goes into the cell below it (the "stilt"), the bucket is emptied on top of it, and the stilt
    // is broken again: the cell below has source water on two sides and refills by itself.
    private BlockPos stilt, stiltTop;
    private int stiltStage;            // 0 none, 1 placing the stilt, 2 water on top, 3 breaking the stilt
    private boolean stiltLayer;        // this layer has cells that only stilts can reach: no more waiting between them
    private int stiltY = Integer.MIN_VALUE;   // the layer stiltLayer belongs to (a refilled stilt cell below may switch layers briefly)
    private final List<BlockPos> stiltsLeft = new ArrayList<>();   // stilts that could not be broken (reported)
    private final Set<BlockPos> badSources = new HashSet<>();      // sources a scoop could not reach: not tried again

    FillProcess() {
        super("BuildTools fill", 14);
    }

    @Override
    boolean busy() {
        return state != State.IDLE;
    }

    // ------------------------------------------------------------------ start

    void startNow() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        if (state != State.IDLE) { Util.msg("Already busy with a fill."); return; }
        failure = null;
        Level level = mc.level;

        ISelection[] selections = Util.baritone().getSelectionManager().getSelections();
        if (selections.length == 0) { Util.msg("Select the area first: #sel pos1, #sel pos2 (or #sel pos1 / pos2 while looking at the corners)."); return; }
        if (level.dimension() == Level.NETHER) { Util.msg("Water evaporates in the Nether."); return; }

        targets.clear();
        skipped.clear();
        minX = Integer.MAX_VALUE;
        minZ = Integer.MAX_VALUE;
        int scanned = 0;
        for (ISelection s : selections) {
            for (int x = s.min().x; x <= s.max().x; x++) {
                for (int y = s.min().y; y <= s.max().y; y++) {
                    for (int z = s.min().z; z <= s.max().z; z++) {
                        if (++scanned > MAX_CELLS) { Util.msg("The selection is too big (more than " + MAX_CELLS + " cells). Fill it in parts."); return; }
                        BlockPos pos = new BlockPos(x, y, z);
                        BlockState st = level.getBlockState(pos);
                        if (Util.isSolid(level, pos) || Util.isSourceWater(st)) continue;   // stays as it is
                        if (!Util.isOpen(level, pos) && !Util.isWater(st)) continue;        // some other block: leave it
                        targets.add(pos);
                        minX = Math.min(minX, x);
                        minZ = Math.min(minZ, z);
                    }
                }
            }
        }
        if (targets.isEmpty()) { Util.msg("Nothing to fill: the selection has no air or flowing water in it."); return; }
        if (Util.slotOf(p, Items.WATER_BUCKET) < 0 && Util.slotOf(p, Items.BUCKET) < 0) {
            Util.msg("You need a bucket (empty or with water) in the inventory.");
            return;
        }

        seal = computeSeal(level);
        int have = Util.countThrowaway(p);
        if (seal.size() > have) {
            Util.msg("Sealing the open sides needs " + seal.size() + " throwaway blocks (cobblestone, dirt, ...), you have " + have + ".");
            return;
        }
        sealAttempts = 0;
        pausedTicks = 0;
        Util.msg("Filling " + targets.size() + " cells with water; " + (seal.isEmpty() ? "nothing to seal." : "sealing " + seal.size() + " open cells first."));
        if (seal.isEmpty()) {
            beginFilling();
        } else {
            setState(State.SEALING);
            startSeal();
        }
    }

    void cancel() {
        if (state == State.SEALING) Util.baritone().getBuilderProcess().onLostControl();
        setState(State.IDLE);
        cur = null;
    }

    /** Open cells next to the target cells (sides and below - water never flows up) that water would run into. */
    private List<BlockPos> computeSeal(Level level) {
        Set<BlockPos> out = new LinkedHashSet<>();
        Direction[] dirs = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        for (BlockPos t : targets) {
            for (Direction d : dirs) {
                BlockPos n = t.relative(d);
                if (!targets.contains(n) && Util.isOpen(level, n)) out.add(n);
            }
        }
        return new ArrayList<>(out);
    }

    // ------------------------------------------------------------------ sealing (Baritone's builder)

    private void startSeal() {
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE;
        int x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (BlockPos s : seal) {
            x0 = Math.min(x0, s.getX()); y0 = Math.min(y0, s.getY()); z0 = Math.min(z0, s.getZ());
            x1 = Math.max(x1, s.getX()); y1 = Math.max(y1, s.getY()); z1 = Math.max(z1, s.getZ());
        }
        Set<Long> relative = new HashSet<>();
        for (BlockPos s : seal) relative.add(SealSchematic.key(s.getX() - x0, s.getY() - y0, s.getZ() - z0));
        Util.baritone().getBuilderProcess().build("buildtools-seal",
                new SealSchematic(x1 - x0 + 1, y1 - y0 + 1, z1 - z0 + 1, relative), new BlockPos(x0, y0, z0));
    }

    /** Wants a plain block on the seal cells and does not care about anything else. */
    private static final class SealSchematic extends AbstractSchematic {
        private final Set<Long> cells;

        SealSchematic(int w, int h, int l, Set<Long> cells) {
            super(w, h, l);
            this.cells = cells;
        }

        static long key(int x, int y, int z) {
            return x | ((long) y << 20) | ((long) z << 40);
        }

        @Override
        public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
            if (!cells.contains(key(x, y, z))) return current;   // not ours: leave as it is
            for (BlockState s : approxPlaceable) {
                if (s.getFluidState().isEmpty() && s.isSolidRender() && !(s.getBlock() instanceof FallingBlock)) return s;
            }
            return current;   // nothing suitable to place
        }
    }

    @Override
    void tick() {
        if (state != State.SEALING) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) { setState(State.IDLE); return; }
        IBuilderProcess builder = Util.baritone().getBuilderProcess();

        if (builder.isActive()) {
            // paused = it hit something it can't do; the drain feature may clear flowing water and resume it
            if (builder.isPaused() && ++pausedTicks > 20 * 45) abort("The builder is stuck while sealing (check its message in chat).");
            else if (!builder.isPaused()) pausedTicks = 0;
            return;
        }
        seal = computeSeal(mc.level);
        if (seal.isEmpty()) {
            Util.msg("Sealed.");
            beginFilling();
        } else if (++sealAttempts < 3) {
            startSeal();
        } else {
            BlockPos first = seal.get(0);
            abort("Could not seal " + seal.size() + " open cells, e.g. at " + first.toShortString() + ". Close them by hand, then run fill again.");
        }
    }

    // ------------------------------------------------------------------ filling

    private void beginFilling() {
        setState(State.FILLING);
        topY = Integer.MIN_VALUE;
        for (BlockPos t : targets) topY = Math.max(topY, t.getY());
        cur = null;
        waitTicks = 0;
        pass = 0;
        layerY = Integer.MIN_VALUE;
        noPlaceable = 0;
        placed = 0;
        scooped = 0;
        refilling = false;
        stilt = null;
        stiltTop = null;
        stiltStage = 0;
        stiltLayer = false;
        stiltY = Integer.MIN_VALUE;
        stiltsLeft.clear();
        badSources.clear();
        flowWaits = 0;
        budget = targets.size() * 8 + 400;
    }

    @Override
    boolean active() {
        if (state != State.IDLE && Minecraft.getInstance().player == null) setState(State.IDLE);
        return state == State.FILLING;
    }

    @Override
    PathingCommand step(boolean calcFailed, boolean isSafeToCancel) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (state != State.FILLING || p == null || mc.level == null || mc.gameMode == null) return defer();
        Level level = mc.level;

        if (waitTicks > 0) {
            waitTicks--;
            return pause();
        }

        placeHigh(p.getY() >= topY + 1);   // on the rim or higher: no pillars, no bridges over the open water (both leave dirt in the canal)

        // never drown: with the head under water and half the air gone, come up first (the action carries on after)
        if (p.isUnderWater() && p.getAirSupply() < p.getMaxAirSupply() / 2) {
            BlockPos up = breathSpot(level, p);
            return new PathingCommand(up != null ? new GoalBlock(up) : new GoalNear(p.blockPosition().above(2), 1), PathingCommandType.SET_GOAL_AND_PATH);
        }

        // a source picked for a scoop that is gone meanwhile (scooped, or flowed away): choose again
        if (cur != null && curMode == Placer.Mode.PICKUP && stiltStage == 0 && !Util.isSourceWater(level.getBlockState(cur))) {
            cur = null;
            return pause();
        }

        // an action (placing water / scooping the bucket / a stilt step) is in progress
        if (cur != null) {
            switch (placer.tick(mc, p)) {
                case DONE -> {
                    if (curMode == Placer.Mode.WATER) placed++;
                    if (curMode == Placer.Mode.PICKUP) scooped++;
                    cur = null;
                    if (stiltStage > 0) return stiltNext(true);
                    return pause();
                }
                case FAILED -> {
                    if (stiltStage > 0) {
                        Util.warn("fill-stilt", "Stilt step failed: " + placer.failure());
                        cur = null;
                        return stiltNext(false);
                    }
                    if (curMode == Placer.Mode.PICKUP) {   // another source next time; none left ends in refill()
                        badSources.add(cur);
                        Util.warn("fill-scoop", "Could not scoop at " + cur.toShortString() + ": " + placer.failure() + "; trying another source.");
                    } else {
                        skipped.add(cur);
                        Util.warn("fill-fail", "Skipping a cell: " + placer.failure());
                    }
                    cur = null;
                    return pause();
                }
                default -> {
                    return placer.canReach(mc, p) ? pause() : walkTo(cur);
                }
            }
        }

        if (--budget < 0) {
            abort("Gave up after too many attempts.");
            return pause();
        }

        // what is still dry, and which layer is the lowest
        List<BlockPos> remaining = new ArrayList<>();
        int lowest = Integer.MAX_VALUE;
        for (BlockPos t : targets) {
            if (skipped.contains(t) || Util.isSourceWater(level.getBlockState(t))) continue;
            remaining.add(t);
            lowest = Math.min(lowest, t.getY());
        }
        if (remaining.isEmpty()) {
            finish();
            return pause();
        }
        if (lowest != layerY) {   // new layer
            layerY = lowest;
            pass = 0;
            noPlaceable = 0;
            stiltLayer = lowest == stiltY;
        }
        final int y = lowest;
        // Baritone may have put a throwaway block into a cell on its way (a pillar to climb out of the trench): take it
        // out again before this layer is filled, nearest first (the cell was open when the fill started)
        BlockPos plug = null;
        double plugD = Double.MAX_VALUE;
        var throwaway = baritone.api.BaritoneAPI.getSettings().acceptableThrowawayItems.value;
        for (BlockPos c : remaining) {
            if (c.getY() != y || !Util.isSolid(level, c) || !throwaway.contains(level.getBlockState(c).getBlock().asItem())) continue;
            double d = c.distSqr(p.blockPosition());
            if (d < plugD) { plugD = d; plug = c; }
        }
        if (plug != null) {
            cur = plug;
            curMode = Placer.Mode.BREAK;
            placer.start(plug, Placer.Mode.BREAK);
            return pause();
        }
        List<BlockPos> placeable = new ArrayList<>();
        for (BlockPos c : remaining) {
            if (c.getY() == y && !Util.isSolid(level, c) && Placer.hasClickable(level, c)) placeable.add(c);
        }

        if (placeable.isEmpty()) {
            if (!stiltLayer && ++noPlaceable <= 3) {   // the water spreading from what we placed may still be turning into sources
                waitTicks = 40;
                return pause();
            }
            // nothing to click against: a stilt below (lattice cells first, so the rest fills by itself)
            BlockPos top = chooseStilt(level, p, remaining, y);
            if (top != null && Util.countThrowaway(p) > 0) {
                if (!stiltLayer) Util.msg("Cells at y=" + y + " have nothing to click against: placing water on temporary blocks.");
                stiltLayer = true;
                stiltY = y;
                PathingCommand r = refill(level, p, remaining);
                if (r != null) return r;
                stiltTop = top;
                stilt = top.below();
                stiltStage = 1;
                cur = stilt;
                curMode = Placer.Mode.BLOCK;
                placer.start(stilt, Placer.Mode.BLOCK);
                return pause();
            }
            if (stiltLayer && ++noPlaceable <= 3) {   // the last stilts' water may still be spreading
                waitTicks = 40;
                return pause();
            }
            int n = 0;
            for (BlockPos c : remaining) {
                if (c.getY() == y) { skipped.add(c); n++; }
            }
            Util.warn("fill-unreach", n + " cells at y=" + y + " cannot be placed (nothing to click against) and did not fill by themselves.");
            return pause();
        }
        noPlaceable = 0;

        List<BlockPos> choices = placeable;
        if (pass == 0) {   // first pass: every second cell in both directions; the water fills in between
            List<BlockPos> lattice = new ArrayList<>();
            for (BlockPos c : placeable) {
                if (((c.getX() - minX) & 1) == 0 && ((c.getZ() - minZ) & 1) == 0) lattice.add(c);
            }
            if (lattice.isEmpty()) {
                pass = 1;
                waitTicks = 60;
                return pause();
            }
            choices = lattice;
        }   // from pass 1 on every placeable gap is filled directly; `budget` stops a layer that never fills

        PathingCommand r = refill(level, p, remaining);
        if (r != null) return r;

        BlockPos next = null;
        double best = Double.MAX_VALUE;
        for (BlockPos c : choices) {
            double d = c.distSqr(p.blockPosition());
            if (d < best) { best = d; next = c; }
        }
        cur = next;
        curMode = Placer.Mode.WATER;
        placer.start(next, Placer.Mode.WATER);
        return pause();
    }

    /**
     * The buckets: refill when empty-handed, several at once, then place them all before walking back.
     * Returns the command when it started a scoop, a wait or stopped the fill; null when there is water to place.
     */
    private PathingCommand refill(Level level, LocalPlayer p, List<BlockPos> remaining) {
        int water = Util.countItem(p, Items.WATER_BUCKET), empty = Util.countItem(p, Items.BUCKET);
        if (water == 0 && empty == 0) {
            abort("No bucket left in the inventory.");
            return pause();
        }
        if (water == 0) refilling = true;
        int want = Math.min(remaining.size(), water + empty);   // buckets worth holding now
        if (refilling && water < want && empty > 0) {
            BlockPos source = findSource(level, p);
            if (source != null) {
                flowWaits = 0;
                cur = source;
                curMode = Placer.Mode.PICKUP;
                placer.start(source, Placer.Mode.PICKUP, water);
                return pause();
            }
            if (water == 0 && flowWaits < 6) {
                // two placed sources one cell apart: the cell between them fills by itself and then regenerates
                flowWaits++;
                waitTicks = 30;
                return pause();
            }
            if (water == 0) {
                abort("No water source to refill from within 16 blocks (carrying " + water + " water and " + empty + " empty buckets). Stand next to a pond or carry water buckets.");
                return pause();
            }
            // no source left in reach: place the water we have
        }
        refilling = false;
        return null;
    }

    /**
     * An upper-layer cell that needs a stilt: nothing solid around it, and the cell below it can take a block (it has a
     * solid neighbour, e.g. the floor) and is water or air. Cells on the 2x2 lattice first: their neighbours then fill
     * by themselves (two source neighbours and source water below). Never a cell the player stands in.
     */
    private BlockPos chooseStilt(Level level, LocalPlayer p, List<BlockPos> remaining, int y) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        var box = p.getBoundingBox();
        for (BlockPos t : remaining) {
            if (t.getY() != y || Util.isSolid(level, t)) continue;
            BlockPos s = t.below();
            BlockState sb = level.getBlockState(s);
            if (!(sb.isAir() || Util.isWater(sb))) continue;
            if (!Placer.hasClickable(level, s)) continue;
            if (box.intersects(new net.minecraft.world.phys.AABB(s)) || box.intersects(new net.minecraft.world.phys.AABB(t))) continue;
            boolean lattice = ((t.getX() - minX) & 1) == 0 && ((t.getZ() - minZ) & 1) == 0;
            double score = t.distSqr(p.blockPosition()) + (lattice ? 0 : 10_000);
            if (score < bestScore) { bestScore = score; best = t; }
        }
        return best;
    }

    /** The next stilt step after one finished (ok) or failed. */
    private PathingCommand stiltNext(boolean ok) {
        switch (stiltStage) {
            case 1 -> {   // the stilt is in: the bucket on top of it
                if (!ok) { skipped.add(stiltTop); stiltStage = 0; return pause(); }
                stiltStage = 2;
                cur = stiltTop;
                curMode = Placer.Mode.WATER;
                placer.start(stiltTop, Placer.Mode.WATER);
            }
            case 2 -> {   // water on top (or not): the stilt goes again either way
                if (!ok) skipped.add(stiltTop);
                stiltStage = 3;
                cur = stilt;
                curMode = Placer.Mode.BREAK;
                placer.start(stilt, Placer.Mode.BREAK);
            }
            default -> {  // broken (or not): done with this cell
                if (!ok) stiltsLeft.add(stilt);
                stiltStage = 0;
                waitTicks = 12;   // the emptied stilt cell turns back into a source
                stilt = null;
                stiltTop = null;
            }
        }
        return pause();
    }

    /** A source to scoop: one that refills by itself (infinite water) or any pond source outside the area. */
    private BlockPos findSource(Level level, LocalPlayer p) {
        BlockPos origin = p.blockPosition();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -16; dx <= 16; dx++) {
            for (int dy = -8; dy <= 8; dy++) {
                for (int dz = -16; dz <= 16; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!Util.isSourceWater(level.getBlockState(pos)) || badSources.contains(pos)) continue;
                    if (!level.getFluidState(pos.above()).isEmpty()) continue;   // something on top: the scoop ray would hit that
                    boolean regen = Util.regenerates(level, pos);
                    if (targets.contains(pos) && !regen) continue;   // would undo our own work
                    double score = pos.distSqr(origin) - (regen ? 1000 : 0);
                    if (score < bestScore) { bestScore = score; best = pos; }
                }
            }
        }
        return best;
    }

    /** Walks to a dry spot from which the cell can be clicked (not just "3 blocks away": that may be a wall). */
    private PathingCommand walkTo(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        BlockPos stand = mc.level == null || mc.player == null ? null : standNear(mc.level, mc.player, pos);
        return new PathingCommand(stand == null ? new GoalNear(pos, 3) : new GoalBlock(stand), PathingCommandType.SET_GOAL_AND_PATH);
    }

    /**
     * A standing spot near the cell with a clear view of it (for a scoop, a placement or a stilt), nearest to the player.
     * Dry ground is best; wading is fine (feet in water on a solid floor, head out of source water), since a filled
     * bottom layer leaves no dry spot inside a trench. Never the cell itself or the stilt cells.
     */
    private BlockPos standNear(Level level, LocalPlayer p, BlockPos cell) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos feet = cell.offset(dx, dy, dz);
                    if (Util.isSolid(level, feet) || Util.isSolid(level, feet.above())) continue;   // room for the body
                    if (!level.getFluidState(feet.above()).isEmpty()) continue;                     // the head stays dry: never drown
                    boolean feetWet = !level.getFluidState(feet).isEmpty();
                    boolean swim = feetWet && !Util.isSolid(level, feet.below());                    // floating at the surface
                    if (!Util.isSolid(level, feet.below()) && !swim) continue;                       // something to stand on
                    if (feet.equals(cell) || feet.above().equals(cell) || feet.equals(stilt) || feet.above().equals(stilt)) continue;
                    if (stiltTop != null && (feet.equals(stiltTop) || feet.above().equals(stiltTop))) continue;
                    Vec3 eye = new Vec3(feet.getX() + 0.5, feet.getY() + 1.62, feet.getZ() + 0.5);
                    boolean ok = switch (curMode) {
                        case PICKUP -> sourceVisibleFrom(level, p, eye, cell);
                        case BREAK -> Placer.breakableFrom(level, p, eye, cell);
                        default -> Placer.placeableFrom(level, p, eye, cell);
                    };
                    if (!ok) continue;
                    double d = feet.distSqr(p.blockPosition());
                    if (feetWet) d += swim ? 20 : 6;   // wading a little worse than dry, swimming more
                    if (d < bestD) { bestD = d; best = feet; }
                }
            }
        }
        return best;
    }

    /** The nearest spot with the head out of the water (dry ground, wading or swimming at the surface). */
    private static BlockPos breathSpot(Level level, LocalPlayer p) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        BlockPos o = p.blockPosition();
        for (int dx = -6; dx <= 6; dx++) {
            for (int dy = -1; dy <= 4; dy++) {
                for (int dz = -6; dz <= 6; dz++) {
                    BlockPos feet = o.offset(dx, dy, dz);
                    if (Util.isSolid(level, feet) || Util.isSolid(level, feet.above())) continue;
                    if (!level.getFluidState(feet.above()).isEmpty()) continue;
                    if (!Util.isSolid(level, feet.below()) && level.getFluidState(feet).isEmpty()) continue;
                    double d = feet.distSqr(o);
                    if (d < bestD) { bestD = d; best = feet; }
                }
            }
        }
        return best;
    }

    private static boolean sourceVisibleFrom(Level level, LocalPlayer p, Vec3 eye, BlockPos cell) {
        Vec3 aim = Vec3.atCenterOf(cell);
        if (eye.distanceTo(aim) > 4.2) return false;
        BlockHitResult hit = level.clip(new ClipContext(eye, aim, ClipContext.Block.COLLIDER, ClipContext.Fluid.SOURCE_ONLY, p));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(cell);
    }

    private void finish() {
        setState(State.IDLE);
        cur = null;
        Level level = Minecraft.getInstance().level;
        int dry = 0;   // skipped cells that did not fill by themselves (most fill from their neighbours)
        BlockPos firstDry = null;
        for (BlockPos c : skipped) {
            if (level != null && Util.isSourceWater(level.getBlockState(c))) continue;
            dry++;
            if (firstDry == null) firstDry = c;
        }
        Util.msg("Done: " + placed + " buckets placed" + (dry == 0 ? "." : ", " + dry + " cells still without water, e.g. at " + firstDry.toShortString() + " (see the warnings above)."));
        if (!stiltsLeft.isEmpty()) Util.msg("Temporary blocks that could not be broken again: " + stiltsLeft.size() + ", e.g. at " + stiltsLeft.get(0).toShortString() + ".");
    }

    private void abort(String why) {
        if (state == State.SEALING) Util.baritone().getBuilderProcess().onLostControl();
        setState(State.IDLE);
        cur = null;
        failure = why;
        Util.msg("Fill stopped: " + why);
    }

    /** Why the last fill stopped early, or null. flood() reads it so that the script stops with that error. */
    String failure() {
        return failure;
    }

    @Override
    public void onLostControl() {   // e.g. #stop
        if (state != State.IDLE) {
            setState(State.IDLE);
            cur = null;
        }
    }
}
