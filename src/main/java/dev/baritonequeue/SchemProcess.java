package dev.baritonequeue;

import dev.baritonequeue.Schem.Mode;
import dev.baritonequeue.Schem.Phase;
import dev.baritonequeue.Schem.Plan;
import dev.midtown.api.EngineSelector;
import dev.midtown.api.EngineStatus;
import dev.midtown.api.GoalSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private boolean changed;
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

    private int lastPlaced;

    private void apply() {
        if (mode != Mode.ADD) breakBefore = dev.buildtools.Api.setOwnBreak(true);
        changed = true;
    }

    private void restore() {
        if (!changed) return;
        if (breakBefore != null) dev.buildtools.Api.setOwnBreak(breakBefore);
        breakBefore = null;
        changed = false;
    }

    private static boolean isMidtown() {
        return EngineSelector.ENGINE_MIDTOWN.equals(EngineSelector.selectedName());
    }

    // Midtown-native builder state (Phase.FULL, SHAPED, ATTACHED, REDSTONE, POWER)
    private static final double MT_REACH = 4.3;
    private static final int MT_MOVE_TIMEOUT = 20 * 20;
    private static final int MT_NO_PROGRESS = 20 * 8;
    private static final int MT_BREAK_TIMEOUT = 20 * 8;
    private final List<BlockPos> mtQueue = new ArrayList<>();
    private final Set<BlockPos> mtDeferred = new HashSet<>();
    private final Set<BlockPos> mtBadSpots = new HashSet<>();
    private final Set<Integer> mtRetriedLayers = new HashSet<>();
    private BlockPos mtCur, mtStand, mtBreakPos;
    private BlockState mtWant;
    private Item mtItem;
    private boolean mtMoving, mtBreaking;
    private int mtMoveTicks, mtNoProgress, mtBreakTicks, mtPlaced, mtStallPass;
    private double mtBestDist;

    private void startPhase() {
        Phase ph = phases.get(pi);
        Schem.Counts c = Schem.count(plan, mode, Minecraft.getInstance().level);
        int todo = c.todoByPhase.getOrDefault(ph, 0);
        lastTodo = todo;
        attemptTodo = todo;
        lastPlaced = 0;
        since = 0;
        noProgress = 0;
        Util.msg("Phase " + (pi + 1) + "/" + phases.size() + ": " + ph.label + ", " + todo + " blocks" + (ph == Phase.CLEAR ? " (top-down, our own digging)" : " (bottom-up)") + ".");
        if (ph == Phase.ORIENTED || ph == Phase.CLEAR) {
            EngineSelector.current().cancel();
            placer.begin(plan, mode, ph);
            return;
        }
        neededItems.clear();
        neededItems.addAll(c.items.keySet());
        refreshMissing();
        restarts = 0;
        EngineSelector.current().cancel();
        mtStartPhase(ph);
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
        mtTickPhase(mc, ph, itemsChanged);
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
        mtStop();
        EngineSelector.current().cancel();
        restore();
        Util.msg("Schematic building stopped.");
    }

    /** An "on" block takes over: stop, keep the plan. */
    void suspendRun() {
        if (!working) return;
        working = false;
        paused = true;
        placer.stop();
        mtStop();
        EngineSelector.current().cancel();
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

    // ------------------------------------------------------------------ Midtown native builder

    private void mtStartPhase(Phase ph) {
        mtQueue.clear();
        mtDeferred.clear();
        mtBadSpots.clear();
        mtRetriedLayers.clear();
        mtCur = null;
        mtStand = null;
        mtBreakPos = null;
        mtMoving = false;
        mtBreaking = false;
        mtPlaced = 0;
        mtStallPass = 0;
        Level level = Minecraft.getInstance().level;
        if (level == null) return;
        for (int y = 0; y < plan.sy; y++) {
            for (int z = 0; z < plan.sz; z++) {
                for (int x = 0; x < plan.sx; x++) {
                    int idx = plan.idx(x, y, z);
                    BlockState d = plan.st[idx];
                    if (d == null || d.isAir() || plan.excluded.get(idx)) continue;
                    BlockPos w = plan.world(x, y, z);
                    if (!level.hasChunkAt(w)) continue;
                    BlockState cur = Schem.norm(level.getBlockState(w));
                    if (Schem.phaseAt(plan, idx, d, cur) == ph
                            && Schem.todo(plan, idx, mode, d, cur)
                            && Schem.cost(d) != null) {
                        mtQueue.add(w);
                    }
                }
            }
        }
    }

    private void mtStop() {
        if (mtMoving || isMidtown()) {
            EngineSelector.current().cancel();
            mtMoving = false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mtBreaking && mc.gameMode != null) {
            mc.gameMode.stopDestroyBlock();
        }
        mtBreaking = false;
        mtBreakPos = null;
        mtCur = null;
        mtStand = null;
    }

    private void mtTickPhase(Minecraft mc, Phase ph, boolean itemsChanged) {
        LocalPlayer p = mc.player;
        Level level = mc.level;
        if (p == null || level == null || mc.gameMode == null) return;

        if (mtBreaking && mtBreakPos != null) {
            BlockState bs = level.getBlockState(mtBreakPos);
            if (bs.isAir() || Schem.replaceable(bs)) {
                mtBreaking = false;
                mtBreakPos = null;
            } else if (++mtBreakTicks > MT_BREAK_TIMEOUT) {
                mc.gameMode.stopDestroyBlock();
                mtBreaking = false;
                mtBreakPos = null;
                if (mtCur != null) mtDeferred.add(mtCur);
                mtCur = null;
                return;
            } else {
                mtAim(p, Vec3.atCenterOf(mtBreakPos));
                ClearProcess.equipFor(mc, p, bs);
                mc.gameMode.continueDestroyBlock(mtBreakPos, Direction.UP);
                p.swing(InteractionHand.MAIN_HAND);
                return;
            }
        }

        if (mtCur != null) {
            int idx = plan.idx(mtCur.getX() - plan.min.getX(), mtCur.getY() - plan.min.getY(), mtCur.getZ() - plan.min.getZ());
            BlockState now = Schem.norm(level.getBlockState(mtCur));
            if (!Schem.todo(plan, idx, mode, mtWant, now)) {
                mtPlaced++;
                mtRetriedLayers.remove(mtCur.getY());
                noProgress = 0;
                mtCur = null;
                mtStand = null;
                if (mtMoving) {
                    EngineSelector.current().cancel();
                    mtMoving = false;
                }
            } else if (skipMissing && mtItem != null && Schem.have(mtItem) == 0) {
                mtCur = null;
                mtStand = null;
                if (mtMoving) {
                    EngineSelector.current().cancel();
                    mtMoving = false;
                }
            }
        }

        if (mtCur == null) {
            if (!mtPickNext(level, p, ph)) {
                if (!mtDeferred.isEmpty() && mtStallPass == 0 && mtPlaced > 0) {
                    mtDeferred.clear();
                    mtBadSpots.clear();
                    mtStallPass++;
                    if (!mtPickNext(level, p, ph)) {
                        mtStop();
                        endPhase(ph, null);
                        return;
                    }
                } else {
                    mtStop();
                    endPhase(ph, mtDeferred.isEmpty() ? null : mtDeferred.size() + " blocks unreachable");
                    return;
                }
            }
        }

        if (mtCur == null) return;
        BlockState now = level.getBlockState(mtCur);

        // Wrong block in the way (REPLACE / EXACT mode): break it first
        if (mode != Mode.ADD && !now.isAir() && !Schem.replaceable(now) && !Schem.norm(now).equals(mtWant)) {
            if (p.getEyePosition().distanceTo(Vec3.atCenterOf(mtCur)) <= MT_REACH) {
                if (mtMoving) {
                    EngineSelector.current().cancel();
                    mtMoving = false;
                }
                mtBreakPos = mtCur;
                mtBreaking = true;
                mtBreakTicks = 0;
                mtAim(p, Vec3.atCenterOf(mtCur));
                ClearProcess.equipFor(mc, p, now);
                mc.gameMode.startDestroyBlock(mtCur, Direction.UP);
                p.swing(InteractionHand.MAIN_HAND);
                return;
            }
        }

        // Avoid self-trapping: if standing inside target cell, step out first
        if (p.getBoundingBox().intersects(new AABB(mtCur))) {
            if (!mtMoving) {
                mtStand = mtFindStandSpot(level, p, mtCur);
                if (mtStand != null) {
                    EngineSelector.current().goTo(GoalSpec.exact(mtStand));
                    mtMoving = true;
                    mtMoveTicks = 0;
                    mtNoProgress = 0;
                    mtBestDist = p.getEyePosition().distanceTo(Vec3.atCenterOf(mtCur));
                } else {
                    mtDeferred.add(mtCur);
                    mtCur = null;
                }
            } else {
                mtPollMove(level, p);
            }
            return;
        }

        BlockHitResult hit = mtFindSupportHit(level, p, mtCur);
        if (hit == null && !mtHasSupportNeighbor(level, mtCur)) {
            BlockPos below = mtCur.below();
            BlockState belowState = level.getBlockState(below);
            if ((belowState.isAir() || Schem.replaceable(belowState)) && !p.getBoundingBox().intersects(new AABB(below))) {
                if (mtPlaceScaffold(mc, p, level, below)) return;
            }
        }

        if (hit != null && p.getEyePosition().distanceTo(hit.getLocation()) <= MT_REACH) {
            if (mtMoving) {
                EngineSelector.current().cancel();
                mtMoving = false;
            }
            if (!mtEquip(mc, p, mtItem)) {
                mtDeferred.add(mtCur);
                mtCur = null;
                return;
            }
            if (mtAim(p, hit.getLocation())) {
                mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
                p.swing(InteractionHand.MAIN_HAND);
            }
            return;
        }

        if (!mtMoving) {
            mtStand = mtFindStandSpot(level, p, mtCur);
            BlockPos supportTarget = mtFindSupportNeighbor(level, mtCur);
            GoalSpec goal = mtStand != null
                    ? GoalSpec.exact(mtStand)
                    : GoalSpec.lineOfSight(supportTarget != null ? supportTarget : mtCur, MT_REACH - 0.3);
            EngineSelector.current().goTo(goal);
            mtMoving = true;
            mtMoveTicks = 0;
            mtNoProgress = 0;
            mtBestDist = p.getEyePosition().distanceTo(Vec3.atCenterOf(mtCur));
        } else {
            mtPollMove(level, p);
        }
    }

    private void mtPollMove(Level level, LocalPlayer p) {
        double dist = p.getEyePosition().distanceTo(Vec3.atCenterOf(mtCur));
        if (dist < mtBestDist - 0.5) {
            mtBestDist = dist;
            mtNoProgress = 0;
        } else {
            mtNoProgress++;
        }
        EngineStatus st = EngineSelector.current().status();
        if (++mtMoveTicks > MT_MOVE_TIMEOUT || mtNoProgress > MT_NO_PROGRESS || st == EngineStatus.FAILED) {
            EngineSelector.current().cancel();
            mtMoving = false;
            if (mtStand != null) mtBadSpots.add(mtStand);
            if (mtCur != null) mtDeferred.add(mtCur);
            mtCur = null;
            mtStand = null;
        } else if (st == EngineStatus.DONE) {
            EngineSelector.current().cancel();
            mtMoving = false;
            if (mtCur != null && mtFindSupportHit(level, p, mtCur) == null) {
                mtBadSpots.add(p.blockPosition());
                if (mtStand != null) mtBadSpots.add(mtStand);
                mtDeferred.add(mtCur);
                mtCur = null;
                mtStand = null;
            }
        }
    }

    private static BlockPos mtFindSupportNeighbor(Level level, BlockPos target) {
        for (Direction d : Direction.values()) {
            BlockPos n = target.relative(d);
            BlockState ns = level.getBlockState(n);
            if (!ns.isAir() && !Schem.replaceable(ns)) return n;
        }
        return null;
    }

    private boolean mtPickNext(Level level, LocalPlayer p, Phase ph) {
        Vec3 eye = p.getEyePosition();
        BlockPos best = null;
        BlockState bestWant = null;
        Item bestItem = null;
        double bestScore = Double.MAX_VALUE;
        int minLayerY = Integer.MAX_VALUE;
        int minDeferredY = Integer.MAX_VALUE;

        // First pass: find the lowest unfinished Y layer
        List<BlockPos> candidates = new ArrayList<>();
        var it = mtQueue.iterator();
        while (it.hasNext()) {
            BlockPos w = it.next();
            if (!level.hasChunkAt(w)) continue;
            int idx = plan.idx(w.getX() - plan.min.getX(), w.getY() - plan.min.getY(), w.getZ() - plan.min.getZ());
            BlockState d = plan.st[idx];
            BlockState cur = Schem.norm(level.getBlockState(w));
            if (!Schem.todo(plan, idx, mode, d, cur) || Schem.phaseAt(plan, idx, d, cur) != ph) {
                it.remove();
                continue;
            }
            Object[] cost = Schem.cost(d);
            if (cost == null) { it.remove(); continue; }
            Item item = (Item) cost[0];
            if (skipMissing && Schem.have(item) == 0) continue;
            if (mtDeferred.contains(w)) {
                if (w.getY() < minDeferredY) minDeferredY = w.getY();
                continue;
            }
            if (w.getY() < minLayerY) {
                minLayerY = w.getY();
                candidates.clear();
            }
            if (w.getY() == minLayerY) candidates.add(w);
        }

        // Retry deferred cells on a lower Y layer before advancing to higher Y layers that could enclose them
        if (minDeferredY < minLayerY && !mtRetriedLayers.contains(minDeferredY)) {
            mtRetriedLayers.add(minDeferredY);
            final int retryY = minDeferredY;
            mtDeferred.removeIf(pos -> pos.getY() == retryY);
            mtBadSpots.clear();
            candidates.clear();
            minLayerY = retryY;
            for (BlockPos w : mtQueue) {
                if (w.getY() != retryY) continue;
                int idx = plan.idx(w.getX() - plan.min.getX(), w.getY() - plan.min.getY(), w.getZ() - plan.min.getZ());
                BlockState d = plan.st[idx];
                Object[] cost = Schem.cost(d);
                if (cost == null) continue;
                Item item = (Item) cost[0];
                if (skipMissing && Schem.have(item) == 0) continue;
                candidates.add(w);
            }
        }

        for (BlockPos w : candidates) {
            int idx = plan.idx(w.getX() - plan.min.getX(), w.getY() - plan.min.getY(), w.getZ() - plan.min.getZ());
            BlockState d = plan.st[idx];
            Item item = (Item) Schem.cost(d)[0];
            boolean supported = mtHasSupportNeighbor(level, w);
            boolean overlaps = p.getBoundingBox().intersects(new AABB(w));
            double dist = eye.distanceTo(Vec3.atCenterOf(w));
            boolean inReach = dist <= MT_REACH && !overlaps && mtFindSupportHit(level, p, w) != null;

            double score = dist;
            if (!inReach) score += 100.0;
            if (!supported) score += 50.0;
            if (overlaps) score += 200.0;
            // Prefer slightly farther in-reach blocks first so we don't box ourselves in
            if (inReach && dist < 1.6) score += 5.0;

            if (score < bestScore) {
                bestScore = score;
                best = w;
                bestWant = d;
                bestItem = item;
            }
        }

        if (best == null) return false;
        mtCur = best;
        mtWant = bestWant;
        mtItem = bestItem;
        return true;
    }

    private static boolean mtHasSupportNeighbor(Level level, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockState ns = level.getBlockState(pos.relative(d));
            if (!ns.isAir() && !Schem.replaceable(ns)) return true;
        }
        return false;
    }

    private static BlockHitResult mtFindSupportHit(Level level, LocalPlayer p, BlockPos target) {
        return mtFindSupportHitFrom(level, p, p.getEyePosition(), target);
    }

    private static BlockHitResult mtFindSupportHitFrom(Level level, LocalPlayer p, Vec3 eye, BlockPos target) {
        for (Direction d : Direction.values()) {
            BlockPos n = target.relative(d);
            BlockState ns = level.getBlockState(n);
            if (ns.isAir() || Schem.replaceable(ns)) continue;
            Direction face = d.getOpposite();
            Vec3 hit = Vec3.atCenterOf(n).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            if (eye.distanceTo(hit) > MT_REACH) continue;
            Vec3 end = hit.subtract(face.getStepX() * 0.02, face.getStepY() * 0.02, face.getStepZ() * 0.02);
            BlockHitResult r = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
            if (r.getType() == HitResult.Type.BLOCK && r.getBlockPos().equals(n)) {
                return new BlockHitResult(hit, face, n, false);
            }
        }
        return null;
    }

    private BlockPos mtFindStandSpot(Level level, LocalPlayer p, BlockPos target) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos s = target.offset(dx, dy, dz);
                    if (s.equals(target) || s.equals(target.below()) || mtBadSpots.contains(s)) continue;
                    if (!level.hasChunkAt(s)) continue;
                    if (!dev.buildtools.Api.solid(level, s.below())) continue;
                    if (dev.buildtools.Api.solid(level, s) || dev.buildtools.Api.solid(level, s.above())) continue;
                    Vec3 eye = new Vec3(s.getX() + 0.5, s.getY() + 1.62, s.getZ() + 0.5);
                    double eyeDist = Vec3.atCenterOf(target).distanceTo(eye);
                    if (eyeDist > MT_REACH - 0.2 || eyeDist < 1.2) continue;
                    if (mtFindSupportHitFrom(level, p, eye, target) == null) continue;
                    double d = p.blockPosition().distSqr(s);
                    if (d < bestDist) {
                        bestDist = d;
                        best = s;
                    }
                }
            }
        }
        return best;
    }

    private boolean mtPlaceScaffold(Minecraft mc, LocalPlayer p, Level level, BlockPos pos) {
        BlockHitResult hit = mtFindSupportHit(level, p, pos);
        if (hit == null || p.getEyePosition().distanceTo(hit.getLocation()) > MT_REACH) return false;
        int slot = -1;
        Item scaffoldItem = null;
        for (int i = 0; i < 9; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (!st.isEmpty() && st.getItem() instanceof BlockItem bi && !neededItems.contains(bi)) {
                slot = i;
                scaffoldItem = bi;
                break;
            }
        }
        if (slot < 0) return false;
        p.getInventory().setSelectedSlot(slot);
        if (mtAim(p, hit.getLocation())) {
            mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
            p.swing(InteractionHand.MAIN_HAND);
            Block b = ((BlockItem) scaffoldItem).getBlock();
            if (dev.buildtools.Api.scaffoldOutsideSchematic == null || dev.buildtools.Api.scaffoldOutsideSchematic.test(pos, b)) {
                dev.buildtools.Api.addScaffold(pos, b);
            }
            return true;
        }
        return false;
    }

    private static boolean mtEquip(Minecraft mc, LocalPlayer p, Item item) {
        if (item == null || item == Items.AIR) return false;
        var inv = p.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).is(item)) {
                inv.setSelectedSlot(i);
                return true;
            }
        }
        for (int i = 9; i < 36; i++) {
            if (inv.getItem(i).is(item)) {
                int hotbar = inv.getSelectedSlot();
                mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, i, hotbar, ContainerInput.SWAP, p);
                return true;
            }
        }
        return false;
    }

    private static boolean mtAim(LocalPlayer p, Vec3 at) {
        Vec3 eye = p.getEyePosition();
        double dx = at.x - eye.x, dy = at.y - eye.y, dz = at.z - eye.z;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        float dYaw = Mth.wrapDegrees(yaw - p.getYRot());
        float dPitch = pitch - p.getXRot();
        p.setYRot(p.getYRot() + Mth.clamp(dYaw, -60f, 60f));
        p.setXRot(Mth.clamp(p.getXRot() + Mth.clamp(dPitch, -45f, 45f), -90f, 90f));
        return Math.abs(dYaw) < 12f && Math.abs(dPitch) < 12f;
    }
}
