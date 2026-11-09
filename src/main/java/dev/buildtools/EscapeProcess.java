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
 */
final class EscapeProcess extends Feature {
    private enum State { IDLE, ESCAPING }

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
    PathingCommand step(boolean calcFailed, boolean isSafeToCancel) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
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

    private void finish() {
        var input = Util.baritone().getInputOverrideHandler();
        input.setInputForceState(Input.MOVE_FORWARD, false);
        input.setInputForceState(Input.JUMP, false);
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
