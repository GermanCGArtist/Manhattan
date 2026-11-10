package dev.baritonequeue;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
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
final class ClearProcess implements IBaritoneProcess {
    private static final int LANE = 6, STEP = 3, PASS = 3;
    private static final int TRAVEL_TIMEOUT = 20 * 45, WALK_STUCK = 30, BREAK_TIMEOUT = 20 * 8, PICK_TIMEOUT = 20 * 3;
    private static final int REPORT_EVERY = 20 * 60 * 5;

    private enum State { NEXT, TRAVEL, WALK, WORK, PICK }

    private boolean working;
    private boolean paused;          // suspended by an "on" block: carryOn() goes on from here
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
    private ItemEntity item;
    private int stateTicks, breakTicks, stuck;
    private double lastDist;
    private Boolean breakBefore;
    private final Set<BlockPos> skipped = new HashSet<>();
    private final Set<Integer> ignoredItems = new HashSet<>();
    private long stepTick, tickCount;
    private boolean keysHeld;

    // where the time goes
    private int broken, tBreak, tWalk, tTravel, tPick, tOther, nearFluid;
    private long started;
    private String failure;
    private double result;

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
        feetY = Math.max(yBottom, yTop - (PASS - 1));
        lastPass = feetY == yBottom;
        planPass(mc.level);
        skipped.clear();
        ignoredItems.clear();
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
        Util.msg("Clearing the area from the top, " + PASS + " layers per pass.");
        return null;
    }

    void cancel() {
        paused = false;
        if (!working) return;
        working = false;
        end();
    }

    /** An "on" block takes over: stop moving and breaking, but keep the plan, the stop and what was given up on. */
    void suspendRun() {
        if (!working) { paused = false; return; }
        working = false;
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
        Util.msg("Carrying on clearing at stop " + Math.min(stop + 1, stops.size()) + " of " + stops.size()
                + " (layer y " + feetY + ", " + broken + " blocks so far).");
        return null;
    }

    private void end() {
        releaseKeys();
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode != null && breaking != null) mc.gameMode.stopDestroyBlock();
        breaking = null;
        if (breakBefore != null) dev.buildtools.Api.setOwnBreak(breakBefore);
        breakBefore = null;
    }

    // ------------------------------------------------------------------ the plan

    private boolean inSelection(BlockPos p) {
        for (int[] b : boxes) {
            if (p.getX() >= b[0] && p.getX() <= b[3] && p.getY() >= b[1] && p.getY() <= b[4] && p.getZ() >= b[2] && p.getZ() <= b[5]) return true;
        }
        return false;
    }

    /** The stops of one pass: lanes along the long side of each box, serpentine. */
    private void planPass(Level level) {
        stops.clear();
        stop = 0;
        for (int[] b : boxes) {
            if (feetY < b[1] || feetY > b[4] + PASS) continue;
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
    }

    /** Goes to the next pass down; false when this was the last one. */
    private boolean nextPass(Level level) {
        if (lastPass) return false;
        feetY = Math.max(yBottom, feetY - PASS);
        lastPass = feetY == yBottom;
        planPass(level);
        return true;
    }

    // ------------------------------------------------------------------ what to break

    private static boolean protectedOrForbidden(BlockState s, BlockPos p) {
        if (dev.buildtools.Api.protectedAt(p)) return true;
        return BaritoneAPI.getSettings().blocksToDisallowBreaking.value.contains(s.getBlock());
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
        if (p.getY() < feetY || !inSelection(p) || skipped.contains(p)) return false;
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
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = 0; dy <= r + 1; dy++) {
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
        // highest first (nothing is left hanging), then nearest
        cand.sort((a, b) -> a.getY() != b.getY() ? b.getY() - a.getY() : Double.compare(eye.distanceToSqr(Vec3.atCenterOf(a)), eye.distanceToSqr(Vec3.atCenterOf(b))));
        for (BlockPos b : cand) {
            BlockHitResult hit = level.clip(new ClipContext(eye, Vec3.atCenterOf(b), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(b)) return hit;
        }
        return null;
    }

    // ------------------------------------------------------------------ tools

    /** Puts the fastest tool for the block in the hand (not one Baritone's item saver would spare). */
    static void equipFor(Minecraft mc, LocalPlayer p, BlockState s) {
        var inv = p.getInventory();
        int best = -1;
        float bestSpeed = 1.0f;
        var set = BaritoneAPI.getSettings();
        for (int i = 0; i < 36; i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty()) continue;
            if (set.itemSaver.value && st.isDamageableItem() && st.getDamageValue() + set.itemSaverThreshold.value >= st.getMaxDamage()) continue;
            float sp = st.getDestroySpeed(s);
            if (sp > bestSpeed + 0.01f || (best >= 9 && i < 9 && sp >= bestSpeed - 0.01f && sp > 1.0f)) { best = i; bestSpeed = sp; }
        }
        if (best < 0) return;
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

    /** Every client tick: let go of the keys as soon as another process has taken over. */
    void tick() {
        tickCount++;
        if (keysHeld && stepTick < tickCount - 1) releaseKeys();
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

    // ------------------------------------------------------------------ Baritone process

    @Override
    public boolean isActive() {
        if (working && (Minecraft.getInstance().player == null || PauseWatch.paused)) {
            if (Minecraft.getInstance().player == null) cancel();
            return false;
        }
        return working;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        stepTick = tickCount;
        try {
            return step(calcFailed);
        } catch (Throwable t) {
            t.printStackTrace();
            failure = "clear_area hit an error (" + t + "). Details are in the game log.";
            working = false;
            end();
            return pause();
        }
    }

    private static PathingCommand pause() {
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private PathingCommand step(boolean calcFailed) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        Level level = mc.level;
        if (p == null || level == null || mc.gameMode == null) return pause();
        stateTicks++;
        if (++workTicks % REPORT_EVERY == 0) report("progress");

        // a block being broken that takes several ticks
        if (breaking != null) {
            tBreak++;
            if (!wanted(level, breaking)) { breaking = null; broken++; }
            else if (++breakTicks > BREAK_TIMEOUT) { skipped.add(breaking); mc.gameMode.stopDestroyBlock(); breaking = null; }
            else {
                aim(p, Vec3.atCenterOf(breaking));
                mc.gameMode.continueDestroyBlock(breaking, Direction.UP);
                p.swing(InteractionHand.MAIN_HAND);
                return pause();
            }
        }

        switch (state) {
            case WORK -> {
                releaseKeys();
                BlockHitResult hit = nextBlock(p, level);
                if (hit == null) { state = State.PICK; stateTicks = 0; item = null; return pause(); }
                tBreak++;
                BlockPos b = hit.getBlockPos();
                BlockState s = level.getBlockState(b);
                equipFor(mc, p, s);
                if (!aim(p, hit.getLocation())) {
                    // still turning: the game's pause between two blocks counts down meanwhile, as it does for a
                    // player who keeps the button held while moving the mouse (nothing is sent while it counts)
                    if (destroyDelay(mc) > 0) mc.gameMode.continueDestroyBlock(b, hit.getDirection());
                    return pause();
                }
                if (s.getDestroyProgress(p, level, b) >= 1.0f) {   // breaks at once: one per tick
                    mc.gameMode.startDestroyBlock(b, hit.getDirection());
                    p.swing(InteractionHand.MAIN_HAND);
                    broken++;
                } else {
                    breaking = b;
                    breakTicks = 0;
                    mc.gameMode.continueDestroyBlock(b, hit.getDirection());
                    p.swing(InteractionHand.MAIN_HAND);
                }
                return pause();
            }
            case PICK -> {   // the drops close by, then on
                tPick++;
                if (item == null || !item.isAlive() || stateTicks > PICK_TIMEOUT) {
                    if (item != null && item.isAlive()) ignoredItems.add(item.getId());
                    item = nearestDrop(p, level);
                    stateTicks = 0;
                    if (item == null) { releaseKeys(); state = State.NEXT; stop++; return pause(); }
                }
                double d = faceTowards(p, item.getX(), item.getZ());
                hold(d > 0.3 && aligned);
                return pause();
            }
            case WALK -> {
                tWalk++;
                double d = faceTowards(p, target.getX() + 0.5, target.getZ() + 0.5);
                if (d < 0.35) { releaseKeys(); state = State.WORK; stateTicks = 0; return pause(); }
                if (d < lastDist - 0.03) { lastDist = d; stuck = 0; }
                else if (++stuck > WALK_STUCK) { releaseKeys(); state = State.TRAVEL; stateTicks = 0; return pause(); }
                hold(aligned);
                return pause();
            }
            case TRAVEL -> {
                tTravel++;
                releaseKeys();
                if (p.blockPosition().equals(target)) { state = State.WORK; stateTicks = 0; return pause(); }
                // the timeout counts only while it gets no closer: the way back from the bed may take minutes
                double d = Math.sqrt(p.blockPosition().distSqr(target));
                if (d < travelBest - 1) { travelBest = d; stateTicks = 0; }
                if (calcFailed || stateTicks > TRAVEL_TIMEOUT) { travelFails++; state = State.NEXT; stop++; return pause(); }
                return new PathingCommand(new GoalBlock(target), PathingCommandType.SET_GOAL_AND_PATH);
            }
            default -> { }
        }

        // NEXT: the next stop that has something to do
        tOther++;
        releaseKeys();
        double reach = reach(p);
        int checked = 0;
        while (true) {
            if (stop >= stops.size()) {
                if (!nextPass(level)) return finish(level);
                continue;
            }
            BlockPos s = stops.get(stop);
            if (++checked > 400) return pause();   // carry on next tick
            if (!workAt(level, s, reach)) { stop++; continue; }
            if (!dev.buildtools.Api.solid(level, s.below())) { stop++; continue; }   // nothing to stand on there
            target = s;
            break;
        }
        stateTicks = 0;
        if (p.blockPosition().equals(target)) { state = State.WORK; return pause(); }
        if (straight(level, p.blockPosition(), target)) {
            state = State.WALK;
            lastDist = Double.MAX_VALUE;
            stuck = 0;
            return pause();
        }
        state = State.TRAVEL;
        travelBest = Double.MAX_VALUE;
        return new PathingCommand(new GoalBlock(target), PathingCommandType.SET_GOAL_AND_PATH);
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

    private PathingCommand finish(Level level) {
        int left = 0;
        outer:
        for (int[] b : boxes) {
            for (int x = b[0]; x <= b[3]; x++) {
                for (int y = b[1]; y <= b[4]; y++) {
                    for (int z = b[2]; z <= b[5]; z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (!level.hasChunkAt(pos)) continue;
                        BlockState s = level.getBlockState(pos);
                        if (!s.isAir() && s.getFluidState().isEmpty() && s.getDestroySpeed(level, pos) >= 0) left++;
                        if (left > 200_000) break outer;
                    }
                }
            }
        }
        result = left;
        working = false;
        end();
        int total = Math.max(1, tBreak + tWalk + tTravel + tPick + tOther);
        long secs = (System.currentTimeMillis() - started) / 1000;
        Util.msg("clear_area done: " + broken + " blocks broken in " + Estimate.duration(secs * 20) + ", " + left + " left"
                + (nearFluid > 0 ? " (" + nearFluid + " next to water or lava were left alone)" : "") + ".");
        Util.msg("Time: breaking " + pct(tBreak, total) + ", walking " + pct(tWalk, total) + ", Baritone paths " + pct(tTravel, total)
                + ", picking up " + pct(tPick, total) + ", planning " + pct(tOther, total) + "."
                + (resumes > 0 ? " Paused " + resumes + " times for other things (eating, fights, bed, chests): "
                        + Estimate.duration(Math.max(0, secs * 20 - workTicks)) + " of it." : ""));
        report("done, left=" + left);
        return pause();
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

    @Override
    public boolean isTemporary() {
        return true;
    }

    @Override
    public void onLostControl() {
        releaseKeys();
    }

    @Override
    public double priority() {
        return 11;
    }

    @Override
    public String displayName0() {
        return "Queue clear_area";
    }
}
