package dev.midtown.body;

import dev.midtown.plan.CalculationContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Predicate;

/**
 * Single authority for hotbar slot selection and main-inventory-to-hotbar swaps.
 * <p>
 * Supports an optional reserved hotbar slot bitmask (bits {@code 0..8}, e.g. slot 8 for food)
 * so swaps from the main inventory avoid overwriting reserved slots whenever non-reserved
 * alternatives exist.
 */
public final class HotbarController {

    /** Bitmask of all 9 hotbar slots ({@code 0..8}). */
    private static final int ALL_HOTBAR_MASK = 0x1FF;

    private static volatile int reservedSlotMask;

    private HotbarController() {}

    // ------------------------------------------------------------------ reserved slot mask

    /** Returns the current reserved hotbar slot bitmask (bits {@code 0..8}). */
    public static int reservedSlotMask() {
        return reservedSlotMask;
    }

    /** Replaces the reserved hotbar slot bitmask (bits {@code 0..8}). */
    public static void setReservedSlotMask(int mask) {
        reservedSlotMask = mask & ALL_HOTBAR_MASK;
    }

    /** Marks a hotbar slot ({@code 0..8}) as reserved against automatic swaps. */
    public static void reserveSlot(int slot) {
        validateHotbarSlot(slot);
        reservedSlotMask |= (1 << slot);
    }

    /** Clears reservation on a hotbar slot ({@code 0..8}). */
    public static void unreserveSlot(int slot) {
        validateHotbarSlot(slot);
        reservedSlotMask &= ~(1 << slot);
    }

    /** Clears all hotbar slot reservations. */
    public static void clearReservedSlots() {
        reservedSlotMask = 0;
    }

    /** Returns {@code true} if hotbar slot {@code slot} ({@code 0..8}) is reserved. */
    public static boolean isSlotReserved(int slot) {
        return slot >= 0 && slot < 9 && (reservedSlotMask & (1 << slot)) != 0;
    }

    // ------------------------------------------------------------------ selection operations

    /**
     * Selects the fastest tool in inventory {@code 0..35} for breaking {@code state}, skipping
     * low-durability tools when {@code itemSaverThreshold > 0} and swapping from main inventory
     * into the hotbar while respecting {@link #reservedSlotMask()}.
     */
    public static void selectBestTool(Minecraft mc, LocalPlayer player, BlockState state, int itemSaverThreshold) {
        selectBestTool(mc, player, state, itemSaverThreshold, reservedSlotMask);
    }

    /**
     * Selects the fastest tool in inventory {@code 0..35} for breaking {@code state}, using an
     * explicit reserved hotbar slot bitmask.
     */
    public static void selectBestTool(
            Minecraft mc,
            LocalPlayer player,
            BlockState state,
            int itemSaverThreshold,
            int reservedMask) {
        if (mc == null || player == null || state == null) {
            return;
        }
        Inventory inv = player.getInventory();
        int cur = inv.getSelectedSlot();
        int safeCur = (cur >= 0 && cur < 9) ? cur : 0;
        int bestSlot = -1;
        float bestSpeed = 1.0f;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || isLowDurabilityTool(stack, itemSaverThreshold)) {
                continue;
            }
            float speed = stack.getDestroySpeed(state);
            if (speed > bestSpeed + 0.01f
                    || (i < 9 && bestSlot >= 9 && speed >= bestSpeed - 0.01f && speed > 1.0f)
                    || (i == safeCur && speed >= bestSpeed - 0.01f && speed > 1.0f)) {
                bestSpeed = speed;
                bestSlot = i;
            }
        }

        if (bestSlot < 0 || bestSpeed <= 1.0f) {
            ItemStack curStack = inv.getItem(safeCur);
            if (isLowDurabilityTool(curStack, itemSaverThreshold)) {
                for (int i = 0; i < 9; i++) {
                    ItemStack stack = inv.getItem(i);
                    if (stack.isEmpty() || !stack.isDamageableItem()) {
                        inv.setSelectedSlot(i);
                        return;
                    }
                }
            }
            return;
        }

        if (bestSlot < 9) {
            if (bestSlot != cur) {
                inv.setSelectedSlot(bestSlot);
            }
            return;
        }

        if (mc.gameMode == null || player.containerMenu != player.inventoryMenu) {
            return;
        }
        int targetHotbar = pickHotbarSwapSlot(inv, safeCur, reservedMask);
        mc.gameMode.handleContainerInput(
                player.inventoryMenu.containerId, bestSlot, targetHotbar, ContainerInput.SWAP, player);
        inv.setSelectedSlot(targetHotbar);
    }

    /**
     * Ensures an expendable solid building block (per {@link CalculationContext#isExpendableBlockItem})
     * is selected in the hotbar, swapping from main inventory if needed.
     *
     * @return {@code true} if an expendable block is now selected
     */
    public static boolean selectExpendableBlock(Minecraft mc, LocalPlayer player) {
        if (player == null) return false;
        var inv = player.getInventory();
        return selectItem(mc, player, st -> CalculationContext.isSpareExpendable(inv, st), reservedSlotMask);
    }

    /**
     * Ensures an item matching {@code predicate} is selected in the hotbar, swapping from main
     * inventory into a non-reserved hotbar slot when needed.
     *
     * @return {@code true} if a matching item is now selected
     */
    public static boolean selectItem(Minecraft mc, LocalPlayer player, Predicate<ItemStack> predicate) {
        return selectItem(mc, player, predicate, reservedSlotMask);
    }

    /**
     * Ensures an item matching {@code predicate} is selected in the hotbar using an explicit
     * reserved hotbar slot bitmask.
     *
     * @return {@code true} if a matching item is now selected
     */
    public static boolean selectItem(
            Minecraft mc,
            LocalPlayer player,
            Predicate<ItemStack> predicate,
            int reservedMask) {
        if (mc == null || player == null || predicate == null) {
            return false;
        }
        Inventory inv = player.getInventory();
        int cur = inv.getSelectedSlot();
        int safeCur = (cur >= 0 && cur < 9) ? cur : 0;

        if (cur >= 0 && cur < 9 && predicate.test(inv.getItem(cur))) {
            return true;
        }
        for (int i = 0; i < 9; i++) {
            if (predicate.test(inv.getItem(i))) {
                inv.setSelectedSlot(i);
                return true;
            }
        }
        if (mc.gameMode == null || player.containerMenu != player.inventoryMenu) {
            return false;
        }
        for (int i = 9; i < 36; i++) {
            if (predicate.test(inv.getItem(i))) {
                int targetHotbar = pickHotbarSwapSlot(inv, safeCur, reservedMask);
                mc.gameMode.handleContainerInput(
                        player.inventoryMenu.containerId, i, targetHotbar, ContainerInput.SWAP, player);
                inv.setSelectedSlot(targetHotbar);
                return true;
            }
        }
        return false;
    }

    /**
     * Returns {@code true} if {@code stack} is a damageable tool whose remaining durability is at
     * or below {@code threshold} (when {@code threshold > 0}).
     */
    public static boolean isLowDurabilityTool(ItemStack stack, int threshold) {
        return threshold > 0
                && stack != null
                && !stack.isEmpty()
                && stack.isDamageableItem()
                && (stack.getMaxDamage() - stack.getDamageValue()) <= threshold;
    }

    // ------------------------------------------------------------------ swap slot selection

    private static int pickHotbarSwapSlot(Inventory inv, int selected, int reservedMask) {
        boolean[] empty = new boolean[9];
        boolean[] protectedItem = new boolean[9];
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getItem(i);
            empty[i] = stack.isEmpty();
            protectedItem[i] = !empty[i] && (stack.has(DataComponents.FOOD) || stack.isDamageableItem());
        }
        return pickSwapSlotIndex(empty, protectedItem, selected, reservedMask);
    }

    /**
     * Pure-Java swap-slot chooser for hotbar {@code 0..8}.
     * Preference order:
     * <ol>
     *   <li>Empty, non-reserved slot</li>
     *   <li>Non-selected, non-reserved, non-food/non-tool slot</li>
     *   <li>Empty slot (even if reserved)</li>
     *   <li>Non-selected, non-reserved slot (even if food/tool)</li>
     *   <li>Any non-reserved slot</li>
     *   <li>Fallback {@code (selected + 1) % 9}</li>
     * </ol>
     */
    public static int pickSwapSlotIndex(boolean[] empty, boolean[] protectedItem, int selected, int reservedMask) {
        int mask = reservedMask & ALL_HOTBAR_MASK;
        int safeSelected = (selected >= 0 && selected < 9) ? selected : 0;

        for (int i = 0; i < 9; i++) {
            if (empty[i] && (mask & (1 << i)) == 0) {
                return i;
            }
        }
        for (int i = 0; i < 9; i++) {
            if (i == safeSelected || (mask & (1 << i)) != 0) continue;
            if (!protectedItem[i]) {
                return i;
            }
        }
        for (int i = 0; i < 9; i++) {
            if (empty[i]) {
                return i;
            }
        }
        for (int i = 0; i < 9; i++) {
            if (i != safeSelected && (mask & (1 << i)) == 0) {
                return i;
            }
        }
        for (int i = 0; i < 9; i++) {
            if ((mask & (1 << i)) == 0) {
                return i;
            }
        }
        return (safeSelected + 1) % 9;
    }

    private static void validateHotbarSlot(int slot) {
        if (slot < 0 || slot >= 9) {
            throw new IllegalArgumentException("hotbar slot must be in 0..8, got " + slot);
        }
    }
}
