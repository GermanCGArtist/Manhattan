package dev.survivalkit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.monster.Monster;

import java.util.ArrayList;
import java.util.List;

import static dev.survivalkit.Config.cfg;

/**
 * Baritone drops its task when you die. If one was running, this brings it back: after the respawn it first gets
 * you healthy again (eating and fighting are allowed meanwhile), waits until no monster is near, then runs the same
 * command again. Three deaths within ten minutes and it stops trying (maxdeaths 0: never; it then also carries
 * on after ten minutes of waiting instead of giving up).
 *
 * While a script runs, the script itself resumes its current step: the two talk through
 * system properties ("baritonequeue.active" -> this stays out of the way, "survivalkit.recovering" while we wait,
 * "survivalkit.safe" when it is time to go on).
 */
final class Recovery {
    private static final long BUSY_WINDOW = 8_000;            // a task counts as "was running" this long after its last tick
    private static final long GIVE_UP_AFTER = 10L * 60_000;   // waiting for a safe moment
    private static final long DEATH_WINDOW = 10L * 60_000;
    private static final double SAFE_RADIUS = 16;
    private static final float SAFE_HEALTH = 0.8f;
    private static final long IGNORE_THREATS_AFTER = 60_000;  // healthy but mobs stay near that nobody can reach: go on

    /** True from the death until the task is resumed or given up: eating and fighting may act without a task. */
    static volatile boolean waiting;

    boolean isWaiting() {
        return waiting;
    }

    private String lastTask;
    private long busyAt, waitStart;
    private int check;
    private boolean queueMode;
    private final List<Long> deaths = new ArrayList<>();

    /** A task command typed by the player (without the prefix). */
    void remember(String command) {
        lastTask = command;
    }

    void forget() {
        lastTask = null;
        cancel();
    }

    /** The player started something else or stopped everything: nothing to bring back. */
    void cancel() {
        if (reconnecting) {
            reconnecting = false;
            dev.midtown.api.RuntimeBus.setRecovering(false);
        }
        if (!waiting) return;
        waiting = false;
        dev.midtown.api.RuntimeBus.setRecovering(false);
    }

    private boolean reconnecting;

    /** The connection dropped: remember whether a task was running (the queue holds its step meanwhile). */
    void onDisconnect() {
        long now = System.currentTimeMillis();
        queueMode = dev.midtown.api.RuntimeBus.isScriptActive();
        boolean wasBusy = now - busyAt < BUSY_WINDOW;
        reconnecting = cfg.resume && (queueMode || (wasBusy && lastTask != null));
        if (reconnecting) dev.midtown.api.RuntimeBus.setRecovering(true);
    }

    /** Back in the world: bring the task back. */
    void afterReconnect() {
        if (!reconnecting) return;
        reconnecting = false;
        dev.midtown.api.RuntimeBus.setRecovering(false);
        if (queueMode) {
            dev.midtown.api.RuntimeBus.setSafeAfterRespawn(true);
            Util.msg("Back on the server - the queue carries on.");
            return;
        }
        Util.msg("Back on the server - resuming: " + lastTask);
        boolean ok;
        try {
            var engine = dev.midtown.api.EngineSelector.current();
            engine.resume();
            ok = true;
        } catch (Throwable t) {
            ok = false;
        }
        if (!ok) Util.warn("resumefail", "Engine did not accept '" + lastTask + "' any more.");
    }

    void onDeath() {
        if (!cfg.resume) return;
        long now = System.currentTimeMillis();
        boolean wasBusy = now - busyAt < BUSY_WINDOW;
        queueMode = dev.midtown.api.RuntimeBus.isScriptActive();
        if (!wasBusy && !queueMode) return;
        if (!queueMode && lastTask == null) {
            Util.warn("noresume", "I do not know which task was running, so I cannot resume it. Type the Baritone command again.");
            return;
        }
        deaths.removeIf(t -> now - t > DEATH_WINDOW);
        deaths.add(now);
        if (cfg.maxDeaths > 0 && deaths.size() > cfg.maxDeaths) {   // cfg.maxDeaths 0: never stop
            if (queueMode) dev.midtown.api.RuntimeBus.setRecoveryGaveUpReason("you died " + deaths.size() + " times within 10 minutes - have a look at what is killing you.");
            Util.warn("deathloop", "You died " + deaths.size() + " times within 10 minutes, so I will not resume the task by itself. "
                    + "Have a look at what is killing you first.");
            return;
        }
        waiting = true;
        waitStart = now;
        check = 0;
        dev.midtown.api.RuntimeBus.setRecovering(true);
        Util.msg("Once you are healthy and no monster is near, I resume: " + (queueMode ? "the queue" : lastTask));
    }

    /** Every client tick while alive. */
    void tick(Minecraft mc, LocalPlayer p) {
        long now = System.currentTimeMillis();
        if (Util.baritoneBusy()) {
            busyAt = now;
            if (waiting && !queueMode) cancel();   // something else is running again (started by hand)
            return;
        }
        if (!waiting || ++check % 20 != 0) return;
        if (now - waitStart > GIVE_UP_AFTER) {
            if (cfg.maxDeaths == 0) {
                // Never stop for dying (keep-inventory server): carry on even if not healthy or safe yet.
                // The task eats, fights and restocks by itself; another death just starts another wait.
                Util.warn("resumeanyway", "Could not get healthy and safe within 10 minutes - carrying on anyway (maxdeaths 0).");
                resume(true);
                return;
            }
            cancel();
            if (queueMode) dev.midtown.api.RuntimeBus.setRecoveryGaveUpReason("could not get healthy again within 10 minutes after the respawn.");
            Util.warn("resumefail", "Could not get safe within 10 minutes, so the task is not resumed.");
            return;
        }
        if (PauseWatch.isPaused() || p.isSleeping() || Util.guiOpen(p)) return;
        if (SurvivalKitMod.eat.busy() || SurvivalKitMod.defence.busy() || SurvivalKitMod.restock.busy() || SurvivalKitMod.sleep.busy()) return;
        // Health only comes back by itself with a food bar of 18 or more. Below that, with nothing being eaten,
        // waiting does not help: after a while go on (the task can restock food), like for unreachable mobs.
        boolean canHeal = p.getFoodData().getFoodLevel() >= 18;
        if (p.getHealth() < p.getMaxHealth() * SAFE_HEALTH && (canHeal || now - waitStart < IGNORE_THREATS_AFTER)) return;
        // Only mobs the defence would fight count (not calm endermen, not mobs in a cave far below). If some
        // stay near that the defence cannot get to, carry on after a while anyway: the task has its own defence.
        if (DefenceProcess.threats(mc, p, SAFE_RADIUS) > 0 && now - waitStart < IGNORE_THREATS_AFTER) return;
        resume(false);
    }

    private void resume(boolean anyway) {
        waiting = false;
        dev.midtown.api.RuntimeBus.setRecovering(false);
        if (queueMode) {
            dev.midtown.api.RuntimeBus.setSafeAfterRespawn(true);
            if (!anyway) Util.msg("Safe again - the queue carries on.");
            return;
        }
        if (lastTask == null) return;
        if (!anyway) Util.msg("Safe again - resuming: " + lastTask);
        boolean ok = false;
        try {
            var engine = dev.midtown.api.EngineSelector.current();
            String trimmed = lastTask.trim();
            String[] parts = trimmed.split("\\s+");
            if (parts.length >= 4 && parts[0].equalsIgnoreCase("goto")) {
                try {
                    int x = Integer.parseInt(parts[1]);
                    int y = Integer.parseInt(parts[2]);
                    int z = Integer.parseInt(parts[3]);
                    engine.goTo(dev.midtown.api.GoalSpec.exact(new net.minecraft.core.BlockPos(x, y, z)));
                    ok = true;
                } catch (NumberFormatException ignored) {}
            } else if (parts.length >= 2 && parts[0].equalsIgnoreCase("mine")) {
                String[] targets = java.util.Arrays.copyOfRange(parts, 1, parts.length);
                engine.mine(dev.midtown.api.BlockFilter.ofNames(targets));
                ok = true;
            }
            if (!ok) {
                engine.resume();
                ok = true;
            }
        } catch (Throwable t) {
            ok = false;
        }
        if (!ok) Util.warn("resumefail", "Engine did not accept '" + lastTask + "' any more.");
    }
}
