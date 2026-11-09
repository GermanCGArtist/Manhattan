package dev.baritonequeue;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static dev.baritonequeue.Config.cfg;

/**
 * Things the script waits for. The game thread makes one, calls start(), then tick() every game tick until done.
 * When an "on" block or a respawn interrupts, suspend() is called and later resume() (by default: cancel, then
 * start again from scratch, which is right for Baritone tasks that look at the world afresh).
 */
final class Actions {
    private Actions() {}

    abstract static class Action {
        boolean done;
        String error;     // set = the script gets an error at that line (try/catch can catch it)
        Object result;
        int ticks;        // ticks since start (for the timeout)
        Estimate estimate = new Estimate();

        abstract void start();

        abstract void tick();

        void cancel() {}

        void suspend() { cancel(); }

        void resume() {
            done = false;
            ticks = 0;
            start();
        }

        /** Whether the general timeout (timeout(seconds) in the script) applies. */
        boolean timed() { return true; }

        /** The stall check: true while it should count (Baritone is busy). */
        boolean stalled() { return false; }

        void finish(Object result) {
            this.result = result;
            done = true;
        }

        void fail(String why) {
            error = why;
            done = true;
        }
    }

    // ------------------------------------------------------------------ done on the game thread at once

    /** A function that changes the game (a message, a setting, the selection ...): run by the game thread, then on. */
    static final class Now extends Action {
        private final java.util.function.Supplier<Object> body;

        Now(java.util.function.Supplier<Object> body) {
            this.body = body;
            this.estimate = new Estimate();
        }

        void start() {
            try {
                finish(body.get());
            } catch (Values.ScriptError e) {
                fail(e.getMessage());
            }
        }

        void tick() {}

        @Override void suspend() {}

        @Override void resume() {}

        @Override boolean timed() { return false; }
    }

    // ------------------------------------------------------------------ waiting

    static final class Wait extends Action {
        private int left;

        Wait(double seconds) {
            left = Math.max(1, (int) Math.round(seconds * 20));
            estimate = Estimate.time(left);
        }

        void start() {}

        void tick() {
            if (--left <= 0) finish(null);
        }

        @Override void suspend() {}            // the time spent in the "on" block counts as waited

        @Override void resume() {}

        @Override boolean timed() { return false; }
    }

    // ------------------------------------------------------------------ Baritone

    /** A Baritone command; done when Baritone has been idle for 2 seconds. Stuck tasks are restarted, then skipped. */
    static final class BaritoneCommand extends Action {
        private static final int START_WAIT = 10;
        private static final int KICK_TICKS = 20 * 60 * 3;
        private static final int MAX_KICKS = 3;
        private static final int IDLE_DONE = 40;

        final String command;
        private int sinceStart, idle, noProgress, still, kicks, builderPaused;
        private double ax, ay, az;
        private boolean anchored;

        BaritoneCommand(String command) {
            this.command = command;
            this.estimate = Estimate.of(command);
        }

        void start() {
            sinceStart = 0;
            idle = 0;
            noProgress = 0;
            still = 0;
            builderPaused = 0;
            anchored = false;
            if (!execute(command)) fail("Baritone did not accept '#" + command + "' (check the spelling).");
        }

        static boolean execute(String command) {
            try {
                return Util.baritone().getCommandManager().execute(Util.normalize(command));
            } catch (Throwable t) {
                return false;
            }
        }

        void tick() {
            estimate.tick();
            if (++sinceStart < START_WAIT) return;
            if (Util.baritoneBusy()) {
                idle = 0;
                // "#sel ca" / "#build" that hit something it cannot do ("Unable to do it. Pausing") waits forever
                builderPaused = Util.baritone().getBuilderProcess().isPaused() ? builderPaused + 1 : 0;
                if (builderPaused > 20 * 10) {
                    Util.warn("queue", "Baritone gave up on '#" + command + "' (\"Unable to do it\" - usually water or "
                            + "lava in the way), so I cancelled it and go on with the script.");
                    Util.baritone().getPathingBehavior().cancelEverything();
                    finish(false);
                    return;
                }
                if (stuck()) {
                    Util.warn("queue", "'#" + command + "' made no progress for " + cfg.stallMinutes
                            + " min (Baritone keeps searching and cannot get anywhere), so I stopped it and go on. "
                            + "Change the limit with stall(minutes) in the script or /queue stall <minutes|off>.");
                    Util.baritone().getPathingBehavior().cancelEverything();
                    finish(false);
                }
                return;
            }
            noProgress = 0;
            if (++idle >= IDLE_DONE) finish(true);
        }

        @Override
        boolean stalled() {
            return noProgress > 1200;
        }

        int noProgressTicks() {
            return noProgress;
        }

        private boolean stuck() {
            var p = Minecraft.getInstance().player;
            if (p == null) return false;
            boolean moved = !anchored || p.distanceToSqr(ax, ay, az) > 9;
            if (moved) { ax = p.getX(); ay = p.getY(); az = p.getZ(); anchored = true; }
            boolean better = estimate.progressed();
            if (estimate.measurable() ? better : moved) noProgress = 0;
            else noProgress++;
            // standing still for minutes while Baritone claims to be busy: do what you would do by hand, start it again
            if (moved || better || p.isSleeping() || Minecraft.getInstance().screen != null) still = 0;
            else if (++still > KICK_TICKS && kicks < MAX_KICKS) {
                kicks++;
                still = 0;
                Util.msg("Baritone has stood still for " + KICK_TICKS / 1200 + " min without getting anywhere. Starting '#"
                        + command + "' again (" + kicks + "/" + MAX_KICKS + ").");
                Util.baritone().getPathingBehavior().cancelEverything();
                if (!execute(command)) fail("Baritone did not accept '#" + command + "' when restarting it.");
                return false;
            }
            int limit = Runner.stallLimit();
            return limit > 0 && noProgress > limit;
        }

        @Override
        void cancel() {
            Util.baritone().getPathingBehavior().cancelEverything();
        }

        @Override
        void suspend() {
            // Baritone's own task stays: the survival features pause it and it carries on by itself. Only an "on" block
            // that wants Baritone for itself needs it gone; cancel and start it again afterwards.
            cancel();
        }
    }

    /** "/command": sent to the server, or to a client command (our own /survival, /buildtools ... too). */
    static final class ChatCommand extends Action {
        private final String command;
        private int since;

        ChatCommand(String command) {
            this.command = command;
        }

        void start() {
            since = 0;
            var p = Minecraft.getInstance().player;
            if (p != null) p.connection.sendCommand(command);
        }

        void tick() {
            if (++since < 10) return;
            if (Modules.busy()) return;   // /buildtools scaffold collect, /survival restock now ... take a while
            finish(null);
        }

        @Override void suspend() {}

        @Override void resume() {}
    }

    // ------------------------------------------------------------------ chests

    static final class Chest extends Action {
        private final ChestOp op;
        private final BlockPos at;
        private boolean begun;

        Chest(ChestOp op, BlockPos at) {
            this.op = op;
            this.at = at;
        }

        void start() {
            begun = QueueMod.chest.begin(op, at);
            if (!begun) fail("The chest helper is busy.");
        }

        void tick() {
            if (QueueMod.chest.working()) return;
            String failed = QueueMod.chest.failure();
            if (failed != null) fail(failed);
            else finish((double) QueueMod.chest.moved());
        }

        @Override
        void cancel() {
            QueueMod.chest.cancel();
        }
    }

    // ------------------------------------------------------------------ the built-in helpers (sleep, fill water ...)

    /**
     * A feature that runs as its own Baritone process: start it, then wait while it is busy.
     * start returns an error text or null; result is read when it ends.
     */
    static final class Feature extends Action {
        private final Supplier<String> starter;
        private final BooleanSupplier busy;
        private final Runnable stopper;
        private final Supplier<Object> resultOf;
        private int since;
        private Runnable pauser;            // set: an interruption pauses it instead of cancelling it ...
        private Supplier<String> resumer;   // ... and this carries on where it was (same answers as starter)

        Feature(Supplier<String> starter, BooleanSupplier busy, Runnable stopper, Supplier<Object> resultOf) {
            this.starter = starter;
            this.busy = busy;
            this.stopper = stopper;
            this.resultOf = resultOf;
        }

        Feature withEstimate(Estimate e) {
            estimate = e;
            return this;
        }

        /** An "on" block or a respawn pauses it; afterwards it carries on where it was instead of starting over. */
        Feature resumable(Runnable pauser, Supplier<String> resumer) {
            this.pauser = pauser;
            this.resumer = resumer;
            return this;
        }

        void start() {
            since = 0;
            begin(starter.get());
        }

        private void begin(String err) {
            if (err == null) return;
            if (err.isEmpty()) finish(false);   // nothing to do right now (not night, no water ...): not an error
            else fail(err);
        }

        void tick() {
            estimate.tick();
            if (++since < 5) return;   // let it get going
            if (busy.getAsBoolean()) return;
            Object r = resultOf == null ? null : resultOf.get();
            if (r instanceof Modules.Failure f) fail(f.why());
            else finish(r);
        }

        @Override
        void cancel() {
            if (stopper != null) stopper.run();
        }

        @Override
        void suspend() {
            if (pauser != null) pauser.run();
            else cancel();
        }

        @Override
        void resume() {
            if (resumer == null) { super.resume(); return; }
            done = false;
            ticks = 0;
            since = 0;
            begin(resumer.get());
        }

        @Override
        boolean timed() {
            return true;
        }
    }
}
