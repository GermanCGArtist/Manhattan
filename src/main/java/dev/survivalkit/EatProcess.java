package dev.survivalkit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import static dev.survivalkit.Config.cfg;

/** Eats when the food bar is low. Holds the use key like a player would, so it works on servers. */
final class EatProcess extends Feature {
    private static final int EAT_TIMEOUT = 120;     // ticks; eating takes ~32
    private static final int FAIL_COOLDOWN = 200;
    private static final float GOLDEN_AT = 8f;      // 4 hearts
    /** pickFood/pickGolden result for food held in the off hand (the off hand's inventory index). */
    private static final int OFFHAND = 40;

    private boolean eating, started;
    private int timer, cooldown, goldenCooldown;
    private int prevSlot;                // hotbar slot to go back to
    private int swappedFrom = -1;        // inventory index the food was swapped in from, -1 = none
    private int swappedTo;
    private Item foodItem;
    private boolean offhandMode, preferMain;
    private InteractionHand hand = InteractionHand.MAIN_HAND;
    private int offFrom = -1;        // inventory index the food was swapped into the off hand from, -1 = none
    private boolean offhandInHeld;   // the off-hand food was swapped into the held slot: swap back when done
    private String foodName = "";
    /** Set by the script's eat(): eat now if the food bar is not full, even above the eatBelow level. */
    static volatile boolean forced;
    private int reselects, failures;

    EatProcess() {
        super("SurvivalKit eat", 20);
    }

    @Override
    boolean busy() {
        return eating;
    }

    @Override
    void tick() {
        if (!isActive()) return;
        stepInternal();
    }

    @Override
    boolean active() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if ((!Util.scriptOn("eat", cfg.eat) && !forced && !Recovery.waiting) || p == null || mc.level == null || mc.gameMode == null) {
            if (eating) abort();
            return false;
        }
        if (eating) return true;
        if (cooldown > 0) { cooldown--; return false; }
        if (goldenCooldown > 0) goldenCooldown--;
        if (!Util.mayAct() || p.isSleeping() || Util.guiOpen(p) || p.isUsingItem()) return false;

        // Natural healing needs a full-ish food bar, so being hurt is a reason to eat just like being hungry is.
        boolean hurt = p.getHealth() < p.getMaxHealth() - 0.01f;
        boolean hungry = p.getFoodData().getFoodLevel() <= cfg.eatBelow || forced;
        if (forced && !p.getFoodData().needsFood()) forced = false;
        int slot = -1;
        // In danger (4 hearts or less, or burning while hurt) a golden apple comes first: plain food heals far too slowly.
        boolean danger = hurt && (p.getHealth() <= GOLDEN_AT || (p.isOnFire() && p.getHealth() <= p.getMaxHealth() * 0.6f));
        if (danger && cfg.golden && goldenCooldown == 0) {
            slot = pickGolden(p);
            if (slot >= 0) goldenCooldown = 20 * 30;
        }
        if (slot < 0 && p.getFoodData().needsFood() && (hungry || hurt)) {
            slot = pickFood(p);
        }
        if (slot < 0) {
            forced = false;
            String stats = "food " + p.getFoodData().getFoodLevel() + "/20, health " + Math.round(p.getHealth()) + "/" + Math.round(p.getMaxHealth());
            if (p.getFoodData().needsFood() && (hungry || hurt)) {
                Util.warn("nofood", "Need to eat (" + stats + ") but there is no safe food in the inventory.");
            } else if (hurt && p.getHealth() <= 6) {
                Util.warn("lowhealth", "Health is low (" + stats + "), the food bar is full and there is no golden apple - waiting for natural healing.");
            }
            return false;
        }
        begin(mc, p, slot);
        return true;
    }

    /** A plain golden apple (never the enchanted one). */
    private static int pickGolden(LocalPlayer p) {
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (!st.isEmpty() && Util.pathOf(st).equals("golden_apple")) return i;
        }
        return Util.pathOf(p.getOffhandItem()).equals("golden_apple") ? OFFHAND : -1;
    }

    /** Best food: the biggest one that does not overshoot the missing hunger, else the smallest one. */
    private static int pickFood(LocalPlayer p) {
        int missing = 20 - p.getFoodData().getFoodLevel();
        int best = -1, bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (!Util.isGoodFood(st)) continue;
            int n = Util.nutrition(st);
            int score = n <= missing ? 100 + n : 100 - n;
            if (score > bestScore) { bestScore = score; best = i; }
        }
        // the off hand counts too (often where the food is kept); on a tie it wins: nothing to swap
        ItemStack off = p.getOffhandItem();
        if (Util.isGoodFood(off)) {
            int n = Util.nutrition(off);
            int score = n <= missing ? 100 + n : 100 - n;
            if (score >= bestScore) best = OFFHAND;
        }
        return best;
    }

    private void begin(Minecraft mc, LocalPlayer p, int slot) {
        eating = true;
        started = false;
        timer = 0;
        reselects = 0;
        ItemStack food = slot == OFFHAND ? p.getOffhandItem() : p.getInventory().getItem(slot);
        foodItem = food.getItem();
        foodName = food.getHoverName().getString();
        prevSlot = p.getInventory().getSelectedSlot();
        if (slot == OFFHAND) {
            // already in the off hand: eat it there. Holding the use key uses the main hand first, so it must not
            // hold a block (it would be placed) or other food
            offhandMode = true;
            hand = InteractionHand.OFF_HAND;
            offFrom = -1;
            swappedFrom = -1;
            int free = harmlessHotbarSlot(p);
            if (free >= 0) {
                p.getInventory().setSelectedSlot(free);
            } else {
                // every hotbar slot does something on right click: swap the off-hand food into the held slot instead
                offhandMode = false;
                hand = InteractionHand.MAIN_HAND;
                swapOffhandWithHeld(mc, p, prevSlot);
                offhandInHeld = true;
            }
            return;
        }
        offhandMode = !preferMain && !Util.pathOf(p.getOffhandItem()).contains("totem")
                && !(p.getMainHandItem().getItem() instanceof net.minecraft.world.item.BlockItem);
        hand = offhandMode ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        if (offhandMode) {
            offFrom = slot;
            swappedFrom = -1;
            Util.swapWithOffhand(mc, p, slot);
        } else if (slot >= 9) {
            swappedFrom = slot;
            swappedTo = prevSlot;
            Util.swapIntoHotbar(mc, p, slot, prevSlot);
        } else {
            swappedFrom = -1;
            p.getInventory().setSelectedSlot(slot);
        }
    }

    /** Swaps the off hand (inventory menu slot 45) with hotbar slot {@code hotbar}; doing it again swaps back. */
    private static void swapOffhandWithHeld(Minecraft mc, LocalPlayer p, int hotbar) {
        mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, 45, hotbar,
                net.minecraft.world.inventory.ContainerInput.SWAP, p);
    }

    /** A hotbar slot whose item does nothing on right click (empty, a tool, ...): the selected one if it is fine. */
    private static int harmlessHotbarSlot(LocalPlayer p) {
        int cur = p.getInventory().getSelectedSlot();
        if (cur >= 0 && cur < 9 && harmless(p.getInventory().getItem(cur))) return cur;
        for (int i = 0; i < 9; i++) {
            if (harmless(p.getInventory().getItem(i))) return i;
        }
        return -1;
    }

    private static boolean harmless(ItemStack st) {
        if (st.isEmpty()) return true;
        if (st.getItem() instanceof net.minecraft.world.item.BlockItem) return false;
        if (st.has(net.minecraft.core.component.DataComponents.FOOD)) return false;
        String path = Util.pathOf(st);
        // hoes till, shovels make paths, axes strip logs
        if (path.endsWith("_hoe") || path.endsWith("_shovel") || path.endsWith("_axe")) return false;
        return !(path.contains("bucket") || path.contains("bow") || path.contains("trident") || path.contains("shield")
                || path.contains("pearl") || path.contains("potion") || path.contains("flint_and_steel")
                || path.contains("fire_charge") || path.contains("spawn_egg"));
    }

    private void stepInternal() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (!eating || p == null) return;

        if (p.isUsingItem()) started = true;
        if ((started && !p.isUsingItem()) || ++timer > EAT_TIMEOUT) {
            if (!started) {
                failures++;
                preferMain = offhandMode;
                cooldown = FAIL_COOLDOWN * Math.min(failures, 6);
                Util.warn("eatfail", "Could not eat " + foodName + ": the item in " + (offhandMode ? "the off hand" : "hand") + " was "
                        + held(p).getHoverName().getString()
                        + (reselects > 0 ? " (something else kept changing the hotbar slot "
                        + reselects + "x)" : "")
                        + ". Trying again in " + cooldown / 20 + " s.");
            } else {
                failures = 0;
            }
            finish(mc, p);
            return;
        }
        if (!offhandMode && !started && foodItem != null && p.getInventory().getSelectedItem().getItem() != foodItem && reselects < 20) {
            reselects++;
            holdFood(mc, p);
        }
        mc.options.keyUse.setDown(true);
        if (!started && timer >= 2 && (timer - 2) % 10 == 0) {
            mc.gameMode.useItem(p, hand);
        }
    }

    private ItemStack held(LocalPlayer p) {
        return offhandMode ? p.getOffhandItem() : p.getInventory().getSelectedItem();
    }

    /** Puts the food we are about to eat back into the held slot. */
    private void holdFood(Minecraft mc, LocalPlayer p) {
        for (int i = 0; i < 9; i++) {
            if (p.getInventory().getItem(i).getItem() == foodItem) {
                p.getInventory().setSelectedSlot(i);
                return;
            }
        }
        for (int i = 9; i < 36; i++) {
            if (p.getInventory().getItem(i).getItem() == foodItem) {
                int to = p.getInventory().getSelectedSlot();
                Util.swapIntoHotbar(mc, p, i, to);
                swappedFrom = i;
                swappedTo = to;
                return;
            }
        }
    }

    private void finish(Minecraft mc, LocalPlayer p) {
        forced = false;
        Session.count("meals");
        mc.options.keyUse.setDown(false);
        if (offFrom >= 0) {
            Util.swapWithOffhand(mc, p, offFrom);
            offFrom = -1;
        }
        if (offhandInHeld) {
            swapOffhandWithHeld(mc, p, prevSlot);
            offhandInHeld = false;
        }
        if (swappedFrom >= 0) {
            Util.swapIntoHotbar(mc, p, swappedFrom, swappedTo);
            swappedFrom = -1;
        }
        p.getInventory().setSelectedSlot(prevSlot);
        eating = false;
    }

    private void abort() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.gameMode != null) finish(mc, mc.player);
        eating = false;
    }

    @Override
    public void onLostControl() {
        forced = false;
        if (eating) abort();
    }
}
