package dev.buildtools;

import dev.midtown.api.EngineSelector;
import dev.midtown.api.EngineStatus;
import dev.midtown.api.GoalSpec;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.buildtools.Config.cfg;

/**
 * Fills the water of the selection strictly from the bottom up.
 *
 * A "wet cell" is every cell that holds water and is not a solid block: plain water, seagrass, kelp, bubble columns.
 * A wet cell is only filled when no wet cell is left below it in its column (inside the selection), so no water is
 * ever sealed in. Kelp and other plants that cannot be built over are broken first.
 *
 * Where it works from:
 *   - dry ground, or water one block deep with the head dry (wading): it reaches about 3 blocks down;
 *   - dive mode (option buildtools.dive, on by default) for what is deeper: it picks a spot on the bottom next to the
 *     cell, with open water straight up to the surface (never under a roof), swims there along the surface, sinks
 *     down, places blocks while its air lasts, and comes up again. Baritone does not steer in the water: it got stuck
 *     there before; this process holds the movement keys itself.
 * The next cell is looked for in the whole selection (nearest first), so a long area is worked through to the end, and
 * after an interruption (bed, eating, a fight) it simply walks back.
 * With sand, gravel or concrete powder it drops the block into the top of a column; it sinks by itself.
 */
public final class TerminatorProcess extends Feature {
    private static final Logger LOGGER = LoggerFactory.getLogger(TerminatorProcess.class);

    private static final double REACH = 4.3;
    private static final int WALK_TIMEOUT = 20 * 40;
    private static final int RANGE = 5;
    private static final int BREAK_TIMEOUT = 20 * 4;
    private static final int TARGET_TIMEOUT = 20 * 3;     // a cell that turned out to be out of reach: next one
    private static final int MAX_FRUITLESS = 120;         // decisions in a row without placing anything: give up
    private static final int AIR_LOW = 110;               // of 300: come up for air
    private static final int SWIM_STUCK = 20 * 4;
    private static final int DIVE_TIMEOUT = 20 * 25;

    private enum State { WORK, WALK, SWIM, SINK, UNDER, PILLAR, RISE }

    private boolean working;
    private Item preferred;
    private boolean falling;
    private final Placer placer = new Placer();
    private State state = State.WORK;
    private BlockPos current, breaking, goal, diveSpot, diveTarget;
    private int walkTicks, placed, breakTicks, countBefore, targetTicks, fruitless, stateTicks, stuckTicks;
    private int stepAside;   // times we stepped out of a wet cell we were standing in (its block can't go where we are)
    private double lastDist;
    private int placedAtDive;
    private BlockPos walkTarget;   // the cell the current walk is for
    private long stepTick, tickCount;
    private long lastHeartbeatTick;
    private boolean keysHeld;
    private final Map<BlockPos, Integer> failures = new HashMap<>();
    private final Set<BlockPos> badStand = new HashSet<>(), badDive = new HashSet<>();
    private final List<int[]> boxes = new ArrayList<>();
    private int[] lastResult = {0, 0, 0};
    private String lastReason = "";
    private boolean ranOut;
    private Boolean breakBefore;   // the player's own allowBreak, given back at the end
    private Boolean placeBefore;   // the player's own allowPlace, given back at the end

    private void setState(State next) {
        if (state != next) {
            LOGGER.info("[Terminator] state {} -> {}", state, next);
            state = next;
        }
    }

    private void markBadStand(BlockPos s) {
        if (s != null && badStand.add(s)) {
            LOGGER.info("[Terminator] badStand added: {}", s.toShortString());
        }
    }

    private void markBadDive(BlockPos s) {
        if (s != null && badDive.add(s)) {
            LOGGER.info("[Terminator] badDive added: {}", s.toShortString());
        }
    }

    TerminatorProcess() {
        super("BuildTools terminator", 13);
    }

    @Override
    boolean busy() {
        return working;
    }

    int[] lastResult() {
        return lastResult;
    }

    boolean ranOut() {
        return ranOut;
    }

    String lastReason() {
        return lastReason;
    }

    /** Starts; returns an error text or null. */
    String startNow(Item item) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return "Not in a world.";
        if (working) return "The water fill is already running.";
        if (item != null && !(item instanceof BlockItem)) return "'" + BuiltInRegistries.ITEM.getKey(item).getPath() + "' cannot be placed as a block.";
        if (item != null && Util.slotOf(p, item) < 0) return "There is no " + BuiltInRegistries.ITEM.getKey(item).getPath() + " in the inventory.";
        if (item == null && Util.countThrowaway(p) == 0) return "I have no blocks to fill with (dirt, cobblestone ... or name one: fill_water(\"dirt\")).";
        boxes.clear();
        boxes.addAll(dev.baritonequeue.Areas.read());
        if (boxes.isEmpty() && Config.cfg.corner1 != null && Config.cfg.corner2 != null) {
            boxes.add(new int[]{
                    Math.min(Config.cfg.corner1[0], Config.cfg.corner2[0]), Math.min(Config.cfg.corner1[1], Config.cfg.corner2[1]), Math.min(Config.cfg.corner1[2], Config.cfg.corner2[2]),
                    Math.max(Config.cfg.corner1[0], Config.cfg.corner2[0]), Math.max(Config.cfg.corner1[1], Config.cfg.corner2[1]), Math.max(Config.cfg.corner1[2], Config.cfg.corner2[2])});
        }
        if (boxes.isEmpty()) return "Select the area first: area(\"name\"), select(corner1, corner2).";
        preferred = item;
        falling = item instanceof BlockItem bi && bi.getBlock() instanceof FallingBlock;
        Placer.preferred = item;
        failures.clear();
        badStand.clear();
        badDive.clear();
        current = null;
        goal = null;
        breaking = null;
        diveSpot = null;
        setState(State.WORK);
        walkTicks = 0;
        placed = 0;
        fruitless = 0;
        stepAside = 0;
        lastReason = "";
        ranOut = false;
        lastHeartbeatTick = 0;
        breakBefore = RegionRules.setOwnBreak(false);
        placeBefore = RegionRules.setOwnPlace(false);
        working = true;
        warnWaterBelow(mc.level);
        Util.msg("Filling the water from the bottom up" + (item != null ? " with " + BuiltInRegistries.ITEM.getKey(item).getPath() : "")
                + (falling ? " (it sinks to the bottom by itself)" : cfg.dive ? " (diving where it is too deep)" : "") + ".");
        return null;
    }

    private void giveBackBreak() {
        if (breakBefore != null) {
            RegionRules.setOwnBreak(breakBefore);
            breakBefore = null;
        }
        if (placeBefore != null) {
            RegionRules.setOwnPlace(placeBefore);
            placeBefore = null;
        }
    }

    // walking: the goal, and whether we are getting closer
    private boolean triedNear, triedExact;
    private double bestGoalDist;
    private int noProgress;

    private void startWalk(Level level, BlockPos to) {
        goal = to;
        setState(State.WALK);
        walkTicks = 0;
        noProgress = 0;
        bestGoalDist = Double.MAX_VALUE;
        LocalPlayer p = Minecraft.getInstance().player;
        boolean inWet = p != null && (p.isInWater() || p.isUnderWater()
                || p.blockPosition().equals(walkTarget)
                || (inSelection(p.blockPosition()) && wet(level, p.blockPosition())));
        boolean useLoS = diveSpot == null && walkTarget != null && !inWet;
        triedNear = !useLoS;
        triedExact = !useLoS && diveSpot == null;
        GoalSpec spec = useLoS
                ? GoalSpec.lineOfSight(walkTarget, 4.5)
                : (triedExact ? GoalSpec.exact(to) : GoalSpec.near(to, 1));
        LOGGER.info("[Terminator] startWalk: to={} useLoS={} walkTarget={} goal={}",
                to.toShortString(), useLoS,
                (walkTarget == null ? "none" : walkTarget.toShortString()),
                to.toShortString());
        EngineSelector.current().goTo(spec);
    }

    void cancel() {
        LOGGER.info("[Terminator] cancel");
        EngineSelector.current().cancel();
        giveBackBreak();
        working = false;
        current = null;
        goal = null;
        breaking = null;
        diveSpot = null;
        setState(State.WORK);
        lastHeartbeatTick = 0;
        Placer.preferred = null;
        releaseKeys();
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
    }

    // ------------------------------------------------------------------ keys (only ever held while this process steers)

    private static void key(KeyMapping k, boolean down) {
        k.setDown(down);
    }

    private void hold(boolean forward, boolean jump, boolean sneak) {
        var o = Minecraft.getInstance().options;
        key(o.keyUp, forward);
        key(o.keyJump, jump);
        key(o.keyShift, sneak);
        keysHeld = forward || jump || sneak;
    }

    private void releaseKeys() {
        if (!keysHeld) return;
        var o = Minecraft.getInstance().options;
        key(o.keyUp, false);
        key(o.keyJump, false);
        key(o.keyShift, false);
        keysHeld = false;
    }

    @Override
    void tick() {
        tickCount++;
        if (!working) {
            if (keysHeld && stepTick < tickCount - 1) releaseKeys();
            return;
        }
        stepTick = tickCount;
        stepInternal();
    }

    // ------------------------------------------------------------------ geometry

    private boolean inSelection(BlockPos pos) {
        for (int[] b : boxes) {
            if (pos.getX() >= b[0] && pos.getX() <= b[3] && pos.getY() >= b[1] && pos.getY() <= b[4] && pos.getZ() >= b[2] && pos.getZ() <= b[5]) return true;
        }
        return false;
    }

    /** Holds water and is not a solid block: water, seagrass, kelp, bubble column, ... (a waterlogged stair is solid). */
    static boolean wet(Level level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return s.getFluidState().is(FluidTags.WATER) && !s.blocksMotion();
    }

    private static boolean dry(Level level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return s.getCollisionShape(level, pos).isEmpty() && s.getFluidState().isEmpty();
    }

    /** Nothing to bump into: air or water. */
    private static boolean open(Level level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    /** A cell the player can stand in without swimming: solid dry ground below, a dry head, feet dry or in shallow water. */
    private static boolean stand(Level level, BlockPos c) {
        BlockState feet = level.getBlockState(c);
        boolean feetOk = feet.getCollisionShape(level, c).isEmpty() && (feet.getFluidState().isEmpty() || wet(level, c));
        return feetOk && dry(level, c.above()) && (!wet(level, c) || dry(level, c.above(2))) && Util.isSolid(level, c.below());
    }

    /** No wet cell below this one in its column (inside the selection): filling it can never trap water. */
    private boolean columnBelowDry(Level level, BlockPos pos) {
        BlockPos.MutableBlockPos m = pos.mutable();
        for (int i = 0; i < 384; i++) {
            m.move(Direction.DOWN);
            if (!inSelection(m)) return true;
            if (wet(level, m)) return false;
            if (Util.isSolid(level, m)) return true;
        }
        return true;
    }

    private boolean fillable(Level level, BlockPos pos) {
        if (!inSelection(pos) || failures.getOrDefault(pos, 0) >= 3 || !wet(level, pos)) return false;
        if (falling) {
            BlockPos up = pos.above();
            return (!inSelection(up) || !wet(level, up)) && (Placer.hasClickable(level, pos) || breakable(level, pos));
        }
        return columnBelowDry(level, pos) && (Placer.hasClickable(level, pos) || breakable(level, pos));
    }

    private static boolean breakable(Level level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return !s.canBeReplaced() && !s.isAir() && s.getDestroySpeed(level, pos) >= 0 && s.getDestroySpeed(level, pos) < 1.0f;
    }

    private void warnWaterBelow(Level level) {
        for (int[] b : boxes) {
            for (int x = b[0]; x <= b[3]; x++) {
                for (int z = b[2]; z <= b[5]; z++) {
                    BlockPos under = new BlockPos(x, b[1] - 1, z);
                    if (level.hasChunkAt(under) && wet(level, under) && wet(level, under.above())) {
                        Util.warn("fill-below", "There is water right under the bottom of the selection. It stays there, sealed in. "
                                + "Make the selection reach down to the floor of the water if that matters.");
                        return;
                    }
                }
            }
        }
    }

    /** A dry spot to stand on next to the feet (solid below, room for the body, no water), nearest first. */
    private BlockPos asideSpot(Level level, BlockPos feet) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    BlockPos f = feet.offset(dx, dy, dz);
                    if (Util.isSolid(level, f) || Util.isSolid(level, f.above()) || !Util.isSolid(level, f.below())) continue;
                    if (!level.getFluidState(f).isEmpty() || !level.getFluidState(f.above()).isEmpty()) continue;
                    double d = f.distSqr(feet);
                    if (d < bestD) { bestD = d; best = f; }
                }
            }
        }
        return best;
    }

    private static Vec3 eyeAt(BlockPos feet) {
        return new Vec3(feet.getX() + 0.5, feet.getY() + 1.62, feet.getZ() + 0.5);
    }

    /** Wet cell to work on from where the player is now, lowest first, or null. Never the column we dive in. */
    private BlockPos findInReach(Minecraft mc, LocalPlayer p, Level level) {
        Vec3 eye = p.getEyePosition();
        BlockPos base = BlockPos.containing(eye);
        BlockPos feet = p.blockPosition();
        boolean underwater = p.isUnderWater() || state == State.UNDER || state == State.SINK;
        var box = p.getBoundingBox().inflate(0.35, 0, 0.35);
        List<BlockPos> cand = new ArrayList<>();
        for (int dx = -RANGE; dx <= RANGE; dx++) {
            for (int dy = -RANGE - 1; dy <= RANGE; dy++) {
                for (int dz = -RANGE; dz <= RANGE; dz++) {
                    BlockPos pos = base.offset(dx, dy, dz);
                    if (pos.equals(feet) || pos.equals(feet.above())) continue;
                    if (underwater && pos.getY() > feet.getY() - 1 && pos.getX() + 1 > box.minX && pos.getX() < box.maxX
                            && pos.getZ() + 1 > box.minZ && pos.getZ() < box.maxZ) continue;   // never over any column the body touches
                    if (underwater && diveSpot != null && pos.getX() == diveSpot.getX() && pos.getZ() == diveSpot.getZ() && pos.getY() >= diveSpot.getY()) continue;   // the way up stays open
                    if (eye.distanceTo(Vec3.atCenterOf(pos)) > REACH + 0.6) continue;
                    if (fillable(level, pos)) cand.add(pos);
                }
            }
        }
        cand.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingDouble(c -> eye.distanceToSqr(Vec3.atCenterOf(c))));
        for (BlockPos pos : cand) {
            if (breakable(level, pos)) {
                if (eye.distanceTo(Vec3.atCenterOf(pos)) <= REACH) return pos;
                continue;
            }
            Placer probe = new Placer();
            probe.start(pos, Placer.Mode.BLOCK);
            if (probe.canReach(mc, p)) return pos;
        }
        return null;
    }

    // ------------------------------------------------------------------ where to go next

    private record Plan(BlockPos target, BlockPos stand, boolean dive) {}

    private static boolean protectedAt(BlockPos c) {
        if (!cfg.protect) return false;
        for (Config.Zone z : cfg.zones) if (z.contains(c)) return true;
        return false;
    }

    /** A dry (or wading) spot from which the cell is in reach, or null. */
    private BlockPos standFor(Level level, BlockPos t) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = -1; dy <= 5; dy++) {
                    BlockPos s = t.offset(dx, dy, dz);
                    if (s.equals(t) || s.equals(t.above()) || badStand.contains(s) || !level.hasChunkAt(s)) continue;
                    double d = eyeAt(s).distanceTo(Vec3.atCenterOf(t));
                    if (d > REACH + 0.5 || d >= bestD || !stand(level, s)) continue;
                    // only where a face to click is really visible from there (else it walks there for nothing)
                    if (!breakable(level, t) && !Placer.placeableFrom(level, Minecraft.getInstance().player, eyeAt(s), t)) continue;
                    best = s;
                    bestD = d;
                }
            }
        }
        return best;
    }

    /**
     * A spot on the bottom from which the cell is in reach while diving: solid below, room for the body, not the cell's
     * own column, and open water (or air) straight up to the surface, so the way up can never be blocked.
     */
    private BlockPos diveSpotFor(Level level, BlockPos t) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        boolean bestFloor = false;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -3; dy <= 2; dy++) {
                    if (dx == 0 && dz == 0 && dy < 1) continue;   // its own column only from above (a narrow hole)
                    BlockPos s = t.offset(dx, dy, dz);
                    if (badDive.contains(s) || !level.hasChunkAt(s)) continue;
                    if (!wet(level, s) || !open(level, s.above())) continue;
                    if (level.getBlockState(s).getBlock() instanceof net.minecraft.world.level.block.BubbleColumnBlock) continue;
                    // a floor to stand on is best; without one it hovers there (no floor needed, any depth)
                    boolean floor = Util.isSolid(level, s.below());
                    if (!floor && !wet(level, s.below())) continue;
                    double d = eyeAt(s).distanceTo(Vec3.atCenterOf(t));
                    if (d > REACH + 0.3 || surfaceAbove(level, s) == null) continue;
                    if (!breakable(level, t) && !Placer.placeableFrom(level, Minecraft.getInstance().player, eyeAt(s), t)) continue;
                    if (bestFloor && !floor) continue;
                    if (floor == bestFloor && d >= bestD) continue;
                    best = s;
                    bestD = d;
                    bestFloor = floor;
                }
            }
        }
        return best;
    }

    /** The first air cell straight above (the surface), when the way up is all water; null if something is in the way. */
    static BlockPos surfaceAbove(Level level, BlockPos s) {
        BlockPos.MutableBlockPos m = s.mutable();
        for (int i = 0; i < 40; i++) {
            m.move(Direction.UP);
            BlockState st = level.getBlockState(m);
            if (!st.getCollisionShape(level, m).isEmpty()) return null;
            if (st.getBlock() instanceof net.minecraft.world.level.block.BubbleColumnBlock) return null;   // pushes up (or drags down): no diving there
            if (st.getFluidState().isEmpty()) {
                BlockPos surface = m.immutable();
                BlockPos head = surface.above();
                BlockState hs = level.getBlockState(head);
                if (!hs.getCollisionShape(level, head).isEmpty() || !hs.getFluidState().isEmpty()) return null;
                LocalPlayer p = Minecraft.getInstance().player;
                if (p != null && (int) Math.floor(p.getY()) > surface.getY()) {
                    int top = (int) Math.floor(p.getY()) + 1;
                    for (int y = surface.getY() + 1; y <= top; y++) {
                        BlockPos c = new BlockPos(surface.getX(), y, surface.getZ());
                        if (!level.getBlockState(c).getCollisionShape(level, c).isEmpty()) return null;
                    }
                }
                // Reject a shallow submerged air pocket under a water-covered roof (even if player is below/null)
                boolean sawSolid = false;
                for (int j = 2; j <= 16; j++) {
                    BlockPos c = surface.above(j);
                    BlockState cs = level.getBlockState(c);
                    if (!cs.getFluidState().isEmpty()) {
                        if (sawSolid || j <= 4) return null;
                    }
                    if (!cs.getCollisionShape(level, c).isEmpty()) {
                        if (j <= 4) sawSolid = true;
                        else break;
                    }
                }
                return surface;
            }
        }
        return null;
    }

    /** The nearest cell of the whole selection that can be filled from somewhere: a dry stand first, else a dive spot. */
    private Plan plan(LocalPlayer p, Level level) {
        BlockPos me = p.blockPosition();
        List<BlockPos> cand = new ArrayList<>();
        for (int[] b : boxes) {
            for (int x = b[0]; x <= b[3]; x++) {
                for (int z = b[2]; z <= b[5]; z++) {
                    for (int y = b[1]; y <= b[4]; y++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (!level.hasChunkAt(pos)) break;
                        if (!wet(level, pos)) continue;
                        if (fillable(level, pos)) cand.add(pos);
                    }
                }
            }
        }
        cand.sort(Comparator.comparingDouble(c -> c.distSqr(me) + c.getY() * 0.0));
        int tried = 0;
        long deadline = System.nanoTime() + 60_000_000L;   // at most ~60 ms per decision, however many cells
        Plan dive = null;
        for (BlockPos t : cand) {
            if (++tried > 80 && (System.nanoTime() > deadline || dive != null)) break;
            BlockPos s = standFor(level, t);
            if (s != null) return new Plan(t, s, false);
            if (dive == null && cfg.dive && !falling) {
                BlockPos d = diveSpotFor(level, t);
                if (d != null) dive = new Plan(t, d, true);
            }
        }
        return dive;
    }

    /** A dry spot at the water's edge near the dive spot, preferring the player's side of the lake. */
    private BlockPos shoreNear(Level level, BlockPos spot, BlockPos surface, BlockPos me) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -16; dx <= 16; dx++) {
            for (int dz = -16; dz <= 16; dz++) {
                for (int dy = -2; dy <= 3; dy++) {
                    BlockPos c = surface.offset(dx, dy - 1, dz);
                    if (badStand.contains(c) || !level.hasChunkAt(c)) continue;
                    double d = c.distSqr(surface) + 0.25 * c.distSqr(me);
                    if (d >= bestD || !stand(level, c)) continue;
                    if (!touchesWater(level, c)) continue;
                    best = c;
                    bestD = d;
                }
            }
        }
        return best;
    }

    private static boolean touchesWater(Level level, BlockPos c) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (wet(level, c.relative(d)) || wet(level, c.relative(d).below())) return true;
        }
        return false;
    }

    private int materialCount(LocalPlayer p) {
        if (preferred != null) {
            int n = 0;
            for (int i = 0; i < 36; i++) if (p.getInventory().getItem(i).is(preferred)) n += p.getInventory().getItem(i).getCount();
            return n;
        }
        return Util.countThrowaway(p);
    }

    // ------------------------------------------------------------------ doing it

    @Override
    boolean active() {
        if (working && Minecraft.getInstance().player == null) cancel();
        return working;
    }

    private void stepInternal() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (!working || p == null || mc.level == null || mc.gameMode == null) return;
        Level level = mc.level;

        if (materialCount(p) == 0) {
            ranOut = true;
            finish(level, "I ran out of blocks to fill with.");
            return;
        }
        // under water without a plan (after an interruption, or a dive that went wrong): come up first
        if (p.isUnderWater() && state != State.SINK && state != State.UNDER && state != State.PILLAR && state != State.RISE) {
            if (state == State.WALK) {
                EngineSelector.current().cancel();
                if (goal != null) {
                    markBadStand(goal);
                    goal = null;
                }
                if (diveSpot != null) {
                    markBadDive(diveSpot);
                    diveSpot = null;
                }
                giveBackBreak();
            }
            setState(State.RISE);
            stateTicks = 0;
        }
        if (p.isUnderWater() && p.getAirSupply() < AIR_LOW && state != State.RISE) {
            abortWork(mc);
            setState(State.RISE);
            stateTicks = 0;
        }

        if (state == State.UNDER && ++stateTicks > DIVE_TIMEOUT) {
            abortWork(mc);
            markBadDive(diveSpot);
            setState(State.RISE);
            stateTicks = 0;
            return;
        }

        // one placement (or a plant to break) in progress
        boolean busyWithCell = workOnCell(mc, p, level);
        if (busyWithCell) return;

        if (tickCount - lastHeartbeatTick >= 100) {
            lastHeartbeatTick = tickCount;
            EngineStatus engStatus = EngineSelector.current().status();
            LOGGER.info("[Terminator] heartbeat: state={} pos={} goal={} diveSpot={} engineStatus={} placed={} fruitless={}",
                    state, p.blockPosition().toShortString(),
                    (goal == null ? "none" : goal.toShortString()),
                    (diveSpot == null ? "none" : diveSpot.toShortString()),
                    engStatus, placed, fruitless);
        }

        switch (state) {
            case RISE -> { rise(p, level); return; }
            case SWIM -> { swim(mc, p, level); return; }
            case SINK -> { sink(mc, p, level); return; }
            case PILLAR -> { pillar(mc, p, level); return; }
            case UNDER -> {
                if (stateTicks > DIVE_TIMEOUT) { markBadDive(diveSpot); setState(State.RISE); stateTicks = 0; return; }
                holdPosition(p);
                BlockPos t = findInReach(mc, p, level);
                if (t != null) { begin(mc, p, level, t); return; }
                BlockPos feet = p.blockPosition();
                if (!falling && p.getAirSupply() >= AIR_LOW && surfaceAbove(level, feet) != null
                        && (fillable(level, feet.below()) || fillable(level, feet) || fillable(level, feet.above()))) {
                    setState(State.PILLAR);
                    stateTicks = 0;
                    hold(false, true, false);
                    return;
                }
                if (placed == placedAtDive && diveSpot != null) markBadDive(diveSpot);
                setState(State.RISE);
                stateTicks = 0;
                return;
            }
            default -> { }
        }
        releaseKeys();

        // from where we stand
        BlockPos target = findInReach(mc, p, level);
        if (target != null) { begin(mc, p, level, target); return; }

        // walking to a stand already
        if (state == State.WALK && goal != null) {
            EngineStatus engStatus = EngineSelector.current().status();
            boolean inLiquid = p.isInWater() || p.isUnderWater();
            boolean needExactDry = inLiquid && !wet(level, goal);
            boolean arrived = needExactDry
                    ? (p.blockPosition().equals(goal) || (!inLiquid && engStatus == EngineStatus.DONE))
                    : (triedExact
                        ? (p.blockPosition().equals(goal) || engStatus == EngineStatus.DONE)
                        : (p.blockPosition().distSqr(goal) <= 2 || engStatus == EngineStatus.DONE));
            boolean engFailed = engStatus == EngineStatus.FAILED;
            double dist = Math.sqrt(p.blockPosition().distSqr(goal));
            if (dist < bestGoalDist - 1) { bestGoalDist = dist; noProgress = 0; }
            else noProgress++;

            if (arrived && diveSpot != null) {
                EngineSelector.current().cancel();
                setState(State.SWIM);
                stateTicks = 0;
                stuckTicks = 0;
                lastDist = Double.MAX_VALUE;
                return;
            }
            if (!arrived && (engFailed || noProgress > 20 * 15) && !triedNear) {
                triedNear = true;
                noProgress = 0;
                LOGGER.info("[Terminator] WALK retry triedNear: goal={} engStatus={}", goal.toShortString(), engStatus);
                EngineSelector.current().goTo(GoalSpec.near(goal, 1));
                return;
            }
            if ((engFailed || arrived || noProgress > 20 * 15) && !triedExact) {
                triedExact = true;
                triedNear = true;
                noProgress = 0;
                LOGGER.info("[Terminator] WALK retry triedExact: goal={} engStatus={}", goal.toShortString(), engStatus);
                EngineSelector.current().goTo(GoalSpec.exact(goal));
                return;
            }
            if (!arrived && !engFailed && noProgress <= 20 * 15 && ++walkTicks <= WALK_TIMEOUT * 6) return;
            EngineSelector.current().cancel();
            markBadStand(goal);
            if (!arrived && diveSpot != null) {
                for (Direction d : Direction.Plane.HORIZONTAL) markBadStand(goal.relative(d));
                markBadDive(diveSpot);
                diveSpot = null;
            }
            if (walkTarget != null) failures.merge(walkTarget, arrived ? 1 : 3, Integer::sum);
            goal = null;
        }

        if (++fruitless > MAX_FRUITLESS) { finish(level, null); return; }
        Plan plan = plan(p, level);
        if (plan == null) {
            LOGGER.info("[Terminator] plan: none found");
            // the last wet cell may be the one we stand in: nothing goes where the body is, so step out of it first
            BlockPos feet = p.blockPosition();
            boolean inWetCell = (inSelection(feet) && wet(level, feet)) || (inSelection(feet.above()) && wet(level, feet.above()));
            if (inWetCell && stepAside < 3) {
                BlockPos aside = asideSpot(level, feet);
                if (aside != null) {
                    stepAside++;
                    startWalk(level, aside);
                    return;
                }
            }
            finish(level, null);
            return;
        }
        LOGGER.info("[Terminator] plan selected: dive={} target={} stand={}",
                plan.dive, plan.target.toShortString(), plan.stand.toShortString());
        walkTicks = 0;
        if (!plan.dive) {
            diveSpot = null;
            walkTarget = plan.target;
            startWalk(level, plan.stand);
            if (p.blockPosition().distSqr(goal) > 64 * 64) Util.msg("Walking to the next part (" + goal.toShortString() + ").");
            return;
        }
        diveSpot = plan.stand;
        diveTarget = plan.target;
        walkTarget = plan.target;
        BlockPos surface = surfaceAbove(level, diveSpot);
        if (p.isInWater() && !p.isUnderWater() && surface != null && p.blockPosition().distSqr(surface) <= 64 * 64) {
            setState(State.SWIM);
            stateTicks = 0;
            stuckTicks = 0;
            lastDist = Double.MAX_VALUE;
            return;
        }
        BlockPos shore = surface == null ? null : shoreNear(level, diveSpot, surface, p.blockPosition());
        if (shore == null) {
            markBadDive(diveSpot);
            diveSpot = null;
            return;
        }
        startWalk(level, shore);
        Util.msg("Too deep to reach from the shore: diving at " + diveSpot.toShortString() + ".");
    }

    /** Places / breaks the current cell; false when nothing is in progress. */
    private boolean workOnCell(Minecraft mc, LocalPlayer p, Level level) {
        if (breaking != null) {
            if (!breakable(level, breaking) || ++breakTicks > BREAK_TIMEOUT) {
                if (breakTicks > BREAK_TIMEOUT) failures.merge(breaking, 1, Integer::sum);
                mc.gameMode.stopDestroyBlock();
                breaking = null;
                return false;
            }
            holdPosition(p);
            if (Util.aimAt(p, Vec3.atCenterOf(breaking))) {
                mc.gameMode.continueDestroyBlock(breaking, Direction.UP);
                p.swing(InteractionHand.MAIN_HAND);
            }
            return true;
        }
        if (current == null) return false;
        if (!wet(level, current) && !falling) {
            done();
            return false;
        }
        Placer.Status st = placer.tick(mc, p);
        if (falling && materialCount(p) < countBefore) {
            placed += countBefore - materialCount(p);
            fruitless = 0;
            current = null;
            return false;
        }
        if (st == Placer.Status.DONE) {
            done();
            return false;
        }
        if (st == Placer.Status.FAILED || (++targetTicks > TARGET_TIMEOUT && !placer.canReach(mc, p)) || targetTicks > TARGET_TIMEOUT * 2) {
            failures.merge(current, 1, Integer::sum);
            current = null;
            return false;
        }
        holdPosition(p);
        return true;
    }

    private void done() {
        placed++;
        fruitless = 0;
        current = null;
    }

    private void begin(Minecraft mc, LocalPlayer p, Level level, BlockPos t) {
        if (state == State.WALK) {
            EngineSelector.current().cancel();
            setState(State.WORK);
        }
        goal = null;
        if (breakable(level, t)) {
            breaking = t;
            breakTicks = 0;
            return;
        }
        placer.start(t, Placer.Mode.BLOCK);
        current = t;
        targetTicks = 0;
        countBefore = materialCount(p);
    }

    private void abortWork(Minecraft mc) {
        current = null;
        if (breaking != null && mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        breaking = null;
    }

    /** Under water: stay put (sneak keeps it on the bottom), or swim straight up while pillaring. */
    private void holdPosition(LocalPlayer p) {
        if (state == State.PILLAR) {
            hold(false, true, false);
            return;
        }
        if (state != State.UNDER || diveSpot == null) { releaseKeys(); return; }
        // stay over the spot: sneak sinks, jump rises; do not touch yaw while Placer/breaking is aiming
        double dx = (diveSpot.getX() + 0.5) - p.getX();
        double dz = (diveSpot.getZ() + 0.5) - p.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        boolean forward = false;
        if (current == null && breaking == null && dist > 0.4) {
            face(p, diveSpot.getX() + 0.5, diveSpot.getZ() + 0.5);
            forward = aligned;
        }
        boolean floor = Util.isSolid(Minecraft.getInstance().level, diveSpot.below());
        double y = p.getY(), want = diveSpot.getY();
        boolean up = !floor && y < want - 0.15;
        boolean down = floor || y > want + 0.25;
        hold(forward, up, down && !up);
    }

    // ------------------------------------------------------------------ diving

    /** Turns towards a point (yaw only) and says how far away it is, horizontally. */
    private static double face(LocalPlayer p, double x, double z) {
        double dx = x - p.getX(), dz = z - p.getZ();
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float d = Mth.wrapDegrees(yaw - p.getYRot());
        p.setYRot(p.getYRot() + Mth.clamp(d, -45f, 45f));
        double dist = Math.sqrt(dx * dx + dz * dz);
        aligned = Math.abs(d) < 30 || dist >= 2.0;   // close by: turn first, then walk (else it circles the spot)
        return dist;
    }

    private static boolean aligned = true;

    /** At the surface, towards the water above the dive spot. */
    private void swim(Minecraft mc, LocalPlayer p, Level level) {
        if (diveSpot == null) { setState(State.WORK); releaseKeys(); return; }
        p.setSprinting(false);
        double dist = face(p, diveSpot.getX() + 0.5, diveSpot.getZ() + 0.5);
        if (dist < 0.2) {
            setState(State.SINK);
            stateTicks = 0;
            hold(false, false, false);
            return;
        }
        if (dist < lastDist - 0.05) { lastDist = dist; stuckTicks = 0; }
        else if (++stuckTicks > SWIM_STUCK) {   // something in the way
            markBadDive(diveSpot);
            diveSpot = null;
            setState(State.WORK);
            releaseKeys();
            return;
        }
        hold(dist > 0.2 && aligned, true, false);
    }

    /** Straight down to the spot on the bottom. */
    private double sinkBestY;
    private int sinkStill;

    private void sink(Minecraft mc, LocalPlayer p, Level level) {
        if (stateTicks == 0) { sinkBestY = p.getY(); sinkStill = 0; }
        if (++stateTicks > 20 * 12 || !wet(level, diveSpot) || !open(level, diveSpot.above())) {   // could not get down, or it changed
            markBadDive(diveSpot);
            setState(State.RISE);
            stateTicks = 0;
            return;
        }
        p.setSprinting(false);
        double dist = face(p, diveSpot.getX() + 0.5, diveSpot.getZ() + 0.5);
        boolean floor = Util.isSolid(level, diveSpot.below());
        boolean down = floor ? p.getY() < diveSpot.getY() + 0.2 && (p.onGround() || p.getDeltaMovement().y > -0.01)
                : p.getY() < diveSpot.getY() + 0.25;   // hovering: deep enough is enough
        if (!down && p.isUnderWater()) {   // something in reach on the way down: start right away
            BlockPos t = findInReach(mc, p, level);
            if (t != null) down = true;
        }
        if (down) {
            placedAtDive = placed;
            setState(State.UNDER);
            stateTicks = 0;
            hold(false, false, !floor || p.getY() > diveSpot.getY() + 0.2);
            return;
        }
        // not getting deeper (a ledge, something pushing up): another spot
        if (p.getY() < sinkBestY - 0.1) { sinkBestY = p.getY(); sinkStill = 0; }
        else if (++sinkStill > 60) {
            LOGGER.info("[Terminator] sink stalled: pos=({}, {}, {}) dist={} onGround={} inWater={} swimming={} feet={} diveSpot={}",
                    String.format("%.2f", p.getX()), String.format("%.2f", p.getY()), String.format("%.2f", p.getZ()),
                    String.format("%.2f", dist), p.onGround(), p.isInWater(), p.isSwimming(),
                    level.getBlockState(p.blockPosition()).getBlock().getName().getString(), diveSpot.toShortString());
            markBadDive(diveSpot);
            setState(State.RISE);
            stateTicks = 0;
            return;
        }
        // over the middle of the hole first; sneaking before centered would sink onto a submerged rim
        boolean centered = dist < 0.15;
        hold(!centered && aligned, false, centered && !p.onGround());
    }

    /** Swims straight up a column while placing blocks underneath, filling a 1x1 shaft or final column in one trip. */
    private void pillar(Minecraft mc, LocalPlayer p, Level level) {
        BlockPos feet = p.blockPosition();
        if (!p.isInWater()) {
            setState(State.WORK);
            diveSpot = null;
            releaseKeys();
            return;
        }
        if (p.getAirSupply() < AIR_LOW || ++stateTicks > DIVE_TIMEOUT || surfaceAbove(level, feet) == null) {
            setState(State.RISE);
            stateTicks = 0;
            return;
        }
        boolean anyFillable = fillable(level, feet.below()) || fillable(level, feet) || fillable(level, feet.above());
        if (!anyFillable) {
            setState(State.RISE);
            stateTicks = 0;
            return;
        }
        hold(false, true, false);
        BlockPos below = feet.below();
        if (p.getY() >= feet.getY() && fillable(level, below)) {
            begin(mc, p, level, below);
        }
    }

    /** Up to the surface for air (and to go on from there): into a column with open water above, digging if need be. */
    private void rise(LocalPlayer p, Level level) {
        Minecraft mc = Minecraft.getInstance();
        abortWork(mc);
        if (!p.isUnderWater() && (!p.isInWater() || ++stateTicks > 10)) {
            setState(State.WORK);
            diveSpot = null;
            releaseKeys();
            return;
        }
        if (++stateTicks > 20 * 30) {   // cannot get up at all: let go, the player must help
            releaseKeys();
            finish(level, "Could not come up from the water.");
            return;
        }
        BlockPos feet = p.blockPosition();
        BlockPos open = surfaceAbove(level, feet) != null ? feet : null;
        if (open == null) {   // something above: the nearest column next to us that is open to the surface
            double best = Double.MAX_VALUE;
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    for (int dy = 0; dy <= 1; dy++) {
                        BlockPos c = feet.offset(dx, dy, dz);
                        double d = c.distSqr(feet);
                        if (d >= best || !open(level, c) || !open(level, c.above()) || surfaceAbove(level, c) == null) continue;
                        best = d;
                        open = c;
                    }
                }
            }
        }
        if (open != null) {
            double dist = face(p, open.getX() + 0.5, open.getZ() + 0.5);
            hold(dist > 0.3 && aligned, true, false);
            return;
        }
        // nowhere open nearby: break the block above the head
        BlockPos roof = BlockPos.containing(p.getEyePosition()).above();
        BlockState rs = level.getBlockState(roof);
        if (!rs.getCollisionShape(level, roof).isEmpty() && rs.getDestroySpeed(level, roof) >= 0) {
            hold(false, true, false);
            if (Util.aimAt(p, Vec3.atCenterOf(roof))) {
                mc.gameMode.continueDestroyBlock(roof, Direction.DOWN);
                p.swing(InteractionHand.MAIN_HAND);
            }
            return;
        }
        hold(false, true, false);
    }

    // ------------------------------------------------------------------ the end

    private void finish(Level level, String reason) {
        LOGGER.info("[Terminator] finish: placed={} fruitless={} reason={}", placed, fruitless, reason);
        int left = 0, notLoaded = 0;
        outer:
        for (int[] b : boxes) {
            for (int x = b[0]; x <= b[3]; x++) {
                for (int y = b[1]; y <= b[4]; y++) {
                    for (int z = b[2]; z <= b[5]; z++) {
                        if (left >= 100_000) break outer;
                        BlockPos pos = new BlockPos(x, y, z);
                        if (!level.hasChunkAt(pos)) notLoaded++;
                        else if (wet(level, pos)) left++;
                    }
                }
            }
        }
        if (reason == null && left > 0) {
            reason = cfg.dive
                    ? "The rest has nothing to build against yet, is cut off, or was not loaded."
                    : "The rest cannot be reached without swimming. Dive mode is off: option(\"buildtools.dive\", true).";
        }
        LOGGER.info("[Terminator] finish: left={} notLoaded={}", left, notLoaded);
        if (left > 0) {   // say where, so it can be looked at
            BlockPos me = Minecraft.getInstance().player == null ? BlockPos.ZERO : Minecraft.getInstance().player.blockPosition();
            List<BlockPos> rest = new ArrayList<>();
            for (int[] b : boxes) {
                for (int x = b[0]; x <= b[3]; x++) for (int y = b[1]; y <= b[4]; y++) for (int z = b[2]; z <= b[5]; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (level.hasChunkAt(pos) && wet(level, pos)) rest.add(pos);
                }
            }
            rest.sort(Comparator.comparingDouble(c -> c.distSqr(me)));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(3, rest.size()); i++) sb.append(i > 0 ? ", " : "").append(rest.get(i).toShortString());
            Util.msg("Nearest water left: " + sb + ".");
            System.out.println("[BuildTools] water left (nearest 20): " + rest.subList(0, Math.min(20, rest.size())));
        }
        lastResult = new int[]{placed, left, notLoaded};
        lastReason = reason == null ? "" : reason;
        EngineSelector.current().cancel();
        giveBackBreak();
        working = false;
        current = null;
        goal = null;
        breaking = null;
        diveSpot = null;
        setState(State.WORK);
        lastHeartbeatTick = 0;
        Placer.preferred = null;
        releaseKeys();
        Util.msg("Water fill done: " + placed + " cell" + (placed == 1 ? "" : "s") + " filled"
                + (left > 0 ? ", " + left + " still water" : ", no water left")
                + (notLoaded > 0 ? " (" + notLoaded + " cells not loaded, not checked)" : "")
                + (reason != null ? ". " + reason : "."));
    }

    @Override
    public void onLostControl() {   // e.g. #stop
        if (working) cancel();
    }

    /** Counts wet cells inside {@code boxes} that TerminatorProcess targets (water, seagrass, kelp, etc.). */
    public static int countWater(Level level, List<int[]> boxes) {
        if (level == null || boxes == null || boxes.isEmpty()) return 0;
        int count = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int[] b : boxes) {
            if (b == null || b.length < 6) continue;
            int x0 = Math.min(b[0], b[3]), x1 = Math.max(b[0], b[3]);
            int y0 = Math.min(b[1], b[4]), y1 = Math.max(b[1], b[4]);
            int z0 = Math.min(b[2], b[5]), z1 = Math.max(b[2], b[5]);
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    for (int y = y0; y <= y1; y++) {
                        pos.set(x, y, z);
                        if (!level.hasChunkAt(pos)) continue;
                        if (wet(level, pos)) count++;
                    }
                }
            }
        }
        return count;
    }
}
