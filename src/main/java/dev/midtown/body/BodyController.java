package dev.midtown.body;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Single client-side authority for player motor control: movement/action key states,
 * smooth camera rotation, block breaking, block placement, and direct local steering.
 * <p>
 * Ownership is mediated by {@link MotorLease}: switching owners automatically releases held
 * movement/action keys and stops any in-progress block destruction before handing control over.
 */
public final class BodyController {

    public static final BodyController INSTANCE = new BodyController();

    public static final float DEFAULT_MAX_YAW_STEP = 30.0f;
    public static final float DEFAULT_MAX_PITCH_STEP = 20.0f;

    private final MotorLease lease = new MotorLease();
    private boolean inputsActive;
    private BlockPos breakingPos;

    private BodyController() {}

    // ------------------------------------------------------------------ lease & ownership

    /** Acquires motor ownership for {@code owner} at default priority {@code 0}. */
    public boolean acquire(String owner) {
        return acquire(owner, 0);
    }

    /** Acquires motor ownership for {@code owner} with the given {@code priority}. */
    public boolean acquire(String owner, int priority) {
        return lease.acquire(owner, priority, () -> {
            if (inputsActive || breakingPos != null) {
                releaseInputsInternal(safeMinecraft());
            }
        });
    }

    /** Acquires motor ownership for {@code owner} using an explicit {@link Minecraft} handle. */
    public boolean acquire(Minecraft mc, String owner) {
        return acquire(mc, owner, 0);
    }

    /** Acquires motor ownership for {@code owner} at {@code priority} using an explicit {@link Minecraft} handle. */
    public boolean acquire(Minecraft mc, String owner, int priority) {
        return lease.acquire(owner, priority, () -> releaseInputsInternal(mc));
    }

    /** Releases all held keys, breaking state, and motor lease unconditionally. */
    public void releaseAll(Minecraft mc) {
        lease.releaseAll(() -> releaseInputsInternal(mc));
    }

    /**
     * Releases all held keys, breaking state, and motor lease only if {@code owner} is the
     * current motor owner.
     */
    public boolean releaseIfOwner(Minecraft mc, String owner) {
        return lease.releaseIfOwner(owner, () -> releaseInputsInternal(mc));
    }

    /** Returns the current motor lease owner, or {@code null} if unowned. */
    public String currentOwner() {
        return lease.currentOwner();
    }

    /** Returns {@code true} if {@code owner} currently holds the motor lease. */
    public boolean isOwnedBy(String owner) {
        return lease.isOwnedBy(owner);
    }

    /** Exposes the underlying {@link MotorLease} for inspection. */
    public MotorLease lease() {
        return lease;
    }

    public boolean areInputsActive() {
        return inputsActive;
    }

    public BlockPos breakingPos() {
        return breakingPos;
    }

    // ------------------------------------------------------------------ key state control

    /** Updates all movement and action key states on {@code mc.options}. */
    public void setKeys(
            Minecraft mc,
            boolean forward,
            boolean back,
            boolean left,
            boolean right,
            boolean jump,
            boolean sneak,
            boolean sprint,
            boolean attack,
            boolean use) {
        if (mc == null) {
            return;
        }
        Options options = mc.options;
        if (options != null) {
            setKey(options.keyUp, forward);
            setKey(options.keyDown, back);
            setKey(options.keyLeft, left);
            setKey(options.keyRight, right);
            setKey(options.keyJump, jump);
            setKey(options.keyShift, sneak);
            setKey(options.keySprint, sprint);
            setKey(options.keyAttack, attack);
            setKey(options.keyUse, use);
        }
        LocalPlayer player = mc.player;
        if (player != null) {
            player.setShiftKeyDown(sneak);
            player.setSprinting(sprint);
        }
        inputsActive = true;
    }

    /** Updates only movement keys (WASD, jump, shift, sprint), leaving attack/use untouched. */
    public void setMovementKeys(
            Minecraft mc,
            boolean forward,
            boolean back,
            boolean left,
            boolean right,
            boolean jump,
            boolean sneak,
            boolean sprint) {
        if (mc == null) {
            return;
        }
        Options options = mc.options;
        if (options != null) {
            setKey(options.keyUp, forward);
            setKey(options.keyDown, back);
            setKey(options.keyLeft, left);
            setKey(options.keyRight, right);
            setKey(options.keyJump, jump);
            setKey(options.keyShift, sneak);
            setKey(options.keySprint, sprint);
        }
        LocalPlayer player = mc.player;
        if (player != null) {
            player.setShiftKeyDown(sneak);
            player.setSprinting(sprint);
        }
        inputsActive = true;
    }

    /** Releases only movement keys (WASD, jump, shift, sprint), leaving attack/use untouched. */
    public void clearMovementKeys(Minecraft mc) {
        setMovementKeys(mc, false, false, false, false, false, false, false);
    }

    /** Updates attack and use key states. */
    public void setActionKeys(Minecraft mc, boolean attack, boolean use) {
        if (mc == null || mc.options == null) {
            return;
        }
        setKey(mc.options.keyAttack, attack);
        setKey(mc.options.keyUse, use);
        inputsActive = true;
    }

    /** Sets the use (right-click) key state (e.g. for eating or shielding). */
    public void setUseKey(Minecraft mc, boolean down) {
        if (mc == null || mc.options == null) {
            return;
        }
        setKey(mc.options.keyUse, down);
        if (down) {
            inputsActive = true;
        }
    }

    /** Sets the attack (left-click) key state. */
    public void setAttackKey(Minecraft mc, boolean down) {
        if (mc == null || mc.options == null) {
            return;
        }
        setKey(mc.options.keyAttack, down);
        if (down) {
            inputsActive = true;
        }
    }

    // ------------------------------------------------------------------ smooth rotation

    /** Smoothly interpolates player yaw and pitch toward target angles using default max steps. */
    public void aimSmooth(LocalPlayer player, float targetYaw, float targetPitch) {
        aimSmooth(player, targetYaw, targetPitch, DEFAULT_MAX_YAW_STEP, DEFAULT_MAX_PITCH_STEP);
    }

    /** Smoothly interpolates player yaw and pitch toward target angles clamped by max steps per tick. */
    public void aimSmooth(LocalPlayer player, float targetYaw, float targetPitch, float maxYawStep, float maxPitchStep) {
        if (player == null) {
            return;
        }
        aimYawSmooth(player, targetYaw, maxYawStep);
        aimPitchSmooth(player, targetPitch, maxPitchStep);
    }

    /** Smoothly interpolates player yaw toward {@code targetYaw}. */
    public void aimYawSmooth(LocalPlayer player, float targetYaw, float maxYawStep) {
        if (player == null) {
            return;
        }
        float yawDelta = Mth.wrapDegrees(targetYaw - player.getYRot());
        player.setYRot(player.getYRot() + Mth.clamp(yawDelta, -maxYawStep, maxYawStep));
    }

    /** Smoothly interpolates player pitch toward {@code targetPitch}. */
    public void aimPitchSmooth(LocalPlayer player, float targetPitch, float maxPitchStep) {
        if (player == null) {
            return;
        }
        float pitchDelta = targetPitch - player.getXRot();
        player.setXRot(Mth.clamp(player.getXRot() + Mth.clamp(pitchDelta, -maxPitchStep, maxPitchStep), -90.0f, 90.0f));
    }

    /** Smoothly aims the player toward the center of {@code block} using default rotation steps. */
    public void aimAtBlock(LocalPlayer player, BlockPos block) {
        aimAtBlock(player, block, DEFAULT_MAX_YAW_STEP, DEFAULT_MAX_PITCH_STEP);
    }

    /** Smoothly aims the player toward the center of {@code block}. */
    public void aimAtBlock(LocalPlayer player, BlockPos block, float maxYawStep, float maxPitchStep) {
        if (player == null || block == null) {
            return;
        }
        aimAtPos(player, new Vec3(block.getX() + 0.5, block.getY() + 0.5, block.getZ() + 0.5), maxYawStep, maxPitchStep);
    }

    /** Smoothly aims the player toward {@code target} world coordinates using default rotation steps. */
    public void aimAtPos(LocalPlayer player, Vec3 target) {
        aimAtPos(player, target, DEFAULT_MAX_YAW_STEP, DEFAULT_MAX_PITCH_STEP);
    }

    /** Smoothly aims the player toward {@code target} world coordinates. */
    public void aimAtPos(LocalPlayer player, Vec3 target, float maxYawStep, float maxPitchStep) {
        if (player == null || target == null) {
            return;
        }
        double dx = target.x - player.getX();
        double dy = target.y - player.getEyeY();
        double dz = target.z - player.getZ();
        double horiz = Math.hypot(dx, dz);
        float targetYaw = horiz > 1e-4 ? yawTo(dx, dz) : player.getYRot();
        float targetPitch = pitchTo(dx, dy, dz);
        aimSmooth(player, targetYaw, targetPitch, maxYawStep, maxPitchStep);
    }

    /** Computes Minecraft yaw degrees for a horizontal delta {@code (dx, dz)}. */
    public static float yawTo(double dx, double dz) {
        return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
    }

    /** Computes Minecraft pitch degrees for a delta {@code (dx, dy, dz)} from player eye position. */
    public static float pitchTo(double dx, double dy, double dz) {
        double horiz = Math.hypot(dx, dz);
        return (float) -Math.toDegrees(Math.atan2(dy, Math.max(1e-4, horiz)));
    }

    // ------------------------------------------------------------------ block breaking

    /**
     * Advances block destruction on {@code pos} for one client tick, starting or continuing
     * {@code mc.gameMode}'s destroy sequence and swinging the main hand.
     *
     * @return {@code true} if destroy packets were sent this tick
     */
    public boolean breakBlockTick(Minecraft mc, BlockPos pos) {
        if (mc == null || mc.player == null || mc.gameMode == null || pos == null) {
            return false;
        }
        LocalPlayer player = mc.player;
        Direction hitDir = Direction.getApproximateNearest(
                player.getX() - (pos.getX() + 0.5),
                player.getEyeY() - (pos.getY() + 0.5),
                player.getZ() - (pos.getZ() + 0.5));
        if (!pos.equals(breakingPos)) {
            breakingPos = pos.immutable();
            mc.gameMode.startDestroyBlock(breakingPos, hitDir);
        } else {
            mc.gameMode.continueDestroyBlock(breakingPos, hitDir);
        }
        player.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
        inputsActive = true;
        return true;
    }

    /** Stops any active block breaking sequence cleanly. */
    public void stopBreaking(Minecraft mc) {
        if (breakingPos != null) {
            if (mc != null && mc.gameMode != null) {
                mc.gameMode.stopDestroyBlock();
            }
            breakingPos = null;
        }
    }

    // ------------------------------------------------------------------ block placement

    /** Places the held main-hand block at {@code placePos} against any adjacent solid neighbor. */
    public boolean placeBlock(Minecraft mc, BlockPos placePos, boolean horizontalFirst) {
        return mc != null && placeBlock(mc, mc.player, placePos, horizontalFirst);
    }

    /** Returns true if right-clicking {@code block} without sneaking would open a GUI or interact instead of placing. */
    public static boolean isInteractiveSupport(net.minecraft.world.level.block.Block block) {
        return block instanceof net.minecraft.world.level.block.EntityBlock
                || block instanceof net.minecraft.world.level.block.CraftingTableBlock
                || block instanceof net.minecraft.world.level.block.AnvilBlock
                || block instanceof net.minecraft.world.level.block.DoorBlock
                || block instanceof net.minecraft.world.level.block.TrapDoorBlock
                || block instanceof net.minecraft.world.level.block.FenceGateBlock
                || block instanceof net.minecraft.world.level.block.ButtonBlock
                || block instanceof net.minecraft.world.level.block.LeverBlock
                || block instanceof net.minecraft.world.level.block.BedBlock
                || block instanceof net.minecraft.world.level.block.NoteBlock
                || block instanceof net.minecraft.world.level.block.RepeaterBlock
                || block instanceof net.minecraft.world.level.block.ComparatorBlock
                || block instanceof net.minecraft.world.level.block.CakeBlock
                || block instanceof net.minecraft.world.level.block.RespawnAnchorBlock
                || block instanceof net.minecraft.world.level.block.GrindstoneBlock
                || block instanceof net.minecraft.world.level.block.LoomBlock
                || block instanceof net.minecraft.world.level.block.CartographyTableBlock
                || block instanceof net.minecraft.world.level.block.StonecutterBlock
                || block instanceof net.minecraft.world.level.block.SmithingTableBlock
                || block instanceof net.minecraft.world.level.block.ComposterBlock;
    }

    private static Vec3 supportFaceHit(Minecraft mc, BlockPos support, Direction face) {
        var shape = mc.level.getBlockState(support).getShape(mc.level, support);
        if (!shape.isEmpty()) {
            net.minecraft.world.phys.AABB bb = shape.bounds();
            double hx = support.getX() + (face == Direction.WEST ? bb.minX : face == Direction.EAST ? bb.maxX : (bb.minX + bb.maxX) * 0.5);
            double hy = support.getY() + (face == Direction.DOWN ? bb.minY : face == Direction.UP ? bb.maxY : (bb.minY + bb.maxY) * 0.5);
            double hz = support.getZ() + (face == Direction.NORTH ? bb.minZ : face == Direction.SOUTH ? bb.maxZ : (bb.minZ + bb.maxZ) * 0.5);
            return new Vec3(hx, hy, hz);
        }
        return Vec3.atCenterOf(support).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
    }

    /** Places the held main-hand block at {@code placePos} against any adjacent solid neighbor. */
    public boolean placeBlock(Minecraft mc, LocalPlayer player, BlockPos placePos, boolean horizontalFirst) {
        if (mc == null || mc.gameMode == null || mc.level == null || player == null || placePos == null) {
            return false;
        }
        if (player.containerMenu != player.inventoryMenu) {
            player.closeContainer();
            return false;
        }
        Direction[] dirs = horizontalFirst
                ? new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN, Direction.UP}
                : Direction.values();
        for (Direction dir : dirs) {
            BlockPos support = placePos.relative(dir);
            var supportState = mc.level.getBlockState(support);
            if (supportState.canBeReplaced()) continue;
            Direction face = dir.getOpposite();
            Vec3 hitVec = supportFaceHit(mc, support, face);
            if (player.getEyePosition().distanceToSqr(hitVec) > 20.25) continue;
            BlockHitResult hit = new BlockHitResult(hitVec, face, support, false);
            boolean sneak = isInteractiveSupport(supportState.getBlock());
            if (sneak && !player.isShiftKeyDown()) {
                if (mc.options != null) mc.options.keyShift.setDown(true);
                player.setShiftKeyDown(true);
                return false; // wait 1 tick for shift=true to sync to server
            }
            var res = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
            if (res.consumesAction()) {
                player.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
                return true;
            }
            return false;
        }
        return false;
    }

    /** Places a pillar block at {@code placePos} by clicking the top face of {@code placePos.below()}. */
    public boolean placePillarBlock(Minecraft mc, BlockPos placePos) {
        return mc != null && placePillarBlock(mc, mc.player, placePos);
    }

    /** Places a pillar block at {@code placePos} by clicking the top face of {@code placePos.below()}. */
    public boolean placePillarBlock(Minecraft mc, LocalPlayer player, BlockPos placePos) {
        if (mc == null || mc.gameMode == null || mc.level == null || player == null || placePos == null) {
            return false;
        }
        if (player.containerMenu != player.inventoryMenu) {
            player.closeContainer();
            return false;
        }
        BlockPos support = placePos.below();
        var supportState = mc.level.getBlockState(support);
        if (supportState.canBeReplaced()) return false;
        Vec3 hitVec = supportFaceHit(mc, support, Direction.UP);
        BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, support, false);
        boolean sneak = isInteractiveSupport(supportState.getBlock());
        if (sneak && !player.isShiftKeyDown()) {
            if (mc.options != null) mc.options.keyShift.setDown(true);
            player.setShiftKeyDown(true);
            return false; // wait 1 tick for shift=true to sync to server
        }
        var res = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        if (res.consumesAction()) {
            player.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ direct local steering

    /**
     * One tick of direct steering toward {@code target} without A* (e.g. close-range combat pursuit
     * or short-range adjustments).
     */
    public void walkDirectTick(Minecraft mc, Vec3 target, boolean sprint) {
        if (target == null) {
            return;
        }
        walkDirectTick(mc, target.x, target.y, target.z, sprint, true);
    }

    /**
     * One tick of direct steering toward {@code (targetX, targetY, targetZ)}.
     */
    public void walkDirectTick(
            Minecraft mc,
            double targetX,
            double targetY,
            double targetZ,
            boolean sprint,
            boolean jumpOnObstacle) {
        if (mc == null || mc.player == null) {
            return;
        }
        LocalPlayer player = mc.player;
        double dx = targetX - player.getX();
        double dz = targetZ - player.getZ();
        double horizDist = Math.hypot(dx, dz);

        if (horizDist > 1e-4) {
            float desiredYaw = yawTo(dx, dz);
            aimYawSmooth(player, desiredYaw, DEFAULT_MAX_YAW_STEP);
        }

        float desiredYaw = horizDist > 1e-4 ? yawTo(dx, dz) : player.getYRot();
        float relAngle = Mth.wrapDegrees(desiredYaw - player.getYRot());

        boolean move = horizDist > 0.15;
        boolean forward = move && Math.abs(relAngle) <= 65.0f;
        boolean back = move && Math.abs(relAngle) >= 135.0f;
        boolean left = move && relAngle < -25.0f && relAngle > -155.0f;
        boolean right = move && relAngle > 25.0f && relAngle < 155.0f;
        boolean jump = jumpOnObstacle
                && ((player.horizontalCollision && player.onGround())
                || (player.isInWater() && player.getY() < targetY));
        boolean doSprint = sprint && forward && !player.isInWater();

        setKeys(mc, forward, back, left, right, jump, false, doSprint, false, false);
    }

    /**
     * One tick of direct swimming toward {@code target} (or rising to the surface when {@code riseForAir} is true).
     */
    public void swimTick(Minecraft mc, Vec3 target, boolean riseForAir) {
        if (target == null) {
            if (mc != null && mc.player != null) {
                swimTick(mc, mc.player.getX(), mc.player.getY() + 2.0, mc.player.getZ(), riseForAir);
            }
            return;
        }
        swimTick(mc, target.x, target.y, target.z, riseForAir);
    }

    /**
     * One tick of direct swimming toward {@code (targetX, targetY, targetZ)}.
     */
    public void swimTick(Minecraft mc, double targetX, double targetY, double targetZ, boolean riseForAir) {
        if (mc == null || mc.player == null) {
            return;
        }
        LocalPlayer player = mc.player;
        double dx = targetX - player.getX();
        double dz = targetZ - player.getZ();
        double horizDist = Math.hypot(dx, dz);

        float targetYaw = horizDist > 1e-4 ? yawTo(dx, dz) : player.getYRot();
        float targetPitch = riseForAir ? -45.0f : pitchTo(dx, targetY - player.getEyeY(), dz);
        aimSmooth(player, targetYaw, targetPitch, DEFAULT_MAX_YAW_STEP, DEFAULT_MAX_PITCH_STEP);

        float relAngle = Mth.wrapDegrees(targetYaw - player.getYRot());
        boolean forward = horizDist > 0.25 && Math.abs(relAngle) <= 75.0f;
        boolean jump = riseForAir
                || (player.isInWater() && player.getY() < targetY + 0.2)
                || player.horizontalCollision;

        setKeys(mc, forward, false, false, false, jump, false, false, false, false);
    }

    // ------------------------------------------------------------------ internals

    private void releaseInputsInternal(Minecraft mc) {
        if (!inputsActive && breakingPos == null) {
            return;
        }
        if (mc != null) {
            Options options = mc.options;
            if (options != null) {
                setKey(options.keyUp, false);
                setKey(options.keyDown, false);
                setKey(options.keyLeft, false);
                setKey(options.keyRight, false);
                setKey(options.keyJump, false);
                setKey(options.keyShift, false);
                setKey(options.keySprint, false);
                setKey(options.keyAttack, false);
                setKey(options.keyUse, false);
            }
            LocalPlayer player = mc.player;
            if (player != null) {
                player.setShiftKeyDown(false);
                player.setSprinting(false);
            }
            if (mc.gameMode != null) {
                mc.gameMode.stopDestroyBlock();
            }
        }
        breakingPos = null;
        inputsActive = false;
    }

    private static void setKey(KeyMapping key, boolean down) {
        if (key != null) {
            key.setDown(down);
        }
    }

    private static Minecraft safeMinecraft() {
        try {
            return Minecraft.getInstance();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
