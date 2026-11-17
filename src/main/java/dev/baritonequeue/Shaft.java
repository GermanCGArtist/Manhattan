package dev.baritonequeue;

import dev.midtown.api.EngineSelector;
import dev.midtown.api.EngineStatus;
import dev.midtown.api.GoalSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

import static dev.baritonequeue.Config.cfg;

/**
 * The stair shaft of a dig: a saved area with the role "stairs" whose first box lies inside the area being dug.
 *
 * The footprint (at least 2 x 2) is never mined as a whole. Instead a spiral staircase is carved along its outer ring,
 * clockwise from the minimum corner: step n stands at ring cell (n mod ring length), one block lower than step n-1,
 * with three blocks of air above the floor (room for the jump onto the next step). Anything in the footprint that is
 * not part of a step (a centre pillar, the blocks between two turns of the spiral) stays, so the steps always have a
 * floor. The staircase grows with the hole: every pass carves the steps down to the level it just reached, so the way
 * out is always connected to the floor the bot is working on.
 *
 * Because the shaft is an ordinary saved area it persists in the config, and it can be sent from Heavymatica with the
 * role STAIRS next to the DIG region: the shaft belongs to the dig area it lies in, no link has to be stored.
 */
final class Shaft {
    private Shaft() {}

    /** The shaft of the dig in progress (set by clear_area); the climb out and the digger read it. */
    static volatile Spec current;

    /** name = the saved area; box = its footprint {x1,y1,z1,x2,y2,z2}; topY = highest block of the dig it lies in. */
    record Spec(String name, int[] box, int topY, int[] ringX, int[] ringZ) {
        int length() {
            return ringX.length;
        }

        /** Where the feet stand on step n (step 0 is at ground level, beside the hole's rim). */
        BlockPos stand(int n) {
            int i = Math.floorMod(n, length());
            return new BlockPos(ringX[i], topY + 1 - n, ringZ[i]);
        }
    }

    static boolean valid(int[] box) {
        return box != null && box.length >= 6 && box[3] - box[0] >= 1 && box[5] - box[2] >= 1;
    }

    /** Ring cells of the footprint, clockwise from the minimum corner: {x} and {z} arrays. */
    static int[][] ring(int[] box) {
        List<int[]> cells = new ArrayList<>();
        int x0 = box[0], z0 = box[2], x1 = box[3], z1 = box[5];
        for (int x = x0; x < x1; x++) cells.add(new int[]{x, z0});
        for (int z = z0; z < z1; z++) cells.add(new int[]{x1, z});
        for (int x = x1; x > x0; x--) cells.add(new int[]{x, z1});
        for (int z = z1; z > z0; z--) cells.add(new int[]{x0, z});
        int[] xs = new int[cells.size()], zs = new int[cells.size()];
        for (int i = 0; i < xs.length; i++) { xs[i] = cells.get(i)[0]; zs[i] = cells.get(i)[1]; }
        return new int[][]{xs, zs};
    }

    static Spec spec(String name, int[] box, int topY) {
        int[][] r = ring(box);
        return new Spec(name, box.clone(), topY, r[0], r[1]);
    }

    /**
     * The shaft for a dig over these boxes: a saved "stairs" area whose footprint lies (in x and z) inside one of them.
     * Null when there is none. The top is the highest block of the boxes that hold the footprint.
     */
    static Spec find(List<int[]> selection) {
        if (selection == null || selection.isEmpty() || cfg.areaMeta == null) return null;
        for (String name : Areas.savedNames()) {
            if (!"stairs".equalsIgnoreCase(Areas.roleOf(name))) continue;
            List<int[]> boxes = Areas.getBoxes(name);
            if (boxes == null || boxes.isEmpty() || !valid(boxes.get(0))) continue;
            int[] f = boxes.get(0);
            int top = Integer.MIN_VALUE;
            for (int[] b : selection) {
                if (f[0] >= b[0] && f[3] <= b[3] && f[2] >= b[2] && f[5] <= b[5]) top = Math.max(top, b[4]);
            }
            if (top != Integer.MIN_VALUE) return spec(name, f, top);
        }
        return null;
    }

    /**
     * The blocks of the footprint that must stay: the centre column and, in every ring cell, the solid between one
     * step's air and the next turn's. (The steps themselves must not be in it: protected blocks are not dug.)
     * Boxes {x1,y1,z1,x2,y2,z2}, down to {@code yMin}.
     */
    static List<int[]> keepBoxes(Spec s, int yMin) {
        List<int[]> out = new ArrayList<>();
        int[] b = s.box;
        if (b[3] - b[0] >= 2 && b[5] - b[2] >= 2) out.add(new int[]{b[0] + 1, yMin, b[2] + 1, b[3] - 1, s.topY, b[5] - 1});
        int len = s.length();
        for (int i = 0; i < len; i++) {
            int roof = s.topY + 1 - i + 3;   // the solid above the first step of this cell (a spiral is a tube)
            if (roof <= s.topY) out.add(new int[]{s.ringX[i], Math.max(yMin, roof), s.ringZ[i], s.ringX[i], s.topY, s.ringZ[i]});
            for (int n = i; s.topY + 1 - n - 1 >= yMin; n += len) {
                int feet = s.topY + 1 - n;
                int yHi = feet - 1, yLo = feet - len < yMin ? yMin : feet - len + 3;   // the next step is below the dig: solid to the bottom
                if (yLo <= yHi) out.add(new int[]{s.ringX[i], yLo, s.ringZ[i], s.ringX[i], yHi, s.ringZ[i]});
            }
        }
        return out;
    }

    static boolean inFootprint(Spec s, BlockPos p) {
        return s != null && p.getX() >= s.box[0] && p.getX() <= s.box[3] && p.getZ() >= s.box[2] && p.getZ() <= s.box[5];
    }

    /** Is this block part of a step whose floor is at {@code minFeet} or higher (so it is to be carved out now)? */
    static boolean isCarve(Spec s, BlockPos p, int minFeet) {
        int idx = -1;
        for (int i = 0; i < s.length(); i++) {
            if (s.ringX[i] == p.getX() && s.ringZ[i] == p.getZ()) { idx = i; break; }
        }
        if (idx < 0) return false;
        // step n has air at feet .. feet + 2 where feet = topY + 1 - n
        for (int n = s.topY + 1 - p.getY(); n <= s.topY + 3 - p.getY(); n++) {
            if (n >= 0 && Math.floorMod(n, s.length()) == idx && s.topY + 1 - n >= minFeet) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ the way out

    private static boolean climbing;
    private static Spec climbSpec;
    private static int goalStep, climbTicks, fails, sinceIssue;
    private static BlockPos goal;
    private static final int CLIMB_TIMEOUT = 20 * 180;

    static boolean climbing() {
        return climbing;
    }

    private static boolean passable(Level level, BlockPos c) {
        BlockState s = level.getBlockState(c);
        return s.getCollisionShape(level, c).isEmpty() && s.getFluidState().isEmpty();
    }

    /**
     * Walks up the staircase to ground level (an "on" block is about to leave the hole, for food, a chest or the bed).
     * Does nothing outside the hole or when no step is carved yet. While it runs the script waits (Runner.tick).
     */
    static boolean startClimb() {
        Spec s = current;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (s == null || p == null || mc.level == null) return false;
        if (p.getBlockY() > s.topY - 1) return false;   // already at the top
        int est = Math.max(0, s.topY + 1 - p.getBlockY());
        int found = -1;
        for (int n = Math.max(0, est - 2); n <= est + s.length(); n++) {
            BlockPos st = s.stand(n);
            if (mc.level.hasChunkAt(st) && passable(mc.level, st) && passable(mc.level, st.above())
                    && dev.buildtools.Api.solid(mc.level, st.below())) { found = n; break; }
        }
        if (found < 0) {
            Util.msg("No carved step of the stair shaft '" + s.name + "' is within reach (feet at y " + p.getBlockY()
                    + ", steps from y " + (s.topY + 1) + "); leaving the hole without the stairs.");
            return false;
        }
        climbSpec = s;
        goalStep = found;
        goal = s.stand(found);
        fails = 0;
        climbTicks = 0;
        sinceIssue = 0;
        climbing = true;
        EngineSelector.current().goTo(GoalSpec.exact(goal));
        Util.msg("Climbing out of the hole by the stair shaft '" + s.name + "'.");
        return true;
    }

    static void stopClimb() {
        if (!climbing) return;
        climbing = false;
        EngineSelector.current().cancel();
    }

    /** Every tick (from ClearProcess.tick): steps the climb on. */
    static void tick() {
        if (!climbing) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || ++climbTicks > CLIMB_TIMEOUT) {
            Util.msg("Could not climb out by the stair shaft in time; carrying on without it.");
            stopClimb();
            return;
        }
        double dx = p.getX() - (goal.getX() + 0.5), dz = p.getZ() - (goal.getZ() + 0.5), dy = p.getY() - goal.getY();
        if (dx * dx + dz * dz <= 0.8 && Math.abs(dy) <= 1.0) {
            if (goalStep == 0) {
                stopClimb();
                return;
            }
            goalStep = Math.max(0, goalStep - 2);
            goal = climbSpec.stand(goalStep);
            fails = 0;
            sinceIssue = 0;
            EngineSelector.current().goTo(GoalSpec.exact(goal));
            return;
        }
        if (++sinceIssue < 10) return;   // let the path start before reading its status
        EngineStatus st = EngineSelector.current().status();
        if (st == EngineStatus.FAILED || st == EngineStatus.IDLE || st == EngineStatus.DONE) {
            if (++fails > 4) {
                Util.msg("The stair shaft is blocked; carrying on without it.");
                stopClimb();
                return;
            }
            sinceIssue = 0;
            EngineSelector.current().goTo(GoalSpec.exact(goal));
        }
    }
}
