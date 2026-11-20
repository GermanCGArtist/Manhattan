package dev.buildtools;

import dev.midtown.api.EngineSelector;
import dev.midtown.api.GoalSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static dev.buildtools.Config.cfg;

/**
 * Gets water out of the way of building operations.
 */
public final class DrainProcess extends Feature {
    private record Task(BlockPos pos, boolean bucket, boolean dam) {}

    private static final int MAX_TASKS = 400;
    private static final int MAX_SCAN = 200_000;
    private static final int MAX_ROUNDS = 3;

    private boolean working;
    private List<int[]> explicitBoxes;
    private final List<Task> tasks = new ArrayList<>();
    private final Set<BlockPos> failed = new HashSet<>();
    private Task current;
    private final Placer placer = new Placer();
    private int waitTicks, cooldown, check;
    private int rounds;
    private int bucketed, plugged, dammed;

    DrainProcess() {
        super("BuildTools drain", 9);
    }

    @Override
    boolean active() {
        return working;
    }

    @Override
    boolean busy() {
        return working;
    }

    @Override
    void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (cooldown > 0) cooldown--;
        if (!working) return;
        if (p == null || mc.level == null || mc.gameMode == null) return;
        stepInternal(mc, p);
    }

    /** /buildtools drain now */
    void startNow() {
        startNow(null);
    }

    /** Starts draining within {@code explicitBoxes} (or the current selection when null/empty). */
    public void startNow(List<int[]> explicitBoxes) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (working) { Util.msg("Already draining."); return; }
        this.explicitBoxes = copyBoxes(explicitBoxes);
        start(mc, mc.player, false);
    }

    private static List<int[]> copyBoxes(List<int[]> raw) {
        if (raw == null || raw.isEmpty()) return null;
        List<int[]> out = new ArrayList<>(raw.size());
        for (int[] b : raw) {
            if (b != null && b.length >= 6) {
                out.add(new int[]{
                        Math.min(b[0], b[3]), Math.min(b[1], b[4]), Math.min(b[2], b[5]),
                        Math.max(b[0], b[3]), Math.max(b[1], b[4]), Math.max(b[2], b[5])
                });
            }
        }
        return out.isEmpty() ? null : out;
    }

    void cancel() {
        working = false;
        explicitBoxes = null;
        tasks.clear();
        current = null;
        EngineSelector.current().cancel();
    }

    private void start(Minecraft mc, LocalPlayer p, boolean auto) {
        List<Task> found = scan(mc, p);
        if (found.isEmpty()) {
            if (auto) Util.warn("drain-none", "No water found to remove.");
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
        working = true;
        Util.msg("Removing " + found.size() + " water cells" + (cfg.dam ? " (including a dam outside the selection edge)" : "") + "...");
    }

    private List<int[]> boxes(LocalPlayer p) {
        if (explicitBoxes != null && !explicitBoxes.isEmpty()) {
            return explicitBoxes;
        }
        List<int[]> boxes = new ArrayList<>(dev.baritonequeue.Areas.read());
        if (boxes.isEmpty() && cfg.corner1 != null && cfg.corner2 != null) {
            boxes.add(new int[]{
                    Math.min(cfg.corner1[0], cfg.corner2[0]), Math.min(cfg.corner1[1], cfg.corner2[1]), Math.min(cfg.corner1[2], cfg.corner2[2]),
                    Math.max(cfg.corner1[0], cfg.corner2[0]), Math.max(cfg.corner1[1], cfg.corner2[1]), Math.max(cfg.corner1[2], cfg.corner2[2])});
        }
        if (boxes.isEmpty()) {
            BlockPos b = p.blockPosition();
            boxes.add(new int[]{b.getX() - 8, b.getY() - 4, b.getZ() - 8, b.getX() + 8, b.getY() + 4, b.getZ() + 8});
        }
        return boxes;
    }

    private List<Task> scan(Minecraft mc, LocalPlayer p) {
        Level level = mc.level;
        List<int[]> boxes = boxes(p);
        boolean hasSelection = (explicitBoxes != null && !explicitBoxes.isEmpty())
                || dev.baritonequeue.Areas.any()
                || (cfg.corner1 != null && cfg.corner2 != null);
        List<Task> dams = new ArrayList<>();
        List<Task> plugs = new ArrayList<>();
        List<Task> buckets = new ArrayList<>();
        int count = 0;

        for (int[] b : boxes) {
            for (int y = b[4]; y >= b[1]; y--) {
                for (int x = b[0]; x <= b[3]; x++) {
                    for (int z = b[2]; z <= b[5]; z++) {
                        if (++count > MAX_SCAN) break;
                        BlockPos pos = new BlockPos(x, y, z);
                        BlockState s = level.getBlockState(pos);
                        if (!Util.isWater(s)) continue;
                        if (Api.protectedAt(pos)) continue;

                        boolean damCell = false;
                        if (cfg.dam && hasSelection) {
                            for (Direction d : Direction.Plane.HORIZONTAL) {
                                BlockPos n = pos.relative(d);
                                if (!inAnyBox(n, boxes) && !Util.isSolid(level, n) && !Api.protectedAt(n)) {
                                    dams.add(new Task(n, false, true));
                                    damCell = true;
                                }
                            }
                        }
                        if (damCell) continue;

                        if (Util.isSourceWater(s)) {
                            if (Util.regenerates(level, pos)) continue;
                            if (loneSource(level, pos) && Util.slotOf(p, net.minecraft.world.item.Items.BUCKET) >= 0) buckets.add(new Task(pos, true, false));
                            else plugs.add(new Task(pos, false, false));
                        } else {
                            plugs.add(new Task(pos, false, false));
                        }
                        if (dams.size() + plugs.size() + buckets.size() >= MAX_TASKS) break;
                    }
                }
            }
        }
        List<Task> out = new ArrayList<>();
        out.addAll(dams);
        out.addAll(plugs);
        out.addAll(buckets);
        return out;
    }

    private static boolean inAnyBox(BlockPos p, List<int[]> boxes) {
        for (int[] b : boxes) {
            if (p.getX() >= b[0] && p.getX() <= b[3] && p.getY() >= b[1] && p.getY() <= b[4] && p.getZ() >= b[2] && p.getZ() <= b[5]) return true;
        }
        return false;
    }

    private static boolean loneSource(Level level, BlockPos pos) {
        int sources = 0;
        for (Direction d : Direction.values()) {
            if (Util.isSourceWater(level.getBlockState(pos.relative(d)))) sources++;
        }
        return sources <= 1;
    }

    private Task nextTask(LocalPlayer p) {
        if (tasks.isEmpty()) return null;
        BlockPos pos = p.blockPosition();
        int bestIdx = 0;
        double bestD = tasks.get(0).pos().distSqr(pos);
        for (int i = 1; i < tasks.size(); i++) {
            double d = tasks.get(i).pos().distSqr(pos);
            if (d < bestD) { bestD = d; bestIdx = i; }
        }
        return tasks.remove(bestIdx);
    }

    private void stepInternal(Minecraft mc, LocalPlayer p) {
        if (!working || p == null || mc.level == null || mc.gameMode == null) return;
        if (waitTicks > 0) {
            waitTicks--;
            return;
        }

        if (current == null) {
            current = nextTask(p);
            if (current == null) {
                endOfRound(mc, p);
                return;
            }
            placer.start(current.pos(), current.bucket() ? Placer.Mode.PICKUP : Placer.Mode.BLOCK);
        }

        if (!Util.isWater(mc.level.getBlockState(current.pos()))) {
            current = null;
            return;
        }

        if (!placer.canReach(mc, p)) {
            EngineSelector.current().goTo(GoalSpec.near(current.pos(), 3));
            return;
        } else {
            EngineSelector.current().cancel();
        }

        switch (placer.tick(mc, p)) {
            case DONE -> {
                if (current.bucket()) bucketed++;
                else if (current.dam()) dammed++;
                else plugged++;
                current = null;
            }
            case FAILED -> {
                if (current.bucket()) {
                    tasks.add(new Task(current.pos(), false, false));
                } else {
                    failed.add(current.pos());
                    Util.warn("drain-fail", "Could not plug water at " + current.pos().toShortString() + ": " + placer.failure());
                }
                current = null;
            }
            default -> {}
        }
    }

    private void endOfRound(Minecraft mc, LocalPlayer p) {
        if (waitTicks == 0 && rounds <= MAX_ROUNDS) {
            List<Task> more = scan(mc, p);
            more.removeIf(t -> failed.contains(t.pos()));
            if (!more.isEmpty()) {
                tasks.addAll(more);
                rounds++;
                waitTicks = 20;
                return;
            }
        }
        finish();
    }

    private void finish() {
        working = false;
        explicitBoxes = null;
        tasks.clear();
        current = null;
        cooldown = 20 * 10;
        EngineSelector.current().cancel();
        Util.msg("Water removed: " + plugged + " plugged, " + bucketed + " bucketed, " + dammed + " dammed outside"
                + (failed.isEmpty() ? "" : ", " + failed.size() + " could not be reached."));
    }

    @Override
    public void onLostControl() {
        if (working) {
            working = false;
            explicitBoxes = null;
            tasks.clear();
            current = null;
            EngineSelector.current().cancel();
        }
    }

    /** Counts fluid cells inside {@code boxes} that DrainProcess targets (non-regenerating water). */
    public static int countFluid(Level level, List<int[]> boxes) {
        if (level == null || boxes == null || boxes.isEmpty()) return 0;
        int count = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int[] b : boxes) {
            if (b == null || b.length < 6) continue;
            int x0 = Math.min(b[0], b[3]), x1 = Math.max(b[0], b[3]);
            int y0 = Math.min(b[1], b[4]), y1 = Math.max(b[1], b[4]);
            int z0 = Math.min(b[2], b[5]), z1 = Math.max(b[2], b[5]);
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    for (int y = y0; y <= y1; y++) {
                        pos.set(x, y, z);
                        if (!level.hasChunkAt(pos)) continue;
                        BlockState s = level.getBlockState(pos);
                        if (!Util.isWater(s)) continue;
                        if (Util.isSourceWater(s) && Util.regenerates(level, pos)) continue;
                        count++;
                    }
                }
            }
        }
        return count;
    }
}
