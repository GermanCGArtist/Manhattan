package dev.baritonequeue;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
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

    // ------------------------------------------------------------------ big mining / water tasks in chunks (job queue stage 2)

    /**
     * clear_area, fill_water and drain on a selection bigger than one chunk (Config.chunkSize): the selection is cut into
     * chunk columns (Chunker), and for one chunk after the other the selection is narrowed to that chunk, the unchanged
     * process runs (made by the supplier), and the selection is put back. The walk / load / "on chunk_needs" / retry once /
     * defer / second pass / ledger / report loop is ChunkLoop, the same as for schem_build. A chunk that is DONE in the
     * ledger is never entered again (until /queue reset): the world is not counted again, the result of the process is
     * trusted. A selection that fits in one chunk does not get here (willChunk), it runs as it always did.
     * An error of the process itself (nothing to fill with, no area ...) stops the action with that error, as it does unchunked.
     */
    static final class Chunked extends Action implements ChunkLoop.Host {
        private static Chunked active;   // for the getter chunk_needs

        /** true while a chunked mining/water task waits at a chunk for the script's "on chunk_needs" block. */
        static boolean needs() {
            Chunked c = active;
            return c != null && c.loop != null && c.loop.needs() && !c.done;
        }

        private final String fn, args, what;
        private final boolean numeric;
        private final Supplier<Action> make;
        private final int line;
        private List<int[]> whole = new ArrayList<>();
        private ChunkLoop loop;
        private Action inner;
        private boolean narrowedNow;
        private long startedAt;

        /**
         * fn: the script function (ids in the ledger and messages); args: the arguments as text (part of the id); numeric: the
         * result is the blocks left (clear_area, fill_water), else true (drain); what: "blocks", "water cells" for messages;
         * make: makes the unchanged action for one chunk (the selection is narrowed when it starts).
         */
        Chunked(String fn, int line, String args, boolean numeric, String what, Supplier<Action> make) {
            this.fn = fn;
            this.line = line;
            this.args = args;
            this.numeric = numeric;
            this.what = what;
            this.make = make;
        }

        // ---- the selection as chunks (pure: tested in LangTest)

        /** The box around all boxes of the selection. */
        static Chunker.Box bounds(List<int[]> boxes) {
            int x0 = Integer.MAX_VALUE, y0 = x0, z0 = x0, x1 = Integer.MIN_VALUE, y1 = x1, z1 = x1;
            for (int[] b : boxes) {
                x0 = Math.min(x0, b[0]); y0 = Math.min(y0, b[1]); z0 = Math.min(z0, b[2]);
                x1 = Math.max(x1, b[3]); y1 = Math.max(y1, b[4]); z1 = Math.max(z1, b[5]);
            }
            return new Chunker.Box(x0, y0, z0, x1, y1, z1);
        }

        /** The selection boxes cut down to one chunk (those that do not touch it drop out). */
        static List<int[]> narrow(List<int[]> boxes, Chunker.Box c) {
            List<int[]> out = new ArrayList<>();
            for (int[] b : boxes) {
                int x0 = Math.max(b[0], c.x0()), x1 = Math.min(b[3], c.x1()), z0 = Math.max(b[2], c.z0()), z1 = Math.min(b[5], c.z1());
                int y0 = Math.max(b[1], c.y0()), y1 = Math.min(b[4], c.y1());
                if (x0 <= x1 && y0 <= y1 && z0 <= z1) out.add(new int[]{x0, y0, z0, x1, y1, z1});
            }
            return out;
        }

        /** The chunks of a selection: columns of the box around it, aligned to its min corner; only those that hold a part of it. */
        static List<Chunker.Box> chunksOf(List<int[]> boxes, int size) {
            List<Chunker.Box> out = new ArrayList<>();
            if (boxes.isEmpty()) return out;
            for (Chunker.Box c : Chunker.split(bounds(boxes), size)) if (!narrow(boxes, c).isEmpty()) out.add(c);
            return out;
        }

        /** What the ledger says about a chunk: DONE is never entered again, everything else is worked on (no recount of the world). */
        static ChunkLoop.Verdict verdict(Ledger.Entry e) {
            return e != null && e.state == Ledger.State.DONE ? ChunkLoop.Verdict.SKIP : ChunkLoop.Verdict.RUN;
        }

        /** What the chunks of the ledger were made for: the selection and the chunk size (another selection = the entries are stale). */
        static String signature(List<int[]> boxes, int size) {
            Chunker.Box b = bounds(boxes);
            long vol = 0;
            for (int[] x : boxes) vol += (x[3] - x[0] + 1L) * (x[4] - x[1] + 1L) * (x[5] - x[2] + 1L);
            return b.x0() + "," + b.y0() + "," + b.z0() + " " + b.sx() + "x" + b.sy() + "x" + b.sz() + " n=" + boxes.size() + " v=" + vol + " /" + size;
        }

        /** Is every chunk column the boxes touch loaded? */
        static boolean loadedBoxes(List<int[]> boxes, java.util.function.Predicate<BlockPos> loaded) {
            for (int[] b : boxes) {
                for (int x = b[0]; ; x = Math.min(b[3], x + 16)) {
                    for (int z = b[2]; ; z = Math.min(b[5], z + 16)) {
                        if (!loaded.test(new BlockPos(x, b[1], z))) return false;
                        if (z >= b[5]) break;
                    }
                    if (x >= b[3]) break;
                }
            }
            return true;
        }

        /** Does the current selection need more than one chunk? Then the action is a Chunked one. */
        static boolean willChunk() {
            return chunksOf(Areas.read(), SchemProcess.chunkSize()).size() > 1;
        }

        // ---- the action

        @Override
        void start() {
            Minecraft mc = Minecraft.getInstance();
            whole = Areas.read();
            int size = SchemProcess.chunkSize();
            List<Chunker.Box> cs = chunksOf(whole, size);
            if (cs.isEmpty() || mc.player == null) { fail("Select the area first: area(\"name\") or select(corner1, corner2)."); return; }
            Jobs.Node action = Jobs.action(fn, line, args, true);
            loop = new ChunkLoop(this);
            loop.idle();
            int done = loop.init(cs, action, signature(whole, size));
            active = this;
            startedAt = System.currentTimeMillis();
            Util.msg(fn + ": " + cs.size() + " chunks of up to " + size + "x" + size + " blocks" + (done > 0 ? " (" + done + " done in an earlier run)" : "") + ".");
            if (!loop.start()) {
                Util.msg(fn + ": nothing to do, every chunk is done.");
                end();
                finish(numeric ? (Object) 0.0 : Boolean.TRUE);
            }
        }

        @Override
        void tick() {
            Minecraft mc = Minecraft.getInstance();
            if (loop == null || done || mc.level == null || mc.player == null) return;
            if (loop.waiting()) { loop.tick(mc); return; }
            if (inner == null) return;
            inner.ticks++;
            inner.tick();
            if (inner.done) innerDone();
        }

        @Override
        void cancel() {
            if (inner != null && !inner.done) inner.cancel();
            if (loop != null) {
                Util.baritone().getPathingBehavior().cancelEverything();
                if (!done) loop.interrupted();   // a rerun picks the chunk up again
                end();
            }
        }

        @Override
        void suspend() {
            if (loop == null || done) return;
            if (loop.step() == ChunkLoop.Step.RUN && inner != null && !inner.done) inner.suspend();
            else Util.baritone().getPathingBehavior().cancelEverything();
        }

        @Override
        void resume() {
            if (loop == null || done) { super.resume(); return; }
            ticks = 0;
            if (loop.step() == ChunkLoop.Step.RUN) narrowTo(loop.box());   // an "on" block may have changed the selection
            loop.carryOn();
        }

        @Override
        boolean stalled() {
            return inner != null && !inner.done && inner.stalled();
        }

        /** The chunk's process is through. */
        private void innerDone() {
            Action a = inner;
            restore();
            if (a.error != null) {   // the process itself cannot go on (no blocks to fill with ...): not a matter of this chunk
                Util.baritone().getPathingBehavior().cancelEverything();
                Jobs.set(loop.cur(), Ledger.State.FAILED, loop.tries(loop.box()) + 1, 1, a.error, loop.sig());
                end();
                fail(a.error);
                return;
            }
            Object r = a.result;
            int left = r instanceof Double d ? (int) Math.ceil(d) : 0;
            loop.finished(left, () -> left + " " + what + " left that " + fn + " could not get at");
        }

        private void narrowTo(Chunker.Box c) {
            Areas.narrowed = true;
            Areas.apply(narrow(whole, c));
            narrowedNow = true;
        }

        /** Puts the whole selection back. */
        private void restore() {
            if (!narrowedNow) return;
            narrowedNow = false;
            Areas.apply(whole);
            Areas.narrowed = false;
        }

        private void end() {
            restore();
            loop.stop();
            if (active == this) active = null;
        }

        // ---- ChunkLoop.Host

        @Override
        public boolean empty(Chunker.Box b) {
            return narrow(whole, b).isEmpty();
        }

        @Override
        public ChunkLoop.Verdict check(Chunker.Box b, Ledger.Entry e) {
            return verdict(e);
        }

        @Override
        public boolean loaded(Chunker.Box b) {
            var level = Minecraft.getInstance().level;
            return level != null && loadedBoxes(narrow(whole, b), level::hasChunkAt);
        }

        @Override
        public void arrive(Chunker.Box b) {}

        @Override
        public void run(Chunker.Box b) {
            narrowTo(b);
            loop.running(0);
            inner = make.get();
            estimate = inner.estimate;
            inner.ticks = 0;
            try {
                inner.start();
            } catch (Throwable t) {
                t.printStackTrace();
                inner.fail("Could not start " + fn + " (" + t + ").");
            }
            if (inner.done) innerDone();   // nothing to do there, or it failed at once
        }

        @Override
        public void resumeWork() {
            if (inner != null && !inner.done) inner.resume();
        }

        @Override
        public int cells(Chunker.Box b) {
            long n = 0;
            for (int[] x : narrow(whole, b)) n += (x[3] - x[0] + 1L) * (x[4] - x[1] + 1L) * (x[5] - x[2] + 1L);
            return (int) Math.max(1, Math.min(Integer.MAX_VALUE, n));
        }

        @Override
        public void allDone() {
            end();
            int left = loop.report(fn, startedAt, "/queue jobs lists the chunks; /queue retry <job> opens them again.");
            finish(numeric ? (Object) (double) left : Boolean.TRUE);
        }
    }

    /**
     * fill_water for one chunk, with the chest trips of the unchunked fill_water (same rules, as an action): fill; when the
     * blocks ran out fetch more and go on; when a round got something done but water is left, another round; else give the
     * water that is left as the result. fetch = null: no chest.
     */
    static final class FillWater extends Action {
        private final Supplier<Action> fetch, fill;
        private final BooleanSupplier none;   // nothing to fill with in the inventory
        private final String block;
        private Action inner;
        private boolean fetching;
        private int trips, rounds;

        FillWater(Supplier<Action> fetch, BooleanSupplier none, Supplier<Action> fill, String block) {
            this.fetch = fetch;
            this.none = none;
            this.fill = fill;
            this.block = block;
        }

        void start() {
            trips = 0;
            rounds = 0;
            next();
        }

        private void next() {
            fetching = fetch != null && none.getAsBoolean();   // nothing to fill with: fetch first
            inner = fetching ? fetch.get() : fill.get();
            if (!fetching) estimate = inner.estimate;
            inner.ticks = 0;
            inner.start();
            if (inner.done) after();
        }

        void tick() {
            if (inner == null || done) return;
            inner.ticks++;
            inner.tick();
            if (inner.done) after();
        }

        private void after() {
            if (done) return;
            if (fetching) {
                if (inner.error != null) {
                    Util.msg("fill_water: could not fetch more " + block + " (" + inner.error + ")");
                    finish((double) dev.buildtools.Api.lastFill()[1]);
                    return;
                }
                if (inner.result instanceof Double d && d == 0 && none.getAsBoolean()) {
                    Util.msg("fill_water: the chest gave no " + block + ".");
                    finish((double) dev.buildtools.Api.lastFill()[1]);
                    return;
                }
                trips++;
                next();
                return;
            }
            if (inner.error != null) { fail(inner.error); return; }
            double left = inner.result instanceof Double d ? d : 0;
            if (left == 0) { finish(0.0); return; }
            if (dev.buildtools.Api.fillRanOut()) {
                if (fetch == null || trips > 200) { finish(left); return; }
                Util.msg("Out of " + block + " with " + Values.num(left) + " water left: fetching more.");
                next();
                return;
            }
            // not out of blocks: what got filled may have opened new ways; go again while a round gets anything done
            if (dev.buildtools.Api.lastFill()[0] == 0 || ++rounds > 30) {
                Util.msg("fill_water: " + Values.num(left) + " water cells left that it cannot get at.");
                finish(left);
                return;
            }
            Util.msg("Another round: " + Values.num(left) + " water left.");
            next();
        }

        @Override
        void cancel() {
            if (inner != null && !inner.done) inner.cancel();
        }

        @Override
        void suspend() {
            if (inner != null && !inner.done) inner.suspend();
        }

        @Override
        void resume() {
            if (inner != null && !inner.done) inner.resume();
            else if (!done) next();
        }
    }
}
