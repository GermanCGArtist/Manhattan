package dev.baritonequeue;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The chunk loop shared by every chunked task (SchemProcess for schem_build, Actions.Chunked for clear_area, fill_water and
 * drain): pick the nearest open chunk, walk there, wait until it is loaded, let the script's "on chunk_needs" block take
 * a turn, hand the chunk to the host to work on, then DONE / retry once / DEFERRED, one more pass over the deferred chunks,
 * and the report. Every state change goes into the Ledger through Jobs. The host decides what "working on a chunk" is and
 * calls finished() when it is through.
 */
final class ChunkLoop {
    enum Step { WALK, LOAD, NEEDS, RUN }

    /** What to do with a chunk before walking to it. */
    enum Verdict { SKIP, DONE, RUN }

    interface Host {
        /** Nothing to do in this chunk, ever (no cells of the task in it): it is closed at once. */
        boolean empty(Chunker.Box b);

        /** Look at the ledger entry (null = none for this signature) and, where the host wants it, the world: skip it (done earlier), mark it done now, or work on it. */
        Verdict check(Chunker.Box b, Ledger.Entry e);

        /** Is everything the chunk needs loaded? */
        boolean loaded(Chunker.Box b);

        /** The bot has arrived and the chunk is loaded (schem: remember which cells are open). */
        void arrive(Chunker.Box b);

        /** Work on the chunk; later call finished(). Also called again for the retry. */
        void run(Chunker.Box b);

        /** After an "on" block: carry on the work of the chunk that was running (the step is RUN). */
        void resumeWork();

        /** How many blocks the chunk counts as "left" when it could not be reached or did not load. */
        int cells(Chunker.Box b);

        /** No chunk is left (the deferred pass included). */
        void allDone();

        /** A chunk the loop closed as DONE: has it lost blocks since (a walk, a mob, ...)? Only where it is loaded. */
        default boolean damaged(Chunker.Box b) { return false; }

        /** Where to walk to for the chunk (null = its centre). The host may pick a spot beside its structure. */
        default BlockPos walkTarget(Chunker.Box b) { return null; }

        /** A walk to a chunk starts (attempt 0, 1, ...) or has ended (attempt -1). */
        default void walking(int attempt) { }
    }

    static final int CHUNK_GAP = 12;          // ticks at a new chunk before "chunk_needs" goes up: the flag was down for a handler check
    static final int NEEDS_TICKS = 20;        // how long the script's "on chunk_needs" block has to take over
    static final int WALK_TICKS = 20 * 120;   // no arrival after this long: the walk counts as failed
    static final int LOAD_TICKS = 20 * 40;    // the chunk does not load
    static final int NEAR = 10;               // close enough to the chunk's centre (blocks)

    private final Host host;
    private Step step = Step.RUN;
    private boolean needs;
    private List<Chunker.Box> chunks = new ArrayList<>();
    private final Map<Chunker.Box, Jobs.Node> nodes = new HashMap<>();
    private final Set<Chunker.Box> closed = new HashSet<>(), marked = new HashSet<>();
    private final Map<Chunker.Box, Integer> tries = new HashMap<>();
    private final List<Chunker.Box> deferred = new ArrayList<>();
    private Chunker.Box box;     // the chunk being worked on
    private Jobs.Node action, cur;
    private String sig;
    private int pass, chunkTicks, walkTries, doneChunks;
    private boolean rechecked;   // the safety net over the DONE chunks has run

    ChunkLoop(Host host) {
        this.host = host;
    }

    // ------------------------------------------------------------------ state for the host

    Step step() { return step; }

    /** The loop itself is busy (walking, loading, waiting for the script): the host has nothing to tick. */
    boolean waiting() { return step != Step.RUN; }

    /** true while the engine waits at a chunk for the script's "on chunk_needs" block. */
    boolean needs() { return needs; }

    Chunker.Box box() { return box; }

    Jobs.Node cur() { return cur; }

    Jobs.Node action() { return action; }

    List<Chunker.Box> chunks() { return chunks; }

    int done() { return doneChunks; }

    int tries(Chunker.Box b) { return tries.getOrDefault(b, 0); }

    /** The chunk number for messages ("chunk 3/9"). */
    int index() { return doneChunks + 1; }

    String sig() { return sig; }

    /** A chunk that may still get work: not closed in this run, not DONE in the ledger, not empty. */
    boolean isOpen(Chunker.Box b) {
        return !closed.contains(b) && !host.empty(b) && Jobs.ledger().state(nodes.get(b).id, sig) != Ledger.State.DONE;
    }

    /** The host's work on the current chunk has started: RUNNING with this many blocks to do. */
    void running(int left) {
        Jobs.set(cur, Ledger.State.RUNNING, tries(box), left, null, sig);
    }

    // ------------------------------------------------------------------ a run

    /** No chunk work going on: back to the quiet state (before a begin, after the end). */
    void idle() {
        step = Step.RUN;
        box = null;
        needs = false;
    }

    /** Sets up the chunk nodes under the action and takes over what the ledger already knows. Returns how many chunks are DONE there. */
    int init(List<Chunker.Box> cs, Jobs.Node action, String sig) {
        chunks = cs;
        closed.clear();
        marked.clear();
        tries.clear();
        deferred.clear();
        nodes.clear();
        pass = 0;
        doneChunks = 0;
        walkTries = 0;
        rechecked = false;
        this.action = action;
        this.sig = sig;
        action.kids.clear();
        int done = 0;
        for (Chunker.Box b : cs) {
            Jobs.Node n = Jobs.chunk(action, b);
            nodes.put(b, n);
            Ledger.Entry e = Jobs.ledger().get(n.id, sig);
            if (e != null) {
                n.state = e.state;
                n.tries = e.tries;
                n.left = e.left;
                n.reason = e.reason;
                if (e.state == Ledger.State.DONE) done++;
            }
        }
        return done;
    }

    /** Starts the first chunk; false when no chunk is left (every one is done). */
    boolean start() {
        return nextChunk();
    }

    /** Ends the run (also after a cancel): the action's own summary, back to the quiet state. */
    void stop() {
        needs = false;
        if (action != null) Jobs.settle(action);
        step = Step.RUN;
    }

    /** A cancel: the chunk that was running goes back to PENDING, a rerun picks it up again. */
    void interrupted() {
        if (cur != null && cur.state == Ledger.State.RUNNING) Jobs.set(cur, Ledger.State.PENDING, cur.tries, cur.left, "interrupted", sig);
    }

    /** Picks the nearest open chunk and starts walking to it; false when no chunk is left (the deferred pass included). */
    private boolean nextChunk() {
        Minecraft mc = Minecraft.getInstance();
        BlockPos from = mc.player.blockPosition();
        while (true) {
            Chunker.Box b = Chunker.next(chunks, from, c -> !closed.contains(c));
            if (b == null) {
                if (pass == 0 && !deferred.isEmpty()) {
                    // neighbours are built now: a seam's attached or redstone blocks have their support
                    pass = 1;
                    Util.msg("Second pass over " + deferred.size() + " deferred chunk" + (deferred.size() == 1 ? "" : "s") + ".");
                    for (Chunker.Box d : deferred) { closed.remove(d); tries.put(d, 1); }   // one try each
                    deferred.clear();
                    continue;
                }
                if (!rechecked) {
                    // safety net: a chunk that was DONE may have lost blocks since (nothing else looks at it again)
                    rechecked = true;
                    int reopened = 0;
                    for (Chunker.Box d : chunks) {
                        Jobs.Node dn = nodes.get(d);
                        if (!closed.contains(d) || dn.state != Ledger.State.DONE || host.empty(d) || !host.damaged(d)) continue;
                        closed.remove(d);
                        tries.put(d, 1);   // one more try
                        doneChunks--;
                        reopened++;
                    }
                    if (reopened > 0) {
                        Util.msg("Checking the finished chunks again: " + reopened + " lost blocks, building " + (reopened == 1 ? "it" : "them") + " again.");
                        continue;
                    }
                }
                return false;
            }
            Jobs.Node n = nodes.get(b);
            if (host.empty(b)) { closed.add(b); continue; }
            Verdict v = host.check(b, Jobs.ledger().get(n.id, sig));
            if (v == Verdict.SKIP) { closed.add(b); n.state = Ledger.State.DONE; doneChunks++; continue; }   // done earlier
            if (v == Verdict.DONE) {
                Jobs.set(n, Ledger.State.DONE, tries(b), 0, null, sig);
                closed.add(b);
                doneChunks++;
                continue;
            }
            box = b;
            cur = n;
            step = Step.WALK;
            needs = false;
            chunkTicks = 0;
            walkTries = 0;
            Util.msg("Chunk " + (doneChunks + 1) + "/" + chunks.size() + " (" + b.text() + ").");
            startWalk(mc);
            return true;
        }
    }

    private boolean near(Minecraft mc) {
        return box.distanceXZ(mc.player.getX(), mc.player.getZ()) <= NEAR;
    }

    private void startWalk(Minecraft mc) {
        if (near(mc)) { host.walking(-1); step = Step.LOAD; return; }
        step = Step.WALK;
        host.walking(walkTries);
        BlockPos t = host.walkTarget(box);
        int gx = t != null ? t.getX() : (int) box.cx(), gz = t != null ? t.getZ() : (int) box.cz();
        Util.baritone().getCustomGoalProcess().setGoalAndPath(new baritone.api.pathing.goals.GoalXZ(gx, gz));
    }

    /** One game tick while the loop itself is busy (step is not RUN). */
    void tick(Minecraft mc) {
        chunkTicks++;
        switch (step) {
            case WALK -> {
                if (near(mc)) { Util.baritone().getPathingBehavior().cancelEverything(); host.walking(-1); step = Step.LOAD; return; }
                if (chunkTicks % 20 != 0) return;
                if (chunkTicks > WALK_TICKS) { failed("could not get there in " + WALK_TICKS / 20 + " s"); return; }
                if (!Util.baritone().getCustomGoalProcess().isActive()) {
                    if (++walkTries > 3) { failed("no way there (Baritone stopped walking " + walkTries + " times)"); return; }
                    startWalk(mc);
                }
            }
            case LOAD -> {
                if (chunkTicks < CHUNK_GAP || chunkTicks % 5 != 0) return;
                if (!host.loaded(box)) {
                    if (chunkTicks > LOAD_TICKS) failed("the chunk did not load");
                    return;
                }
                if (marked.add(box)) host.arrive(box);   // on arrival: what is open now is what the build may replace
                step = Step.NEEDS;
                needs = true;
                chunkTicks = 0;
            }
            case NEEDS -> {
                // the script's "on chunk_needs" block gets its turn (it checks every 10 ticks; if it fires, suspend and carryOn take it from here)
                if (chunkTicks < NEEDS_TICKS) return;
                needs = false;
                run();
            }
            default -> { }
        }
    }

    /** At the chunk, materials fetched (or none asked for): the host goes to work. */
    void run() {
        step = Step.RUN;
        host.run(box);
    }

    /** After an "on" block: the step the loop was in carries on. */
    void carryOn() {
        switch (step) {
            case WALK -> startWalk(Minecraft.getInstance());
            case LOAD -> { }
            case NEEDS -> { needs = false; run(); }   // the script has fetched what it wanted
            case RUN -> host.resumeWork();
        }
    }

    // ------------------------------------------------------------------ the end of a chunk

    /**
     * The host is through with the chunk: left blocks still to do (0 = done). DONE, one retry (the host runs it again), or
     * DEFERRED with the reason. why is asked only when the chunk is deferred.
     */
    void finished(int left, Supplier<String> why) {
        int t = tries.merge(box, 1, Integer::sum);
        if (left == 0) {
            Jobs.set(cur, Ledger.State.DONE, t, 0, null, sig);
            closed.add(box);
            doneChunks++;
            Util.msg("Chunk " + box.text() + " is done.");
        } else if (t < 2) {
            Util.msg("Chunk " + box.text() + ": " + left + " blocks left, trying once more.");
            run();
            return;
        } else {
            defer(left, why.get());
        }
        advance();
    }

    /** A chunk that cannot be reached or does not load: one retry (the walk again), then deferred. */
    void failed(String why) {
        Util.baritone().getPathingBehavior().cancelEverything();
        int t = tries.merge(box, 1, Integer::sum);
        if (t < 2 && pass == 0) {
            Util.msg("Chunk " + box.text() + ": " + why + ", trying once more.");
            chunkTicks = 0;
            walkTries = 0;
            startWalk(Minecraft.getInstance());
            return;
        }
        defer(host.cells(box), why);
        advance();
    }

    private void defer(int left, String why) {
        Jobs.set(cur, Ledger.State.DEFERRED, tries(box), left, why, sig);
        closed.add(box);
        if (pass == 0) deferred.add(box);
        Util.msg("Chunk " + box.text() + " deferred: " + left + " left (" + why + ").");
    }

    private void advance() {
        Util.baritone().getPathingBehavior().cancelEverything();
        host.walking(-1);
        if (!nextChunk()) host.allDone();
    }

    /** The final report of the chunks (call after stop()): totals and every chunk still open. Returns the blocks still wrong. */
    int report(String label, long startedAt, String hint) {
        int left = 0, done = 0;
        List<Jobs.Node> open = new ArrayList<>();
        for (Jobs.Node n : action.kids) {
            if (n.state == Ledger.State.DONE) done++;
            else if (n.state == Ledger.State.DEFERRED || n.state == Ledger.State.FAILED) { left += n.left; open.add(n); }
        }
        long secs = (System.currentTimeMillis() - startedAt) / 1000;
        Util.msg("Finished " + label + " in " + secs + " s: " + done + " of " + chunks.size() + " chunks done, "
                + (left == 0 ? "everything is as it should be." : left + " blocks still wrong in " + open.size() + " chunk" + (open.size() == 1 ? "" : "s") + "."));
        for (Jobs.Node n : open) Util.msg("  chunk " + n.label + ": " + n.left + " left - " + n.reason);
        if (left > 0) Util.msg(hint);
        return left;
    }
}
