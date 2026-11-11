package dev.baritonequeue;

import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Does one chest line of the script: walks to the chest, opens it and moves the items with shift-clicks (whole
 * stacks), then closes it. The queue waits until working() is false and then reads failure().
 */
final class ChestProcess implements IBaritoneProcess {
    private enum State { IDLE, PATHING, OPENING, TRANSFER }

    private static final int PATH_TIMEOUT = 20 * 180;
    private static final int OPEN_TIMEOUT = 20 * 5;

    private State state = State.IDLE;
    private ChestOp op;
    private BlockPos chest;
    private int timer;
    private String failure;
    private ArrayDeque<Integer> todo;   // menu slot indexes still to move
    private int needed, moved, skipped, reopen;

    int moved() {
        return moved;
    }

    boolean working() {
        return state != State.IDLE;
    }

    /** Why the last operation failed, or null. */
    String failure() {
        return failure;
    }

    boolean begin(ChestOp op, BlockPos chest) {
        if (state != State.IDLE) return false;
        this.op = op;
        this.chest = chest;
        failure = null;
        todo = null;
        timer = 0;
        reopen = 0;
        state = State.PATHING;
        Util.msg("Chest: " + op.describe() + ".");
        return true;
    }

    void cancel() {
        if (state == State.IDLE) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
        state = State.IDLE;
        todo = null;
    }

    // ------------------------------------------------------------------ Baritone process

    @Override
    public boolean isActive() {
        if (state != State.IDLE && PauseWatch.paused) { cancel(); return false; }
        if (state != State.IDLE && Minecraft.getInstance().player == null) state = State.IDLE;
        return state != State.IDLE;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        try {
            return step(calcFailed);
        } catch (Throwable t) {
            t.printStackTrace();
            return fail("The chest step hit an error (" + t + "). Details are in the game log.");
        }
    }

    private static PathingCommand pause() {
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private PathingCommand fail(String why) {
        failure = why;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
        state = State.IDLE;
        todo = null;
        return pause();
    }

    private PathingCommand finish(String text) {
        Util.msg(text);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
        state = State.IDLE;
        todo = null;
        return pause();
    }

    private static boolean isStorage(Block b) {
        return b instanceof ChestBlock || b instanceof BarrelBlock || b instanceof ShulkerBoxBlock;
    }

    private PathingCommand step(boolean calcFailed) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.gameMode == null || mc.level == null) { state = State.IDLE; return pause(); }

        switch (state) {
            case PATHING -> {
                if (++timer > PATH_TIMEOUT) return fail("Could not reach the chest " + op.chest + " in time.");
                if (calcFailed) return fail("Baritone found no path to the chest " + op.chest + ".");
                GoalNear goal = new GoalNear(chest, 3);
                if (goal.isInGoal(p.blockPosition())) {
                    state = State.OPENING;
                    timer = 0;
                    return pause();
                }
                return new PathingCommand(goal, PathingCommandType.SET_GOAL_AND_PATH);
            }
            case OPENING -> {
                if (!isStorage(mc.level.getBlockState(chest).getBlock())) {
                    return fail("There is no chest, barrel or shulker box at " + chest.toShortString() + ". Is the position right?");
                }
                if (p.containerMenu != p.inventoryMenu) {
                    state = State.TRANSFER;
                    timer = 0;
                    return pause();
                }
                if (timer == 1) Util.readyToOpen(p);   // never place a block on top of it or pour a bucket
                if (timer == 4) {   // let the player come to a stop first
                    Util.readyToOpen(p);
                    BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(chest), Direction.UP, chest, false);
                    mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
                }
                if (++timer > OPEN_TIMEOUT) return fail("Could not open the chest " + op.chest + "." + Util.blockedText(mc.level, chest));
                return pause();
            }
            case TRANSFER -> {
                AbstractContainerMenu h = p.containerMenu;
                if (h == p.inventoryMenu) {
                    if (todo == null && ++reopen <= 3) {   // closed before we did anything: open it again
                        state = State.OPENING;
                        timer = 0;
                        return pause();
                    }
                    return fail("The chest closed unexpectedly.");
                }
                if (todo == null) {
                    if (++timer < 8) return pause();   // on a server the contents can arrive a moment after it opens
                    String problem = plan(p, h);
                    if (problem != null) return fail(problem);
                    if (todo.isEmpty()) return finish(nothingToDo());
                }
                if (++timer % 2 != 0) return pause();   // one click every 2 ticks
                Integer index = todo.poll();
                if (index == null) return finish(summary());
                Slot s = h.slots.get(index);
                int before = s.getItem().getCount();
                if (before == 0) return pause();
                int still = needed == Integer.MAX_VALUE ? Integer.MAX_VALUE : needed - moved;
                if (op.kind == ChestOp.Kind.GET && still > 0 && still < before) {
                    // take exactly what is still needed: all of the stack onto the cursor, one into an empty
                    // slot of ours each click, the rest back into its slot (a quick move would take the whole stack)
                    Slot target = null;
                    for (Slot sl : h.slots) {
                        if (sl.container == p.getInventory() && sl.getItem().isEmpty()) { target = sl; break; }
                    }
                    if (target == null) return fail("No free slot in the inventory for " + still + " of " + before + " in the chest.");
                    mc.gameMode.handleContainerInput(h.containerId, s.index, 0, ContainerInput.PICKUP, p);
                    for (int k = 0; k < still; k++) mc.gameMode.handleContainerInput(h.containerId, target.index, 1, ContainerInput.PICKUP, p);
                    mc.gameMode.handleContainerInput(h.containerId, s.index, 0, ContainerInput.PICKUP, p);
                    moved += still;
                    todo.clear();
                    return pause();
                }
                mc.gameMode.handleContainerInput(h.containerId, s.index, 0, ContainerInput.QUICK_MOVE, p);
                int delta = before - s.getItem().getCount();   // the client already applied the move
                if (delta <= 0) {
                    skipped++;   // no room on the other side
                    if (op.kind == ChestOp.Kind.GET) todo.clear();
                } else {
                    moved += delta;
                    if (op.kind != ChestOp.Kind.DUMP && needed != Integer.MAX_VALUE && moved >= needed) todo.clear();
                }
                return pause();
            }
            default -> {
                return pause();
            }
        }
    }

    // ------------------------------------------------------------------ what to move

    private static boolean mine(Slot s, LocalPlayer p) {
        return s.container == p.getInventory();
    }

    private String plan(LocalPlayer p, AbstractContainerMenu h) {
        todo = new ArrayDeque<>();
        moved = 0;
        skipped = 0;
        needed = Integer.MAX_VALUE;

        switch (op.kind) {
            case GET -> {
                int have = 0;
                for (Slot s : h.slots) if (mine(s, p) && s.getItem().is(op.item)) have += s.getItem().getCount();
                if (op.topUp) needed = Math.max(0, op.count - have);
                else if (op.count < 0) needed = Integer.MAX_VALUE;
                else if (op.count == 0) needed = new ItemStack(op.item).getMaxStackSize();
                else needed = op.count;
                if (needed == 0) return null;   // already carrying enough: nothing to do
                int available = 0;
                for (Slot s : h.slots) {
                    if (mine(s, p) || !s.getItem().is(op.item)) continue;
                    available += s.getItem().getCount();
                    todo.add(s.index);
                }
                if (available == 0) return "The chest " + op.chest + " has no " + ChestOp.name(op.item) + ".";
            }
            case PUT -> {
                needed = op.count < 0 ? Integer.MAX_VALUE : op.count;
                for (Slot s : h.slots) {
                    if (mine(s, p) && s.getItem().is(op.item)) todo.add(s.index);   // the hotbar counts too: it is named on purpose
                }
            }
            case DUMP -> {
                JsonObject keepFile = loadChestConfig();
                JsonObject rules = keepFile != null && keepFile.has("keep") && keepFile.get("keep").isJsonObject() ? keepFile.getAsJsonObject("keep") : new JsonObject();
                boolean[] keep = keepPlan(p, rules);
                for (Slot s : h.slots) {   // the hotbar too: mob drops land there first and would stay for good
                    if (!mine(s, p)) continue;
                    int inv = s.getContainerSlot();
                    if (s.getItem().isEmpty() || inv < 0 || inv >= 36 || keep[inv]) continue;
                    todo.add(s.index);
                }
            }
        }
        return null;
    }

    /** Food a mob drops or that makes you sick: never kept by a dump. */
    private static final java.util.Set<String> JUNK_FOOD = java.util.Set.of(
            "rotten_flesh", "spider_eye", "poisonous_potato", "pufferfish", "chicken");

    private static final String[] KINDS = {"pickaxe", "shovel", "axe", "hoe", "sword", "crossbow", "bow", "shears",
            "fishing_rod", "trident", "mace", "shield", "helmet", "chestplate", "leggings", "boots", "flint_and_steel",
            "brush", "elytra", "spear"};

    /** The kind of a tool or armour piece: "diamond_shovel" -> "shovel". */
    static String kind(String path) {
        for (String k : KINDS) if (path.equals(k) || path.endsWith("_" + k)) return k;
        return path;
    }

    private static String pathOf(ItemStack st) {
        return BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
    }

    /**
     * Which inventory slots (0-35) a dump leaves alone. Tools and armour: what the want-list asks for (best first, up
     * to its count); with an empty want-list, the best one of each kind (armour only when that slot is not worn). Food
     * unless it is junk (rotten flesh, spider eyes ...). Buckets. And what the LoadChest keep rules protect. Everything
     * else goes - spare tools, mob drops (bows, armour, swords) and the hotbar's leftovers included.
     */
    static boolean[] keepPlan(LocalPlayer p, JsonObject rules) {
        boolean[] keep = new boolean[36];
        Map<String, Integer> keepLeft = new HashMap<>();   // keep rule -> items still to keep
        java.util.List<Integer> gear = new java.util.ArrayList<>();
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st.isEmpty()) continue;
            String path = pathOf(st);
            if (st.isDamageableItem()) gear.add(i);
            else if (st.has(DataComponents.FOOD) && !JUNK_FOOD.contains(path)) keep[i] = true;
            else if (path.endsWith("bucket")) keep[i] = true;
            else keep[i] = ruleKeeps(st, rules, keepLeft);
        }
        // best (most uses left) first; worn-out ones last, so a spare fresh tool wins over them
        gear.sort(java.util.Comparator.comparingInt((Integer i) -> {
            ItemStack st = p.getInventory().getItem(i);
            return dev.survivalkit.Api.worn(st) ? 1 : 0;
        }).thenComparingInt(i -> {
            ItemStack st = p.getInventory().getItem(i);
            return -(st.getMaxDamage() - st.getDamageValue());
        }));
        Map<String, Integer> wantLeft = new HashMap<>(dev.survivalkit.Api.wantList());
        boolean noWants = wantLeft.isEmpty();
        java.util.Set<String> keptKinds = new java.util.HashSet<>();
        for (int i : gear) {
            ItemStack st = p.getInventory().getItem(i);
            String kind = kind(pathOf(st));
            boolean k = false;
            for (Map.Entry<String, Integer> e : wantLeft.entrySet()) {
                if (e.getValue() > 0 && dev.survivalkit.Api.wantMatches(st, e.getKey())) {
                    e.setValue(e.getValue() - st.getCount());
                    k = true;
                    break;
                }
            }
            if (!k && noWants && !keptKinds.contains(kind) && !wearing(p, kind)) {
                keptKinds.add(kind);   // no want-list: keep the best one of each kind
                k = true;
            }
            keep[i] = k || ruleKeeps(st, rules, keepLeft);
        }
        return keep;
    }

    /** Whether the player already wears armour of this kind. */
    private static boolean wearing(LocalPlayer p, String kind) {
        net.minecraft.world.entity.EquipmentSlot slot = switch (kind) {
            case "helmet" -> net.minecraft.world.entity.EquipmentSlot.HEAD;
            case "chestplate", "elytra" -> net.minecraft.world.entity.EquipmentSlot.CHEST;
            case "leggings" -> net.minecraft.world.entity.EquipmentSlot.LEGS;
            case "boots" -> net.minecraft.world.entity.EquipmentSlot.FEET;
            default -> null;
        };
        return slot != null && !p.getItemBySlot(slot).isEmpty();
    }

    /** What the LoadChest keep rules protect ("dirt": 128 keeps 128 dirt, "_pickaxe": -1 keeps every pickaxe). */
    private static boolean ruleKeeps(ItemStack st, JsonObject rules, Map<String, Integer> keepLeft) {
        String path = pathOf(st);
        for (Map.Entry<String, JsonElement> e : rules.entrySet()) {
            String key = e.getKey().toLowerCase();
            boolean hit = key.equals("@food") ? st.has(DataComponents.FOOD) && !JUNK_FOOD.contains(path) : key.startsWith("_") ? path.endsWith(key) || path.contains(key) : path.equals(key) || path.contains(key);
            if (!hit) continue;
            int count = e.getValue().getAsInt();
            if (count < 0) return true;   // keep all of it
            int left = keepLeft.computeIfAbsent(key, k -> count);
            if (left > 0) {
                keepLeft.put(key, left - st.getCount());
                return true;
            }
        }
        return false;
    }

    private static JsonObject loadChestConfig() {
        try {
            var path = FabricLoader.getInstance().getConfigDir().resolve("loadchest.json");
            if (!Files.exists(path)) return null;
            return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ reports

    private String nothingToDo() {
        return switch (op.kind) {
            case GET -> "Already carrying enough " + ChestOp.name(op.item) + ", nothing taken.";
            case PUT -> "No " + ChestOp.name(op.item) + " in the inventory, nothing put in.";
            case DUMP -> "Nothing to put in (everything is wanted tools, food or kept).";
        };
    }

    /** Called when the last slot is done. */
    private String summary() {
        String what = op.kind == ChestOp.Kind.DUMP ? "items" : ChestOp.name(op.item);
        switch (op.kind) {
            case GET -> {
                if (moved == 0) {
                    failure = "Could not take any " + what + " from '" + op.chest + "': the inventory is full.";
                    return failure;
                }
                String more = needed != Integer.MAX_VALUE && moved < needed
                        ? (skipped > 0 ? " (the inventory is full)" : " (the chest had no more; wanted " + needed + ")") : "";
                return "Took " + moved + " " + what + " from '" + op.chest + "'" + more + ".";
            }
            default -> {
                if (skipped > 0) {
                    String full = "Chest '" + op.chest + "' is full: " + skipped + " stack" + (skipped == 1 ? "" : "s") + " of " + what + " stayed in the inventory.";
                    // putting a named item back (leftovers) never stops the script; a dump that fits nothing does
                    if (moved == 0 && op.kind == ChestOp.Kind.DUMP) failure = full;
                    else Util.warn("chestfull", full);
                    return "Put " + moved + " " + what + " into '" + op.chest + "', then it was full.";
                }
                return "Put " + moved + " " + what + " into '" + op.chest + "'.";
            }
        }
    }

    @Override
    public boolean isTemporary() {
        return true;
    }

    @Override
    public void onLostControl() {
        // other processes (eating, sleeping) take over now and then; the step carries on afterwards
    }

    @Override
    public double priority() {
        return 12;
    }

    @Override
    public String displayName0() {
        return "Queue chest";
    }
}
