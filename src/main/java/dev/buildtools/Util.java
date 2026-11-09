package dev.buildtools;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class Util {
    private Util() {}

    static IBaritone baritone() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    static void msg(String s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal("[BuildTools] " + s));
    }

    /** The queue's "addons off ..." line: is this feature switched off for now? (names or "all", comma separated) */
    /** The script's enable("eat", ...): this helper runs while the script runs, whatever its saved option says. */
    static boolean scriptOn(String feature, boolean saved) {
        String v = System.getProperty("baritonequeue.helpers.on");
        if (v != null) {
            for (String k : v.split(",")) {
                k = k.trim();
                if (k.equals("all") || k.equals(feature)) return true;
            }
        }
        return saved;
    }

    static boolean addonOff(String feature) {
        String v = System.getProperty("baritonequeue.addons.off");
        if (v == null) return false;
        String f = feature.toLowerCase();
        for (String k : v.split(",")) {
            k = k.trim();
            if (k.equals("all") || (!k.isEmpty() && f.contains(k))) return true;
        }
        return false;
    }

    /** True while any Baritone task other than ours runs. */
    static boolean baritoneBusy() {
        IBaritone b = baritone();
        return b.getMineProcess().isActive() || b.getBuilderProcess().isActive() || b.getFarmProcess().isActive()
                || b.getExploreProcess().isActive() || b.getGetToBlockProcess().isActive()
                || b.getFollowProcess().isActive() || b.getCustomGoalProcess().isActive();
    }

    /** Tells the running script to stop. */
    static void haltQueue(String why) {
        System.setProperty("baritonequeue.halt", why);
    }

    private static final Map<String, Long> lastWarn = new HashMap<>();

    /** Tells the player about a problem in chat, at most once a minute per kind. */
    static void warn(String key, String text) {
        long t = System.currentTimeMillis();
        Long last = lastWarn.get(key);
        if (last != null && t - last < 60_000) return;
        lastWarn.put(key, t);
        msg("PROBLEM: " + text);
    }

    // ------------------------------------------------------------------ blocks and water

    /** A pure water block (not a waterlogged stair etc.), source or flowing. */
    static boolean isWater(BlockState s) {
        return s.getBlock() instanceof LiquidBlock
                && (s.getFluidState().getType() == Fluids.WATER || s.getFluidState().getType() == Fluids.FLOWING_WATER);
    }

    static boolean isSourceWater(BlockState s) {
        return s.getBlock() instanceof LiquidBlock && s.getFluidState().isSourceOfType(Fluids.WATER);
    }

    /** A block you can click against to place something next to it. */
    static boolean isSolid(Level level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return !s.isAir() && s.getFluidState().isEmpty() && !s.canBeReplaced() && s.blocksMotion();
    }

    /** Air or something a block/water can simply replace (but no fluid): water placed next to it would spread. */
    static boolean isOpen(Level level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return s.isAir() || (s.canBeReplaced() && s.getFluidState().isEmpty());
    }

    /**
     * Minecraft's infinite water rule: a water block turns into a source when two horizontal neighbours are sources
     * and the block below is solid or a source. Bucketing such a source does nothing: it refills at once.
     */
    static boolean regenerates(Level level, BlockPos pos) {
        int sources = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (isSourceWater(level.getBlockState(pos.relative(d)))) sources++;
        }
        if (sources < 2) return false;
        BlockPos below = pos.below();
        return isSourceWater(level.getBlockState(below)) || level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
    }

    // ------------------------------------------------------------------ inventory

    /** Inventory index of a block Baritone considers a throwaway (cobblestone, dirt, ...), hotbar first; -1 if none. */
    static int throwawaySlot(LocalPlayer p) {
        List<Item> ok = BaritoneAPI.getSettings().acceptableThrowawayItems.value;
        int found = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (!st.isEmpty() && st.getItem() instanceof BlockItem && ok.contains(st.getItem())) {
                if (i < 9) return i;
                if (found < 0) found = i;
            }
        }
        return found;
    }

    static int countThrowaway(LocalPlayer p) {
        List<Item> ok = BaritoneAPI.getSettings().acceptableThrowawayItems.value;
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (!st.isEmpty() && st.getItem() instanceof BlockItem && ok.contains(st.getItem())) n += st.getCount();
        }
        return n;
    }

    /** Inventory index of an item, hotbar first; -1 if none. */
    static int slotOf(LocalPlayer p, Item item) {
        int found = -1;
        for (int i = 0; i < 36; i++) {
            if (p.getInventory().getItem(i).is(item)) {
                if (i < 9) return i;
                if (found < 0) found = i;
            }
        }
        return found;
    }

    /** Puts the item at that inventory index in the hand: selects it, or swaps it into a spare hotbar slot. */
    static void equip(Minecraft mc, LocalPlayer p, int invIndex) {
        if (invIndex < 9) {
            p.getInventory().setSelectedSlot(invIndex);
            return;
        }
        int target = p.getInventory().getSelectedSlot();
        for (int h = 0; h < 9; h++) {                       // an empty slot is best
            if (p.getInventory().getItem(h).isEmpty()) { target = h; break; }
        }
        if (!p.getInventory().getItem(target).isEmpty()) {  // else one holding plain blocks, never a tool
            for (int h = 0; h < 9; h++) {
                if (p.getInventory().getItem(h).getItem() instanceof BlockItem) { target = h; break; }
            }
        }
        mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, invIndex, target, ContainerInput.SWAP, p);
        p.getInventory().setSelectedSlot(target);
    }

    // ------------------------------------------------------------------ aiming

    /** Turns smoothly (at most 40/30 degrees per tick) towards a point. Returns true once it is looking at it. */
    static boolean aimAt(LocalPlayer p, Vec3 aim) {
        Vec3 eye = p.getEyePosition();
        double dx = aim.x - eye.x, dy = aim.y - eye.y, dz = aim.z - eye.z;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        float dYaw = Mth.wrapDegrees(yaw - p.getYRot());
        float dPitch = pitch - p.getXRot();
        p.setYRot(p.getYRot() + Mth.clamp(dYaw, -40f, 40f));
        p.setXRot(Mth.clamp(p.getXRot() + Mth.clamp(dPitch, -30f, 30f), -90f, 90f));
        return Math.abs(dYaw) < 6f && Math.abs(dPitch) < 6f;
    }
}
