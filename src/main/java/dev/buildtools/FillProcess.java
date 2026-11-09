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
    private final Set<BlockPos> targets = new HashSet<>();     // cells that must become water sources
    private final Set<BlockPos> skipped = new HashSet<>();     // cells we could not reach
    private List<BlockPos> seal = new ArrayList<>();
    private int sealAttempts, pausedTicks;

    private final Placer placer = new Placer();
    private BlockPos cur;
    private Placer.Mode curMode;
    private int waitTicks, pass, layerY, noPlaceable, budget, placed, minX, minZ;

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
            state = State.SEALING;
            startSeal();
        }
    }

    void cancel() {
        if (state == State.SEALING) Util.baritone().getBuilderProcess().onLostControl();
        state = State.IDLE;
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
        if (mc.player == null || mc.level == null) { state = State.IDLE; return; }
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
        state = State.FILLING;
        cur = null;
        waitTicks = 0;
        pass = 0;
        layerY = Integer.MIN_VALUE;
        noPlaceable = 0;
        placed = 0;
        budget = targets.size() * 3 + 200;
    }

    @Override
    boolean active() {
        if (state != State.IDLE && Minecraft.getInstance().player == null) state = State.IDLE;
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

        // an action (placing water / scooping the bucket) is in progress
        if (cur != null) {
            switch (placer.tick(mc, p)) {
                case DONE -> {
                    if (curMode == Placer.Mode.WATER) placed++;
                    cur = null;
                    return pause();
                }
                case FAILED -> {
                    if (curMode == Placer.Mode.PICKUP) {
                        abort("Could not scoop water: " + placer.failure());
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
        }
        final int y = lowest;
        List<BlockPos> placeable = new ArrayList<>();
        for (BlockPos c : remaining) {
            if (c.getY() == y && !Util.isSolid(level, c) && Placer.hasClickable(level, c)) placeable.add(c);
        }

        if (placeable.isEmpty()) {
            if (++noPlaceable <= 3) {   // the water spreading from what we placed may still be turning into sources
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

        // need a full bucket?
        if (Util.slotOf(p, Items.WATER_BUCKET) < 0) {
            if (Util.slotOf(p, Items.BUCKET) < 0) {
                abort("No bucket left in the inventory.");
                return pause();
            }
            BlockPos source = findSource(level, p);
            if (source == null) {
                abort("No water source to refill from within 16 blocks. Stand next to a pond or carry water buckets.");
                return pause();
            }
            cur = source;
            curMode = Placer.Mode.PICKUP;
            placer.start(source, Placer.Mode.PICKUP);
            return pause();
        }

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

    /** A source to scoop: one that refills by itself (infinite water) or any pond source outside the area. */
    private BlockPos findSource(Level level, LocalPlayer p) {
        BlockPos origin = p.blockPosition();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -16; dx <= 16; dx++) {
            for (int dy = -8; dy <= 8; dy++) {
                for (int dz = -16; dz <= 16; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!Util.isSourceWater(level.getBlockState(pos))) continue;
                    boolean regen = Util.regenerates(level, pos);
                    if (targets.contains(pos) && !regen) continue;   // would undo our own work
                    double score = pos.distSqr(origin) - (regen ? 1000 : 0);
                    if (score < bestScore) { bestScore = score; best = pos; }
                }
            }
        }
        return best;
    }

    private PathingCommand walkTo(BlockPos pos) {
        return new PathingCommand(new GoalNear(pos, 3), PathingCommandType.SET_GOAL_AND_PATH);
    }

    private void finish() {
        state = State.IDLE;
        cur = null;
        Util.msg("Done: " + placed + " buckets placed" + (skipped.isEmpty() ? "." : ", " + skipped.size() + " cells skipped (see the warnings above)."));
    }

    private void abort(String why) {
        if (state == State.SEALING) Util.baritone().getBuilderProcess().onLostControl();
        state = State.IDLE;
        cur = null;
        Util.msg("Fill stopped: " + why);
    }

    @Override
    public void onLostControl() {   // e.g. #stop
        if (state != State.IDLE) {
            state = State.IDLE;
            cur = null;
        }
    }
}
