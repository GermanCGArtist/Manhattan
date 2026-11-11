package dev.survivalkit;

import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import static dev.survivalkit.Config.cfg;

/** At night: walk to the saved bed, sleep until morning, then hand control back to the task that was running. */
final class SleepProcess extends Feature {
    private enum State { IDLE, PATHING, WAITING, ENTERING, SLEEPING }

    private static final int MIN_PATH_TIMEOUT = 20 * 90;
    /** Dusk: hostile mobs start to spawn outdoors about now; beds can be used from tick 12542. */
    private static final long ARRIVE_BY = 12300;
    private static final long SLEEP_FROM = 12542;
    private static final int WAIT_NIGHT_TIMEOUT = 20 * 60 * 5;
    private static final int SLEEP_TIMEOUT = 20 * 60 * 15;
    private static final int MAX_TRIES = 5;

    private State state = State.IDLE;
    private BlockPos bed;
    private int timer, tries, cooldown, check, pathTimeout = MIN_PATH_TIMEOUT;
    private double startDist;
    private boolean wasThunder;

    SleepProcess() {
        super("SurvivalKit sleep", 15);
    }

    @Override
    boolean busy() {
        return state != State.IDLE;
    }

    static boolean isNight(Minecraft mc) {
        Level lvl = mc.level;
        return lvl != null && lvl.dimension() == Level.OVERWORLD && (lvl.isDarkOutside() || (cfg.weather && lvl.isThundering()));
    }

    /** Cheap trigger check, once per client tick. */
    @Override
    void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (state != State.IDLE || p == null || mc.level == null) return;
        if (cooldown > 0) { cooldown--; return; }
        if (!Util.scriptOn("sleep", cfg.sleep) || ++check % 20 != 0) return;
        boolean thunder = mc.level.dimension() == Level.OVERWORLD && mc.level.isThundering();
        if (thunder != wasThunder) {
            wasThunder = thunder;
            if (cfg.weather) Util.msg(thunder ? "A thunderstorm started: monsters can spawn in daylight, and beds work now."
                    : "The thunderstorm is over.");
        }
        if (!Util.mayAct() || p.isSleeping() || Util.guiOpen(p)) return;
        boolean night = isNight(mc);
        if (!night && !(cfg.bedPlan && departureDue(mc, p))) return;
        if (cfg.bed == null) {
            if (night) Util.warn("nobed", "It is night but no bed is saved. Look at your bed and run /survival bed set (or /survival sleep off).");
            return;
        }
        start(mc);
    }

    /** Walking time to the bed in ticks, from the straight-line distance, plus the configured slack. */
    private static double travelTicks(LocalPlayer p) {
        BlockPos bed = Util.pos(cfg.bed);
        double dist = Math.sqrt(bed.distSqr(p.blockPosition()));
        return dist * cfg.ticksPerBlock + cfg.bedLead * 20.0;
    }

    /**
     * True when it is time to leave for the bed to be there by dusk (before mobs spawn): the later in the afternoon
     * and the further away the bed is, the sooner. Nothing happens while the bed is so close that leaving now would
     * only mean standing around.
     */
    private static boolean departureDue(Minecraft mc, LocalPlayer p) {
        Level lvl = mc.level;
        if (lvl == null || lvl.dimension() != Level.OVERWORLD || cfg.bed == null) return false;
        long time = lvl.getOverworldClockTime() % 24000L;
        if (time < 6000 || time >= SLEEP_FROM) return false;   // morning/noon: far too early; at night the normal trigger applies
        return ARRIVE_BY - time <= travelTicks(p);
    }

    /** Night (or a thunderstorm), or time to leave to be in bed before dusk. */
    static boolean bedTime(Minecraft mc, LocalPlayer p) {
        return cfg.bed != null && (isNight(mc) || (cfg.bedPlan && departureDue(mc, p)));
    }

    /** /survival sleep now */
    void startNow() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (cfg.bed == null) { Util.msg("No bed saved. Look at a bed and run /survival bed set"); return; }
        if (state != State.IDLE) { Util.msg("Already on the way to bed."); return; }
        if (!isNight(mc) && !departureDue(mc, mc.player)) { Util.msg("It is not night yet (or you are not in the overworld)."); return; }
        start(mc);
    }

    private void start(Minecraft mc) {
        bed = Util.pos(cfg.bed);
        // far away the bed's chunk is not loaded and reads as air: that says nothing, so walk there and look then
        if (mc.level.hasChunkAt(bed) && !(mc.level.getBlockState(bed).getBlock() instanceof BedBlock)) {
            Util.msg("No bed at " + bed.toShortString() + " (it was removed?). Checking again in 2 minutes.");
            cooldown = 20 * 120;
            return;
        }
        timer = 0;
        tries = 0;
        state = State.PATHING;
        startDist = Math.sqrt(bed.distSqr(mc.player.blockPosition()));
        double travel = travelTicks(mc.player);
        pathTimeout = (int) Math.max(MIN_PATH_TIMEOUT, travel * 2 + 400);
        if (isNight(mc)) {
            Util.msg((mc.level.isThundering() && !mc.level.isDarkOutside() ? "Thunderstorm" : "Night") + ": going to bed at " + bed.toShortString());
        } else {
            long time = mc.level.getOverworldClockTime() % 24000L;
            Util.msg("Dusk is near (" + Math.max(0, (SLEEP_FROM - time)) / 20 + " s until beds work): the bed is "
                    + Math.round(Math.sqrt(bed.distSqr(mc.player.blockPosition()))) + " blocks away, about " + Math.round(travel / 20)
                    + " s with slack - leaving now to be there before mobs spawn.");
        }
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
        if (p == null || mc.gameMode == null) { state = State.IDLE; return defer(); }
        timer++;

        switch (state) {
            case PATHING -> {
                if (timer > pathTimeout) return fail("Could not reach the bed in time.");
                if (mc.level.hasChunkAt(bed) && !(mc.level.getBlockState(bed).getBlock() instanceof BedBlock)) {
                    return fail("There is no bed at " + bed.toShortString() + ".");
                }
                if (calcFailed) return fail("Baritone found no path to the bed.");
                GoalNear goal = new GoalNear(bed, 2);
                if (goal.isInGoal(p.blockPosition())) {
                    learnPace();
                    state = isNight(mc) ? State.ENTERING : State.WAITING;
                    timer = 0;
                    if (state == State.WAITING) Util.msg("At the bed, waiting for nightfall.");
                    return pause();
                }
                return new PathingCommand(goal, PathingCommandType.SET_GOAL_AND_PATH);
            }
            case WAITING -> {
                // early at the bed: hold still until it can be slept in (the mobs outside cannot reach us in a house)
                if (isNight(mc)) {
                    state = State.ENTERING;
                    timer = 0;
                    return pause();
                }
                if (timer > WAIT_NIGHT_TIMEOUT) return fail("Waited five minutes at the bed and it never got dark.");
                return pause();
            }
            case ENTERING -> {
                if (!isNight(mc)) {   // the storm passed while we walked
                    state = State.IDLE;
                    cooldown = 20 * 10;
                    Util.msg("It is not night any more - no need to sleep.");
                    return pause();
                }
                if (p.isSleeping()) {
                    state = State.SLEEPING;
                    timer = 0;
                    Util.msg("Sleeping...");
                    return pause();
                }
                if (timer % 15 == 1) {   // try every 0.75 s, the server answers a tick or two later
                    if (tries++ >= MAX_TRIES) return fail("Could not sleep (monsters nearby, or the server refused).");
                    Util.readyToOpen(p);   // sneaking with a block in hand would place it on the bed
                    BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(bed), Direction.UP, bed, false);
                    mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
                }
                return pause();
            }
            case SLEEPING -> {
                if (!p.isSleeping() || timer > SLEEP_TIMEOUT) {
                    state = State.IDLE;
                    cooldown = 20 * 10;
                    Util.msg("Awake - carrying on.");
                }
                return pause();
            }
            default -> {
                return defer();
            }
        }
    }

    /** After a trip of a useful length: the pace actually walked becomes the plan for the next departure. */
    private void learnPace() {
        if (startDist < 15 || timer < 40) return;
        double sample = timer / startDist;
        double old = cfg.ticksPerBlock;
        cfg.ticksPerBlock = Math.max(3.0, Math.min(14.0, old * 0.7 + sample * 0.3));
        Config.save();
        Util.msg(String.format("Bed trip: %d blocks in %d s (%.1f ticks per block). Next time I plan with %.1f.",
                Math.round(startDist), timer / 20, sample, cfg.ticksPerBlock));
    }

    private PathingCommand fail(String why) {
        Util.msg(why + " Trying again in 1 minute.");
        state = State.IDLE;
        cooldown = 20 * 60;
        return pause();
    }

    @Override
    public void onLostControl() {   // e.g. #stop
        state = State.IDLE;
    }
}
