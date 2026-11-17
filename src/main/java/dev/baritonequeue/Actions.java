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

        /** Whether this came from a literal "#cmd" line in a script (the only case that is deprecated). */
        private final boolean legacy;

        /** Used by the native script functions (goto(), mine(), clear_trees() ...): no deprecation warning. */
        BaritoneCommand(String command) {
            this(command, false);
        }

        /** A literal "#cmd" line typed in a script: runs the same, but warns that it is deprecated. */
        static BaritoneCommand legacy(String command) {
            return new BaritoneCommand(command, true);
        }

        private BaritoneCommand(String command, boolean legacy) {
            this.command = command;
            this.legacy = legacy;
            this.estimate = Estimate.of(command);
        }

        void start() {
            sinceStart = 0;
            idle = 0;
            noProgress = 0;
            still = 0;
            builderPaused = 0;
            anchored = false;
            if (!execute(command, legacy)) fail("Baritone did not accept '#" + command + "' (check the spelling).");
        }

        static boolean execute(String command, boolean warnLegacy) {
            try {
                String norm = Util.normalize(command);
                String trimmed = norm.trim();
                if (trimmed.isEmpty()) return false;
                String[] parts = trimmed.split("\\s+");
                String cmd = parts[0].toLowerCase();
                var engine = dev.midtown.api.EngineSelector.current();
                if (warnLegacy) Util.warn("shim-" + cmd,"Legacy '#" + cmd + "' command is deprecated; use native script functions (goto(), mine(), ...).");
                switch (cmd) {
                    case "goto" -> {
                        if (parts.length >= 4) {
                            int x = Integer.parseInt(parts[1]);
                            int y = Integer.parseInt(parts[2]);
                            int z = Integer.parseInt(parts[3]);
                            engine.goTo(dev.midtown.api.GoalSpec.exact(new net.minecraft.core.BlockPos(x, y, z)));
                            return true;
                        }
                        if (parts.length >= 3) {
                            int x = Integer.parseInt(parts[1]);
                            int z = Integer.parseInt(parts[2]);
                            engine.goTo(dev.midtown.api.GoalSpec.xz(x, z));
                            return true;
                        }
                        return false;
                    }
                    case "mine" -> {
                        if (parts.length < 2) return false;
                        String[] targets = java.util.Arrays.copyOfRange(parts, 1, parts.length);
                        engine.mine(dev.midtown.api.BlockFilter.ofNames(targets));
                        return true;
                    }
                    case "stop", "cancel" -> {
                        engine.cancel();
                        return true;
                    }
                    case "p", "pause" -> {
                        PauseWatch.handleCommand("pause");
                        engine.pause();
                        return true;
                    }
                    case "r", "resume", "unpause" -> {
                        PauseWatch.handleCommand("resume");
                        engine.resume();
                        return true;
                    }
                    case "set" -> {
                        if (parts.length >= 3) {
                            return engine.setSetting(parts[1], parts[2]);
                        }
                        return false;
                    }
                    case "sel", "selection" -> {
                        if (parts.length < 2) return false;
                        String sub = parts[1].toLowerCase();
                        if (sub.equals("clear") || sub.equals("c") || sub.equals("clearall")) {
                            Areas.clearSelection();
                            return true;
                        }
                        if (sub.equals("ca") || sub.equals("cleararea")) {
                            return QueueMod.clear.begin() == null;
                        }
                        if (sub.equals("pos1") || sub.equals("1") || sub.equals("p1")) {
                            net.minecraft.core.BlockPos p = parts.length >= 5
                                    ? new net.minecraft.core.BlockPos(Integer.parseInt(parts[2]), Integer.parseInt(parts[3]), Integer.parseInt(parts[4]))
                                    : Minecraft.getInstance().player.blockPosition();
                            Areas.setPos1(p);
                            return true;
                        }
                        if (sub.equals("pos2") || sub.equals("2") || sub.equals("p2")) {
                            net.minecraft.core.BlockPos p = parts.length >= 5
                                    ? new net.minecraft.core.BlockPos(Integer.parseInt(parts[2]), Integer.parseInt(parts[3]), Integer.parseInt(parts[4]))
                                    : Minecraft.getInstance().player.blockPosition();
                            Areas.setPos2(p);
                            return true;
                        }
                        if (sub.equals("set") && parts.length >= 8) {
                            net.minecraft.core.BlockPos a = new net.minecraft.core.BlockPos(Integer.parseInt(parts[2]), Integer.parseInt(parts[3]), Integer.parseInt(parts[4]));
                            net.minecraft.core.BlockPos b = new net.minecraft.core.BlockPos(Integer.parseInt(parts[5]), Integer.parseInt(parts[6]), Integer.parseInt(parts[7]));
                            Areas.setSingleBox(a, b);
                            return true;
                        }
                        if (sub.equals("replace") && parts.length >= 4) {
                            String to = parts[parts.length - 1].toLowerCase();
                            if (!to.equals("air") && !to.equals("minecraft:air")) return false;
                            java.util.List<int[]> boxes = Areas.read();
                            if (boxes.isEmpty()) return false;
                            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
                            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
                            for (int[] b : boxes) {
                                minX = Math.min(minX, b[0]); minY = Math.min(minY, b[1]); minZ = Math.min(minZ, b[2]);
                                maxX = Math.max(maxX, b[3]); maxY = Math.max(maxY, b[4]); maxZ = Math.max(maxZ, b[5]);
                            }
                            String[] from = java.util.Arrays.copyOfRange(parts, 2, parts.length - 1);
                            engine.mine(dev.midtown.api.BlockFilter.ofNames(from),
                                    dev.midtown.api.Area.of(minX, minY, minZ, maxX, maxY, maxZ));
                            return true;
                        }
                        return false;
                    }
                    default -> {
                        if (parts.length >= 2 && engine.setSetting(cmd, parts[1])) return true;
                        if (parts.length >= 2 && (cmd.equals("acceptablethrowawayitems")
                                || cmd.equals("allowinventory")
                                || cmd.equals("buildinlayers")
                                || cmd.equals("layerorder"))) {
                            return true;
                        }
                        return false;
                    }
                }
            } catch (Throwable t) {
                return false;
            }
        }

        void tick() {
            estimate.tick();
            if (++sinceStart < START_WAIT) return;
            if (Util.baritoneBusy() || QueueMod.clear.working()) {
                idle = 0;
                builderPaused = 0;
                if (stuck()) {
                    Util.warn("queue", "'#" + command + "' made no progress for " + cfg.stallMinutes
                            + " min, so I stopped it and go on. "
                            + "Change the limit with stall(minutes) in the script or /queue stall <minutes|off>.");
                    dev.midtown.api.EngineSelector.current().cancel();
                    QueueMod.clear.cancel();
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
            // standing still for minutes while busy: do what you would do by hand, start it again
            if (moved || better || p.isSleeping() || Minecraft.getInstance().screen != null) still = 0;
            else if (++still > KICK_TICKS && kicks < MAX_KICKS) {
                kicks++;
                still = 0;
                Util.msg("The task has stood still for " + KICK_TICKS / 1200 + " min without getting anywhere. Starting '#"
                        + command + "' again (" + kicks + "/" + MAX_KICKS + ").");
                dev.midtown.api.EngineSelector.current().cancel();
                if (!execute(command, false)) fail("Engine did not accept '#" + command + "' when restarting it.");
                return false;
            }
            int limit = Runner.stallLimit();
            return limit > 0 && noProgress > limit;
        }

        @Override
        void cancel() {
            dev.midtown.api.EngineSelector.current().cancel();
        }

        @Override
        void suspend() {
            dev.midtown.api.EngineSelector.current().pause();
        }

        @Override
        void resume() {
            dev.midtown.api.EngineSelector.current().resume();
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
