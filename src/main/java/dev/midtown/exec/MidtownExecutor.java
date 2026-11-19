package dev.midtown.exec;

import dev.midtown.body.BodyController;
import dev.midtown.body.HotbarController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Client-tick movement and rotation controller for Midtown.
 * <p>
 * Delegates all key presses, camera rotation, block breaking, block placement, and tool/block
 * hotbar selection to {@link BodyController#INSTANCE} (under motor owner {@value #MOTOR_OWNER})
 * and {@link HotbarController}.
 */
public final class MidtownExecutor {

    public static final String MOTOR_OWNER = "midtown";

    private static final float DEFAULT_MAX_YAW_STEP = BodyController.DEFAULT_MAX_YAW_STEP;
    private static final float DEFAULT_MAX_PITCH_STEP = BodyController.DEFAULT_MAX_PITCH_STEP;

    private float maxYawStep = DEFAULT_MAX_YAW_STEP;
    private float maxPitchStep = DEFAULT_MAX_PITCH_STEP;
    private boolean inputsActive;
    private int placeCooldown;
    private int itemSaverThreshold;

    /**
     * Applies one client tick of rotation and movement input toward {@code target}.
     */
    public void tick(Minecraft mc, PathNode target) {
        if (mc == null || mc.player == null || target == null) {
            releaseAllInputs(mc);
            return;
        }
        BodyController body = BodyController.INSTANCE;
        if (!body.acquire(mc, MOTOR_OWNER)) {
            inputsActive = false;
            return;
        }

        LocalPlayer player = mc.player;

        double targetX = target.pos().getX() + 0.5;
        double targetZ = target.pos().getZ() + 0.5;
        double dx = targetX - player.getX();
        double dz = targetZ - player.getZ();
        double horizDist = Math.hypot(dx, dz);

        boolean attack = false;
        boolean blockBreakingWait = false;
        boolean landingMissing = false;
        boolean placingLanding = false;
        BlockPos aimBlock = target.action() == PathNode.ActionType.PARKOUR ? null : target.targetBlock();

        if (placeCooldown > 0) placeCooldown--;

        if (mc.level != null && !target.targetBlocks().isEmpty()) {
            if (target.action() == PathNode.ActionType.BREAK_THROUGH) {
                for (BlockPos tb : target.targetBlocks()) {
                    BlockState state = mc.level.getBlockState(tb);
                    if (!state.isAir() && state.getFluidState().isEmpty()) {
                        aimBlock = tb;
                        HotbarController.selectBestTool(mc, player, state, itemSaverThreshold);
                        attack = true;
                        blockBreakingWait = true;
                        break;
                    }
                }
            } else if (target.action() == PathNode.ActionType.PARKOUR) {
                // Parkour onto a landing that needs a block: place it from the takeoff block, never mid-air,
                // and hold still until it is there (the jump waits for it, see shouldJump).
                BlockPos tb = target.targetBlock();
                if (tb != null && mc.level.getBlockState(tb).canBeReplaced()) {
                    landingMissing = true;
                    if (player.onGround()) {
                        placingLanding = true;
                        aimBlock = tb;
                        if (placeCooldown == 0 && HotbarController.selectExpendableBlock(mc, player)
                                && body.placeBlock(mc, player, tb, true)) {
                            placeCooldown = 4;
                        }
                    }
                }
            } else if (target.action() == PathNode.ActionType.BRIDGE) {
                BlockPos tb = target.targetBlock();
                if (tb != null && placeCooldown == 0 && player.onGround()
                        && mc.level.getBlockState(tb).canBeReplaced()
                        && HotbarController.selectExpendableBlock(mc, player)) {
                    if (body.placeBlock(mc, player, tb, true)) {
                        placeCooldown = 4;
                    }
                }
            } else if (target.action() == PathNode.ActionType.PILLAR) {
                BlockPos tb = target.targetBlock();
                if (tb != null
                        && placeCooldown == 0
                        && mc.level.getBlockState(tb).canBeReplaced()
                        && player.getY() >= tb.getY() + 1.0
                        && HotbarController.selectExpendableBlock(mc, player)) {
                    if (body.placePillarBlock(mc, player, tb)) {
                        placeCooldown = 4;
                    }
                }
            }
        }

        if (blockBreakingWait && aimBlock != null) {
            body.breakBlockTick(mc, aimBlock);
        } else {
            body.stopBreaking(mc);
        }

        float aimYaw = player.getYRot();
        if (blockBreakingWait && aimBlock != null) {
            double bx = aimBlock.getX() + 0.5 - player.getX();
            double bz = aimBlock.getZ() + 0.5 - player.getZ();
            if (Math.hypot(bx, bz) > 1e-4) {
                aimYaw = BodyController.yawTo(bx, bz);
            }
        } else if (horizDist > 1e-4) {
            aimYaw = BodyController.yawTo(dx, dz);
        }

        float desiredPitch = computeDesiredPitch(player, target, aimBlock, horizDist);
        body.aimSmooth(player, aimYaw, desiredPitch, maxYawStep, maxPitchStep);

        // Drive movement keys relative to current player yaw
        float desiredYaw = horizDist > 1e-4 ? BodyController.yawTo(dx, dz) : player.getYRot();
        float relAngle = Mth.wrapDegrees(desiredYaw - player.getYRot());

        boolean isPillar = target.action() == PathNode.ActionType.PILLAR;
        double moveThresh = isPillar ? 0.25 : 0.12;
        boolean moveAllowed = !blockBreakingWait && !placingLanding;
        boolean forward = moveAllowed && horizDist > moveThresh && Math.abs(relAngle) <= 65.0f;
        boolean back = moveAllowed && horizDist > moveThresh && Math.abs(relAngle) >= 135.0f;
        boolean left = moveAllowed && horizDist > moveThresh && relAngle < -25.0f && relAngle > -155.0f;
        boolean right = moveAllowed && horizDist > moveThresh && relAngle > 25.0f && relAngle < 155.0f;

        boolean jump = shouldJump(player, target, horizDist, relAngle, landingMissing);
        boolean sneak = target.sneak();
        boolean sprint = target.sprint() && forward && !sneak && !player.isInWater();

        body.setKeys(mc, forward, back, left, right, jump, sneak, sprint, attack, false);
        inputsActive = true;
    }

    /**
     * Explicitly releases all movement, jump, sneak, sprint, attack, and use key states
     * if Midtown currently owns {@link BodyController#INSTANCE}.
     * Safe to call even when no inputs are currently held.
     */
    public void releaseAllInputs(Minecraft mc) {
        if (!inputsActive) {
            return;
        }
        BodyController.INSTANCE.releaseIfOwner(mc, MOTOR_OWNER);
        placeCooldown = 0;
        inputsActive = false;
    }

    public boolean areInputsActive() {
        return inputsActive;
    }

    public float maxYawStep() {
        return maxYawStep;
    }

    public void setMaxYawStep(float maxYawStep) {
        if (maxYawStep <= 0) throw new IllegalArgumentException("maxYawStep must be > 0");
        this.maxYawStep = maxYawStep;
    }

    public float maxPitchStep() {
        return maxPitchStep;
    }

    public void setMaxPitchStep(float maxPitchStep) {
        if (maxPitchStep <= 0) throw new IllegalArgumentException("maxPitchStep must be > 0");
        this.maxPitchStep = maxPitchStep;
    }

    public int itemSaverThreshold() {
        return itemSaverThreshold;
    }

    public void setItemSaverThreshold(int itemSaverThreshold) {
        if (itemSaverThreshold < 0) throw new IllegalArgumentException("itemSaverThreshold must be >= 0");
        this.itemSaverThreshold = itemSaverThreshold;
    }

    private static float computeDesiredPitch(LocalPlayer player, PathNode target, BlockPos aimBlock, double horizDist) {
        if (target.action() == PathNode.ActionType.PILLAR) {
            return 90.0f;
        }
        if (aimBlock != null) {
            double bx = aimBlock.getX() + 0.5 - player.getX();
            double by = aimBlock.getY() + 0.5 - player.getEyeY();
            double bz = aimBlock.getZ() + 0.5 - player.getZ();
            return BodyController.pitchTo(bx, by, bz);
        }
        if (target.action() == PathNode.ActionType.ASCEND) {
            return -10.0f;
        }
        if (target.action() == PathNode.ActionType.DESCEND || target.action() == PathNode.ActionType.FALL) {
            return 15.0f;
        }
        return horizDist > 0.5 ? 0.0f : player.getXRot();
    }

    private static boolean shouldJump(LocalPlayer player, PathNode target, double horizDist, float relAngle,
                                      boolean landingMissing) {
        if (target.action() == PathNode.ActionType.PARKOUR) {
            if (player.isInWater()) return true;
            if (!player.onGround() || landingMissing) return false;
            // Jump at the takeoff edge, facing the landing: from the takeoff centre the landing is
            // gap + 1 away, the edge gap + 0.5. Jumping from the centre loses half a block of reach,
            // jumping sideways (still turning) misses the landing.
            int dist = Math.max(Math.abs(target.pos().getX() - player.getBlockX()),
                    Math.abs(target.pos().getZ() - player.getBlockZ()));
            boolean atEdge = horizDist <= dist - 0.35 || !groundAhead(player, target);
            return atEdge && Math.abs(relAngle) <= 20.0f;
        }
        if (target.action() == PathNode.ActionType.ASCEND
                || target.action() == PathNode.ActionType.PILLAR) {
            return player.onGround() || player.isInWater();
        }
        if (player.isInWater() && player.getY() < target.pos().getY()) {
            return true;
        }
        return player.horizontalCollision && player.onGround() && target.pos().getY() >= player.getBlockY();
    }

    /** True if there is still ground under the player's feet 0.35 blocks further toward {@code target}. */
    private static boolean groundAhead(LocalPlayer player, PathNode target) {
        double dx = target.pos().getX() + 0.5 - player.getX();
        double dz = target.pos().getZ() + 0.5 - player.getZ();
        double len = Math.hypot(dx, dz);
        if (len < 1e-4) return true;
        BlockPos ahead = BlockPos.containing(player.getX() + dx / len * 0.35, player.getY() - 0.5,
                player.getZ() + dz / len * 0.35);
        return !player.level().getBlockState(ahead).getCollisionShape(player.level(), ahead).isEmpty();
    }
}
