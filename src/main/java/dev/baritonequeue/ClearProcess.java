package dev.baritonequeue;

import dev.midtown.api.EngineSelector;
import dev.midtown.api.EngineStatus;
import dev.midtown.api.GoalSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * clear_area(): mines out the selection without Baritone's builder.
 *
 * It works from the top down in passes three blocks thick (feet, head and the block above the head). In each pass it
 * walks lanes along the long side, six blocks apart, and stops every three blocks. At each stop it breaks everything
 * in reach before moving on, one block per tick when the block breaks at once (Haste + Efficiency), otherwise as fast
 * as the game allows. Short steps on level ground it walks itself; longer or awkward moves go to Baritone (which may
 * dig while this runs). Drops near the lane are picked up on the way. Blocks next to water or lava are left alone, so
 * nothing floods; protected areas and Baritone's do-not-break list are respected. What is left at the end can be
 * finished with #sel ca. At the end it says where the time went.
 *
 * An "on" block (eating, a fight, a trip to the bed or the dump chest) only pauses it: afterwards it walks back to the
 * stop it was at and carries on, keeping the blocks it already gave up on. Every 5 minutes of work it writes where the
 * time went so far into the game log ("[Queue] clear_area progress").
 */
public final class ClearProcess {
    private static final int LANE = 6, STEP = 3;
    private int pass = 3;   // layers per pass (2 or 3: feet and head, plus the block above the head)
    private static final int TRAVEL_TIMEOUT = 20 * 45, WALK_STUCK = 30, BREAK_TIMEOUT = 20 * 8, PICK_TIMEOUT = 20 * 3;
    private static final int REPORT_EVERY = 20 * 60 * 5;

    private enum State { NEXT, TRAVEL, WALK, WORK, PICK }

    private static volatile boolean activeClear;

    private boolean working;
    private boolean paused;          // suspended by an "on" block: carryOn() goes on from here
    private boolean scaffoldCleanupPass;
    private List<int[]> pausedBoxes;
    private int resumes, travelFails;
    private double travelBest;       // closest Baritone has got to the stop it travels to
    private long workTicks;
    private State state = State.NEXT;
    private final List<int[]> boxes = new ArrayList<>();
    private int yTop, yBottom, feetY;
    private boolean lastPass;
    private final List<BlockPos> stops = new ArrayList<>();
    private int stop;
    private BlockPos target, breaking;
    private boolean exactTarget;     // target is a spot in the air reached by pillaring: go exactly there
    private ItemEntity item;
    private int stateTicks, breakTicks, stuck;
    private double lastDist;
    private Boolean breakBefore;
    private final Set<BlockPos> skipped = new HashSet<>();
    private final Set<Integer> ignoredItems = new HashSet<>();
    private long stepTick, tickCount;
    private boolean keysHeld;
    private boolean midtownTraveling;

    // where the time goes
    private int broken, tBreak, tWalk, tTravel, tPick, tOther, nearFluid;
    private long started;
    private String failure;
    private double result;

    public static boolean isClearWorking() {
        return activeClear;
    }

    boolean working() {
        return working;
    }

    String failure() {
        return failure;
    }

    double result() {
        return result;
    }

    /** Starts on the current selection; returns an error text or null. */
    String begin() {
        return begin(3);
    }

    /** layers: how many layers one pass takes off (2 = feet and head, 3 = also the block above the head). */
    String begin(int layers) {
        pass = Math.max(1, Math.min(3, layers));
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return "Not in a world.";
        if (working) return "clear_area is already running.";
        paused = false;
        boxes.clear();
        boxes.addAll(Areas.read());
        if (boxes.isEmpty()) return "Select the area first: area(\"name\") or select(corner1, corner2).";
        yTop = Integer.MIN_VALUE;
        yBottom = Integer.MAX_VALUE;
        for (int[] b : boxes) { yTop = Math.max(yTop, b[4]); yBottom = Math.min(yBottom, b[1]); }
        feetY = Math.max(yBottom, yTop - (pass - 1));
        lastPass = feetY == yBottom;
        scaffoldCleanupPass = false;
        skipped.clear();
        ignoredItems.clear();
        planPass(mc.level);
        broken = tBreak = tWalk = tTravel = tPick = tOther = nearFluid = resumes = travelFails = 0;
        workTicks = 0;
        started = System.currentTimeMillis();
        failure = null;
        state = State.NEXT;
        breaking = null;
        item = null;
        // the way between stops may need digging: Baritone may break blocks while this runs
        breakBefore = dev.buildtools.Api.setOwnBreak(true);   // near a safe area it still stays off there
        working = true;
        activeClear = true;
        Util.msg("Clearing the area from the top, " + pass + " layers per pass.");
        return null;
    }

    void cancel() {
        paused = false;
        if (!working) return;
        working = false;
        activeClear = false;
        end();
    }

    /** An "on" block takes over: stop moving and breaking, but keep the plan, the stop and what was given up on. */
    void suspendRun() {
        if (!working) { paused = false; return; }
        working = false;
        activeClear = false;
        end();
        paused = true;
        pausedBoxes = new ArrayList<>(boxes);
    }

    /** After the "on" block: on from the same stop (or from scratch if the selection changed meanwhile). */
    String carryOn() {
        Minecraft mc = Minecraft.getInstance();
        if (!paused || mc.player == null || mc.level == null) return begin();
        List<int[]> now = Areas.read();
        boolean same = now.size() == pausedBoxes.size();
        for (int i = 0; same && i < now.size(); i++) same = java.util.Arrays.equals(now.get(i), pausedBoxes.get(i));
        if (!same) { paused = false; return begin(); }
        paused = false;
        failure = null;
        state = State.NEXT;
        breaking = null;
        item = null;
        resumes++;
        breakBefore = dev.buildtools.Api.setOwnBreak(true);
        working = true;
        activeClear = true;
        Util.msg("Carrying on clearing at stop " + Math.min(stop + 1, stops.size()) + " of " + stops.size()
                + " (layer y " + feetY + ", " + broken + " blocks so far).");
        return null;
    }

    private void end() {
        releaseKeys();
        if (midtownTraveling) {
            EngineSelector.current().cancel();
            midtownTraveling = false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode != null && breaking != null) mc.gameMode.stopDestroyBlock();
        breaking = null;
        if (breakBefore != null) dev.buildtools.Api.setOwnBreak(breakBefore);
        breakBefore = null;
    }

    // ------------------------------------------------------------------ the plan

    private boolean inSelection(BlockPos p) {
        return inSelection(p, 0);
    }

    private boolean inSelection(BlockPos p, int pad) {
        for (int[] b : boxes) {
            if (p.getX() >= b[0] - pad && p.getX() <= b[3] + pad
                    && p.getY() >= b[1] - pad && p.getY() <= b[4] + pad
                    && p.getZ() >= b[2] - pad && p.getZ() <= b[5] + pad) return true;
        }
        return false;
    }

    /** The stops of one pass: lanes along the long side of each box, serpentine, plus ledges/bridges. */
    private void planPass(Level level) {
        stops.clear();
        stop = 0;
        for (int[] b : boxes) {
            if (feetY < b[1] || feetY > b[4] + pass) continue;
            boolean alongZ = (b[5] - b[2]) >= (b[3] - b[0]);
            int c1 = alongZ ? b[0] : b[2], c2 = alongZ ? b[3] : b[5];
            int a1 = alongZ ? b[2] : b[0], a2 = alongZ ? b[5] : b[3];
            List<Integer> lanes = new ArrayList<>();
            if (c2 - c1 <= 4) lanes.add((c1 + c2) / 2);
            else {
                for (int c = c1 + 2; c < c2 - 2; c += LANE) lanes.add(c);
                if (lanes.get(lanes.size() - 1) < c2 - 2) lanes.add(c2 - 2);
            }
            boolean forward = true;
            for (int c : lanes) {
                List<Integer> along = new ArrayList<>();
                for (int a = a1; a <= a2; a += STEP) along.add(a);
                if (along.get(along.size() - 1) != a2) along.add(a2);
                if (!forward) java.util.Collections.reverse(along);
                for (int a : along) stops.add(alongZ ? new BlockPos(c, feetY, a) : new BlockPos(a, feetY, c));
                forward = !forward;
            }
        }
        addUncoveredBridgeStops(level);
    }

    /** Appends standing stops for any wanted blocks in this pass band not reachable from a solid grid stop. */
    private void addUncoveredBridgeStops(Level level) {
        final double reach = 4.4;
        final double reachSq = reach * reach;
        List<BlockPos> validStops = new ArrayList<>();
        for (BlockPos s : stops) {
            if (dev.buildtools.Api.solid(level, s.below())) validStops.add(s);
        }
        int bandTop = Math.min(yTop, feetY + pass);
        for (int[] b : boxes) {
            int yMin = Math.max(feetY, b[1]);
            int yMax = Math.min(bandTop, b[4]);
            if (yMin > yMax) continue;
            for (int y = yMax; y >= yMin; y--) {
                for (int x = b[0]; x <= b[3]; x++) {
                    for (int z = b[2]; z <= b[5]; z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (!level.hasChunkAt(pos) || !wanted(level, pos) || nextToFluid(level, pos)) continue;
                        if (coveredByStop(validStops, pos, reachSq)) continue;
                        BlockPos stand = findStand(level, pos, reachSq);
                        if (stand != null) {
                            stops.add(stand);
                            validStops.add(stand);
                        }
                    }
                }
            }
        }
        addUncoveredScaffoldStops(level, validStops, reachSq);
    }

    /** Appends top-down stops for any recorded scaffold blocks within {@code selection.inflate(2)} at {@code y >= feetY}. */
    private void addUncoveredScaffoldStops(Level level, List<BlockPos> validStops, double reachSq) {
        List<BlockPos> spots = new ArrayList<>();
        for (BlockPos pos : dev.buildtools.Api.scaffoldSpots()) {
            if (pos.getY() >= feetY && inSelection(pos, 2)) {
                spots.add(pos);
            }
        }
        if (spots.isEmpty()) return;
        spots.sort((a, b) -> Integer.compare(b.getY(), a.getY()));
        for (BlockPos pos : spots) {
            if (!level.hasChunkAt(pos) || !wanted(level, pos) || nextToFluid(level, pos)) continue;
            if (coveredByStop(validStops, pos, reachSq)) continue;
            BlockPos stand = findStand(level, pos, reachSq);
            if (stand != null) {
                stops.add(stand);
                validStops.add(stand);
            }
        }
    }

    private static double eyeDistSq(BlockPos feet, BlockPos target) {
        double dx = target.getX() - feet.getX();
        double dy = (target.getY() + 0.5) - (feet.getY() + 1.62);
        double dz = target.getZ() - feet.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean coveredByStop(List<BlockPos> validStops, BlockPos target, double reachSq) {
        for (BlockPos s : validStops) {
            if (Math.abs(s.getX() - target.getX()) <= 5
                    && Math.abs(s.getZ() - target.getZ()) <= 5
                    && eyeDistSq(s, target) <= reachSq) {
                return true;
            }
        }
        return false;
    }

    /** Finds a safe standable spot within 4 blocks and eye reach of {@code target}, preferring floor below over bridge top. */
    private static BlockPos findStand(Level level, BlockPos target, double reachSq) {
        BlockPos best = null;
        int bestTier = Integer.MAX_VALUE;
        int bestDistSq = Integer.MAX_VALUE;
        for (int dy = -3; dy <= 2; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    int distSq = dx * dx + dy * dy + dz * dz;
                    if (distSq > 16) continue;
                    BlockPos stand = target.offset(dx, dy, dz);
                    if (!level.hasChunkAt(stand)) continue;
                    if (eyeDistSq(stand, target) > reachSq) continue;
                    if (!dev.buildtools.Api.solid(level, stand.below())) continue;
                    if (!passable(level, stand) || !passable(level, stand.above())) continue;
                    int tier = stand.getY() <= target.getY() ? 0 : (!stand.below().equals(target) ? 1 : 2);
                    if (tier < bestTier || (tier == bestTier && distSq < bestDistSq)) {
                        best = stand;
                        bestTier = tier;
                        bestDistSq = distSq;
                    }
                }
            }
        }
        return best;
    }

    /** Goes to the next pass down; false when this was the last one. */
    private boolean nextPass(Level level) {
        if (lastPass) {
            if (!scaffoldCleanupPass) {
                scaffoldCleanupPass = true;
                stops.clear();
                stop = 0;
                addUncoveredScaffoldStops(level, new ArrayList<>(), 4.4 * 4.4);
                if (!stops.isEmpty()) return true;
            }
            return false;
        }
        feetY = Math.max(yBottom, feetY - pass);
        lastPass = feetY == yBottom;
        planPass(level);
        return true;
    }

    // ------------------------------------------------------------------ what to break

    private static boolean protectedOrForbidden(BlockState s, BlockPos p) {
        return dev.buildtools.Api.protectedAt(p);
    }

    private static boolean nextToFluid(Level level, BlockPos p) {
        for (Direction d : Direction.values()) {
            if (d == Direction.DOWN) continue;
            if (!level.getBlockState(p.relative(d)).getFluidState().isEmpty()) return true;
        }
        return false;
    }

    /** A block of the selection this pass (and anything above it that is still there) should break. */
    private boolean wanted(Level level, BlockPos p) {
        if (p.getY() < feetY || skipped.contains(p)) return false;
        if (!inSelection(p) && !(inSelection(p, 2) && dev.buildtools.Api.isScaffold(level, p))) return false;
        BlockState s = level.getBlockState(p);
        if (s.isAir() || !s.getFluidState().isEmpty()) return false;
        float hard = s.getDestroySpeed(level, p);
        if (hard < 0) return false;
        return !protectedOrForbidden(s, p);
    }

    private double reach(LocalPlayer p) {
        return Math.min(4.4, p.blockInteractionRange() - 0.1);
    }

    /** Is there anything to break within reach of a stop (worked out without going there)? */
    private boolean workAt(Level level, BlockPos feet, double reach) {
        Vec3 eye = new Vec3(feet.getX() + 0.5, feet.getY() + 1.62, feet.getZ() + 0.5);
        int r = (int) Math.ceil(reach);
        int minDy = Math.max(-r, feetY - feet.getY());
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = minDy; dy <= r + 1; dy++) {
                    BlockPos b = feet.offset(dx, dy, dz);
                    if (eye.distanceTo(Vec3.atCenterOf(b)) > reach) continue;
                    if (wanted(level, b) && !nextToFluid(level, b)) return true;
                }
            }
        }
        return false;
    }

    /** The nearest block in reach that can be seen, or null. */
    private BlockHitResult nextBlock(LocalPlayer p, Level level) {
        Vec3 eye = p.getEyePosition();
        double reach = reach(p);
        int r = (int) Math.ceil(reach);
        BlockPos base = BlockPos.containing(eye);
        List<BlockPos> cand = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -r; dy <= r; dy++) {
                    BlockPos b = base.offset(dx, dy, dz);
                    if (eye.distanceTo(Vec3.atCenterOf(b)) > reach || !wanted(level, b)) continue;
                    if (nextToFluid(level, b)) {
                        if (skipped.add(b)) nearFluid++;
                        continue;
                    }
                    cand.add(b);
                }
            }
        }
        // highest first (nothing is left hanging); below feet break farthest-first so footing stays until last
        int playerY = p.getBlockY();
        cand.sort((a, b) -> a.getY() != b.getY()
                ? b.getY() - a.getY()
                : (a.getY() < playerY
                        ? Double.compare(eye.distanceToSqr(Vec3.atCenterOf(b)), eye.distanceToSqr(Vec3.atCenterOf(a)))
                        : Double.compare(eye.distanceToSqr(Vec3.atCenterOf(a)), eye.distanceToSqr(Vec3.atCenterOf(b)))));
        for (BlockPos b : cand) {
            BlockHitResult hit = level.clip(new ClipContext(eye, Vec3.atCenterOf(b), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(b)) return hit;
        }
        return null;
    }

    // ------------------------------------------------------------------ tools

    /** Puts the fastest tool for the block in the hand (sparing nearly-broken tools). */
    public static void equipFor(Minecraft mc, LocalPlayer p, BlockState s) {
        int saverThreshold = 5;
        var engine = EngineSelector.current();
        if ("true".equalsIgnoreCase(engine.getSetting("itemSaver"))) {
            String raw = engine.getSetting("itemSaverThreshold");
            if (raw != null) {
                try {
                    saverThreshold = Math.max(saverThreshold, Integer.parseInt(raw.trim()));
                } catch (NumberFormatException ignored) { }
            }
        }
        var inv = p.getInventory();
        int best = -1;
        float bestSpeed = 1.0f;
        for (int i = 0; i < 36; i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty()) continue;
            if (st.isDamageableItem() && st.getMaxDamage() - st.getDamageValue() <= saverThreshold) continue;
            float sp = st.getDestroySpeed(s);
            if (sp > bestSpeed + 0.01f || (best >= 9 && i < 9 && sp >= bestSpeed - 0.01f && sp > 1.0f)) { best = i; bestSpeed = sp; }
        }
        if (best < 0) {
            ItemStack cur = inv.getSelectedItem();
            if (cur.isDamageableItem() && cur.getMaxDamage() - cur.getDamageValue() <= saverThreshold) {
                for (int i = 0; i < 9; i++) {
                    ItemStack st = inv.getItem(i);
                    if (st.isEmpty() || !st.isDamageableItem()) {
                        inv.setSelectedSlot(i);
                        return;
                    }
                }
            }
            return;
        }
        if (best < 9) { inv.setSelectedSlot(best); return; }
        int target = inv.getSelectedSlot();
        mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, best, target, ContainerInput.SWAP, p);
    }

    private static java.lang.reflect.Field delayField;
    private static boolean delayLooked;

    /** The game's own pause before the next block can be started (5 ticks after each block in survival), or 0. */
    private static int destroyDelay(Minecraft mc) {
        try {
            if (!delayLooked) {
                delayLooked = true;
                delayField = mc.gameMode.getClass().getDeclaredField("destroyDelay");
                delayField.setAccessible(true);
            }
            return delayField == null ? 0 : delayField.getInt(mc.gameMode);
        } catch (ReflectiveOperationException | RuntimeException e) {
            delayField = null;
            return 0;
        }
    }

    // ------------------------------------------------------------------ steering

    private void hold(boolean forward) {
        Minecraft.getInstance().options.keyUp.setDown(forward);
        keysHeld = forward;
    }

    private void releaseKeys() {
        if (!keysHeld) return;
        Minecraft.getInstance().options.keyUp.setDown(false);
        keysHeld = false;
    }

    /** Every client tick: drives clear_area when active, or releases keys if paused. */
    void tick() {
        tickCount++;
        if (working && (Minecraft.getInstance().player == null || PauseWatch.paused)) {
            if (Minecraft.getInstance().player == null) cancel();
            releaseKeys();
            return;
        }
        if (working) {
            stepTick = tickCount;
            try {
                step();
            } catch (Throwable t) {
                t.printStackTrace();
                failure = "clear_area hit an error (" + t + "). Details are in the game log.";
                working = false;
                activeClear = false;
                end();
            }
        } else if (keysHeld && stepTick < tickCount - 1) {
            releaseKeys();
        }
    }

    private static boolean aim(LocalPlayer p, Vec3 at) {
        Vec3 eye = p.getEyePosition();
        double dx = at.x - eye.x, dy = at.y - eye.y, dz = at.z - eye.z;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        float dYaw = Mth.wrapDegrees(yaw - p.getYRot()), dPitch = pitch - p.getXRot();
        p.setYRot(p.getYRot() + Mth.clamp(dYaw, -60f, 60f));
        p.setXRot(Mth.clamp(p.getXRot() + Mth.clamp(dPitch, -45f, 45f), -90f, 90f));
        return Math.abs(dYaw) < 12f && Math.abs(dPitch) < 12f;
    }

    private static double faceTowards(LocalPlayer p, double x, double z) {
        double dx = x - p.getX(), dz = z - p.getZ();
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float err = Mth.wrapDegrees(yaw - p.getYRot());
        p.setYRot(p.getYRot() + Mth.clamp(err, -45f, 45f));
        double dist = Math.sqrt(dx * dx + dz * dz);
        aligned = Math.abs(err) < 30 || dist >= 2.0;   // close by: turn first, then walk (else it circles the spot)
        return dist;
    }

    private static boolean aligned = true;

    private static boolean passable(Level level, BlockPos c) {
        BlockState s = level.getBlockState(c);
        return s.getCollisionShape(level, c).isEmpty() && s.getFluidState().isEmpty();
    }

    /** A straight walk on level ground: every cell on the line has room and a floor. */
    private static boolean straight(Level level, BlockPos from, BlockPos to) {
        if (from.getY() != to.getY()) return false;
        int n = Math.max(Math.abs(to.getX() - from.getX()), Math.abs(to.getZ() - from.getZ()));
        if (n > 6) return false;
        for (int i = 1; i <= n; i++) {
            double f = i / (double) n;
            BlockPos c = BlockPos.containing(from.getX() + 0.5 + (to.getX() - from.getX()) * f, from.getY(), from.getZ() + 0.5 + (to.getZ() - from.getZ()) * f);
            if (!passable(level, c) || !passable(level, c.above()) || !dev.buildtools.Api.solid(level, c.below())) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ step

    private void step() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        Level level = mc.level;
        if (p == null || level == null || mc.gameMode == null) return;
        stateTicks++;
        if (++workTicks % REPORT_EVERY == 0) report("progress");

        // a block being broken that takes several ticks
        if (breaking != null) {
            tBreak++;
            if (!wanted(level, breaking)) { dev.buildtools.Api.forgetScaffold(breaking); breaking = null; broken++; }
            else if (++breakTicks > BREAK_TIMEOUT) { skipped.add(breaking); mc.gameMode.stopDestroyBlock(); breaking = null; }
            else {
                aim(p, Vec3.atCenterOf(breaking));
                mc.gameMode.continueDestroyBlock(breaking, Direction.UP);
                p.swing(InteractionHand.MAIN_HAND);
                return;
            }
        }

        switch (state) {
            case WORK -> {
                releaseKeys();
                BlockHitResult hit = nextBlock(p, level);
                if (hit == null) { state = State.PICK; stateTicks = 0; item = null; return; }
                tBreak++;
                BlockPos b = hit.getBlockPos();
                BlockState s = level.getBlockState(b);
                equipFor(mc, p, s);
                if (!aim(p, hit.getLocation())) {
                    // still turning: the game's pause between two blocks counts down meanwhile, as it does for a
                    // player who keeps the button held while moving the mouse (nothing is sent while it counts)
                    if (destroyDelay(mc) > 0) mc.gameMode.continueDestroyBlock(b, hit.getDirection());
                    return;
                }
                if (s.getDestroyProgress(p, level, b) >= 1.0f) {   // breaks at once: one per tick
                    mc.gameMode.startDestroyBlock(b, hit.getDirection());
                    p.swing(InteractionHand.MAIN_HAND);
                    if (level.getBlockState(b).isAir()) dev.buildtools.Api.forgetScaffold(b);
                    broken++;
                } else {
                    breaking = b;
                    breakTicks = 0;
                    mc.gameMode.continueDestroyBlock(b, hit.getDirection());
                    p.swing(InteractionHand.MAIN_HAND);
                }
                return;
            }
            case PICK -> {   // the drops close by, then on
                tPick++;
                if (item == null || !item.isAlive() || stateTicks > PICK_TIMEOUT) {
                    if (item != null && item.isAlive()) ignoredItems.add(item.getId());
                    item = nearestDrop(p, level);
                    stateTicks = 0;
                    if (item == null) { releaseKeys(); state = State.NEXT; stop++; return; }
                }
                double d = faceTowards(p, item.getX(), item.getZ());
                hold(d > 0.3 && aligned);
                return;
            }
            case WALK -> {
                tWalk++;
                double d = faceTowards(p, target.getX() + 0.5, target.getZ() + 0.5);
                if (d < 0.35) { releaseKeys(); state = State.WORK; stateTicks = 0; return; }
                if (d < lastDist - 0.03) { lastDist = d; stuck = 0; }
                else if (++stuck > WALK_STUCK) {
                    releaseKeys();
                    state = State.TRAVEL;
                    stateTicks = 0;
                    EngineSelector.current().goTo(travelGoal());
                    midtownTraveling = true;
                    return;
                }
                hold(aligned);
                return;
            }
            case TRAVEL -> {
                tTravel++;
                releaseKeys();
                if (!midtownTraveling) {
                    EngineSelector.current().goTo(travelGoal());
                    midtownTraveling = true;
                }
                EngineStatus engStatus = EngineSelector.current().status();
                if (engStatus == EngineStatus.IDLE) {
                    EngineSelector.current().goTo(travelGoal());
                    engStatus = EngineSelector.current().status();
                }
                // a pillar-beside stand is only worth it at its height: climb all the way
                boolean closeWithWork = !exactTarget && p.blockPosition().distSqr(target) <= 4 && workAt(level, p.blockPosition(), reach(p));
                // a pillar-beside stand counts once standing on it, not at the top of the last jump
                boolean arrived = p.blockPosition().equals(target) && (!exactTarget || p.onGround());
                if (arrived || engStatus == EngineStatus.DONE || closeWithWork) {
                    EngineSelector.current().cancel();
                    midtownTraveling = false;
                    state = State.WORK;
                    stateTicks = 0;
                    return;
                }
                double d = Math.sqrt(p.blockPosition().distSqr(target));
                if (d < travelBest - 1) { travelBest = d; stateTicks = 0; }
                if (engStatus == EngineStatus.FAILED || stateTicks > TRAVEL_TIMEOUT) {
                    EngineSelector.current().cancel();
                    midtownTraveling = false;
                    if (workAt(level, p.blockPosition(), reach(p))) {
                        state = State.WORK;
                        stateTicks = 0;
                        return;
                    }
                    travelFails++;
                    state = State.NEXT;
                    stop++;
                    return;
                }
                return;
            }
            default -> { }
        }

        // NEXT: the next stop that has something to do
        tOther++;
        exactTarget = false;
        releaseKeys();
        double reach = reach(p);
        int checked = 0;
        while (true) {
            if (stop >= stops.size()) {
                if (!nextPass(level)) { finish(level); return; }
                continue;
            }
            BlockPos s = stops.get(stop);
            if (++checked > 400) return;   // carry on next tick
            if (!workAt(level, s, reach)) { stop++; continue; }
            if (!dev.buildtools.Api.solid(level, s.below())) {
                BlockPos lower = lowerFloorStop(level, s, reach);
                if (lower == null) lower = groundStop(level, s, reach);
                if (lower == null) { stop++; continue; }
                target = lower;
                break;
            }
            if (precarious(level, s.below())) {
                // the stop stands on a lone pillar top or a floating block that is itself to be cleared:
                // climbing up there means pillaring next to it, mining its own footing and dropping off again
                BlockPos ground = groundStop(level, s, reach);
                if (ground != null) { target = ground; break; }
                // too high to reach from the ground: pillar up beside it rather than standing on it, so the last
                // block broken under the feet is our own column (a one block drop) instead of a long fall
                BlockPos beside = pillarStand(level, s, reach);
                if (beside != null) { target = beside; exactTarget = true; break; }
            }
            target = s;
            break;
        }
        stateTicks = 0;
        System.out.println("[Queue] clear_area stop " + (stop + 1) + "/" + stops.size() + " y=" + feetY + ": "
                + stops.get(stop).toShortString() + " -> stand " + target.toShortString() + (exactTarget ? " (pillar beside)" : ""));
        if (p.blockPosition().equals(target)) { state = State.WORK; return; }
        if (straight(level, p.blockPosition(), target)) {
            state = State.WALK;
            lastDist = Double.MAX_VALUE;
            stuck = 0;
            return;
        }
        state = State.TRAVEL;
        travelBest = Double.MAX_VALUE;
        EngineSelector.current().goTo(travelGoal());
        midtownTraveling = true;
    }

    /** A support block that is itself to be cleared (now or in a later pass) and has at most one solid block beside it (a 1x1 pillar top or a floating block). */
    private boolean precarious(Level level, BlockPos support) {
        // to be cleared in this pass or a later one (wanted() only looks at this pass)
        if (!inSelection(support) || skipped.contains(support)) return false;
        BlockState st = level.getBlockState(support);
        if (st.isAir() || !st.getFluidState().isEmpty() || st.getDestroySpeed(level, support) < 0) return false;
        // at most one solid side: a lone pillar, a floating block, or such a pillar with our own scaffold column
        // beside it (solid terrain to stand on has more)
        int sides = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (dev.buildtools.Api.solid(level, support.relative(d))) sides++;
        }
        return sides <= 1;
    }

    /**
     * A stand on steady ground near {@code s} (up to 7 below, 3 to the side) from where {@link #workAt}
     * still finds blocks to break; the nearest, highest one. Null if there is none.
     */
    private BlockPos groundStop(Level level, BlockPos s, double reach) {
        BlockPos best = null;
        int bestScore = Integer.MAX_VALUE;
        for (int dy = 0; dy <= 7; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    int score = (dx * dx + dz * dz) * 4 + dy;
                    if (score >= bestScore) continue;
                    BlockPos c = s.offset(dx, -dy, dz);
                    if (!level.hasChunkAt(c)) continue;
                    if (!passable(level, c) || !passable(level, c.above())) continue;
                    if (!dev.buildtools.Api.solid(level, c.below()) || precarious(level, c.below())) continue;
                    if (!workAt(level, c, reach)) continue;
                    best = c;
                    bestScore = score;
                }
            }
        }
        return best;
    }

    private GoalSpec travelGoal() {
        return exactTarget ? GoalSpec.exact(target) : GoalSpec.near(target, 2);
    }

    /**
     * A spot in the air next to {@code s} (same level or one lower) above a clear column down to steady ground
     * (at most 11 deep), from where {@link #workAt} finds blocks to break: Midtown pillars straight up to it.
     * The lowest such column wins. Null if there is none.
     */
    private BlockPos pillarStand(Level level, BlockPos s, double reach) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        BlockPos best = null;
        int bestScore = Integer.MAX_VALUE;
        for (int dy = -1; dy <= 0; dy++) {
            for (int[] d : dirs) {
                BlockPos c = s.offset(d[0], dy, d[1]);
                if (!level.hasChunkAt(c) || !passable(level, c) || !passable(level, c.above())) continue;
                int h = 0;
                BlockPos q = c.below();
                while (h < 12 && level.hasChunkAt(q) && passable(level, q) && level.getBlockState(q).getFluidState().isEmpty()) {
                    q = q.below();
                    h++;
                }
                if (h == 0 || h >= 12) continue;
                if (!dev.buildtools.Api.solid(level, q) || precarious(level, q)) continue;
                if (!workAt(level, c, reach)) continue;
                int score = h * 4 + Math.abs(d[0]) + Math.abs(d[1]);
                if (score < bestScore) { best = c; bestScore = score; }
            }
        }
        return best;
    }

    /** Looks 1..3 blocks below {@code s} for a solid floor where {@link #workAt} still finds blocks to break. */
    private BlockPos lowerFloorStop(Level level, BlockPos s, double reach) {
        for (int d = 1; d <= 3; d++) {
            BlockPos lowerStop = s.below(d);
            if (!dev.buildtools.Api.solid(level, lowerStop.below())) continue;
            if (precarious(level, lowerStop.below())) continue;
            if (!passable(level, lowerStop)) continue;
            if (!passable(level, lowerStop.above()) && !wanted(level, lowerStop.above())) continue;
            if (workAt(level, lowerStop, reach)) return lowerStop;
        }
        return null;
    }

    private ItemEntity nearestDrop(LocalPlayer p, Level level) {
        ItemEntity best = null;
        double bestD = 36;
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(6, 2, 6))) {
            if (!e.isAlive() || ignoredItems.contains(e.getId()) || e.tickCount < 8) continue;
            if (Math.abs(e.getY() - p.getY()) > 1.2) continue;
            if (!straight(level, p.blockPosition(), BlockPos.containing(e.getX(), p.getY(), e.getZ()))) continue;
            double d = e.distanceToSqr(p);
            if (d < bestD) { bestD = d; best = e; }
        }
        return best;
    }

    private void finish(Level level) {
        int left = 0, wet = 0, guarded = 0;
        outer:
        for (int[] b : boxes) {
            for (int x = b[0]; x <= b[3]; x++) {
                for (int y = b[1]; y <= b[4]; y++) {
                    for (int z = b[2]; z <= b[5]; z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (!level.hasChunkAt(pos)) continue;
                        BlockState s = level.getBlockState(pos);
                        if (s.isAir() || !s.getFluidState().isEmpty() || s.getDestroySpeed(level, pos) < 0) continue;
                        left++;
                        if (protectedOrForbidden(s, pos)) guarded++;
                        else if (nextToFluid(level, pos)) wet++;
                        else if (left - wet - guarded <= 10) System.out.println("[Queue] clear_area left: " + pos.toShortString());
                        if (left > 200_000) break outer;
                    }
                }
            }
        }
        int open = left - wet - guarded;
        result = open;   // what is still worth another go; the wet and protected ones never are
        working = false;
        activeClear = false;
        end();
        int total = Math.max(1, tBreak + tWalk + tTravel + tPick + tOther);
        long secs = (System.currentTimeMillis() - started) / 1000;
        Util.msg("clear_area done: " + broken + " blocks broken in " + Estimate.duration(secs * 20) + ", " + left + " left"
                + (left == 0 ? "." : ": " + wet + " next to water or lava (left alone so nothing floods)"
                + (guarded > 0 ? ", " + guarded + " protected" : "") + ", " + open + " it could not get at."));
        Util.msg("Time: breaking " + pct(tBreak, total) + ", walking " + pct(tWalk, total) + ", Baritone paths " + pct(tTravel, total)
                + ", picking up " + pct(tPick, total) + ", planning " + pct(tOther, total) + "."
                + (resumes > 0 ? " Paused " + resumes + " times for other things (eating, fights, bed, chests): "
                        + Estimate.duration(Math.max(0, secs * 20 - workTicks)) + " of it." : ""));
        report("done, left=" + left + " wet=" + wet + " protected=" + guarded + " open=" + open);
    }

    /** One line in the game log: what it has done so far and where the time went (ticks it was in control). */
    private void report(String what) {
        long secs = (System.currentTimeMillis() - started) / 1000;
        System.out.println("[Queue] clear_area " + what + ": broken=" + broken + " stop=" + Math.min(stop + 1, stops.size()) + "/" + stops.size()
                + " y=" + feetY + " ticks break=" + tBreak + " walk=" + tWalk + " travel=" + tTravel + " pick=" + tPick + " other=" + tOther
                + " working=" + workTicks / 20 + "s total=" + secs + "s resumes=" + resumes + " travelFails=" + travelFails);
    }

    private static String pct(int part, int total) {
        return Math.round(100.0 * part / total) + "%";
    }

    /** Counts remaining breakable blocks inside {@code boxes} using ClearProcess's block filter. */
    public static int countSolid(Level level, List<int[]> boxes) {
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
                        BlockState s = level.getBlockState(pos);
                        if (s.isAir() || !s.getFluidState().isEmpty()) continue;
                        if (s.is(net.minecraft.world.level.block.Blocks.BEDROCK)
                                || s.is(net.minecraft.world.level.block.Blocks.END_PORTAL_FRAME)) continue;
                        if (s.getDestroySpeed(level, pos) < 0) continue;
                        count++;
                    }
                }
            }
        }
        return count;
    }
}
