package dev.baritonequeue;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.process.IBuilderProcess;
import dev.baritonequeue.Schem.Mode;
import dev.baritonequeue.Schem.Phase;
import dev.baritonequeue.Schem.Plan;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
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
    private Boolean breakBefore;

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

    boolean working() { return working; }

    String failure() { return failure; }

    double result() { return result; }

    Plan plan() { return plan; }

    /** Starts building; returns an error text, "" when there is nothing to do, or null when it started. */
    String begin(List<SchemSource.Placement> pls, Mode mode) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return "Not in a world.";
        if (working) return "A schematic is already being built. /schem stop ends it.";
        String[] err = new String[1];
        Plan p = Schem.plan(pls, err);
        if (p == null) return err[0];
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
        giveUp = java.util.Set.of();
        stallsInRow = 0;
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
        s.buildInLayers.value = true;   // bottom-up; a layer that cannot be finished is given up on after a stall (see giveUpLayer)
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
        if (breakBefore != null) dev.buildtools.Api.setOwnBreak(breakBefore);
        breakBefore = null;
        changed = false;
    }

    private void startPhase() {
        Phase ph = phases.get(pi);
        BaritoneAPI.getSettings().layerOrder.value = ph == Phase.CLEAR;   // true = top-down
        BaritoneAPI.getSettings().goalBreakFromAbove.value = ph == Phase.CLEAR || oldAbove;
        int todo = 0;
        Schem.Counts c = Schem.count(plan, mode, Minecraft.getInstance().level);
        todo = c.todoByPhase.getOrDefault(ph, 0);
        lastTodo = todo;
        attemptTodo = todo;
        lastPlaced = 0;
        since = 0;
        noProgress = 0;
        Util.msg("Phase " + (pi + 1) + "/" + phases.size() + ": " + ph.label + ", " + todo + " blocks" + (ph == Phase.CLEAR ? " (top-down, our own digging)" : " (bottom-up)") + ".");
        if (ph == Phase.ORIENTED || ph == Phase.CLEAR) {
            Util.baritone().getPathingBehavior().cancelEverything();
            placer.begin(plan, mode, ph);
            return;
        }
        neededItems.clear();
        neededItems.addAll(c.items.keySet());
        refreshMissing();
        restarts = 0;
        Util.baritone().getBuilderProcess().build("schem " + plan.name + " " + ph.name().toLowerCase(),
                new Schem.Wrapper(plan, mode, ph, skipMissing ? () -> missingNow : null, i -> giveUp.contains(i)), plan.min);
    }

    // ------------------------------------------------------------------ skip blocks without items

    /** true: blocks whose item is not in the inventory are left out and the rest is built; false: all or nothing. */
    private boolean skipMissing = true;
    private final java.util.Set<net.minecraft.world.item.Item> neededItems = new java.util.HashSet<>();
    private volatile java.util.Set<net.minecraft.world.item.Item> missingNow = java.util.Set.of();
    private int restarts;
    private int attemptTodo;   // blocks to do when the builder was last started for this phase
    /** Cells (plan indices) the builder is told to ignore: it stalled on them. Read by Baritone's path thread: replaced, never changed. */
    private volatile java.util.Set<Integer> giveUp = java.util.Set.of();
    private int stallsInRow;

    /**
     * Baritone finishes a layer before it starts the next. A cell in it that it cannot reach (boxed in, no spot to stand
     * on, no side to click) keeps it pathing for ever and nothing above is built: on the canal run thousands of blocks
     * "stalled" behind a few cells of the bottom row. After a stall the cells of the lowest unfinished layer are given
     * up (the builder ignores them, the layers above get built) and tried once more at the end. Returns false when
     * there is nothing to give up or it keeps stalling without any progress.
     */
    private boolean giveUpLayer(Phase ph, int todo) {
        if (todo < attemptTodo) stallsInRow = 0;
        else if (++stallsInRow > 3) return false;
        Minecraft mc = Minecraft.getInstance();
        int low = Integer.MAX_VALUE;
        List<Integer> cells = new ArrayList<>();
        var miss = missingNow;
        net.minecraft.core.BlockPos.MutableBlockPos mp = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (int y = 0; y < plan.sy; y++) for (int z = 0; z < plan.sz; z++) for (int x = 0; x < plan.sx; x++) {
            int i = plan.idx(x, y, z);
            var d = plan.st[i];
            if (d == null || d.isAir() || plan.excluded.get(i) || giveUp.contains(i)) continue;
            mp.set(plan.min.getX() + x, plan.min.getY() + y, plan.min.getZ() + z);
            if (!mc.level.hasChunkAt(mp)) continue;
            var cur = Schem.norm(mc.level.getBlockState(mp));
            if (cur.equals(d) || Schem.phaseAt(plan, i, d, cur) != ph || !Schem.todo(plan, i, mode, d, cur)) continue;
            Object[] cost = Schem.cost(d);
            if (skipMissing && cost != null && miss.contains((net.minecraft.world.item.Item) cost[0])) continue;   // left out for want of items, not stuck
            if (y < low) { low = y; cells.clear(); }
            if (y == low) cells.add(i);
        }
        if (cells.isEmpty()) return false;
        var next = new java.util.HashSet<>(giveUp);
        next.addAll(cells);
        giveUp = next;
        Util.msg("Phase " + ph.label + ": " + todo + " left, no progress for " + STALL_TICKS / 20 + " s: giving up on " + cells.size() + " blocks of the layer at y " + (plan.min.getY() + low) + " for now, building the rest.");
        return true;
    }

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
        Schem.Counts c = Schem.count(plan, mode, Minecraft.getInstance().level);
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
            int todo = Schem.count(plan, mode, mc.level).todoByPhase.getOrDefault(ph, 0);
            if (todo < lastTodo) noProgress = 0;
            else noProgress += STALL_STEP;
            lastTodo = todo;
            if (noProgress >= STALL_TICKS) {
                Util.baritone().getPathingBehavior().cancelEverything();
                if (giveUpLayer(ph, todo)) {
                    since = 0;
                    int r = restarts;
                    startPhase();
                    restarts = r;
                    return;
                }
                endPhase(ph, "no progress for " + noProgress / 20 + " s");
            }
        }
    }

    private void endPhase(Phase ph, String why) {
        Schem.Counts c = Schem.count(plan, mode, Minecraft.getInstance().level);
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
            if (round == 0 && c.todoTotal() > 0 && (done > 0 || !giveUp.isEmpty())) {
                List<Phase> before = new ArrayList<>(phases);
                choosePhases(c, mode);
                if (!phases.isEmpty()) {
                    round = 1;
                    giveUp = java.util.Set.of();   // the cells given up on get one more try
                    stallsInRow = 0;
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
            finish(c);
        } else {
            since = 0;
            startPhase();
        }
    }

    private void finish(Schem.Counts c) {
        working = false;
        restore();
        result = c.todoTotal();
        long secs = (System.currentTimeMillis() - startedAt) / 1000;
        Util.msg("Finished " + plan.name + " (" + mode.label() + ") in " + secs + " s: " + (c.todoTotal() == 0 ? "everything is as it should be." : c.todoTotal() + " blocks still wrong."));
        for (String s : stuck) Util.msg("  stuck - " + s);
        refreshLedger();
        if (c.todoTotal() > 0) Util.msg("  " + breakdown(Schem.leftCells(plan, mode, Minecraft.getInstance().level), "of this build not built"));
        for (String e : c.examples) Util.msg("  " + e);
        if (c.todoTotal() > 0) Util.msg("/schem check shows what is left.");
    }

    // ------------------------------------------------------------------ what is left, over all builds

    /**
     * Blocks that were left unbuilt, with the reason, over all builds of the same schematic (a canal is built in
     * sections: the count of one build only sees what is loaded around it, the rest is remembered here).
     */
    private final Map<Long, Schem.Why> ledger = new LinkedHashMap<>();
    private String ledgerKey;

    private String keyOf(Plan p) { return p.name + "@" + p.min + "+" + p.sx + "x" + p.sy + "x" + p.sz + "/" + mode; }

    /** Looks at the loaded blocks again; blocks in unloaded chunks keep what was recorded last. */
    private void refreshLedger() {
        Minecraft mc = Minecraft.getInstance();
        if (plan == null || mc.level == null) return;
        String key = keyOf(plan);
        if (!key.equals(ledgerKey)) { ledger.clear(); ledgerKey = key; }
        net.minecraft.core.BlockPos.MutableBlockPos mp = new net.minecraft.core.BlockPos.MutableBlockPos();
        ledger.keySet().removeIf(k -> mc.level.hasChunkAt(mp.set(k)));
        ledger.putAll(Schem.leftCells(plan, mode, mc.level));
    }

    /**
     * A fresh plan (schem_check, schem_materials) does not know which cells were open when the last build started, so in
     * add mode it called a block that Baritone's scaffolding left in a cell "foreign, leave alone" and counted fewer
     * blocks than the build did. The same placements as the last build take that memory over.
     */
    void inheritOpen(Plan fresh) {
        Plan last = plan;
        if (last != null && last.open != null && planWorld == Minecraft.getInstance().level && last.min.equals(fresh.min)
                && last.sx == fresh.sx && last.sy == fresh.sy && last.sz == fresh.sz) fresh.open = last.open;
    }

    private Map<Long, Schem.Why> ledgerOfPlan() { return ledger; }

    /** "120 blocks not built: 100 no material with you (stone x80, gravel x20), 20 nothing to place against". */
    static String breakdown(Map<Long, Schem.Why> cells, String what) {
        Map<Schem.Reason, Integer> n = new java.util.EnumMap<>(Schem.Reason.class);
        Map<String, Integer> items = new LinkedHashMap<>();
        for (Schem.Why w : cells.values()) {
            n.merge(w.reason(), 1, Integer::sum);
            if (w.item() != null) items.merge(w.item(), 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder(cells.size() + " blocks " + what);
        String sep = ": ";
        for (var e : n.entrySet()) {
            sb.append(sep).append(e.getValue()).append(' ').append(e.getKey().label);
            if (e.getKey() == Schem.Reason.NO_ITEM) sb.append(" (").append(items.entrySet().stream().map(i -> i.getKey() + " x" + i.getValue()).collect(java.util.stream.Collectors.joining(", "))).append(')');
            sep = "; ";
        }
        return sb.toString();
    }

    /** schem_left(): prints and returns how many blocks of the last built schematic are still not there, over all builds, and why. */
    int reportLeft(boolean reset) {
        if (reset) { ledger.clear(); ledgerKey = null; Util.msg("The list of unbuilt blocks is cleared."); return 0; }
        if (plan == null) { Util.msg("Nothing has been built yet."); return 0; }
        refreshLedger();
        if (ledger.isEmpty()) { Util.msg("Nothing is left: every block that was built (or seen) is there."); return 0; }
        Util.msg(breakdown(ledger, "are NOT built") + ".");
        int shown = 0;
        for (var e : ledger.entrySet()) {
            if (shown++ >= 6) break;
            net.minecraft.core.BlockPos q = net.minecraft.core.BlockPos.of(e.getKey());
            var want = plan.at(q.getX() - plan.min.getX(), q.getY() - plan.min.getY(), q.getZ() - plan.min.getZ());
            Util.msg("  " + q.getX() + " " + q.getY() + " " + q.getZ() + ": " + (want == null ? "?" : net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(want.getBlock()).getPath()) + " - " + e.getValue().reason().label);
        }
        return ledger.size();
    }

    void cancel() {
        paused = false;
        if (!working) return;
        working = false;
        placer.stop();
        Util.baritone().getPathingBehavior().cancelEverything();
        restore();
        Util.msg("Schematic building stopped.");
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
        startPhase();
        return null;
    }

    String status() {
        if (!working) return "No schematic is being built.";
        return "Building " + plan.name + " (" + mode.label() + "), phase " + (pi + 1) + "/" + phases.size() + ": " + phases.get(pi).label + ".";
    }
}
