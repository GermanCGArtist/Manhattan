package dev.baritonequeue;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.process.IBuilderProcess;
import dev.baritonequeue.Schem.Mode;
import dev.baritonequeue.Schem.Phase;
import dev.baritonequeue.Schem.Plan;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.baritonequeue.Config.cfg;

/**
 * A plan bigger than one chunk (Config.chunkSize, default 32) is cut into chunks and worked chunk by chunk (stage 1 of the
 * job queue): the nearest open chunk, walk there, wait until it is loaded, mark which cells are open, give the script's
 * "on chunk_needs" block its turn to fetch materials, run the phases below on that chunk alone, count, retry once, defer.
 * After the last chunk one more pass over the deferred ones, then the report. The result of every chunk is in the Ledger
 * (see Jobs), so a rerun does not visit what is done. A plan that fits one chunk runs as one piece, as it always did.
 * <p>
 * Builds a Litematica placement with Baritone's own builder, one phase at a time: the plan is wrapped in an ISchematic
 * that only "contains" the blocks of the current phase (see Schem.Wrapper), the builder runs on it layer by layer
 * (bottom-up; the clear phase top-down) and when it is done the next phase starts. Never goes through the Litematica
 * printer.
 */
final class SchemProcess {
    private static final int FIRST_CHECK = 20;      // ticks before "builder idle" counts as finished
    private static final int STALL_STEP = 40;       // how often progress is looked at
    private static final int STALL_TICKS = 20 * 60; // no new correct block for this long ends the phase

    private boolean working, paused;
    private Plan plan;
    private Mode mode;
    private final List<Phase> phases = new ArrayList<>();
    private final SchemPlacer placer = new SchemPlacer();
    private int pi, since, noProgress, lastTodo;
    private String failure;
    private double result;
    private long startedAt;
    private final Map<Phase, String> phaseResult = new LinkedHashMap<>();
    private final List<String> stuck = new ArrayList<>();

    // Baritone settings we change while building, put back afterwards
    private int lastPlaced;
    private boolean oldLayers, oldOrder, oldBreak, oldAbove, oldDir, oldInventory, changed, retried;
    private java.util.List<String> oldIgnore;
    private java.util.Map<net.minecraft.world.level.block.Block, java.util.List<net.minecraft.world.level.block.Block>> oldSubs;
    private int oldStart;
    private Object planWorld;   // the level the plan was made in
    private Boolean breakBefore, walkBreak;   // Baritone's allowBreak before the build / before a walk to a chunk

    // ------------------------------------------------------------------ the chunk loop (plans bigger than one chunk)

    private boolean chunked;
    private final ChunkLoop loop = new ChunkLoop(new ChunkLoop.Host() {
        public boolean empty(Chunker.Box b) { return plan.cells(b) == 0; }

        public ChunkLoop.Verdict check(Chunker.Box b, Ledger.Entry e) {
            Minecraft mc = Minecraft.getInstance();
            Schem.Counts c = Schem.count(plan, mode, mc.level, b);
            boolean loaded = c.unloaded == 0;
            if (e != null && e.state == Ledger.State.DONE) {
                if (!loaded || c.todoTotal() == 0 || Jobs.SKIPPED.equals(e.reason)) return ChunkLoop.Verdict.SKIP;   // done earlier; the world is not looked at where it is not loaded
                Util.msg("Chunk " + b.text() + " was done, but " + c.todoTotal() + " blocks are missing now: building it again.");
            } else if (loaded && c.todoTotal() == 0) {
                return ChunkLoop.Verdict.DONE;
            }
            return ChunkLoop.Verdict.RUN;
        }

        public boolean loaded(Chunker.Box b) { return Schem.count(plan, mode, Minecraft.getInstance().level, b).unloaded == 0; }

        public void arrive(Chunker.Box b) { plan.markOpen(Minecraft.getInstance().level, b); }

        public void run(Chunker.Box b) { runChunk(); }

        public void resumeWork() { startPhase(); }

        public int cells(Chunker.Box b) { return plan.cells(b); }

        public void allDone() { finishChunks(); }

        public boolean damaged(Chunker.Box b) {
            Schem.Counts c = Schem.count(plan, mode, Minecraft.getInstance().level, b);
            return c.unloaded == 0 && c.todoTotal() > 0;
        }

        public net.minecraft.core.BlockPos walkTarget(Chunker.Box b) { return plan.standBeside(b); }

        public void walking(int attempt) {
            // the first walk to a chunk never breaks anything: the way leads past what is built already. A second try may.
            if (!changed) return;
            if (attempt == 0 && walkBreak == null) walkBreak = dev.buildtools.Api.setOwnBreak(false);
            else if (attempt != 0 && walkBreak != null) { dev.buildtools.Api.setOwnBreak(walkBreak); walkBreak = null; }
        }
    });

    static int chunkSize() { return cfg.chunkSize >= 8 ? cfg.chunkSize : Chunker.DEFAULT_SIZE; }

    /** true while the engine waits at a chunk for the script's "on chunk_needs" block (the getter chunk_needs). */
    boolean needs() { return loop.needs() && (working || paused); }

    {
        // throwaway blocks Baritone places where the schematic wants something else are scaffolding: let buildtools
        // record them, so collect_scaffold() can take them down after the build
        dev.buildtools.Api.scaffoldOutsideSchematic = (pos, block) -> {
            // true = the block is OUTSIDE the schematic (scaffolding, if Baritone placed it). With no plan for this
            // world nothing is wanted, so everything counts as outside.
            Plan p = plan;   // the last build's plan stays: its blocks are never scaffolding, even after the build
            if (p == null || planWorld != Minecraft.getInstance().level) return true;
            var want = p.at(pos.getX() - p.min.getX(), pos.getY() - p.min.getY(), pos.getZ() - p.min.getZ());
            return want == null || want.getBlock() != block;
        };
    }

    {
        // Baritone must not break a block the plan wants and that is right already, in any chunk (a walk past a finished
        // chunk, a path through a pillar). The builder only breaks wrong blocks, so it is not in the way.
        dev.buildtools.Api.keepBlock = pos -> {
            Plan p = plan;
            Minecraft mc = Minecraft.getInstance();
            if (!(working || paused) || p == null || mc.level == null || planWorld != mc.level) return false;
            return keeps(p, pos, mc.level.getBlockState(pos));
        };
    }

    /** The block at pos is what the plan wants there (and the cell is part of the build). */
    static boolean keeps(Plan p, net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState cur) {
        int x = pos.getX() - p.min.getX(), y = pos.getY() - p.min.getY(), z = pos.getZ() - p.min.getZ();
        var want = p.at(x, y, z);
        if (want == null || want.isAir() || (p.excluded != null && p.excluded.get(p.idx(x, y, z)))) return false;
        return Schem.norm(cur).equals(want);
    }

    boolean working() { return working; }

    String failure() { return failure; }

    double result() { return result; }

    Plan plan() { return plan; }

    /** Starts building (from the chat); returns an error text, "" when there is nothing to do, or null when it started. */
    String begin(List<SchemSource.Placement> pls, Mode mode) {
        return begin(pls, mode, 0, "chat", false);
    }

    /**
     * Starts building. line and args name the action in the job ledger (the script line and the placement/mode words);
     * scripted: it runs inside a script, so a second schem_build on the same line is a new action (#2).
     */
    String begin(List<SchemSource.Placement> pls, Mode mode, int line, String args, boolean scripted) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return "Not in a world.";
        if (working) return "A schematic is already being built. /schem stop ends it.";
        String[] err = new String[1];
        Plan p = Schem.plan(pls, err);
        if (p == null) return err[0];
        chunked = false;
        loop.idle();
        List<Chunker.Box> cs = Chunker.split(p.box(), chunkSize());
        if (cs.size() > 1) return beginChunked(p, mode, cs, line, args, scripted);
        p.markOpen(mc.level);
        Schem.Counts c = Schem.count(p, mode, mc.level);
        choosePhases(c, mode);
        round = 0;
        plan = p;
        planWorld = Minecraft.getInstance().level;
        this.mode = mode;
        for (String n : p.notes) Util.msg("Note: " + n);
        if (!p.fluids.isEmpty()) Util.msg("Fluids are not built yet (" + p.fluids + "); they are left out.");
        if (c.unloaded > 0) Util.msg("Note: " + c.unloaded + " blocks are in unloaded chunks; they are built when you get there (run it again then).");
        if (phases.isEmpty()) {
            Util.msg(p.name + " (" + mode.label() + "): nothing to do, everything that counts is already right.");
            result = 0;
            return "";
        }
        Util.msg("Building " + p.name + " (" + mode.label() + "), " + c.todoTotal() + " blocks to do in " + phases.size() + " phase" + (phases.size() == 1 ? "" : "s") + ".");
        pi = 0;
        phaseResult.clear();
        stuck.clear();
        failure = null;
        paused = false;
        startedAt = System.currentTimeMillis();
        apply();
        working = true;
        startPhase();
        return null;
    }

    private int round;   // 0: the first time through the phases, 1: once more for what the later phases left (scaffolding ...)

    private void choosePhases(Schem.Counts c, Mode mode) {
        phases.clear();
        for (Phase ph : new Phase[]{Phase.CLEAR, Phase.FULL, Phase.SHAPED, Phase.ORIENTED, Phase.ATTACHED, Phase.REDSTONE, Phase.POWER}) {
            if (c.todoByPhase.getOrDefault(ph, 0) > 0 && (ph != Phase.CLEAR || mode != Mode.ADD)) phases.add(ph);
        }
    }

    private void apply() {
        Settings s = BaritoneAPI.getSettings();
        oldLayers = s.buildInLayers.value;
        oldOrder = s.layerOrder.value;
        oldStart = s.startAtLayer.value;
        oldAbove = s.goalBreakFromAbove.value;
        oldDir = s.buildIgnoreDirection.value;
        oldSubs = s.buildSubstitutes.value;
        oldIgnore = s.buildIgnoreProperties.value;
        oldInventory = s.allowInventory.value;
        s.allowInventory.value = true;   // else the builder only uses what is in the hotbar
        var ign = new java.util.ArrayList<>(oldIgnore);
        for (String n : new String[]{"shape", "north", "east", "south", "west", "up", "down", "power", "waterlogged"}) if (!ign.contains(n)) ign.add(n);
        s.buildIgnoreProperties.value = ign;
        var subs = new java.util.HashMap<>(oldSubs);
        // a torch item placed on a wall side gives a wall torch: let Baritone use it for the wall variants
        for (String[] kv : new String[][]{{"wall_torch", "torch"}, {"soul_wall_torch", "soul_torch"}, {"redstone_wall_torch", "redstone_torch"}}) {
            var from = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.parse(kv[0]));
            var to = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.parse(kv[1]));
            subs.put(from, java.util.List.of(to));
        }
        s.buildSubstitutes.value = subs;
        // Layers make Baritone finish the bottom layer before it touches the next one. With a long build that never
        // happens: one unreachable cell down there (under water, boxed in) and it searches paths to it for ever and
        // nothing above gets built. Add mode never breaks anything, and a block can only be placed against another
        // one, so it still goes up from below without them. Fix and exact mode keep them (break order matters there).
        s.buildInLayers.value = mode != Mode.ADD;
        s.startAtLayer.value = 0;
        if (mode != Mode.ADD) breakBefore = dev.buildtools.Api.setOwnBreak(true);
        changed = true;
    }

    private void restore() {
        if (!changed) return;
        Settings s = BaritoneAPI.getSettings();
        s.buildInLayers.value = oldLayers;
        s.layerOrder.value = oldOrder;
        s.startAtLayer.value = oldStart;
        s.goalBreakFromAbove.value = oldAbove;
        s.buildIgnoreDirection.value = oldDir;
        s.buildSubstitutes.value = oldSubs;
        s.buildIgnoreProperties.value = oldIgnore;
        s.allowInventory.value = oldInventory;
        if (walkBreak != null) dev.buildtools.Api.setOwnBreak(walkBreak);
        walkBreak = null;
        if (breakBefore != null) dev.buildtools.Api.setOwnBreak(breakBefore);
        breakBefore = null;
        changed = false;
    }

    private void startPhase() {
        Phase ph = phases.get(pi);
        BaritoneAPI.getSettings().layerOrder.value = ph == Phase.CLEAR;   // true = top-down
        BaritoneAPI.getSettings().goalBreakFromAbove.value = ph == Phase.CLEAR || oldAbove;
        int todo = 0;
        Schem.Counts c = Schem.count(plan, mode, Minecraft.getInstance().level, loop.box());
        todo = c.todoByPhase.getOrDefault(ph, 0);
        lastTodo = todo;
        lastPlaced = 0;
        since = 0;
        noProgress = 0;
        Util.msg("Phase " + (pi + 1) + "/" + phases.size() + ": " + ph.label + ", " + todo + " blocks" + (ph == Phase.CLEAR ? " (top-down, our own digging)" : " (bottom-up)") + ".");
        if (ph == Phase.ORIENTED || ph == Phase.CLEAR) {
            Util.baritone().getPathingBehavior().cancelEverything();
            placer.begin(plan, mode, ph, loop.box());
            return;
        }
        neededItems.clear();
        neededItems.addAll(c.items.keySet());
        refreshMissing();
        restarts = 0;
        Schem.Wrapper w = new Schem.Wrapper(plan, mode, ph, skipMissing ? () -> missingNow : null, loop.box());
        Util.baritone().getBuilderProcess().build("schem " + plan.name + " " + ph.name().toLowerCase(), w, w.origin());
    }

    // ------------------------------------------------------------------ skip blocks without items

    /** true: blocks whose item is not in the inventory are left out and the rest is built; false: all or nothing. */
    private boolean skipMissing = true;
    private final java.util.Set<net.minecraft.world.item.Item> neededItems = new java.util.HashSet<>();
    private volatile java.util.Set<net.minecraft.world.item.Item> missingNow = java.util.Set.of();
    private int restarts;

    void skipMissing(boolean v) { skipMissing = v; }

    /** Cheap: only the items this phase needs are looked up in the inventory. Returns true when the set changed. */
    private boolean refreshMissing() {
        java.util.Set<net.minecraft.world.item.Item> m = new java.util.HashSet<>();
        for (var it : neededItems) if (Schem.have(it) == 0) m.add(it);
        if (m.equals(missingNow)) return false;
        missingNow = m;
        return true;
    }

    /** "stone x5, gravel x3" for the blocks of this phase left out for want of items. */
    private String skippedText(Phase ph) {
        if (!skipMissing) return "";
        Schem.Counts c = Schem.count(plan, mode, Minecraft.getInstance().level, loop.box());
        StringBuilder sb = new StringBuilder();
        for (var e : c.items.entrySet()) {
            if (Schem.have(e.getKey()) > 0) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(Schem.itemName(e.getKey())).append(" x").append(e.getValue());
        }
        return sb.toString();
    }

    void tick() {
        if (!working) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (chunked && loop.waiting()) { loop.tick(mc); return; }
        if (++since < FIRST_CHECK) return;
        IBuilderProcess b = Util.baritone().getBuilderProcess();
        Phase ph = phases.get(pi);
        if (ph == Phase.ORIENTED || ph == Phase.CLEAR) {
            if (since % STALL_STEP == 0) {
                int pl = placer.placed() + placer.failures.size();
                if (pl > lastPlaced) { lastPlaced = pl; noProgress = 0; } else noProgress += STALL_STEP;
                if (noProgress >= STALL_TICKS * 2) { placer.failures.add("no progress for " + noProgress / 20 + " s"); placer.stop(); endPhase(ph, "no progress"); return; }
            }
            if (placer.tick()) {
                for (String f : placer.failures) Util.msg("  oriented: " + f);
                endPhase(ph, placer.failures.isEmpty() ? null : placer.failures.size() + " could not be placed");
            }
            return;
        }
        boolean itemsChanged = skipMissing && refreshMissing();
        if (b.isPaused()) {
            Util.baritone().getPathingBehavior().cancelEverything();
            // an item just ran out: start the phase again without those blocks (a few times at most)
            if (skipMissing && (itemsChanged || restarts == 0) && restarts < 5) {
                restarts++;
                int r = restarts;
                since = 0;
                startPhase();
                restarts = r;
                return;
            }
            endPhase(ph, "the builder gave up (missing materials, or blocks it cannot reach)");
            return;
        }
        if (!b.isActive()) {
            endPhase(ph, null);
            return;
        }
        // watchdog: the number of blocks still to do must keep falling, else the phase ends and is reported
        if (since % STALL_STEP == 0) {
            int todo = Schem.count(plan, mode, mc.level, loop.box()).todoByPhase.getOrDefault(ph, 0);
            if (todo < lastTodo) noProgress = 0;
            else noProgress += STALL_STEP;
            lastTodo = todo;
            if (noProgress >= STALL_TICKS) {
                Util.baritone().getPathingBehavior().cancelEverything();
                endPhase(ph, "no progress for " + noProgress / 20 + " s");
            }
        }
    }

    private void endPhase(Phase ph, String why) {
        Schem.Counts c = Schem.count(plan, mode, Minecraft.getInstance().level, loop.box());
        int left = c.todoByPhase.getOrDefault(ph, 0);
        if (left > 0 && why == null && skipMissing) {
            String sk = skippedText(ph);
            if (!sk.isEmpty()) why = "skipped, no items: " + sk;
        }
        phaseResult.put(ph, left == 0 ? "done" : left + " left" + (why != null ? " (" + why + ")" : ""));
        if (left > 0) stuck.add(ph.label + ": " + left + " left" + (why != null ? " - " + why : ""));
        Util.msg("Phase " + ph.label + ": " + phaseResult.get(ph) + ".");
        if (++pi >= phases.size()) {
            // Once more: a block a later phase was in the way of (water, scaffolding Baritone put into a cell of the
            // schematic) can be done now. Only when the first round got something done, so a hopeless rest ends quickly.
            int done = 0;
            for (var e : phaseResult.entrySet()) if ("done".equals(e.getValue())) done++;
            if (round == 0 && c.todoTotal() > 0 && done > 0) {
                List<Phase> before = new ArrayList<>(phases);
                choosePhases(c, mode);
                if (!phases.isEmpty()) {
                    round = 1;
                    pi = 0;
                    stuck.clear();
                    phaseResult.clear();
                    Util.msg("Once more for " + c.todoTotal() + " blocks left after the first round.");
                    since = 0;
                    startPhase();
                    return;
                }
                phases.clear();
                phases.addAll(before);
            }
            if (chunked) chunkDone(c);
            else finish(c);
        } else {
            since = 0;
            startPhase();
        }
    }

    // ------------------------------------------------------------------ the chunk loop

    private String beginChunked(Plan p, Mode mode, List<Chunker.Box> cs, int line, String args, boolean scripted) {
        Minecraft mc = Minecraft.getInstance();
        round = 0;
        plan = p;
        planWorld = mc.level;
        this.mode = mode;
        for (String n : p.notes) Util.msg("Note: " + n);
        if (!p.fluids.isEmpty()) Util.msg("Fluids are not built yet (" + p.fluids + "); they are left out.");
        Jobs.Node action = Jobs.action("schem_build", line, args, scripted);
        String sig = p.min.getX() + "," + p.min.getY() + "," + p.min.getZ() + " " + p.sx + "x" + p.sy + "x" + p.sz + " /" + chunkSize();
        int done = loop.init(cs, action, sig);
        Util.msg("Building " + p.name + " (" + mode.label() + ") in " + cs.size() + " chunks of up to " + chunkSize() + "x" + chunkSize() + " blocks"
                + (done > 0 ? " (" + done + " done in an earlier run)" : "") + ".");
        phases.clear();
        pi = 0;
        phaseResult.clear();
        stuck.clear();
        failure = null;
        paused = false;
        startedAt = System.currentTimeMillis();
        apply();
        working = true;
        chunked = true;
        if (!loop.start()) {
            Util.msg(p.name + " (" + mode.label() + "): nothing to do, every chunk is done.");
            endChunks();
            result = 0;
            return "";
        }
        return null;
    }

    /** At the chunk, materials fetched (or none asked for): count, pick the phases, go. */
    private void runChunk() {
        Minecraft mc = Minecraft.getInstance();
        Chunker.Box box = loop.box();
        Schem.Counts c = Schem.count(plan, mode, mc.level, box);
        choosePhases(c, mode);
        round = 0;
        pi = 0;
        phaseResult.clear();
        stuck.clear();
        loop.running(c.todoTotal());
        if (phases.isEmpty()) { chunkDone(c); return; }
        Util.msg("Chunk " + box.text() + ": " + c.todoTotal() + " blocks to do in " + phases.size() + " phase" + (phases.size() == 1 ? "" : "s") + ".");
        since = 0;
        startPhase();
    }

    /** The phases of the chunk are through: DONE, one retry, or DEFERRED. */
    private void chunkDone(Schem.Counts c) {
        loop.finished(c.todoTotal(), () -> (stuck.isEmpty() ? "blocks are still wrong" : String.join("; ", stuck)) + (c.examples.isEmpty() ? "" : "; e.g. " + c.examples.get(0)));
    }

    private void endChunks() {
        working = false;
        restore();
        loop.stop();
        chunked = false;
    }

    private void finishChunks() {
        endChunks();
        result = loop.report(plan.name + " (" + mode.label() + ")", startedAt, "/schem check shows what is left; /queue jobs lists the chunks; /queue retry <job> opens them again.");
    }

    /**
     * The items for the next n chunks (the one the bot is at included): from the world where it is loaded, from the plan
     * alone where it is not. The getter chunk_needed(n); empty when no chunked build runs.
     */
    Map<net.minecraft.world.item.Item, Integer> chunkNeeded(int n) {
        Minecraft mc = Minecraft.getInstance();
        if (!chunked || plan == null || mc.player == null || mc.level == null) return Map.of();
        Chunker.Box box = loop.box();
        Chunker.Box start = box != null && loop.isOpen(box) ? box : Chunker.next(loop.chunks(), mc.player.blockPosition(), loop::isOpen);
        if (start == null) return Map.of();
        return Schem.needed(plan, mode, Schem.World.of(mc.level), Chunker.upcoming(loop.chunks(), start, Math.max(1, n), loop::isOpen));
    }

    private void finish(Schem.Counts c) {
        working = false;
        restore();
        result = c.todoTotal();
        long secs = (System.currentTimeMillis() - startedAt) / 1000;
        Util.msg("Finished " + plan.name + " (" + mode.label() + ") in " + secs + " s: " + (c.todoTotal() == 0 ? "everything is as it should be." : c.todoTotal() + " blocks still wrong."));
        for (String s : stuck) Util.msg("  stuck - " + s);
        for (String e : c.examples) Util.msg("  " + e);
        if (c.todoTotal() > 0) Util.msg("/schem check shows what is left.");
    }

    void cancel() {
        boolean wasPaused = paused;
        paused = false;
        loop.idle();
        if (!working && !wasPaused) return;
        boolean was = working;
        working = false;
        placer.stop();
        Util.baritone().getPathingBehavior().cancelEverything();
        restore();
        if (chunked) loop.interrupted();   // a rerun picks it up again
        chunked = false;
        if (was) Util.msg("Schematic building stopped.");
    }

    /** An "on" block takes over: stop, keep the plan. */
    void suspendRun() {
        if (!working) return;
        working = false;
        paused = true;
        placer.stop();
        Util.baritone().getPathingBehavior().cancelEverything();
        restore();
    }

    /** After the "on" block: the same phase again (the builder looks at the world afresh). */
    String carryOn() {
        if (!paused || plan == null) return "";
        paused = false;
        apply();
        working = true;
        Util.msg("Carrying on with " + plan.name + ".");
        if (chunked) {
            loop.carryOn();
            return null;
        }
        startPhase();
        return null;
    }

    String status() {
        if (!working) return "No schematic is being built.";
        if (chunked && loop.waiting()) return "Building " + plan.name + " (" + mode.label() + "), chunk " + loop.index() + "/" + loop.chunks().size() + ": " + loop.step().name().toLowerCase() + ".";
        return "Building " + plan.name + " (" + mode.label() + ")" + (chunked ? ", chunk " + loop.index() + "/" + loop.chunks().size() : "") + ", phase " + (pi + 1) + "/" + phases.size() + ": " + phases.get(pi).label + ".";
    }

    /** The current chunk as "i/n" for the display; null when the build is not chunked. */
    String chunkText() {
        return chunked && working ? loop.index() + "/" + loop.chunks().size() : null;
    }
}
