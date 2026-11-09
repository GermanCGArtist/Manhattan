package dev.buildtools;

import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.IBuilderProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.selection.ISelection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static dev.buildtools.Config.cfg;

/**
 * Gets water out of the way of Baritone's builder (#build, #sel ca ...). The builder can replace water SOURCE blocks
 * itself but gives up ("Unreplaceable liquids", it pauses) on FLOWING water. Water is infinite in Minecraft, so just
 * bucketing it mostly does not work. What this does instead:
 *
 *   dam    - with a #sel selection: plug the water just OUTSIDE the selection's edge, so nothing flows back in
 *   plug   - put a throwaway block into every flowing / refilling water cell inside the work area
 *   bucket - only for a lone source (fewer than two source neighbours), where it really drains for good
 *
 * then it resumes the builder. It runs by itself when the builder pauses (see tick()), or on demand.
 */
final class DrainProcess extends Feature {
    private record Task(BlockPos pos, boolean bucket, boolean dam) {}

    private static final int MAX_TASKS = 400;
    private static final int MAX_SCAN = 200_000;
    private static final int MAX_ROUNDS = 3;

    private boolean working;
    private final List<Task> tasks = new ArrayList<>();
    private Task current;
    private final Placer placer = new Placer();
    private final Set<BlockPos> failed = new HashSet<>();
    private int cooldown, check, rounds, waitTicks;
    private boolean resumeBuilder;
    private int bucketed, plugged, dammed;

    DrainProcess() {
        super("BuildTools drain", 14);
    }

    @Override
    boolean busy() {
        return working;
    }

    // ------------------------------------------------------------------ triggers

    @Override
    void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (cooldown > 0) cooldown--;
        if (working || p == null || mc.level == null || !Util.scriptOn("drain", cfg.drainAuto) || cooldown > 0 || ++check % 20 != 0 || Util.addonOff("drain")) return;
        IBuilderProcess builder = Util.baritone().getBuilderProcess();
        if (builder.isActive() && builder.isPaused()) start(mc, p, true);
    }

    /** /buildtools drain now */
    void startNow() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (working) { Util.msg("Already draining."); return; }
        start(mc, mc.player, false);
    }

    void cancel() {
        working = false;
        tasks.clear();
        current = null;
    }

    private void start(Minecraft mc, LocalPlayer p, boolean auto) {
        List<Task> found = scan(mc, p);
        if (found.isEmpty()) {
            if (auto) Util.warn("drain-none", "The builder is paused, but I found no water to remove. Check /buildtools status and the builder's own message (missing materials?).");
            else Util.msg("No water found in the selection (or within 8 blocks of you).");
            cooldown = 20 * 10;
            return;
        }
        if (Util.countThrowaway(p) == 0 && found.stream().anyMatch(t -> !t.bucket())) {
            Util.warn("drain-noblocks", "Plugging water needs throwaway blocks (cobblestone, dirt, ...) in the inventory.");
            cooldown = 20 * 10;
            return;
        }
        tasks.clear();
        tasks.addAll(found);
        failed.clear();
        current = null;
        rounds = 1;
        waitTicks = 0;
        bucketed = plugged = dammed = 0;
        resumeBuilder = Util.baritone().getBuilderProcess().isPaused();
        working = true;
        Util.msg("Removing " + found.size() + " water cells" + (cfg.dam ? " (including a dam outside the selection edge)" : "") + "...");
    }

    // ------------------------------------------------------------------ what to do

    /** The area to clear: the #sel selections, or a box around the player when there is none. */
    private List<int[]> boxes(LocalPlayer p) {
        List<int[]> boxes = new ArrayList<>();
        for (ISelection s : Util.baritone().getSelectionManager().getSelections()) {
            boxes.add(new int[]{s.min().x, s.min().y, s.min().z, s.max().x, s.max().y, s.max().z});
        }
        if (boxes.isEmpty()) {
            BlockPos b = p.blockPosition();
            boxes.add(new int[]{b.getX() - 8, b.getY() - 4, b.getZ() - 8, b.getX() + 8, b.getY() + 4, b.getZ() + 8});
        }
        return boxes;
    }

    private List<Task> scan(Minecraft mc, LocalPlayer p) {
        Level level = mc.level;
        boolean haveBucket = Util.slotOf(p, Items.BUCKET) >= 0;
        boolean hasSelection = Util.baritone().getSelectionManager().getSelections().length > 0;
        List<Task> out = new ArrayList<>();
        int scanned = 0;

        for (int[] b : boxes(p)) {
            boolean dam = hasSelection && cfg.dam;
            int pad = dam ? 1 : 0;   // the shell just outside the selection is the dam
            for (int x = b[0] - pad; x <= b[3] + pad && scanned < MAX_SCAN; x++) {
                for (int y = b[1] - pad; y <= b[4] + pad; y++) {
                    for (int z = b[2] - pad; z <= b[5] + pad; z++) {
                        scanned++;
                        BlockPos pos = new BlockPos(x, y, z);
                        if (!Util.isWater(level.getBlockState(pos))) continue;
                        boolean inside = x >= b[0] && x <= b[3] && y >= b[1] && y <= b[4] && z >= b[2] && z <= b[5];
                        if (!inside) {
                            out.add(new Task(pos, false, true));
                        } else if (haveBucket && Util.isSourceWater(level.getBlockState(pos)) && !Util.regenerates(level, pos)) {
                            out.add(new Task(pos, true, false));   // a lone source: draining it really works
                        } else {
                            out.add(new Task(pos, false, false));
                        }
                    }
                }
            }
        }
        if (out.size() > MAX_TASKS) {
            Util.msg("Lots of water (" + out.size() + " cells): doing the first " + MAX_TASKS + " now, then looking again.");
            out = new ArrayList<>(out.subList(0, MAX_TASKS));
        }
        return out;
    }

    private Task nextTask(LocalPlayer p) {
        Task best = null;
        double bestDist = Double.MAX_VALUE;
        for (Task t : tasks) {
            double d = t.pos().distSqr(p.blockPosition());
            if (d < bestDist) { bestDist = d; best = t; }
        }
        if (best != null) tasks.remove(best);
        return best;
    }

    // ------------------------------------------------------------------ doing it

    @Override
    boolean active() {
        if (working && Minecraft.getInstance().player == null) cancel();
        return working;
    }

    @Override
    PathingCommand step(boolean calcFailed, boolean isSafeToCancel) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (!working || p == null || mc.level == null || mc.gameMode == null) return defer();

        if (waitTicks > 0) {
            waitTicks--;
            return pause();
        }

        if (current == null) {
            current = nextTask(p);
            if (current == null) return endOfRound(mc, p);
            placer.start(current.pos(), current.bucket() ? Placer.Mode.PICKUP : Placer.Mode.BLOCK);
        }

        if (!Util.isWater(mc.level.getBlockState(current.pos()))) {   // gone already (its source was removed)
            current = null;
            return pause();
        }

        switch (placer.tick(mc, p)) {
            case DONE -> {
                if (current.bucket()) bucketed++;
                else if (current.dam()) dammed++;
                else plugged++;
                current = null;
                return pause();
            }
            case FAILED -> {
                if (current.bucket()) {   // could not bucket it: plug it instead
                    tasks.add(new Task(current.pos(), false, false));
                } else {
                    failed.add(current.pos());
                    Util.warn("drain-fail", "Could not plug water at " + current.pos().toShortString() + ": " + placer.failure());
                }
                current = null;
                return pause();
            }
            default -> {
                if (placer.canReach(mc, p)) return pause();
                return new PathingCommand(new GoalNear(current.pos(), 3), PathingCommandType.SET_GOAL_AND_PATH);
            }
        }
    }

    /** All planned cells done: give removed water a moment to recede, look again, and finish when it stays dry. */
    private PathingCommand endOfRound(Minecraft mc, LocalPlayer p) {
        if (waitTicks == 0 && rounds <= MAX_ROUNDS) {
            List<Task> more = scan(mc, p);
            more.removeIf(t -> failed.contains(t.pos()));
            if (!more.isEmpty()) {
                tasks.addAll(more);
                rounds++;
                waitTicks = 20;
                return pause();
            }
        }
        finish();
        return pause();
    }

    private void finish() {
        working = false;
        tasks.clear();
        current = null;
        cooldown = 20 * 10;
        if (resumeBuilder) Util.baritone().getBuilderProcess().resume();
        Util.msg("Water removed: " + plugged + " plugged, " + bucketed + " bucketed, " + dammed + " dammed outside"
                + (failed.isEmpty() ? "" : ", " + failed.size() + " could not be reached")
                + (resumeBuilder ? ". Builder resumed." : "."));
    }

    @Override
    public void onLostControl() {   // e.g. #stop
        if (working) {
            working = false;
            tasks.clear();
            current = null;
        }
    }
}
