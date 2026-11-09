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
 * command again. Three deaths within ten minutes and it stops trying.
 *
 * With the Baritone Queue addon running a list, the list itself resumes its current step: the two talk through
 * system properties ("baritonequeue.active" -> this stays out of the way, "survivalkit.recovering" while we wait,
 * "survivalkit.safe" when it is time to go on).
 */
final class Recovery {
    private static final long BUSY_WINDOW = 8_000;            // a task counts as "was running" this long after its last tick
    private static final long GIVE_UP_AFTER = 10L * 60_000;   // waiting for a safe moment
    private static final long DEATH_WINDOW = 10L * 60_000;
    private static final int MAX_DEATHS = 2;                  // the third death in the window ends it
    private static final double SAFE_RADIUS = 16;
    private static final float SAFE_HEALTH = 0.8f;

    /** True from the death until the task is resumed or given up: eating and fighting may act without a task. */
    static volatile boolean waiting;

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
            System.clearProperty("survivalkit.recovering");
        }
        if (!waiting) return;
        waiting = false;
        System.clearProperty("survivalkit.recovering");
    }

    private boolean reconnecting;

    /** The connection dropped: remember whether a task was running (the queue holds its step meanwhile). */
    void onDisconnect() {
        long now = System.currentTimeMillis();
        queueMode = System.getProperty("baritonequeue.active") != null;
        boolean wasBusy = now - busyAt < BUSY_WINDOW;
        reconnecting = cfg.resume && (queueMode || (wasBusy && lastTask != null));
        if (reconnecting) System.setProperty("survivalkit.recovering", "1");
    }

    /** Back in the world: bring the task back. */
    void afterReconnect() {
        if (!reconnecting) return;
        reconnecting = false;
        System.clearProperty("survivalkit.recovering");
        if (queueMode) {
            System.setProperty("survivalkit.safe", "1");
            Util.msg("Back on the server - the queue carries on.");
            return;
        }
        Util.msg("Back on the server - resuming: " + lastTask);
        boolean ok;
        try {
            ok = Util.baritone().getCommandManager().execute(lastTask);
        } catch (Throwable t) {
            ok = false;
        }
        if (!ok) Util.warn("resumefail", "Baritone did not accept '" + lastTask + "' any more.");
    }

    void onDeath() {
        if (!cfg.resume) return;
        long now = System.currentTimeMillis();
        boolean wasBusy = now - busyAt < BUSY_WINDOW;
        queueMode = System.getProperty("baritonequeue.active") != null;
        if (!wasBusy && !queueMode) return;
        if (!queueMode && lastTask == null) {
            Util.warn("noresume", "I do not know which task was running, so I cannot resume it. Type the Baritone command again.");
            return;
        }
        deaths.removeIf(t -> now - t > DEATH_WINDOW);
        deaths.add(now);
        if (deaths.size() > MAX_DEATHS) {
            Util.warn("deathloop", "You died " + deaths.size() + " times within 10 minutes, so I will not resume the task by itself. "
                    + "Have a look at what is killing you first.");
            return;
        }
        waiting = true;
        waitStart = now;
        check = 0;
        System.setProperty("survivalkit.recovering", "1");
        Util.msg("Once you are healthy and no monster is near, I resume: " + (queueMode ? "the queue" : lastTask));
    }

    /** Every client tick while alive. */
    void tick(Minecraft mc, LocalPlayer p) {
        long now = System.currentTimeMillis();
        if (Util.baritoneBusy()) {
            busyAt = now;
            if (waiting) cancel();   // something else is running again (started by hand)
            return;
        }
        if (!waiting || ++check % 20 != 0) return;
        if (now - waitStart > GIVE_UP_AFTER) {
            cancel();
            Util.warn("resumefail", "Could not get safe within 10 minutes, so the task is not resumed.");
            return;
        }
        if (PauseWatch.paused || p.isSleeping() || Util.guiOpen(p)) return;
        if (p.getHealth() < p.getMaxHealth() * SAFE_HEALTH) return;
        if (!mc.level.getEntitiesOfClass(Monster.class, p.getBoundingBox().inflate(SAFE_RADIUS), Monster::isAlive).isEmpty()) return;
        if (SurvivalKitMod.eat.busy() || SurvivalKitMod.defence.busy() || SurvivalKitMod.restock.busy() || SurvivalKitMod.sleep.busy()) return;

        waiting = false;
        System.clearProperty("survivalkit.recovering");
        if (queueMode) {
            System.setProperty("survivalkit.safe", "1");
            Util.msg("Safe again - the queue carries on.");
            return;
        }
        Util.msg("Safe again - resuming: " + lastTask);
        boolean ok;
        try {
            ok = Util.baritone().getCommandManager().execute(lastTask);
        } catch (Throwable t) {
            ok = false;
        }
        if (!ok) Util.warn("resumefail", "Baritone did not accept '" + lastTask + "' any more.");
    }
}
