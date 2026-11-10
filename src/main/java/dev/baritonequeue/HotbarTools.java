package dev.baritonequeue;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * While a script runs, the tools on the want-list live in the hotbar: Baritone only uses tools from the hotbar, and
 * restocking, dumping and picking things up would otherwise leave them anywhere in the inventory. Once a second, a
 * wanted tool that is only in the main inventory is swapped into the hotbar: into an empty slot, else into a slot
 * holding blocks or other things (never food, another wanted tool, the selected slot or a slot hotbar() pinned).
 */
final class HotbarTools {
    private HotbarTools() {}

    /** Hotbar slots (0-8) the script pinned with hotbar(slot, item). */
    static final Map<Integer, String> pinned = new HashMap<>();
    private static int ticks;

    static void tick(Minecraft mc) {
        if (++ticks % 20 != 0) return;
        LocalPlayer p = mc.player;
        if (p == null || mc.gameMode == null || System.getProperty("baritonequeue.active") == null) return;
        if (p.containerMenu != p.inventoryMenu || (mc.screen != null && !(mc.screen instanceof ChatScreen))) return;
        if (mc.gameMode.isDestroying()) return;
        var inv = p.getInventory();
        for (String rule : dev.survivalkit.Api.wantList().keySet()) {
            boolean inHotbar = false;
            int from = -1;
            for (int i = 0; i < 36; i++) {
                ItemStack st = inv.getItem(i);
                if (!isWantedTool(st) || !dev.survivalkit.Api.wantMatches(st, rule)) continue;
                if (i < 9) { inHotbar = true; break; }
                if (from < 0) from = i;
            }
            if (inHotbar || from < 0) continue;
            int to = freeSlot(p);
            if (to < 0) return;
            mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, from, to, ContainerInput.SWAP, p);
            return;   // one swap per second is plenty
        }
    }

    /** A usable tool or weapon (not armour, not one at the item-saver limit) that some want-list rule asks for. */
    private static boolean isWantedTool(ItemStack st) {
        if (st.isEmpty() || !st.isDamageableItem() || dev.survivalkit.Api.worn(st)) return false;
        String kind = ChestProcess.kind(BuiltInRegistries.ITEM.getKey(st.getItem()).getPath());
        if (kind.equals("helmet") || kind.equals("chestplate") || kind.equals("leggings") || kind.equals("boots") || kind.equals("elytra")) return false;
        for (String rule : dev.survivalkit.Api.wantList().keySet()) if (dev.survivalkit.Api.wantMatches(st, rule)) return true;
        return false;
    }

    /** The hotbar slot a tool may go into: empty first, then one holding something that is neither food nor a wanted tool. */
    private static int freeSlot(LocalPlayer p) {
        var inv = p.getInventory();
        int selected = inv.getSelectedSlot();
        for (int i = 0; i < 9; i++) if (inv.getItem(i).isEmpty() && !pinned.containsKey(i)) return i;
        for (int i = 8; i >= 0; i--) {
            if (i == selected || pinned.containsKey(i)) continue;
            ItemStack st = inv.getItem(i);
            if (st.has(DataComponents.FOOD) || isWantedTool(st)) continue;
            return i;
        }
        return -1;
    }
}
