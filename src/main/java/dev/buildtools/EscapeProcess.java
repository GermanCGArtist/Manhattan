package dev.buildtools;

import baritone.api.pathing.goals.GoalBlock;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.input.Input;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import static dev.buildtools.Config.cfg;

/**
 * Baritone sometimes walks in circles in or next to water (currents, a flooded tunnel, water it cannot plan through).
 * This notices "in water, not getting anywhere" while a task runs, swims to the nearest dry land and hands control
 * back. If it keeps ending up in the same water, the task is stopped with an explanation.
 *
 * The air guard is always on, script or not: under water with less than half of the air left, it takes over from
 * everything (fights included), swims up to the nearest open water surface - breaking a block over the head if that
 * is the only way - waits for the air to fill up and then swims to dry land if there is any close by.
 */
final class EscapeProcess extends Feature {
    private enum State { IDLE, ESCAPING, AIR }

    private static final int SAMPLES = 10;              // one per second
    private static final double MAX_SPREAD = 4.0;       // blocks the player may have moved in that time
    private static final int SEARCH = 12;
    private static final int TIMEOUT = 20 * 20;
    private static final int COOLDOWN = 20 * 20;
    private static final int GIVE_UP_AFTER = 3;         // escapes within RECENT ticks
    private static final long RECENT = 20L * 60 * 5;

    private State state = State.IDLE;
    private BlockPos shore;
    private boolean manual;
    private int timer, cooldown, sampleTick, filled;
    private long now;
    private final double[] xs = new double[SAMPLES], ys = new double[SAMPLES], zs = new double[SAMPLES];
    private final long[] escapes = new long[GIVE_UP_AFTER];
    private int escapeCount;
    private boolean wetRecently;
    private int airTicks;
    private static final int AIR_TIMEOUT = 20 * 40;

    EscapeProcess() {
        super("BuildTools water escape", 10);
    }

    @Override
    boolean busy() {
        return state != State.IDLE;
    }

    @Override
    void tick() {
        now++;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) { filled = 0; return; }
        if (cooldown > 0) cooldown--;
        if (p.isInWater()) wetRecently = true;
        if (state != State.AIR && p.isUnderWater() && p.getAirSupply() < p.getMaxAirSupply() / 2
                && !BuildToolsMod.terminator.busy() && !p.isSleeping()) {   // the water fill dives on its own terms
            if (state == State.ESCAPING) finish();
            state = State.AIR;
            airTicks = 0;
            Util.msg("Running out of air - swimming up.");
            return;
        }
        if (++sampleTick % 20 != 0) return;

        xs[filled % SAMPLES] = p.getX();
        ys[filled % SAMPLES] = p.getY();
        zs[filled % SAMPLES] = p.getZ();
        filled++;

        if (state != State.IDLE || !Util.scriptOn("escape", cfg.escape) || cooldown > 0 || filled < SAMPLES) return;
        boolean inWater = p.isInWater();
        if (!inWater && !wetRecently) return;
        if (!inWater) { wetRecently = false; return; }   // only act while actually in the water
        if (!Util.baritoneBusy() || p.isSleeping() || p.containerMenu != p.inventoryMenu) return;
        if (BuildToolsMod.drain.busy() || BuildToolsMod.fill.busy() || BuildToolsMod.terminator.busy()) return;
        if (mc.gameMode != null && mc.gameMode.isDestroying()) return;   // mining something underwater is progress
        // Filling or clearing water is the job: standing in it and placing blocks under your feet looks like "stuck".
        String step = System.getProperty("baritonequeue.step");
        if (step != null && step.toLowerCase().contains("water") && Util.baritone().getBuilderProcess().isActive()) return;

        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int i = 0; i < SAMPLES; i++) {
            minX = Math.min(minX, xs[i]); maxX = Math.max(maxX, xs[i]);
            minY = Math.min(minY, ys[i]); maxY = Math.max(maxY, ys[i]);
            minZ = Math.min(minZ, zs[i]); maxZ = Math.max(maxZ, zs[i]);
        }
        if (maxX - minX > MAX_SPREAD || maxZ - minZ > MAX_SPREAD || maxY - minY > 3) return;

        BlockPos target = findShore(mc.level, p.blockPosition());
        if (target == null) {
            Util.warn("noshore", "Stuck in water, but there is no dry land within " + SEARCH + " blocks to swim to.");
            cooldown = COOLDOWN;
            return;
        }
        // Third time in 5 minutes: more of the same would not help.
        long oldest = escapes[escapeCount % GIVE_UP_AFTER];
        if (escapeCount >= GIVE_UP_AFTER && now - oldest < RECENT) {
            Util.haltQueue("The task was stopped, so the queue stopped too.");
            Util.baritone().getPathingBehavior().cancelEverything();
            Util.warn("waterloop", "Baritone keeps ending up stuck in water here (" + GIVE_UP_AFTER
                    + " times in 5 minutes), so I stopped the task. Dam or drain the water first (/buildtools drain now) or choose a different spot.");
            escapeCount = 0;
            cooldown = COOLDOWN;
            return;
        }
        escapes[escapeCount % GIVE_UP_AFTER] = now;
        escapeCount++;

        shore = target;
        manual = false;
        timer = 0;
        state = State.ESCAPING;
        Util.msg("Stuck in water - swimming to dry land at " + shore.toShortString() + ".");
    }

    /** Nearest standable spot with no water at feet or head, found by a plain search around the player. */
    private static BlockPos findShore(Level level, BlockPos from) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -SEARCH; dx <= SEARCH; dx++) {
            for (int dz = -SEARCH; dz <= SEARCH; dz++) {
                for (int dy = -3; dy <= 4; dy++) {
                    BlockPos q = from.offset(dx, dy, dz);
                    if (!Util.isSolid(level, q.below())) continue;
                    if (!Util.isOpen(level, q) || !Util.isOpen(level, q.above())) continue;
                    if (Util.isWater(level.getBlockState(q)) || Util.isWater(level.getBlockState(q.above()))) continue;
                    // away from the water's edge: no water directly beside it, so it is not about to be flooded again
                    boolean wet = false;
                    for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                        if (Util.isWater(level.getBlockState(q.relative(d)))) { wet = true; break; }
                    }
                    double dist = q.distSqr(from) + (wet ? 25 : 0);
                    if (dist < bestD) { bestD = dist; best = q; }
                }
            }
        }
        return best;
    }

    @Override
    boolean active() {
        if (state != State.IDLE && Minecraft.getInstance().player == null) state = State.IDLE;
        return state != State.IDLE;
    }

    @Override
    public double priority() {
        return state == State.AIR ? 100 : super.priority();   // breathing beats fighting, eating and everything else
    }

    @Override
    PathingCommand step(boolean calcFailed, boolean isSafeToCancel) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (state == State.AIR && p != null) return air(mc, p);
        if (state != State.ESCAPING || p == null) return defer();

        boolean there = shore.distSqr(p.blockPosition()) <= 2 && !p.isInWater();
        if (there || p.onGround() && !p.isInWater() && shore.distSqr(p.blockPosition()) <= 9) {
            finish();
            Util.msg("Out of the water - carrying on.");
            return pause();
        }
        if (++timer > TIMEOUT) {
            finish();
            Util.warn("escapefail", "Could not get out of the water in time. Trying again in 20 s.");
            return pause();
        }
        if (calcFailed) manual = true;   // Baritone found no way: just swim there

        if (manual) {
            Util.aimAt(p, Vec3.atCenterOf(shore));
            var input = Util.baritone().getInputOverrideHandler();
            input.setInputForceState(Input.MOVE_FORWARD, true);
            input.setInputForceState(Input.JUMP, true);
            return pause();
        }
        return new PathingCommand(new GoalBlock(shore), PathingCommandType.SET_GOAL_AND_PATH);
    }

    /** Up to the surface, breathe, then on to dry land. */
    private PathingCommand air(Minecraft mc, LocalPlayer p) {
        var input = Util.baritone().getInputOverrideHandler();
        Level level = mc.level;
        if (!p.isUnderWater()) {
            input.setInputForceState(Input.JUMP, true);   // keep the head above the water
            input.setInputForceState(Input.MOVE_FORWARD, false);
            if (p.getAirSupply() < p.getMaxAirSupply() && ++airTicks <= AIR_TIMEOUT) return pause();
            input.setInputForceState(Input.JUMP, false);
            BlockPos land = p.isInWater() ? findShore(level, p.blockPosition()) : null;
            if (land == null) { finish(); cooldown = 0; Util.msg("Breathing again - carrying on."); return pause(); }
            shore = land;   // the same water would pull us back down: get out of it first
            manual = false;
            timer = 0;
            state = State.ESCAPING;
            Util.msg("Breathing again - swimming to dry land at " + shore.toShortString() + ".");
            return pause();
        }
        if (++airTicks > AIR_TIMEOUT) {
            finish();
            cooldown = 0;
            Util.warn("noair", "Could not get up to the air. Help needed!");
            return pause();
        }
        if (mc.gameMode != null && mc.gameMode.isDestroying() && breakingUp == null) mc.gameMode.stopDestroyBlock();
        BlockPos feet = p.blockPosition();
        BlockPos column = TerminatorProcess.surfaceAbove(level, feet.above()) != null || TerminatorProcess.surfaceAbove(level, feet) != null ? feet : null;
        if (column == null) {   // something over the head: the nearest column next to us that is open to the surface
            double best = Double.MAX_VALUE;
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    for (int dy = 0; dy <= 1; dy++) {
                        BlockPos c = feet.offset(dx, dy, dz);
                        double d = c.distSqr(feet);
                        if (d >= best || !passable(level, c) || !passable(level, c.above()) || TerminatorProcess.surfaceAbove(level, c) == null) continue;
                        if (level.getFluidState(c).isEmpty() || !swimmable(level, feet, c)) continue;   // in this water, not behind a wall
                        best = d;
                        column = c;
                    }
                }
            }
        }
        if (column != null && !column.equals(feet)) {
            breakingUp = null;
            Util.aimAt(p, new Vec3(column.getX() + 0.5, p.getEyeY(), column.getZ() + 0.5));
            input.setInputForceState(Input.MOVE_FORWARD, true);
            input.setInputForceState(Input.JUMP, true);
            return pause();
        }
        input.setInputForceState(Input.MOVE_FORWARD, false);
        input.setInputForceState(Input.JUMP, true);
        if (column == null) {   // closed in: break the block over the head
            BlockPos head = BlockPos.containing(p.getEyePosition());
            BlockPos up = head.above();
            if (!passable(level, up) && !Api.protectedAt(up) && level.getBlockState(up).getDestroySpeed(level, up) >= 0 && mc.gameMode != null) {
                Util.aimAt(p, Vec3.atCenterOf(up));
                bestTool(p, level.getBlockState(up));
                if (!up.equals(breakingUp)) { mc.gameMode.startDestroyBlock(up, net.minecraft.core.Direction.DOWN); breakingUp = up; }
                else mc.gameMode.continueDestroyBlock(up, net.minecraft.core.Direction.DOWN);
                p.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                if (passable(level, up)) breakingUp = null;
            }
        } else {
            breakingUp = null;
            p.setXRot(-90);   // straight up
        }
        return pause();
    }

    private BlockPos breakingUp;

    /** A straight swim at head height from a to b with nothing solid in between. */
    private static boolean swimmable(Level level, BlockPos a, BlockPos b) {
        int n = Math.max(Math.abs(b.getX() - a.getX()), Math.abs(b.getZ() - a.getZ()));
        for (int i = 1; i <= n; i++) {
            BlockPos c = new BlockPos(a.getX() + Math.round((b.getX() - a.getX()) * i / (float) n), b.getY(),
                    a.getZ() + Math.round((b.getZ() - a.getZ()) * i / (float) n));
            if (!passable(level, c) || !passable(level, c.above())) return false;
        }
        return true;
    }

    /** Selects the hotbar item that breaks this block fastest. */
    private static void bestTool(LocalPlayer p, net.minecraft.world.level.block.state.BlockState s) {
        var inv = p.getInventory();
        int best = inv.getSelectedSlot();
        float speed = inv.getItem(best).getDestroySpeed(s);
        for (int i = 0; i < 9; i++) {
            float sp = inv.getItem(i).getDestroySpeed(s);
            if (sp > speed) { speed = sp; best = i; }
        }
        if (best != inv.getSelectedSlot()) inv.setSelectedSlot(best);
    }

    private static boolean passable(Level level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    private void finish() {
        var input = Util.baritone().getInputOverrideHandler();
        input.setInputForceState(Input.MOVE_FORWARD, false);
        input.setInputForceState(Input.JUMP, false);
        if (breakingUp != null && Minecraft.getInstance().gameMode != null) Minecraft.getInstance().gameMode.stopDestroyBlock();
        breakingUp = null;
        state = State.IDLE;
        cooldown = COOLDOWN;
        filled = 0;          // measure the next stretch from scratch
        wetRecently = false;
    }

    @Override
    public void onLostControl() {
        if (state != State.IDLE) finish();
    }
}
