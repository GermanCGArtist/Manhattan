package dev.manhattan.fleet;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shares what this bot sees inside storage it opens (chests, barrels, shulker boxes) as fleet stock, whichever
 * job opened it: when a container opens, the block the player looks at is remembered; its contents are read
 * every tick while open and reported once when it closes. Nothing happens outside a fleet.
 */
public final class FleetChestWatcher {
    private static BlockPos openPos;
    private static AbstractContainerMenu openMenu;
    private static Map<String, Integer> lastContents;
    private static int openTicks;

    private FleetChestWatcher() {}

    public static void tick(Minecraft mc) {
        FleetClient fc = FleetClient.instance();
        if (mc == null || mc.player == null || mc.level == null || !fc.isConnected()) {
            openPos = null;
            openMenu = null;
            return;
        }
        AbstractContainerMenu menu = mc.player.containerMenu;
        boolean open = menu != null && menu != mc.player.inventoryMenu;
        if (open && menu != openMenu) {
            finish(fc); // another container replaced the last one
            openMenu = menu;
            openPos = lookedAtStorage(mc);
            openTicks = 0;
            lastContents = null;
        }
        if (open && openPos != null) {
            openTicks++;
            if (openTicks >= 2) lastContents = contents(menu); // the server fills the menu a tick after opening
        }
        if (!open && openMenu != null) {
            finish(fc);
        }
    }

    /** Called by chest jobs right before they close a container, with its final contents. */
    public static void noteBeforeClose(Minecraft mc) {
        if (mc == null || mc.player == null || openPos == null) return;
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu != null && menu == openMenu && menu != mc.player.inventoryMenu) {
            lastContents = contents(menu);
        }
    }

    private static void finish(FleetClient fc) {
        if (openPos != null && lastContents != null) {
            fc.reportChestStock(openPos, lastContents);
        }
        openPos = null;
        openMenu = null;
        lastContents = null;
    }

    private static BlockPos lookedAtStorage(Minecraft mc) {
        HitResult hit = mc.hitResult;
        if (!(hit instanceof BlockHitResult bhr) || hit.getType() != HitResult.Type.BLOCK) return null;
        BlockPos pos = bhr.getBlockPos();
        Block b = mc.level.getBlockState(pos).getBlock();
        if (b instanceof ChestBlock || b instanceof BarrelBlock || b instanceof ShulkerBoxBlock) return pos.immutable();
        return null;
    }

    /** Item id -> count of the container's own slots (not the player's inventory). */
    static Map<String, Integer> contents(AbstractContainerMenu menu) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Slot slot : menu.slots) {
            if (slot.container instanceof Inventory) continue;
            ItemStack st = slot.getItem();
            if (st.isEmpty()) continue;
            out.merge(BuiltInRegistries.ITEM.getKey(st.getItem()).toString(), st.getCount(), Integer::sum);
        }
        return out;
    }
}
