package dev.baritonequeue;

import baritone.api.BaritoneAPI;
import baritone.api.utils.BetterBlockPos;
import dev.baritonequeue.Values.Pos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.baritonequeue.Builtins.def;
import static dev.baritonequeue.Builtins.getter;
import static dev.baritonequeue.Builtins.toPos;
import static dev.baritonequeue.Config.cfg;
import static dev.baritonequeue.Values.error;
import static dev.baritonequeue.Values.str;
import static dev.baritonequeue.Values.toInt;
import static dev.baritonequeue.Values.toNum;

/** The script functions that look at or act in the game. The ones that take time wait until they are finished. */
final class GameBuiltins {
    private GameBuiltins() {}

    /** {selection or null, mode, "skip"|"strict"} from schem_*(n, "mode", "strict") in any order. */
    private static String[] schemArgs(List<Object> a, String fn, int line, String defaultMode) {
        String sel = null, mode = defaultMode, missing = "skip";
        for (Object o : a) {
            if (o == null) continue;
            String s = o instanceof Double d ? String.valueOf((int) Math.floor(d)) : str(o);
            if (Schem.Mode.of(s) != null) mode = s.toLowerCase();
            else if (s.equalsIgnoreCase("all") || s.matches("[0-9]+")) sel = s;
            else if (s.equalsIgnoreCase("skip") || s.equalsIgnoreCase("strict")) missing = s.toLowerCase();
            else throw error(fn + ": '" + s + "' is neither a placement number, \"all\", a mode (add, fix, exact), nor \"skip\" / \"strict\".", line);
        }
        return new String[]{sel, mode, missing};
    }

    private static void schem() {
        String g = "schem";
        def(g, "schem_list", 0, 0, "schem_list()", "list the Litematica placements in chat; returns how many there are", (in, a, l) -> (double) SchemCmd.list());
        def(g, "schem_materials", 0, 2, "schem_materials([n | \"all\"] [, mode])", "print the items still needed for the placement (minus what is built); returns how many items the inventory lacks", (in, a, l) -> {
            String[] sm = schemArgs(a, "schem_materials()", l, "fix");
            return (double) SchemCmd.materials(sm[0], Schem.Mode.of(sm[1]));
        });
        def(g, "schem_needed", 0, 2, "schem_needed([n | \"all\"] [, mode])", "the items the placement still needs (minus what is built, not minus the inventory): a map item -> count", (in, a, l) -> {
            String[] sm = schemArgs(a, "schem_needed()", l, "fix");
            String[] err = new String[1];
            var pls = SchemCmd.select(sm[0], err);
            if (pls == null) throw error(err[0], l);
            Schem.Plan plan = Schem.plan(pls, err);
            if (plan == null) throw error(err[0], l);
            Schem.Counts c = Schem.count(plan, Schem.Mode.of(sm[1]), level(l));
            Map<Object, Object> out = Values.newMap();
            c.items.forEach((it, n) -> out.put(Schem.itemName(it), (double) n));
            return out;
        });
        def(g, "schem_check", 0, 2, "schem_check([n | \"all\"] [, mode])", "print what differs between the schematic and the world; returns the blocks that still need work", (in, a, l) -> {
            String[] sm = schemArgs(a, "schem_check()", l, "fix");
            return (double) SchemCmd.check(sm[0], Schem.Mode.of(sm[1]));
        });
        def(g, "schem_stop", 0, 0, "schem_stop()", "stop the running schematic build", (in, a, l) -> (double) SchemCmd.stop());
    }

    private static LocalPlayer player(int line) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) throw error("Not in a world.", line);
        return p;
    }

    private static Level level(int line) {
        Level l = Minecraft.getInstance().level;
        if (l == null) throw error("Not in a world.", line);
        return l;
    }

    static Item item(Object v, String fn, int line) {
        if (!(v instanceof String s)) throw error(fn + " needs an item name in quotes, for example \"dirt\".", line);
        Item it = ChestOp.itemOf(s);
        if (it == null) throw error("'" + s + "' is not an item name (for example \"dirt\", \"cobblestone\", \"oak_log\").", line);
        return it;
    }

    static Block block(String s, int line) {
        String w = s.toLowerCase().trim();
        Identifier id = Identifier.tryParse(w.contains(":") ? w : "minecraft:" + w);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) throw error("'" + s + "' is not a block name.", line);
        return BuiltInRegistries.BLOCK.getValue(id);
    }

    /** A chest given as pos(...) or as the name of a chest saved with /queue chest set. */
    private static Object[] chest(Object v, String fn, int line) {
        if (v instanceof Pos p) return new Object[]{p.block(), "at " + p};
        if (v instanceof String name) {
            int[] c = cfg.chests.get(name.toLowerCase());
            if (c == null) throw error("There is no saved chest '" + name + "'. Use a position instead: let " + name + " = pos(x, y, z)  (Pick block in the editor fills it in).", line);
            return new Object[]{new BlockPos(c[0], c[1], c[2]), "'" + name + "' (" + c[0] + " " + c[1] + " " + c[2] + ")"};
        }
        throw error(fn + " needs the chest first: a position like pos(100, 64, -20).", line);
    }

    private static int count(LocalPlayer p, String what) {
        int n = 0;
        String w = what.toLowerCase();
        for (int k = 0; k < 36; k++) {
            ItemStack st = p.getInventory().getItem(k);
            if (st.isEmpty()) continue;
            var id = BuiltInRegistries.ITEM.getKey(st.getItem());
            if (w.contains(":") ? id.toString().equals(w) : id.getPath().equals(w)) n += st.getCount();
        }
        if (n == 0 && !w.contains(":") && ChestOp.itemOf(w) == null) {   // "log" counts every kind of log
            for (int k = 0; k < 36; k++) {
                ItemStack st = p.getInventory().getItem(k);
                if (!st.isEmpty() && BuiltInRegistries.ITEM.getKey(st.getItem()).getPath().contains(w)) n += st.getCount();
            }
        }
        return n;
    }

    /** "food" = anything edible; otherwise the item id contains the word ("pickaxe", "diamond_sword"). */
    private static boolean matchesItem(ItemStack st, String want) {
        if (st.isEmpty()) return false;
        if (want.equals("food")) return st.has(net.minecraft.core.component.DataComponents.FOOD);
        String id = BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
        return want.contains(":") ? id.equals(want) : id.substring(id.indexOf(':') + 1).contains(want);
    }

    private static boolean night() {
        Level level = Minecraft.getInstance().level;
        if (level == null || level.dimension() != Level.OVERWORLD) return false;
        long t = level.getOverworldClockTime() % 24000L;
        return t >= 12542 && t < 23460;
    }

    private static List<Object> strings(List<Object> a, int from) {
        List<Object> out = new ArrayList<>();
        for (int i = from; i < a.size(); i++) {
            if (a.get(i) instanceof List<?> l) out.addAll(l);
            else out.add(a.get(i));
        }
        return out;
    }

    /** Every cell of the selection, a slice per tick, counting those that match. */
    private static double countInSelection(Interp in, java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> test, int line) {
        Level level = level(line);
        var sels = Util.baritone().getSelectionManager().getSelections();
        if (sels.length == 0) throw error("There is no selection. Select an area first: area(\"name\") or select(corner1, corner2).", line);
        long n = 0, seen = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (var s : sels) {
            for (int x = s.min().x; x <= s.max().x; x++) {
                for (int z = s.min().z; z <= s.max().z; z++) {
                    for (int y = s.min().y; y <= s.max().y; y++) {
                        m.set(x, y, z);
                        if (++seen % 40000 == 0) in.yieldTick();
                        if (level.hasChunkAt(m) && test.test(level.getBlockState(m))) n++;
                    }
                }
            }
        }
        return (double) n;
    }

    static void register() {
        info();
        actions();
        chests();
        areas();
        helpers();
        settings();
        schem();
    }

    // ------------------------------------------------------------------ what the player and the world look like

    private static void info() {
        String g = "world";
        def(g, "pos", 0, 3, "pos(x, y, z) | pos()", "a position; pos() alone is where you stand", (in, a, l) -> {
            if (a.isEmpty()) return Pos.of(player(l).blockPosition());
            if (a.size() != 3) throw error("pos needs x, y and z: pos(100, 64, -20)", l);
            return new Pos(toInt(a.get(0), "x", l), toInt(a.get(1), "y", l), toInt(a.get(2), "z", l));
        });
        getter(g, "here", "where you stand, as a position", (in, a, l) -> Pos.of(player(l).blockPosition()));
        getter(g, "health", "your health, 0 to 20", (in, a, l) -> (double) player(l).getHealth());
        getter(g, "food", "your food bar, 0 to 20", (in, a, l) -> (double) player(l).getFoodData().getFoodLevel());
        getter(g, "night", "true at night (in the overworld)", (in, a, l) -> night());
        getter(g, "day", "true by day (always outside the overworld)", (in, a, l) -> !night());
        getter(g, "raining", "true while it rains", (in, a, l) -> level(l).isRaining());
        getter(g, "thundering", "true during a thunderstorm", (in, a, l) -> level(l).isThundering());
        getter(g, "daytime", "the time of day in ticks, 0 to 23999 (0 = morning, 13000 = dusk)", (in, a, l) -> (double) (level(l).getOverworldClockTime() % 24000L));
        getter(g, "dimension", "\"overworld\", \"the_nether\" or \"the_end\"", (in, a, l) -> {
            var d = level(l).dimension();
            return d == Level.OVERWORLD ? "overworld" : d == Level.NETHER ? "the_nether" : d == Level.END ? "the_end" : String.valueOf(d);
        });
        getter(g, "full", "true when no inventory slot is free", (in, a, l) -> {
            LocalPlayer p = player(l);
            for (int k = 0; k < 36; k++) if (p.getInventory().getItem(k).isEmpty()) return false;
            return true;
        });
        getter(g, "free_slots", "how many inventory slots are empty", (in, a, l) -> {
            LocalPlayer p = player(l);
            int n = 0;
            for (int k = 0; k < 36; k++) if (p.getInventory().getItem(k).isEmpty()) n++;
            return (double) n;
        });
        getter(g, "busy", "true while Baritone works on a task", (in, a, l) -> Util.baritoneBusy());
        getter(g, "mobs", "hostile mobs within 7 blocks that are worth fighting", (in, a, l) -> (double) dev.survivalkit.Api.threats(7));
        def(g, "mobs_near", 1, 1, "mobs_near(blocks)", "hostile mobs within that many blocks", (in, a, l) -> (double) dev.survivalkit.Api.threats(toNum(a.get(0), "mobs_near()", l)));
        getter(g, "restock_needed", "true when a tool is worn out or something on the want-list is below half", (in, a, l) -> dev.survivalkit.Api.restockNeeded());
        getter(g, "bed_time", "true at night or in a thunderstorm, or when it is time to leave to reach the bed before dusk", (in, a, l) -> dev.survivalkit.Api.bedTime());
        getter(g, "looking", "the block you look at, as a position (null if none)", (in, a, l) -> {
            var hit = Minecraft.getInstance().hitResult;
            return hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK ? Pos.of(b.getBlockPos()) : null;
        });
        def(g, "count", 1, 1, "count(\"item\")", "how many of it you carry (\"log\" counts every log)", (in, a, l) -> (double) count(player(l), str(a.get(0))));
        def(g, "has", 1, 2, "has(\"item\" [, n])", "true if you carry at least n (default 1)", (in, a, l) ->
                count(player(l), str(a.get(0))) >= (a.size() > 1 ? toNum(a.get(1), "has()", l) : 1));
        def(g, "block", 1, 3, "block(position)", "the block there, like \"stone\" or \"water\"", (in, a, l) -> {
            Pos p = toPos(a, 0, "block()", l);
            Level level = level(l);
            if (!level.hasChunkAt(p.block())) return null;
            return BuiltInRegistries.BLOCK.getKey(level.getBlockState(p.block()).getBlock()).getPath();
        });
        def(g, "is_wet", 1, 3, "is_wet(position)", "true if the cell holds water and is not solid (water, seagrass, kelp ...)", (in, a, l) -> {
            Pos p = toPos(a, 0, "is_wet()", l);
            return dev.buildtools.Api.wet(level(l), p.block());
        });
        def(g, "dist", 1, 2, "dist(a [, b])", "blocks from you to a (or from a to b)", (in, a, l) -> {
            Pos p = toPos(a, 0, "dist()", l);
            if (a.size() > 1) {
                Pos q = toPos(a, 1, "dist()", l);
                return Math.sqrt(p.block().distSqr(q.block()));
            }
            var pl = player(l);
            return Math.sqrt(pl.distanceToSqr(p.x() + 0.5, p.y() + 0.5, p.z() + 0.5));
        });
        def(g, "count_blocks", 1, -1, "count_blocks(\"block\", ...)", "how many of these blocks are in the selection", (in, a, l) -> {
            Set<Block> wanted = new HashSet<>();
            for (Object o : strings(a, 0)) wanted.add(block(str(o), l));
            return countInSelection(in, st -> wanted.contains(st.getBlock()), l);
        });
        def(g, "water_left", 0, 0, "water_left()", "wet cells in the selection (water, seagrass, kelp ...)", (in, a, l) -> {
            return countInSelection(in, st -> st.getFluidState().is(net.minecraft.tags.FluidTags.WATER) && !st.blocksMotion(), l);
        });
    }

    // ------------------------------------------------------------------ things that take time

    private static void actions() {
        String g = "actions";
        def(g, "wait", 1, 1, "wait(seconds)", "do nothing for a while (also: wait 30, wait until day)", (in, a, l) -> {
            double s = toNum(a.get(0), "wait()", l);
            return in.await(() -> new Actions.Wait(s), "wait " + Values.num(s) + " s", l);
        });
        def(g, "baritone", 1, -1, "baritone(\"command\")", "run a Baritone command and wait for it (same as a # line)", (in, a, l) -> {
            List<String> parts = new ArrayList<>();
            for (Object o : a) parts.add(str(o));
            String c = String.join(" ", parts).trim();
            if (c.startsWith("#")) c = c.substring(1).trim();
            String cmd = c;
            return in.await(() -> new Actions.BaritoneCommand(cmd), cmd, l);
        });
        def(g, "chat", 1, 1, "chat(\"/command\")", "run a chat command and wait for it (same as a / line)", (in, a, l) -> {
            String c = str(a.get(0)).trim();
            if (c.startsWith("/")) c = c.substring(1);
            String cmd = c;
            return in.await(() -> new Actions.ChatCommand(cmd), "/" + cmd, l);
        });
        def(g, "goto", 1, 3, "goto(position)", "walk there with Baritone", (in, a, l) -> {
            Pos p = toPos(a, 0, "goto()", l);
            String cmd = "goto " + p;
            return in.await(() -> new Actions.BaritoneCommand(cmd), cmd, l);
        });
        def(g, "cancel", 0, 0, "cancel()", "stop whatever Baritone is doing (like #stop, but the script goes on)", (in, a, l) -> {
            Util.baritone().getPathingBehavior().cancelEverything();
            return null;
        });

        // --- water
        def(g, "fill_water", 0, 3, "fill_water([\"block\" [, chest [, per_trip]]])", "fill the water of the selection from dry ground, every column from the bottom up; with a chest it fetches more blocks whenever they run out (per_trip, default 512); returns the water left (0 = all done)", (in, a, l) -> {
            Item it = a.isEmpty() || a.get(0) == null ? null : item(a.get(0), "fill_water()", l);
            Object[] chest = a.size() > 1 && a.get(1) != null ? chest(a.get(1), "fill_water()", l) : null;
            if (chest != null && it == null) throw error("fill_water with a chest needs the block too: fill_water(\"dirt\", chest)", l);
            int trip = a.size() > 2 ? Math.max(1, toInt(a.get(2), "per_trip", l)) : 512;
            String text = "fill_water" + (it == null ? "" : " " + ChestOp.name(it));
            int trips = 0, rounds = 0;
            while (true) {
                if (chest != null && count(player(l), ChestOp.name(it)) == 0) {   // nothing to fill with: fetch first
                    ChestOp op = new ChestOp((String) chest[1], ChestOp.Kind.GET, it, trip, true);
                    Object got;
                    try {
                        got = in.await(() -> new Actions.Chest(op, (BlockPos) chest[0]), "fetch " + ChestOp.name(it), l);
                    } catch (Values.ScriptError e) {
                        Util.msg("fill_water: could not fetch more " + ChestOp.name(it) + " (" + e.getMessage() + ")");
                        return (double) dev.buildtools.Api.lastFill()[1];
                    }
                    if (got instanceof Double d && d == 0 && count(player(l), ChestOp.name(it)) == 0) {
                        Util.msg("fill_water: the chest gave no " + ChestOp.name(it) + ".");
                        return (double) dev.buildtools.Api.lastFill()[1];
                    }
                    trips++;
                }
                Object r = in.await(() -> new Actions.Feature(
                        () -> dev.buildtools.Api.fillWater(it),
                        dev.buildtools.Api::fillingWater,
                        dev.buildtools.Api::stopFillingWater,
                        () -> (double) dev.buildtools.Api.lastFill()[1]).withEstimate(Estimate.water()), text, l);
                double left = r instanceof Double d ? d : 0;
                if (left == 0) return left;
                if (dev.buildtools.Api.fillRanOut()) {
                    if (chest == null || trips > 200) return left;
                    Util.msg("Out of " + ChestOp.name(it) + " with " + Values.num(left) + " water left: fetching more.");
                    continue;
                }
                // not out of blocks: what got filled may have opened new ways; go again while a round gets anything done
                if (dev.buildtools.Api.lastFill()[0] == 0 || ++rounds > 30) {
                    Util.msg("fill_water: " + Values.num(left) + " water cells left that it cannot get at.");
                    return left;
                }
                Util.msg("Another round: " + Values.num(left) + " water left.");
            }
        });
        def(g, "schem_build", 0, 3, "schem_build([n | \"all\"] [, \"add\" | \"fix\" | \"exact\"] [, \"skip\" | \"strict\"])", "build the Litematica placement n (or all enabled ones) with Baritone's builder, phase by phase (clear, full blocks, shaped, attached, redstone, power); add = only fill empty spots, fix = also replace wrong blocks, exact = also dig out schematic air; skip (default) = blocks you have no item for are left out and the rest is built, strict = stop when an item is missing; waits; returns the blocks still wrong (0 = done)", (in, a, l) -> {
            String[] sm = schemArgs(a, "schem_build()", l, "add");
            QueueMod.schem.skipMissing(!"strict".equals(sm[2]));
            Object r = in.await(() -> new Actions.Feature(
                    () -> {
                        String[] err = new String[1];
                        var pls = SchemCmd.select(sm[0], err);
                        if (pls == null) return err[0];
                        return QueueMod.schem.begin(pls, Schem.Mode.of(sm[1]));
                    },
                    QueueMod.schem::working,
                    QueueMod.schem::cancel,
                    () -> QueueMod.schem.failure() != null ? new Modules.Failure(QueueMod.schem.failure()) : (Object) QueueMod.schem.result())
                    .resumable(QueueMod.schem::suspendRun, QueueMod.schem::carryOn), "schem_build", l);
            return r instanceof Double d ? d : 0.0;
        });
        def(g, "clear_area", 0, 0, "clear_area()", "mine out the selection from the top, 3 layers per pass, everything in reach at each stop, picking up drops on the way; returns the blocks left (finish those with #sel ca)", (in, a, l) ->
                in.await(() -> new Actions.Feature(
                        () -> QueueMod.clear.begin(),
                        QueueMod.clear::working,
                        QueueMod.clear::cancel,
                        () -> QueueMod.clear.failure() != null ? new Modules.Failure(QueueMod.clear.failure()) : (Object) QueueMod.clear.result())
                        .resumable(QueueMod.clear::suspendRun, QueueMod.clear::carryOn)
                        .withEstimate(Estimate.solid()), "clear_area", l));
        def(g, "drain", 0, 0, "drain()", "remove the flowing water that blocks the builder (selection, or 8 blocks around you)", (in, a, l) ->
                in.await(() -> new Actions.Feature(dev.buildtools.Api::drainNow, dev.buildtools.Api::draining, dev.buildtools.Api::stopDraining, () -> true), "drain", l));
        def(g, "flood", 0, 0, "flood()", "fill the selection WITH water, using buckets (seals the open sides first)", (in, a, l) ->
                in.await(() -> new Actions.Feature(dev.buildtools.Api::floodNow, dev.buildtools.Api::flooding, dev.buildtools.Api::stopFlooding, () -> true), "flood", l));
        def(g, "collect_scaffold", 0, 0, "collect_scaffold()", "take down the blocks Baritone placed to reach things, and pick them up", (in, a, l) ->
                in.await(() -> new Actions.Feature(dev.buildtools.Api::collectScaffoldNow, dev.buildtools.Api::collectingScaffold, dev.buildtools.Api::stopCollectingScaffold, () -> true), "collect_scaffold", l));

        // --- survival
        def(g, "sleep", 0, 0, "sleep()", "go to the saved bed and sleep (only at night)", (in, a, l) ->
                in.await(() -> new Actions.Feature(dev.survivalkit.Api::sleepNow, dev.survivalkit.Api::sleeping, dev.survivalkit.Api::stopSleeping, () -> true), "sleep", l));
        def(g, "defend", 0, 0, "defend()", "fight every hostile mob nearby until none is left (backs away from a creeper about to blow); false if there was none", (in, a, l) ->
                in.await(() -> new Actions.Feature(dev.survivalkit.Api::defendNow, dev.survivalkit.Api::fighting, dev.survivalkit.Api::stopDefending, () -> true), "defend", l));
        def(g, "restock", 0, 0, "restock()", "go to the supply chest and take what the want-list says", (in, a, l) ->
                in.await(() -> new Actions.Feature(dev.survivalkit.Api::restockNow, dev.survivalkit.Api::restocking, dev.survivalkit.Api::stopRestocking, () -> true), "restock", l));
        def(g, "eat", 0, 0, "eat()", "eat now (if the food bar is not full)", (in, a, l) ->
                in.await(() -> new Actions.Feature(dev.survivalkit.Api::eatNow, dev.survivalkit.Api::eating, null, () -> true), "eat", l));
        def(g, "unload", 0, 2, "unload([chest] [, \"filter\"])", "walk to a chest and put the inventory in, as LoadChest did (its keep rules apply)", (in, a, l) -> {
            BlockPos at = a.isEmpty() || a.get(0) == null ? null : (BlockPos) chest(a.get(0), "unload()", l)[0];
            String filter = a.size() > 1 && a.get(1) != null ? str(a.get(1)) : "";
            return in.await(() -> new Actions.Feature(
                    () -> dev.loadchest.LoadChestMod.unloadNow(filter, at) ? null : "Could not start the chest trip (no chest found? see the message above).",
                    dev.loadchest.LoadChestMod::unloading, dev.loadchest.LoadChestMod::stopUnloading, () -> true), "unload", l);
        });
        def(g, "pickup", 0, 0, "pickup()", "pick up every dropped item nearby", (in, a, l) ->
                in.await(() -> new Actions.Feature(() -> { dev.loadchest.LoadChestMod.pickupNow(); return null; },
                        dev.loadchest.LoadChestMod::pickingUp, null, () -> true), "pickup", l));
    }

    // ------------------------------------------------------------------ chests

    private static void chests() {
        String g = "chests";
        def(g, "take", 2, 3, "take(chest, \"item\" [, count | \"all\"])", "walk to the chest and take items (default one stack); returns how many", (in, a, l) -> {
            Object[] c = chest(a.get(0), "take()", l);
            Item it = item(a.get(1), "take()", l);
            int n = 0;
            if (a.size() > 2) n = "all".equals(a.get(2)) ? -1 : Math.max(1, toInt(a.get(2), "The count", l));
            ChestOp op = new ChestOp((String) c[1], ChestOp.Kind.GET, it, n, false);
            return in.await(() -> new Actions.Chest(op, (BlockPos) c[0]), "take " + ChestOp.name(it), l);
        });
        def(g, "take_upto", 3, 3, "take_upto(chest, \"item\", count)", "take only as many as needed to carry that many in all; returns how many were taken", (in, a, l) -> {
            Object[] c = chest(a.get(0), "take_upto()", l);
            Item it = item(a.get(1), "take_upto()", l);
            int n = Math.max(1, toInt(a.get(2), "The count", l));
            ChestOp op = new ChestOp((String) c[1], ChestOp.Kind.GET, it, n, true);
            return in.await(() -> new Actions.Chest(op, (BlockPos) c[0]), "take up to " + n + " " + ChestOp.name(it), l);
        });
        def(g, "put", 2, 3, "put(chest, \"item\" [, count])", "walk to the chest and put that item in (default all of it); returns how many", (in, a, l) -> {
            Object[] c = chest(a.get(0), "put()", l);
            Item it = item(a.get(1), "put()", l);
            int n = a.size() > 2 && !"all".equals(a.get(2)) ? Math.max(1, toInt(a.get(2), "The count", l)) : -1;
            ChestOp op = new ChestOp((String) c[1], ChestOp.Kind.PUT, it, n, false);
            return in.await(() -> new Actions.Chest(op, (BlockPos) c[0]), "put " + ChestOp.name(it), l);
        });
        def(g, "dump", 1, 1, "dump(chest)", "put everything in except tools, armour, food, buckets and the keep rules; returns how many", (in, a, l) -> {
            Object[] c = chest(a.get(0), "dump()", l);
            ChestOp op = new ChestOp((String) c[1], ChestOp.Kind.DUMP, null, -1, false);
            return in.await(() -> new Actions.Chest(op, (BlockPos) c[0]), "dump", l);
        });
        def(g, "chest", 1, 1, "chest(\"name\")", "the position of a chest saved with /queue chest set <name>", (in, a, l) -> {
            Object[] c = chest(str(a.get(0)), "chest()", l);
            return Pos.of((BlockPos) c[0]);
        });
        def(g, "find_item", 1, 1, "find_item(\"item\")", "chests that hold it, as far as they were seen open: a list of {pos, count}, nearest first", (in, a, l) -> {
            List<Object> out = new ArrayList<>();
            for (int[] e : dev.survivalkit.Api.findItem(str(a.get(0)).toLowerCase())) {
                Map<Object, Object> m = Values.newMap();
                m.put("pos", new Pos(e[0], e[1], e[2]));
                m.put("count", (double) e[3]);
                out.add(m);
            }
            return out;
        });
        def(g, "chest_contents", 1, 3, "chest_contents(chest)", "what the chest held when it was last open: a map item -> count (null if never seen)", (in, a, l) -> {
            Pos p = toPos(a, 0, "chest_contents()", l);
            var m = dev.survivalkit.Api.chestContents(p.block());
            if (m == null) return null;
            Map<Object, Object> out = Values.newMap();
            m.forEach((k, v) -> out.put(k.replace("minecraft:", ""), (double) v));
            return out;
        });
    }

    // ------------------------------------------------------------------ the selection

    private static void areas() {
        String g = "areas";
        def(g, "area", 1, 1, "area(\"name\")", "select a saved area (save one with /queue area save <name>)", (in, a, l) -> {
            String err = Areas.load(str(a.get(0)));
            if (err != null) throw error(err, l);
            return null;
        });
        def(g, "select", 2, 6, "select(corner1, corner2)", "make the selection this box (replaces the selection)", (in, a, l) -> {
            Pos p = toPos(a, 0, "select()", l), q = toPos(a, a.get(0) instanceof Pos ? 1 : 3, "select()", l);
            var sm = Util.baritone().getSelectionManager();
            sm.removeAllSelections();
            sm.addSelection(new BetterBlockPos(p.x(), p.y(), p.z()), new BetterBlockPos(q.x(), q.y(), q.z()));
            return null;
        });
        def(g, "add_box", 2, 6, "add_box(corner1, corner2)", "add a box to the selection", (in, a, l) -> {
            Pos p = toPos(a, 0, "add_box()", l), q = toPos(a, a.get(0) instanceof Pos ? 1 : 3, "add_box()", l);
            Util.baritone().getSelectionManager().addSelection(new BetterBlockPos(p.x(), p.y(), p.z()), new BetterBlockPos(q.x(), q.y(), q.z()));
            return null;
        });
        def(g, "clear_selection", 0, 0, "clear_selection()", "no selection any more", (in, a, l) -> {
            Util.baritone().getSelectionManager().removeAllSelections();
            return null;
        });
        def(g, "save_area", 1, 1, "save_area(\"name\")", "save the selection under a name", (in, a, l) -> {
            String err = Areas.save(str(a.get(0)));
            if (err != null) throw error(err, l);
            return null;
        });
        def(g, "selection", 0, 0, "selection()", "the boxes of the selection: a list of [corner1, corner2]", (in, a, l) -> {
            List<Object> out = new ArrayList<>();
            for (int[] b : Areas.read()) out.add(new ArrayList<>(List.of(new Pos(b[0], b[1], b[2]), new Pos(b[3], b[4], b[5]))));
            return out;
        });
        def(g, "areas", 0, 0, "areas()", "the names of the saved areas", (in, a, l) -> new ArrayList<Object>(cfg.areas.keySet()));
        def(g, "protect", 1, 3, "protect(\"name\" [, corner1, corner2])", "a safe area: Baritone never breaks or places blocks there (no corners: the selection)", (in, a, l) -> {
            String name = str(a.get(0));
            String err;
            if (a.size() == 1) err = dev.buildtools.Api.protectSelection(name);
            else {
                Pos p = toPos(a, 1, "protect()", l), q = toPos(a, 2, "protect()", l);
                err = dev.buildtools.Api.protect(name, p.x(), p.y(), p.z(), q.x(), q.y(), q.z());
            }
            if (err != null) throw error(err, l);
            return null;
        });
        def(g, "unprotect", 1, 1, "unprotect(\"name\")", "remove a safe area; true if it existed", (in, a, l) -> dev.buildtools.Api.unprotect(str(a.get(0))));
    }

    // ------------------------------------------------------------------ helper rules

    private static void helpers() {
        String g = "helpers";
        def(g, "want", 2, 2, "want(\"item\", count)", "restock() keeps this many with you (\"food\" = any food); 0 removes the rule", (in, a, l) -> {
            String it = str(a.get(0));
            if (!it.equalsIgnoreCase("food") && ChestOp.itemOf(it) == null && !it.contains("_")) {
                // fragments like "pickaxe" are fine: they match every pickaxe
            }
            dev.survivalkit.Api.want(it, toInt(a.get(1), "want()", l));
            return null;
        });
        def(g, "work_item", 1, 1, "work_item(\"rule\")", "a tool with enchantments to keep, like /survival workitem (e.g. \"diamond_pickaxe 2 efficiency 5\")", (in, a, l) -> {
            String err = dev.survivalkit.Api.workItem(str(a.get(0)));
            if (err != null) throw error(err, l);
            return null;
        });
        def(g, "hotbar", 2, 2, "hotbar(slot, \"item\")", "put that item into hotbar slot 1-9 (\"food\" = any food, \"pickaxe\" = any pickaxe); false if there is none", (in, a, l) -> {
            int slot = toInt(a.get(0), "The hotbar slot", l);
            if (slot < 1 || slot > 9) throw error("A hotbar slot is 1 to 9.", l);
            LocalPlayer p = player(l);
            String want = str(a.get(1)).toLowerCase();
            var inv = p.getInventory();
            if (matchesItem(inv.getItem(slot - 1), want)) return true;   // already there
            int from = -1;
            for (int i = 9; i < 36 && from < 0; i++) if (matchesItem(inv.getItem(i), want)) from = i;
            for (int i = 0; i < 9 && from < 0; i++) if (i != slot - 1 && matchesItem(inv.getItem(i), want)) from = i;
            if (from < 0) return false;
            var mc = Minecraft.getInstance();
            if (p.containerMenu != p.inventoryMenu || mc.gameMode == null) return false;   // a chest is open: later
            int menuSlot = from < 9 ? 36 + from : from;
            mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, menuSlot, slot - 1, net.minecraft.world.inventory.ContainerInput.SWAP, p);
            return true;
        });
        def(g, "keep", 2, 2, "keep(\"item\", count)", "unload() and dump() leave this many in the inventory (-1 = all of it); \"food\" = any food", (in, a, l) -> {
            String it = str(a.get(0)).toLowerCase();
            if (it.equals("food")) it = "@food";
            int n = toInt(a.get(1), "keep()", l);
            if (n == 0) dev.loadchest.LoadChestMod.keepRules().remove(it);
            else dev.loadchest.LoadChestMod.keepRules().put(it, n);
            dev.loadchest.LoadChestMod.saveSettings();
            return null;
        });
        def(g, "disable", 0, -1, "disable(\"feature\", ...)", "keep helpers from acting by themselves while this script runs (none = all)", (in, a, l) -> {
            List<String> names = new ArrayList<>();
            for (Object o : strings(a, 0)) names.add(str(o).toLowerCase());
            in.runner().switchOff(names);
            return null;
        });
        def(g, "enable", 0, -1, "enable(\"feature\", ...)", "let helpers act by themselves while this script runs: eat, sleep, defence, restock, escape, drain, scaffold, pickup, autoempty (none = all)", (in, a, l) -> {
            List<String> names = new ArrayList<>();
            for (Object o : strings(a, 0)) names.add(str(o).toLowerCase());
            in.runner().switchOn(names);
            return null;
        });
    }

    // ------------------------------------------------------------------ settings

    private static Object settingValue(Object v) {
        if (v instanceof Boolean || v instanceof String) return v;
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object o : l) out.add(settingValue(o));
            return out;
        }
        if (v instanceof Item it) return ChestOp.name(it);
        if (v instanceof Block b) return BuiltInRegistries.BLOCK.getKey(b).getPath();
        return String.valueOf(v);
    }

    private static void settings() {
        String g = "settings";
        def(g, "set", 2, 2, "set(\"setting\", value)", "change a Baritone setting (like #set)", (in, a, l) -> {
            String name = str(a.get(0));
            String canon = Util.settingName(name);
            if (canon == null) throw error("'" + name + "' is not a Baritone setting.", l);
            Object v = a.get(1);
            if (canon.equals("allowBreak") && v instanceof Boolean b) {   // through the safe-area guard: near a safe area it stays off
                dev.buildtools.Api.setOwnBreak(b);
                return null;
            }
            String text = v instanceof List<?> list ? String.join(",", list.stream().map(Values::str).toList()) : str(v);
            if (!Actions.BaritoneCommand.execute("set " + canon + " " + text)) throw error("Baritone did not accept " + canon + " = " + text + ".", l);
            return null;
        });
        def(g, "setting", 1, 1, "setting(\"setting\")", "the value of a Baritone setting", (in, a, l) -> {
            var s = BaritoneAPI.getSettings().byLowerName.get(str(a.get(0)).toLowerCase());
            if (s == null) throw error("'" + str(a.get(0)) + "' is not a Baritone setting.", l);
            return settingValue(s.value);
        });
        def(g, "option", 1, 2, "option(\"group.name\" [, value])", "read or change a helper option, e.g. option(\"survival.eatBelow\", 10), option(\"survival.bed\", pos(...)), option(\"loadchest.auto\", true)", (in, a, l) -> {
            if (a.size() == 1) return Modules.get(str(a.get(0)), l);
            Modules.set(str(a.get(0)), a.get(1), l);
            return null;
        });
        def(g, "options", 0, 1, "options([\"group\"])", "print every helper option and its value (or those of one group)", (in, a, l) -> {
            String group = a.isEmpty() ? "" : str(a.get(0)).toLowerCase() + ".";
            Modules.all().forEach((k, v) -> { if (k.startsWith(group)) Util.msg(k + " = " + v); });
            return null;
        });
        def(g, "timeout", 1, 1, "timeout(seconds | null)", "an action that takes longer is stopped and the script goes on (null: no limit)", (in, a, l) -> {
            in.runner().setTimeout(a.get(0) == null ? 0 : toNum(a.get(0), "timeout()", l));
            return null;
        });
        def(g, "stall", 1, 1, "stall(minutes | null)", "a Baritone task without progress for this long is skipped (null: never)", (in, a, l) -> {
            cfg.stallMinutes = a.get(0) == null ? 0 : Math.max(0, toInt(a.get(0), "stall()", l));
            Config.save();
            return null;
        });
    }
}
