package dev.buildtools;

import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

import static dev.buildtools.Config.cfg;

/**
 * Picks up the scaffolding Baritone left behind (see Scaffold): walks to the nearest recorded block, and breaks
 * everything within reach from where it stands with the fastest tool in the hotbar, then collects the drops.
 * Runs on demand (/buildtools scaffold collect, also usable as a queue line) and, when switched on, by itself once enough
 * scaffolding has piled up near you while Baritone is busy. The task underneath carries on afterwards.
 */
final class ScaffoldProcess extends Feature {
    private static final double REACH = 4.4;
    private static final int TARGET_TIMEOUT = 20 * 12;
    private static final int GATHER_TIMEOUT = 20 * 6;

    private boolean working;
    private BlockPos target;
    private ItemEntity item;
    private int targetTicks, itemTicks, cooldown, check, broken, skipped;
    private final Set<Item> dropTypes = new HashSet<>();
    private final Set<Integer> ignoredItems = new HashSet<>();
    private final Set<BlockPos> announced = new HashSet<>();

    ScaffoldProcess() {
        super("BuildTools scaffolding", 13);
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
        Scaffold.tick(mc);
        if (cooldown > 0) cooldown--;
        if (working || p == null || mc.level == null || !Util.scriptOn("scaffold", cfg.scaffoldAuto) || cooldown > 0 || ++check % 20 != 0 || Util.addonOff("scaffold")) return;
        if (!Util.baritoneBusy()) return;   // only in the middle of a job; when idle, /buildtools scaffold collect does it
        if (Scaffold.near(p, 40) >= cfg.scaffoldBatch) start(mc, p, true);
    }

    void startNow() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (working) { Util.msg("Already collecting scaffolding."); return; }
        start(mc, mc.player, false);
    }

    private void start(Minecraft mc, LocalPlayer p, boolean auto) {
        int n = Scaffold.near(p, auto ? 40 : 128);
        if (n == 0) {
            if (!auto) Util.msg("No scaffolding recorded near you. (Only blocks Baritone placed itself are remembered, since the mod was loaded.)");
            return;
        }
        working = true;
        target = null;
        item = null;
        broken = skipped = 0;
        dropTypes.clear();
        ignoredItems.clear();
        Util.msg((auto ? "Enough scaffolding piled up (" : "Collecting scaffolding (") + n + " blocks)" + (auto ? ": taking it down now." : "..."));
    }

    void cancel() {
        working = false;
        target = null;
        item = null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
    }

    // ------------------------------------------------------------------ work

    @Override
    boolean active() {
        Minecraft mc = Minecraft.getInstance();
        if (working && (mc.player == null || mc.level == null || mc.gameMode == null)) working = false;
        return working;
    }

    @Override
    PathingCommand step(boolean calcFailed, boolean isSafeToCancel) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.gameMode == null) { working = false; return defer(); }
        Level level = mc.level;

        if (target != null && !Scaffold.valid(level, target)) {   // gone: broken by us (or by anything else)
            broken++;
            target = null;
        }
        if (target != null && level.getBlockState(target).isAir() == false && (p.tickCount % 1) == 0 && !announced.contains(target)) {
            announced.add(target.immutable());   // one line per block we take down, so the chat shows what goes
            Util.msg("Taking down scaffolding at " + target.toShortString() + " (" + level.getBlockState(target).getBlock().getName().getString() + ")");
        }
        if (target == null) {
            target = pickTarget(p, level);
            targetTicks = 0;
        }
        if (target != null) return work(mc, p, level, calcFailed);

        // nothing left to break: gather the drops
        if (item == null || !item.isAlive()) {
            item = findItem(p, level);
            itemTicks = 0;
        }
        if (item == null) { finish(mc); return defer(); }
        if (++itemTicks > GATHER_TIMEOUT || calcFailed) {
            ignoredItems.add(item.getId());
            item = null;
            return defer();
        }
        return new PathingCommand(new GoalNear(item.blockPosition(), 1), PathingCommandType.SET_GOAL_AND_PATH);
    }

    private PathingCommand work(Minecraft mc, LocalPlayer p, Level level, boolean calcFailed) {
        if (++targetTicks > TARGET_TIMEOUT || calcFailed) {
            Scaffold.forget(target);
            skipped++;
            target = null;
            mc.gameMode.stopDestroyBlock();
            return defer();
        }
        Direction face = reachFace(p, level, target);
        if (face == null) return new PathingCommand(new GoalNear(target, 2), PathingCommandType.SET_GOAL_AND_PATH);

        BlockState state = level.getBlockState(target);
        selectBestTool(p, state);
        Vec3 aim = Vec3.atCenterOf(target);
        if (Util.aimAt(p, aim)) {
            dropTypes.add(state.getBlock().asItem());
            mc.gameMode.continueDestroyBlock(target, face);
            p.swing(InteractionHand.MAIN_HAND);
        }
        return pause();   // stand still while digging
    }

    /** The face to dig at when the block can be hit from where the player stands, else null. */
    private static Direction reachFace(LocalPlayer p, Level level, BlockPos pos) {
        Vec3 eye = p.getEyePosition();
        Vec3 aim = Vec3.atCenterOf(pos);
        if (eye.distanceTo(aim) > REACH) return null;
        BlockHitResult hit = level.clip(new ClipContext(eye, aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos)) return null;
        return hit.getDirection();
    }

    /** Highest block in reach first (a pillar goes top-down), else the nearest one; safe areas and failed spots are left out. */
    private BlockPos pickTarget(LocalPlayer p, Level level) {
        BlockPos best = null;
        boolean bestReach = false;
        double bestScore = Double.MAX_VALUE;
        var it = Scaffold.spots().keySet().iterator();
        while (it.hasNext()) {
            BlockPos pos = it.next();
            if (!Scaffold.valid(level, pos)) { it = Scaffold.spots().keySet().iterator(); continue; }   // valid() removed it
            if (cfg.protect) {
                Config.Zone z = Guard.zoneAt(pos);
                if (z != null && !z.allowBreak) continue;
            }
            double d = pos.distSqr(p.blockPosition());
            if (d > 128.0 * 128.0) continue;
            boolean reach = d <= 25 && reachFace(p, level, pos) != null;
            double score = reach ? -pos.getY() : d;
            if ((reach && !bestReach) || (reach == bestReach && score < bestScore)) {
                best = pos;
                bestReach = reach;
                bestScore = score;
            }
        }
        return best;
    }

    private static void selectBestTool(LocalPlayer p, BlockState state) {
        int bestSlot = p.getInventory().getSelectedSlot();
        float bestSpeed = p.getInventory().getItem(bestSlot).getDestroySpeed(state);
        for (int i = 0; i < 9; i++) {
            ItemStack st = p.getInventory().getItem(i);
            float s = st.getDestroySpeed(state);
            if (s > bestSpeed + 0.01f) { bestSpeed = s; bestSlot = i; }
        }
        p.getInventory().setSelectedSlot(bestSlot);
    }

    private ItemEntity findItem(LocalPlayer p, Level level) {
        ItemEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(14, 6, 14))) {
            if (!e.isAlive() || ignoredItems.contains(e.getId()) || !dropTypes.contains(e.getItem().getItem())) continue;
            double d = e.distanceToSqr(p);
            if (d < bestDist) { bestDist = d; best = e; }
        }
        return best;
    }

    private void finish(Minecraft mc) {
        working = false;
        target = null;
        item = null;
        if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        cooldown = 20 * 20;
        Util.msg("Scaffolding collected: " + broken + " block" + (broken == 1 ? "" : "s") + " removed"
                + (skipped > 0 ? ", " + skipped + " left (out of reach or inside a safe area)" : "") + ".");
    }

    @Override
    public void onLostControl() {   // e.g. #stop
        if (working) cancel();
    }
}
