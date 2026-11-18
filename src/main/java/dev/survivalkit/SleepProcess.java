package dev.survivalkit;

import dev.midtown.api.EngineSelector;
import dev.midtown.api.GoalSpec;
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
    private enum State { IDLE, PATHING, TOPUP, WAITING, ENTERING, SLEEPING }

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
    private boolean toppedUp;   // supplies were refilled on this trip to the bed

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

    @Override
    void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;

        if (state != State.IDLE) {
            stepInternal(mc, p);
            return;
        }

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

    private static boolean departureDue(Minecraft mc, LocalPlayer p) {
        Level lvl = mc.level;
        if (lvl == null || lvl.dimension() != Level.OVERWORLD || cfg.bed == null) return false;
        long time = lvl.getOverworldClockTime() % 24000L;
        if (time < 6000 || time >= SLEEP_FROM) return false;
        return ARRIVE_BY - time <= travelTicks(p);
    }

    static boolean bedTime(Minecraft mc, LocalPlayer p) {
        return cfg.bed != null && (isNight(mc) || (cfg.bedPlan && departureDue(mc, p)));
    }

    void startNow() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (cfg.bed == null) { Util.msg("No bed saved. Look at a bed and run /survival bed set"); return; }
        if (state != State.IDLE) { Util.msg("Already on the way to bed."); return; }
        if (!isNight(mc) && !departureDue(mc, mc.player)) { Util.msg("It is not night yet (or you are not in the overworld)."); return; }
        start(mc);
    }

    private static final double TRUST_BED_WITHIN = 48;

    private boolean bedGone(Minecraft mc, LocalPlayer p) {
        if (!mc.level.hasChunkAt(bed)) return false;
        if (bed.distSqr(p.blockPosition()) > TRUST_BED_WITHIN * TRUST_BED_WITHIN) return false;
        return !(mc.level.getBlockState(bed).getBlock() instanceof BedBlock);
    }

    private void start(Minecraft mc) {
        bed = Util.pos(cfg.bed);
        if (bedGone(mc, mc.player)) {
            if (cfg.logoffOnMissingBed) {
                Util.warn("nobed", "Bed missing at " + bed.toShortString() + "; disconnecting.");
                Api.disconnect("Bed missing at " + bed.toShortString());
                return;
            }
            Util.msg("No bed at " + bed.toShortString() + " (it was removed?). Checking again in 2 minutes.");
            cooldown = 20 * 120;
            return;
        }
        timer = 0;
        tries = 0;
        toppedUp = false;
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
        EngineSelector.current().goTo(GoalSpec.near(bed, 1));
    }

    @Override
    boolean active() {
        if (state != State.IDLE && Minecraft.getInstance().player == null) state = State.IDLE;
        return state != State.IDLE;
    }

    private void stepInternal(Minecraft mc, LocalPlayer p) {
        if (p == null || mc.gameMode == null) { state = State.IDLE; return; }
        timer++;

        switch (state) {
            case PATHING -> {
                if (timer > pathTimeout) { fail("Could not reach the bed in time."); return; }
                if (bedGone(mc, p)) {
                    if (cfg.logoffOnMissingBed) {
                        state = State.IDLE;
                        EngineSelector.current().cancel();
                        Api.disconnect("Bed missing at " + bed.toShortString());
                        return;
                    }
                    fail("There is no bed at " + bed.toShortString() + ".");
                    return;
                }
                double dist = Math.sqrt(bed.distSqr(p.blockPosition()));
                if (dist <= 2.5) {
                    EngineSelector.current().cancel();
                    learnPace();
                    // one trip for everything: the base is here anyway, so refill the supplies before the night
                    if (!toppedUp && RestockProcess.topUpWanted(p)) {
                        toppedUp = true;
                        Util.msg("At the bed - topping up supplies first (one trip to the base instead of two).");
                        SurvivalKitMod.restock.startNow();
                        if (SurvivalKitMod.restock.busy()) {
                            state = State.TOPUP;
                            timer = 0;
                            return;
                        }
                    }
                    state = isNight(mc) ? State.ENTERING : State.WAITING;
                    timer = 0;
                    if (state == State.WAITING) Util.msg("At the bed, waiting for nightfall.");
                    return;
                }
                if (!EngineSelector.current().isBusy()) {
                    EngineSelector.current().goTo(GoalSpec.near(bed, 1));
                }
            }
            case TOPUP -> {
                if (SurvivalKitMod.restock.busy() && timer < 20 * 180) return;
                if (SurvivalKitMod.restock.busy()) SurvivalKitMod.restock.onLostControl();
                state = State.PATHING;   // back to the bed
                timer = 0;
                startDist = 0;
                pathTimeout = Math.max(MIN_PATH_TIMEOUT, pathTimeout);
                EngineSelector.current().goTo(GoalSpec.near(bed, 1));
            }
            case WAITING -> {
                if (isNight(mc)) {
                    state = State.ENTERING;
                    timer = 0;
                    return;
                }
                if (timer > WAIT_NIGHT_TIMEOUT) { fail("Waited five minutes at the bed and it never got dark."); return; }
            }
            case ENTERING -> {
                if (!isNight(mc)) {
                    state = State.IDLE;
                    cooldown = 20 * 10;
                    Util.msg("It is not night any more - no need to sleep.");
                    return;
                }
                if (p.isSleeping()) {
                    state = State.SLEEPING;
                    timer = 0;
                    Util.msg("Sleeping...");
                    return;
                }
                if (timer % 15 == 1) {
                    if (tries++ >= MAX_TRIES) { fail("Could not sleep (monsters nearby, or the server refused)."); return; }
                    Util.readyToOpen(p);
                    BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(bed), Direction.UP, bed, false);
                    mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
                }
            }
            case SLEEPING -> {
                if (!p.isSleeping() || timer > SLEEP_TIMEOUT) {
                    state = State.IDLE;
                    cooldown = 20 * 10;
                    Util.msg("Awake - carrying on.");
                }
            }
            default -> { }
        }
    }

    private void learnPace() {
        if (startDist < 15 || timer < 40) return;
        double sample = timer / startDist;
        double old = cfg.ticksPerBlock;
        cfg.ticksPerBlock = Math.max(3.0, Math.min(14.0, old * 0.7 + sample * 0.3));
        Config.save();
        Util.msg(String.format("Bed trip: %d blocks in %d s (%.1f ticks per block). Next time I plan with %.1f.",
                Math.round(startDist), timer / 20, sample, cfg.ticksPerBlock));
    }

    private void fail(String why) {
        Util.msg(why + " Trying again in 1 minute.");
        state = State.IDLE;
        cooldown = 20 * 60;
        EngineSelector.current().cancel();
    }

    @Override
    public void onLostControl() {
        state = State.IDLE;
        EngineSelector.current().cancel();
    }
}
