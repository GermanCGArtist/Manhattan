package dev.midtown.exec;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Client-tick movement and rotation controller for Midtown.
 * <p>
 * Drives {@link LocalPlayer} yaw/pitch via smooth clamped interpolation and updates {@link Options}
 * movement {@link KeyMapping} states toward the active {@link PathNode}. All movement, attack,
 * use, sprint, and sneak states are explicitly released on {@link #releaseAllInputs(Minecraft)}.
 */
public final class MidtownExecutor {

    private static final float DEFAULT_MAX_YAW_STEP = 30.0f;
    private static final float DEFAULT_MAX_PITCH_STEP = 20.0f;

    private float maxYawStep = DEFAULT_MAX_YAW_STEP;
    private float maxPitchStep = DEFAULT_MAX_PITCH_STEP;
    private boolean inputsActive;
    private int placeCooldown;
    private BlockPos breakingPos;
    private int itemSaverThreshold;

    /**
     * Applies one client tick of rotation and movement input toward {@code target}.
     */
    public void tick(Minecraft mc, PathNode target) {
        if (mc == null || mc.player == null || target == null) {
            releaseAllInputs(mc);
            return;
        }
        LocalPlayer player = mc.player;
        Options options = mc.options;

        double targetX = target.pos().getX() + 0.5;
        double targetZ = target.pos().getZ() + 0.5;
        double dx = targetX - player.getX();
        double dz = targetZ - player.getZ();
        double horizDist = Math.hypot(dx, dz);

        boolean attack = false;
        boolean blockBreakingWait = false;
        BlockPos aimBlock = target.targetBlock();

        if (placeCooldown > 0) placeCooldown--;

        if (mc.level != null && !target.targetBlocks().isEmpty()) {
            if (target.action() == PathNode.ActionType.BREAK_THROUGH) {
                for (BlockPos tb : target.targetBlocks()) {
                    BlockState state = mc.level.getBlockState(tb);
                    if (!state.isAir() && state.getFluidState().isEmpty()) {
                        aimBlock = tb;
                        selectBestTool(player, state, itemSaverThreshold);
                        attack = true;
                        blockBreakingWait = true;
                        break;
                    }
                }
            } else if (target.action() == PathNode.ActionType.BRIDGE) {
                BlockPos tb = target.targetBlock();
                if (tb != null && placeCooldown == 0 && mc.level.getBlockState(tb).canBeReplaced() && selectExpendableBlock(mc, player)) {
                    if (tryPlaceBlock(mc, player, tb, true)) {
                        placeCooldown = 4;
                    }
                }
            } else if (target.action() == PathNode.ActionType.PILLAR) {
                BlockPos tb = target.targetBlock();
                if (tb != null
                        && placeCooldown == 0
                        && mc.level.getBlockState(tb).canBeReplaced()
                        && player.getY() >= tb.getY() + 1.0
                        && selectExpendableBlock(mc, player)) {
                    if (tryPlacePillarBlock(mc, player, tb)) {
                        placeCooldown = 4;
                    }
                }
            }
        }

        if (blockBreakingWait && aimBlock != null && mc.gameMode != null) {
            Direction hitDir = Direction.getApproximateNearest(
                    player.getX() - (aimBlock.getX() + 0.5),
                    player.getEyeY() - (aimBlock.getY() + 0.5),
                    player.getZ() - (aimBlock.getZ() + 0.5));
            if (!aimBlock.equals(breakingPos)) {
                breakingPos = aimBlock;
                mc.gameMode.startDestroyBlock(aimBlock, hitDir);
            } else {
                mc.gameMode.continueDestroyBlock(aimBlock, hitDir);
            }
            player.swing(InteractionHand.MAIN_HAND);
        } else if (breakingPos != null) {
            if (mc.gameMode != null) {
                mc.gameMode.stopDestroyBlock();
            }
            breakingPos = null;
        }

        if (blockBreakingWait && aimBlock != null) {
            double bx = aimBlock.getX() + 0.5 - player.getX();
            double bz = aimBlock.getZ() + 0.5 - player.getZ();
            if (Math.hypot(bx, bz) > 1e-4) {
                float aimYaw = (float) (Math.toDegrees(Math.atan2(bz, bx)) - 90.0);
                float yawDelta = Mth.wrapDegrees(aimYaw - player.getYRot());
                player.setYRot(player.getYRot() + Mth.clamp(yawDelta, -maxYawStep, maxYawStep));
            }
        } else if (horizDist > 1e-4) {
            float desiredYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
            float yawDelta = Mth.wrapDegrees(desiredYaw - player.getYRot());
            player.setYRot(player.getYRot() + Mth.clamp(yawDelta, -maxYawStep, maxYawStep));
        }

        float desiredPitch = computeDesiredPitch(player, target, aimBlock, horizDist);
        float pitchDelta = desiredPitch - player.getXRot();
        player.setXRot(Mth.clamp(player.getXRot() + Mth.clamp(pitchDelta, -maxPitchStep, maxPitchStep), -90.0f, 90.0f));

        // Drive movement keys relative to current player yaw
        float desiredYaw = horizDist > 1e-4
                ? (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0)
                : player.getYRot();
        float relAngle = Mth.wrapDegrees(desiredYaw - player.getYRot());

        boolean isPillar = target.action() == PathNode.ActionType.PILLAR;
        double moveThresh = isPillar ? 0.25 : 0.12;
        boolean moveAllowed = !blockBreakingWait;
        boolean forward = moveAllowed && horizDist > moveThresh && Math.abs(relAngle) <= 65.0f;
        boolean back = moveAllowed && horizDist > moveThresh && Math.abs(relAngle) >= 135.0f;
        boolean left = moveAllowed && horizDist > moveThresh && relAngle < -25.0f && relAngle > -155.0f;
        boolean right = moveAllowed && horizDist > moveThresh && relAngle > 25.0f && relAngle < 155.0f;

        boolean jump = shouldJump(player, target);
        boolean sneak = target.sneak();
        boolean sprint = target.sprint() && forward && !sneak && !player.isInWater();

        setKey(options.keyUp, forward);
        setKey(options.keyDown, back);
        setKey(options.keyLeft, left);
        setKey(options.keyRight, right);
        setKey(options.keyJump, jump);
        setKey(options.keyShift, sneak);
        setKey(options.keySprint, sprint);
        setKey(options.keyAttack, attack);
        setKey(options.keyUse, false);

        player.setShiftKeyDown(sneak);
        player.setSprinting(sprint);
        inputsActive = true;
    }

    /**
     * Explicitly releases all movement, jump, sneak, sprint, attack, and use key states.
     * Safe to call even when no inputs are currently held.
     */
    public void releaseAllInputs(Minecraft mc) {
        if (!inputsActive) {
            return;
        }
        if (mc == null) {
            inputsActive = false;
            return;
        }
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
        breakingPos = null;
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

    /** Places a pillar block at {@code placePos} by clicking the top face of {@code placePos.below()}. */
    private static boolean tryPlacePillarBlock(Minecraft mc, LocalPlayer player, BlockPos placePos) {
        if (mc.gameMode == null || mc.level == null) return false;
        BlockPos support = placePos.below();
        if (mc.level.getBlockState(support).canBeReplaced()) return false;
        Vec3 hitVec = Vec3.atCenterOf(support).add(0.0, 0.5, 0.0);
        BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, support, false);
        var res = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        if (res.consumesAction()) {
            player.swing(InteractionHand.MAIN_HAND);
            return true;
        }
        return false;
    }

    /** Places a block at {@code placePos} against any adjacent solid neighbor. */
    private static boolean tryPlaceBlock(Minecraft mc, LocalPlayer player, BlockPos placePos, boolean horizontalFirst) {
        if (mc.gameMode == null || mc.level == null) return false;
        Direction[] dirs = horizontalFirst
                ? new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN, Direction.UP}
                : Direction.values();
        for (Direction dir : dirs) {
            BlockPos support = placePos.relative(dir);
            if (mc.level.getBlockState(support).canBeReplaced()) continue;
            Direction face = dir.getOpposite();
            Vec3 hitVec = Vec3.atCenterOf(support).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            if (player.getEyePosition().distanceToSqr(hitVec) > 20.25) continue;
            BlockHitResult hit = new BlockHitResult(hitVec, face, support, false);
            var res = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
            if (res.consumesAction()) {
                player.swing(InteractionHand.MAIN_HAND);
                return true;
            }
            return false;
        }
        return false;
    }

    public int itemSaverThreshold() {
        return itemSaverThreshold;
    }

    public void setItemSaverThreshold(int itemSaverThreshold) {
        if (itemSaverThreshold < 0) throw new IllegalArgumentException("itemSaverThreshold must be >= 0");
        this.itemSaverThreshold = itemSaverThreshold;
    }

    /** Selects the fastest tool in hotbar {@code 0..8} for breaking {@code state}, skipping low-durability tools when {@code itemSaverThreshold > 0}. */
    private static void selectBestTool(LocalPlayer player, BlockState state, int itemSaverThreshold) {
        Inventory inv = player.getInventory();
        int cur = inv.getSelectedSlot();
        int safeCur = (cur >= 0 && cur < 9) ? cur : 0;
        int bestSlot = -1;
        float bestSpeed = -1.0f;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getItem(i);
            if (isLowDurabilityTool(stack, itemSaverThreshold)) {
                continue;
            }
            float speed = stack.getDestroySpeed(state);
            if (speed > bestSpeed || (speed == bestSpeed && i == safeCur)) {
                bestSpeed = speed;
                bestSlot = i;
            }
        }
        if (bestSlot != -1 && bestSlot != cur) {
            inv.setSelectedSlot(bestSlot);
        }
    }

    private static boolean isLowDurabilityTool(ItemStack stack, int threshold) {
        return threshold > 0
                && !stack.isEmpty()
                && stack.isDamageableItem()
                && (stack.getMaxDamage() - stack.getDamageValue()) <= threshold;
    }

    /**
     * Ensures an expendable solid building block is selected in the hotbar, swapping from main
     * inventory if needed. Returns {@code true} if one is now selected.
     */
    private static boolean selectExpendableBlock(Minecraft mc, LocalPlayer player) {
        Inventory inv = player.getInventory();
        int cur = inv.getSelectedSlot();
        if (cur >= 0 && cur < 9 && isExpendableBlock(inv.getItem(cur))) {
            return true;
        }
        for (int i = 0; i < 9; i++) {
            if (isExpendableBlock(inv.getItem(i))) {
                inv.setSelectedSlot(i);
                return true;
            }
        }
        if (mc.gameMode == null) return false;
        int targetHotbar = (cur >= 0 && cur < 9) ? cur : 0;
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).isEmpty()) {
                targetHotbar = i;
                break;
            }
        }
        for (int i = 9; i < 36; i++) {
            if (isExpendableBlock(inv.getItem(i))) {
                mc.gameMode.handleContainerInput(
                        player.inventoryMenu.containerId, i, targetHotbar, ContainerInput.SWAP, player);
                inv.setSelectedSlot(targetHotbar);
                return true;
            }
        }
        return false;
    }

    private static boolean isExpendableBlock(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem bi)) return false;
        Block b = bi.getBlock();
        if (b instanceof FallingBlock || b instanceof EntityBlock) return false;
        BlockState state = b.defaultBlockState();
        if (state.is(BlockTags.FENCES) || state.is(BlockTags.WALLS)
                || state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS)
                || state.is(BlockTags.SLABS) || state.is(BlockTags.STAIRS)
                || state.is(BlockTags.CAMPFIRES) || state.is(BlockTags.FIRE)) {
            return false;
        }
        return state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    private static float computeDesiredPitch(LocalPlayer player, PathNode target, BlockPos aimBlock, double horizDist) {
        if (target.action() == PathNode.ActionType.PILLAR) {
            return 90.0f;
        }
        if (aimBlock != null) {
            double bx = aimBlock.getX() + 0.5 - player.getX();
            double by = aimBlock.getY() + 0.5 - player.getEyeY();
            double bz = aimBlock.getZ() + 0.5 - player.getZ();
            double bHoriz = Math.hypot(bx, bz);
            return (float) -Math.toDegrees(Math.atan2(by, Math.max(1e-4, bHoriz)));
        }
        if (target.action() == PathNode.ActionType.ASCEND) {
            return -10.0f;
        }
        if (target.action() == PathNode.ActionType.DESCEND || target.action() == PathNode.ActionType.FALL) {
            return 15.0f;
        }
        return horizDist > 0.5 ? 0.0f : player.getXRot();
    }

    private static boolean shouldJump(LocalPlayer player, PathNode target) {
        if (target.action() == PathNode.ActionType.ASCEND
                || target.action() == PathNode.ActionType.PARKOUR
                || target.action() == PathNode.ActionType.PILLAR) {
            return player.onGround() || player.isInWater();
        }
        if (player.isInWater() && player.getY() < target.pos().getY()) {
            return true;
        }
        return player.horizontalCollision && player.onGround() && target.pos().getY() >= player.getBlockY();
    }

    private static void setKey(KeyMapping key, boolean down) {
        if (key != null) {
            key.setDown(down);
        }
    }
}
