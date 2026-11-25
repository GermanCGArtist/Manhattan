package dev.survivalkit;

import dev.midtown.api.EngineSelector;
import dev.midtown.api.EngineStatus;
import dev.midtown.api.GoalSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.survivalkit.Config.cfg;

/**
 * Supplies: when something on the want-list (food, torches, tools, ...) runs low, walk to the supply chest, take what
 * is missing and carry on with the task.
 */
final class RestockProcess extends Feature {
    private enum State { IDLE, PATHING, OPENING, TAKING }

    private record Action(int slot, int button, ContainerInput type) {}

    private static final int PATH_TIMEOUT = 20 * 180;   // at least; a far chest gets 120 s + 1 s per block
    private int pathLimit = PATH_TIMEOUT;
    private static final int OPEN_TIMEOUT = 20 * 5;
    private static final long RETRY_IDLE = 20L * 60 * 5;    // a rule the chest could not fill is skipped for 5 min

    private State state = State.IDLE;
    private BlockPos chest;
    private boolean returnLeg;   // currently on the way to / at the return chest
    private int timer, cooldown, check, topUpCooldown, pathFails;
    private long now;
    private ArrayDeque<Action> queue;
    private static final long RETRY_MAX = 20L * 60 * 30;    // never wait longer than 30 min between looks
    private static final long RECHECK_EMPTY_MS = 30L * 60 * 1000;   // a chest seen without what is missing: skip for 30 min
    private final Map<String, Long> exhausted = new HashMap<>();
    private final Map<String, Integer> emptyTrips = new HashMap<>();
    private long returnExhaustedUntil;
    private int returnEmptyTrips;
    private List<BlockPos> returnRoute = new ArrayList<>();   // return chests to try in order (nearest first)
    private int returnRouteIdx;
    private boolean returnChestWasFull;
    private List<BlockPos> route = new ArrayList<>();   // supply chests to visit this trip, most promising first
    private int routeIdx;
    private final Map<String, int[]> shortHere = new HashMap<>();   // rule -> {got, need} at the chest just emptied
    private final Set<String> tripGot = new HashSet<>();            // rules that some chest gave something for   // trips in a row that found none of a wanted item
    private final Map<String, Integer> lastWorn = new HashMap<>();
    private final List<String> summary = new ArrayList<>();
    private Boolean breakBefore;   // saved allowBreak while traveling to chests (restored on exit)

    private void disableBreakForTravel() {
        if (breakBefore == null) {
            breakBefore = dev.buildtools.Api.setOwnBreak(false);
        }
    }

    private void restoreBreakAfterTravel() {
        if (breakBefore != null) {
            boolean prev = breakBefore;
            breakBefore = null;
            dev.buildtools.Api.setOwnBreak(prev);
        }
    }

    private void enterIdle() {
        state = State.IDLE;
        restoreBreakAfterTravel();
    }

    RestockProcess() {
        super("SurvivalKit restock", 12);
    }

    @Override
    boolean busy() {
        return state != State.IDLE;
    }

    private static boolean isStorage(Block b) {
        return b instanceof ChestBlock || b instanceof BarrelBlock || b instanceof net.minecraft.world.level.block.ShulkerBoxBlock;
    }

    @Override
    void tick() {
        now++;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        if (state != State.IDLE) {
            stepInternal(mc, p);
            return;
        }
        if (cooldown > 0) cooldown--;
        if (cfg.want.isEmpty() || ++check % 10 != 0) return;   // 2x per second

        // A tool that just hit the item-saver limit gets a fresh chance even if the chest was empty a minute ago.
        // (Also when the script decides: restock_needed looks at the same waiting times.)
        for (String key : cfg.want.keySet()) {
            int worn = Util.wornCount(p, key);
            if (worn > lastWorn.getOrDefault(key, 0)) {
                exhausted.remove(key);
                returnExhaustedUntil = 0;
                returnEmptyTrips = 0;
                Util.debug("worn-out " + key + " detected");
            }
            lastWorn.put(key, worn);
        }
        if (!Util.scriptOn("restock", cfg.restock)) return;
        if (cooldown > 0) return;
        if (!Util.mayAct()) return;   // no Baritone task running (and "always" is off): stay out of the way

        // Passing the base already: refilling now costs a few steps instead of a separate trip later, so stock that
        // is only a bit low (under 75%) counts too. Not while LoadChest is on its way, and at most every 3 minutes.
        if (topUpCooldown > 0) topUpCooldown -= 10;
        boolean atBase = cfg.topUp && topUpCooldown <= 0 && Api.hasAnySupply() && Util.nearBase(p) && !Util.loadChestBusy();

        List<String> stock = new ArrayList<>(), worn = new ArrayList<>();
        boolean topUp = false;
        for (Map.Entry<String, Integer> e : cfg.want.entrySet()) {
            String key = e.getKey();
            if (exhausted.getOrDefault(key, 0L) > now) continue;
            int have = Util.countHave(p, key);
            if (have < e.getValue() && Util.hasWorn(p, key)) worn.add(key);   // replace right away
            else if (have * 2 < e.getValue()) stock.add(key);                  // plain stock running low
            else if (atBase && have * 4 < e.getValue() * 3) { stock.add(key); topUp = true; }
        }
        if (worn.isEmpty() && stock.isEmpty()) return;
        if (!Api.hasAnySupply()) {
            Util.warn("nosupply", "Need " + String.join(", ", worn.isEmpty() ? stock : worn)
                    + " but no supply chest is set. Look at the chest and run /survival supply set.");
            return;
        }

        if (p.isSleeping() || Util.guiOpen(p) || SurvivalKitMod.sleep.busy()) {
            Util.debug("restock waiting (sleeping / screen open / on the way to bed)");
            return;
        }
        if (!worn.isEmpty()) {
            Util.msg("Tool at the item-saver limit (" + String.join(", ", worn) + ") - "
                    + (Api.hasAnyReturnChest() ? "returning it and getting a new one." : "swapping it at the supply chest."));
        } else if (topUp) {
            Util.msg("At the base anyway - topping up " + String.join(", ", stock) + ".");
            topUpCooldown = 20 * 60 * 3;
        } else {
            Util.msg("Running low on " + String.join(", ", stock) + " - going to the supply chest.");
        }
        start(mc, p);
    }

    /**
     * A wanted thing is below half, or a tool hit the item-saver limit and there are not enough fresh ones. Things the
     * supply chest was short on last time do not count until their "look again in N min" is up: otherwise the script's
     * "on restock_needed" would walk back to an empty chest every minute.
     */
    static boolean needed(LocalPlayer p) {
        return SurvivalKitMod.restock.neededNow(p);
    }

    private boolean neededNow(LocalPlayer p) {
        // far from the base a trip costs minutes: only for what cannot wait (a worn tool, something used up). Stock that is
        // merely low waits for the next time at the base (the bed trip tops it up, see topUpWanted).
        boolean far = Api.hasAnySupply() && !Util.nearBase(p);
        for (Map.Entry<String, Integer> e : cfg.want.entrySet()) {
            if (exhausted.getOrDefault(e.getKey(), 0L) > now) continue;
            int have = Util.countHave(p, e.getKey());
            if (have < e.getValue() && Util.hasWorn(p, e.getKey())) return true;
            if (have * 2 < e.getValue() && (!far || have == 0)) return true;
        }
        return false;
    }

    /** At the base anyway (on the way to bed): is anything on the want-list under 75% (or worn)? */
    static boolean topUpWanted(LocalPlayer p) {
        return SurvivalKitMod.restock.topUpNow(p);
    }

    private boolean topUpNow(LocalPlayer p) {
        if (!Api.hasAnySupply() || cfg.want.isEmpty()) return false;
        for (Map.Entry<String, Integer> e : cfg.want.entrySet()) {
            if (exhausted.getOrDefault(e.getKey(), 0L) > now) continue;
            int have = Util.countHave(p, e.getKey());
            if (have < e.getValue() && Util.hasWorn(p, e.getKey())) return true;
            if (have * 4 < e.getValue() * 3) return true;
        }
        return false;
    }

    static boolean isExhausted(String rule) {
        return SurvivalKitMod.restock.isExhaustedNow(rule);
    }

    private boolean isExhaustedNow(String rule) {
        if (rule == null || rule.isBlank()) return false;
        String k = rule.trim().toLowerCase(java.util.Locale.ROOT);
        if (k.equals("food")) k = "@food";
        return exhausted.getOrDefault(k, 0L) > now;
    }

    static boolean hasReturnableWornTools(LocalPlayer p) {
        return SurvivalKitMod.restock.hasReturnableWornNow(p);
    }

    private boolean hasReturnableWornNow(LocalPlayer p) {
        if (p == null || cfg.want.isEmpty()) return false;
        if (returnExhaustedUntil > now) return false;
        boolean hasSeparateReturn = Api.hasAnyReturnChest();
        for (String key : cfg.want.keySet()) {
            if (!Util.hasWorn(p, key)) continue;
            if (hasSeparateReturn || exhausted.getOrDefault(key, 0L) <= now) {
                return true;
            }
        }
        return false;
    }

    static void resetBackoff() {
        SurvivalKitMod.restock.resetBackoffNow();
    }

    private void resetBackoffNow() {
        exhausted.clear();
        emptyTrips.clear();
        returnExhaustedUntil = 0;
        returnEmptyTrips = 0;
    }

    /** /survival restock now */
    void startNow() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!Api.hasAnySupply() && !Api.hasAnyReturnChest()) { Util.msg("No supply chest saved. Look at a chest and run /survival supply set"); return; }
        if (cfg.want.isEmpty()) { Util.msg("The want-list is empty. Add rules with /survival want <item> <count>"); return; }
        if (state != State.IDLE) { Util.msg("Already restocking."); return; }
        if (!dev.midtown.api.RuntimeBus.isScriptActive()) {
            // typed by hand: look in the chest for everything again. A running script keeps the waiting times, so
            // "on restock_needed" does not fire again for what the chest did not have.
            resetBackoffNow();
        }
        ChestIndex.hint = null;
        start(mc, mc.player);
    }

    private boolean hasWornWanted(LocalPlayer p) {
        for (String key : cfg.want.keySet()) {
            if (Util.hasWorn(p, key)) return true;
        }
        return false;
    }

    /**
     * With one or more return chests (or return areas) set and a worn-out tool in the inventory, the first stop is
     * the return chest(s) (drop the worn tools there, spilling over to the next return chest if full); then comes
     * the supply chest(s) for the replacement.
     */
    private void start(Minecraft mc, LocalPlayer p) {
        returnRoute = buildReturnRoute(mc, p);
        returnRouteIdx = 0;
        returnChestWasFull = false;
        returnLeg = false;
        if (Api.hasAnyReturnChest() && hasWornWanted(p)) {
            if (!returnRoute.isEmpty()) {
                returnLeg = true;
            } else {
                Util.warn("returnmissing", "No loaded return chest found (not loaded, or removed) - worn tools go into the supply chest instead.");
                returnExhaustedUntil = Math.max(returnExhaustedUntil, now + RETRY_IDLE);
            }
        }
        route = buildRoute(mc, p);
        routeIdx = 0;
        tripGot.clear();
        shortHere.clear();
        chest = returnLeg ? returnRoute.get(0) : route.isEmpty() ? null : route.get(0);
        if (chest == null || !isStorage(mc.level.getBlockState(chest).getBlock())) {
            Util.msg("No supply chest found" + (chest == null ? "" : " at " + chest.toShortString())
                    + " (not loaded, or it was removed). Checking again in 2 minutes.");
            cooldown = 20 * 120;
            return;
        }
        timer = 0;
        pathFails = 0;
        queue = null;
        disableBreakForTravel();
        state = State.PATHING;
        EngineSelector.current().goTo(GoalSpec.near(chest, 2));
    }

    /** All loaded return chests (explicit return chests + chests scanned inside returnAreas), nearest first. */
    private List<BlockPos> buildReturnRoute(Minecraft mc, LocalPlayer p) {
        List<int[]> explicit = Api.allReturnChests();
        List<BlockPos> scanned = Util.scanStorageAreas(mc.level, explicit, cfg.returnAreas);
        List<BlockPos> valid = new ArrayList<>();
        for (BlockPos pos : scanned) {
            if (mc.level.hasChunkAt(pos) && isStorage(mc.level.getBlockState(pos).getBlock())) {
                valid.add(pos);
            }
        }
        valid.sort(java.util.Comparator.comparingDouble(pos -> pos.distSqr(p.blockPosition())));
        return valid;
    }

    /**
     * The supply chests in the order to visit: those the index says hold something that is missing first, then the
     * ones never seen, then those that looked empty last time (they may have been refilled). Nearest first within that.
     * Includes explicit supply chests plus any chests/barrels auto-discovered inside {@code cfg.supplyAreas}.
     */
    private List<BlockPos> buildRoute(Minecraft mc, LocalPlayer p) {
        List<String> need = new ArrayList<>();
        for (Map.Entry<String, Integer> e : cfg.want.entrySet()) {
            if (Util.countHave(p, e.getKey()) < e.getValue()) need.add(e.getKey());
        }
        List<int[]> explicit = new ArrayList<>();
        if (cfg.supply != null) explicit.add(cfg.supply);
        if (cfg.extraSupplies != null) explicit.addAll(cfg.extraSupplies);
        List<BlockPos> all = Util.scanStorageAreas(mc.level, explicit, cfg.supplyAreas);
        record Cand(BlockPos pos, int rank, double dist) {}
        List<Cand> cands = new ArrayList<>();
        for (BlockPos pos : all) {
            if (!mc.level.hasChunkAt(pos) || !isStorage(mc.level.getBlockState(pos).getBlock())) continue;
            ChestIndex.Entry e = ChestIndex.at(mc, pos);
            int rank = 1;
            if (e != null) {
                rank = 2;
                for (String k : need) {
                    if (e.countRule(k) > 0) { rank = 0; break; }
                }
            }
            cands.add(new Cand(pos, rank, pos.distSqr(p.blockPosition())));
        }
        cands.sort((x, y) -> x.rank != y.rank ? Integer.compare(x.rank, y.rank) : Double.compare(x.dist, y.dist));
        // A chest seen recently that held none of what is missing is not worth opening again: with a storage area
        // full of chests, one missing item (a diamond hoe nobody stocked) meant opening every one of them on every
        // trip. Those stay off the route unless the route would otherwise be empty (then the nearest one, so the trip
        // still happens and refreshes the index); chests seen longer ago may have been refilled and stay on it.
        long now = System.currentTimeMillis();
        List<BlockPos> out = new ArrayList<>();
        BlockPos fallback = null;
        for (Cand c : cands) {
            if (c.rank() == 2) {
                ChestIndex.Entry e = ChestIndex.at(mc, c.pos());
                if (e != null && now - e.time < RECHECK_EMPTY_MS) {
                    if (fallback == null) fallback = c.pos();
                    continue;
                }
            }
            out.add(c.pos());
        }
        if (out.isEmpty() && fallback != null) out.add(fallback);
        return out;
    }

    /** One chest is done: on to the next if something is still missing, else wrap the trip up. */
    private void finishChest(Minecraft mc, String text) {
        failStreak = 0;   // the chest was open
        Util.msg(text);
        boolean missing = shortHere.values().stream().anyMatch(a -> a[0] < a[1]);
        if (missing && routeIdx + 1 < route.size()) {
            routeIdx++;
            chest = route.get(routeIdx);
            Util.msg("Not everything was there - trying the next supply chest.");
            timer = 0;
            queue = null;
            shortHere.clear();
            state = State.PATHING;
            EngineSelector.current().goTo(GoalSpec.near(chest, 3));
            return;
        }
        settleShort();
        Session.count("restocks");
        enterIdle();
        queue = null;
        cooldown = 40;
        EngineSelector.current().cancel();
    }

    /** After the last chest: what is still missing is not worth a trip every minute, so the waiting time grows. */
    private void settleShort() {
        for (Map.Entry<String, int[]> e : shortHere.entrySet()) {
            String key = e.getKey();
            int got = e.getValue()[0], need = e.getValue()[1];
            if (got >= need) {
                emptyTrips.remove(key);
                continue;
            }
            // 1, 2, 4, 8, 16, then every 30 minutes (5 minutes first when nothing is running). Anything given resets it.
            boolean gave = got > 0 || tripGot.contains(key);
            int streak = gave ? 0 : emptyTrips.merge(key, 1, Integer::sum);
            if (gave) emptyTrips.remove(key);
            long base = RETRY_IDLE;   // a trip to the base and back is expensive: 5, 10, 20, then every 30 min
            long wait = Math.min(RETRY_MAX, base * (1L << Math.min(5, Math.max(0, streak - 1))));
            Util.warn("short-" + key, "The supply chest" + (route.size() > 1 ? "s are" : " is") + " short on " + key + " (got " + got + " of " + need
                    + "). Refill " + (route.size() > 1 ? "them" : "it") + " - I'll look again in " + Math.max(1, wait / (20 * 60)) + " min"
                    + (streak > 2 ? " (it keeps being empty, so I come less often)." : "."));
            exhausted.put(key, now + wait);
        }
        shortHere.clear();
    }

    private String chestName() {
        return returnLeg ? "return chest" : "supply chest";
    }

    @Override
    boolean active() {
        if (state != State.IDLE && Minecraft.getInstance().player == null) enterIdle();
        return state != State.IDLE;
    }

    private void stepInternal(Minecraft mc, LocalPlayer p) {
        if (p == null || mc.gameMode == null) { enterIdle(); return; }

        switch (state) {
            case PATHING -> {
                if (timer == 0) pathLimit = Math.max(PATH_TIMEOUT, (int) (20 * (120 + Math.sqrt(chest.distSqr(p.blockPosition())))));
                if (++timer > pathLimit) { fail("Could not reach the " + chestName() + " in time."); return; }
                double dist = Math.sqrt(chest.distSqr(p.blockPosition()));
                if (dist <= 3.5) {
                    EngineSelector.current().cancel();
                    state = State.OPENING;
                    timer = 0;
                    return;
                }
                if (!EngineSelector.current().isBusy()) {
                    if (EngineSelector.current().status() == EngineStatus.FAILED) {
                        if (++pathFails >= 3) {
                            fail("Could not find a path to the " + chestName() + ".");
                            return;
                        }
                        if (timer % 20 != 0) return;
                    } else {
                        pathFails = 0;
                    }
                    GoalSpec goal = EngineSelector.current().status() == EngineStatus.DONE
                            ? GoalSpec.near(chest, 1)
                            : GoalSpec.near(chest, 2);
                    EngineSelector.current().goTo(goal);
                }
            }
            case OPENING -> {
                ChestIndex.hint = chest;
                if (p.containerMenu != p.inventoryMenu) {
                    state = State.TAKING;
                    timer = 0;
                    queue = null;
                    return;
                }
                if (timer == 1) Util.readyToOpen(p);   // never place a block on top of it or pour a bucket
                if (timer == 4) {   // let the player come to a stop first
                    Util.readyToOpen(p);
                    BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(chest), Direction.UP, chest, false);
                    mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
                }
                if (++timer > OPEN_TIMEOUT) { fail("Could not open the " + chestName() + "." + Util.blockedText(mc.level, chest)); return; }
            }
            case TAKING -> {
                AbstractContainerMenu h = p.containerMenu;
                if (h == p.inventoryMenu) { enterIdle(); cooldown = 100; return; }
                if (queue == null) {
                    if (++timer < 8) return;   // on a server the chest contents can arrive a moment after it opens
                    summary.clear();
                    queue = returnLeg ? buildReturnPlan(p, h) : buildPlan(p, h);
                    if (queue.isEmpty()) {
                        p.closeContainer();
                        if (returnLeg) { afterReturnLeg(mc, p); return; }
                        finishChest(mc, "Nothing to take from the supply chest" + (summary.isEmpty() ? "." : " (" + String.join(", ", summary) + ")."));
                        return;
                    }
                }
                if (++timer % 2 != 0) return;   // one click every 2 ticks
                Action a = queue.poll();
                if (a == null) {
                    p.closeContainer();
                    if (returnLeg) { afterReturnLeg(mc, p); return; }
                    finishChest(mc, "Restocked: " + String.join(", ", summary) + ".");
                    return;
                }
                mc.gameMode.handleContainerInput(h.containerId, a.slot(), a.button(), a.type(), p);
            }
            default -> {
            }
        }
    }

    private int failStreak;   // trips in a row that did not get to open the chest

    /**
     * The chest could not be reached or opened: 1, 2, 4 ... up to 30 minutes until the next try, and the script's
     * "on restock_needed" waits as long (on the live canal it fired every 30 s for 9 hours and kept interrupting the
     * water fill, which then never got anywhere).
     */
    private void fail(String why) {
        long wait = Math.min(RETRY_MAX, 20L * 60 * (1L << Math.min(5, failStreak++)));
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) {
            for (Map.Entry<String, Integer> e : cfg.want.entrySet()) {
                int have = Util.countHave(p, e.getKey());
                if (have < e.getValue()) exhausted.put(e.getKey(), Math.max(exhausted.getOrDefault(e.getKey(), 0L), now + wait));
            }
        }
        if (returnLeg) returnExhaustedUntil = Math.max(returnExhaustedUntil, now + wait);
        Util.msg(why + " Trying again in " + Math.max(1, wait / (20 * 60)) + " min" + (failStreak > 2 ? " (it keeps failing: check the way to the chest, and that nothing is on top of it)." : "."));
        enterIdle();
        cooldown = (int) wait;
        EngineSelector.current().cancel();
    }

    /** Worn tools are dropped off: spill over to the next return chest if this one was full, else carry on to the supply chest. */
    private void afterReturnLeg(Minecraft mc, LocalPlayer p) {
        if (!summary.isEmpty()) Util.msg("Return chest: " + String.join(", ", summary) + ".");
        if (returnChestWasFull && returnRouteIdx + 1 < returnRoute.size()) {
            returnRouteIdx++;
            returnChestWasFull = false;
            chest = returnRoute.get(returnRouteIdx);
            queue = null;
            timer = 0;
            pathFails = 0;
            Util.msg("Return chest was full - trying next return chest (" + (returnRouteIdx + 1) + "/" + returnRoute.size() + ").");
            state = State.PATHING;
            EngineSelector.current().goTo(GoalSpec.near(chest, 2));
            return;
        }
        returnLeg = false;
        queue = null;
        routeIdx = 0;
        chest = route.isEmpty() ? null : route.get(0);
        if (chest == null || !isStorage(mc.level.getBlockState(chest).getBlock())) {
            done("Could not continue to the supply chest (not set, or not loaded).");
            return;
        }
        timer = 0;
        state = State.PATHING;
        EngineSelector.current().goTo(GoalSpec.near(chest, 2));
    }

    /** Puts every worn-out damageable item that matches a want-rule into the return chest. */
    private ArrayDeque<Action> buildReturnPlan(LocalPlayer p, AbstractContainerMenu h) {
        ArrayDeque<Action> plan = new ArrayDeque<>();
        int free = 0;
        for (Slot s : h.slots) {
            if (s.container != p.getInventory() && s.getItem().isEmpty()) free++;
        }
        int returned = 0, wornTotal = 0;
        for (Slot s : h.slots) {
            if (s.container != p.getInventory()) continue;
            ItemStack st = s.getItem();
            if (st.isEmpty() || !st.isDamageableItem() || !Util.nearlyBroken(st)) continue;
            boolean wanted = false;
            for (String key : cfg.want.keySet()) {
                if (Util.matches(st, key)) { wanted = true; break; }
            }
            if (!wanted) continue;
            wornTotal++;
            if (returned >= free) {
                continue;
            }
            plan.add(new Action(s.index, 0, ContainerInput.QUICK_MOVE));
            returned++;
        }
        returnChestWasFull = wornTotal > returned;
        boolean hasMoreReturnChests = returnRouteIdx + 1 < returnRoute.size();
        if (returnChestWasFull && !hasMoreReturnChests) {
            Util.warn("returnfull", "All return chests are full - some worn tools stay in the inventory. Empty them.");
            int streak = ++returnEmptyTrips;
            long wait = Math.min(RETRY_MAX, RETRY_IDLE * (1L << Math.min(5, Math.max(0, streak - 1))));
            returnExhaustedUntil = now + wait;
        } else if (returned > 0 && !returnChestWasFull) {
            returnEmptyTrips = 0;
            returnExhaustedUntil = 0;
        }
        if (returned > 0) summary.add("returned " + returned + " worn tool" + (returned == 1 ? "" : "s"));
        return plan;
    }

    private void done(String text) {
        Util.msg(text);
        enterIdle();
        queue = null;
        cooldown = 40;
        EngineSelector.current().cancel();
    }

    // ------------------------------------------------------------------ plan

    private ArrayDeque<Action> buildPlan(LocalPlayer p, AbstractContainerMenu h) {
        ArrayDeque<Action> plan = new ArrayDeque<>();
        Slot[] mine = new Slot[36];
        List<Slot> supply = new ArrayList<>();
        for (Slot s : h.slots) {
            if (s.container == p.getInventory()) {
                if (s.getContainerSlot() >= 0 && s.getContainerSlot() < 36) mine[s.getContainerSlot()] = s;
            } else {
                supply.add(s);
            }
        }

        int freeSupply = 0;   // empty slots in the supply chest, for handing in worn-out tools
        for (Slot s : supply) {
            if (s.getItem().isEmpty()) freeSupply++;
        }
        if (Api.hasAnyReturnChest()) freeSupply = 0;   // return chest(s) set: worn tools never go into the supply chest

        Set<Integer> used = new HashSet<>();   // player slots already earmarked by this plan
        for (Map.Entry<String, Integer> rule : cfg.want.entrySet()) {
            String key = rule.getKey();
            int have = 0;
            for (Slot s : mine) {
                if (s != null && Util.matches(s.getItem(), key) && !Util.nearlyBroken(s.getItem())) have += s.getItem().getCount();
            }
            int need = rule.getValue() - have;
            if (need <= 0) continue;

            ArrayDeque<Action> takes = new ArrayDeque<>();
            int got = 0, tools = 0;
            for (Slot s : supply) {
                if (got >= need) break;
                ItemStack st = s.getItem();
                if (!Util.matches(st, key) || Util.nearlyBroken(st)) continue;
                int take = Math.min(st.getCount(), need - got);
                if (take(takes, s, st, take, mine, used)) {
                    got += take;
                    if (st.isDamageableItem()) tools += take;
                }
            }

            // Worn-out tools go back into the chest first (to be repaired). That frees their hotbar slot, and a
            // shift-click from the chest fills the hotbar first - Baritone only picks tools from the hotbar.
            int handedIn = 0;
            for (Slot s : mine) {
                if (handedIn >= tools || freeSupply <= 0) break;
                if (s != null && Util.matches(s.getItem(), key) && s.getItem().isDamageableItem() && Util.nearlyBroken(s.getItem())) {
                    plan.add(new Action(s.index, 0, ContainerInput.QUICK_MOVE));
                    handedIn++;
                    freeSupply--;
                }
            }
            plan.addAll(takes);
            if (got > 0) summary.add("took " + got + "x " + key);
            if (handedIn > 0) summary.add("handed in " + handedIn + " worn " + key);
            if (got > 0) tripGot.add(key);
            shortHere.put(key, new int[]{got, need});
            if (got < need) summary.add("chest is short on " + key + " (" + got + "/" + need + ")");
        }
        if (!plan.isEmpty()) {
            boolean room = false;
            for (Slot s : mine) {
                if (s != null && s.getItem().isEmpty()) { room = true; break; }
            }
            if (!room) {
                Util.warn("fullinv", "The inventory has no empty slot, some of the restock may not fit. Empty it (e.g. /loadchest).");
                summary.add("inventory is full");
            }
        }
        return plan;
    }

    /** Adds the clicks that move `n` items out of the supply slot. Returns false if there is no room. */
    private static boolean take(ArrayDeque<Action> plan, Slot from, ItemStack st, int n, Slot[] mine, Set<Integer> used) {
        int count = st.getCount();
        if (n >= count) {   // whole stack: shift-click
            plan.add(new Action(from.index, 0, ContainerInput.QUICK_MOVE));
            return true;
        }
        Slot dest = findDest(mine, st, n, used);
        if (dest == null) return false;
        if (n <= count / 2) {   // pick the stack up, drop n one by one, put the rest back
            plan.add(new Action(from.index, 0, ContainerInput.PICKUP));
            for (int i = 0; i < n; i++) plan.add(new Action(dest.index, 1, ContainerInput.PICKUP));
            plan.add(new Action(from.index, 0, ContainerInput.PICKUP));
        } else {                // pick the stack up, give back count-n one by one, drop the rest
            plan.add(new Action(from.index, 0, ContainerInput.PICKUP));
            for (int i = 0; i < count - n; i++) plan.add(new Action(from.index, 1, ContainerInput.PICKUP));
            plan.add(new Action(dest.index, 0, ContainerInput.PICKUP));
        }
        return true;
    }

    /** A partial stack of the same item with room, else an empty slot (main inventory first). */
    private static Slot findDest(Slot[] mine, ItemStack st, int n, Set<Integer> used) {
        for (Slot s : mine) {
            if (s == null || used.contains(s.index)) continue;
            ItemStack o = s.getItem();
            if (!o.isEmpty() && ItemStack.isSameItemSameComponents(o, st) && o.getMaxStackSize() - o.getCount() >= n) {
                used.add(s.index);
                return s;
            }
        }
        for (int i = 9; i < 45; i++) {   // inventory indices 9..35, then hotbar 0..8
            Slot s = mine[i % 36];
            if (s != null && s.getItem().isEmpty() && !used.contains(s.index)) {
                used.add(s.index);
                return s;
            }
        }
        return null;
    }

    @Override
    public void onLostControl() {   // e.g. #stop
        if (state != State.IDLE) {
            LocalPlayer p = Minecraft.getInstance().player;
            if (p != null && p.containerMenu != p.inventoryMenu) p.closeContainer();
        }
        enterIdle();
        queue = null;
        EngineSelector.current().cancel();
    }
}
