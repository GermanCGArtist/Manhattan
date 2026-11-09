package dev.survivalkit;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.phys.BlockHitResult;

import java.util.function.Consumer;
import java.util.function.Predicate;

import static dev.survivalkit.Config.cfg;

/**
 * Manhattan's survival helpers (formerly the SurvivalKit addon), started from the script:
 *   eat      - eat(): eats until full
 *   defence  - defend(): fights hostile mobs that get close (getters mobs, mobs_near)
 *   sleep    - sleep(): sleeps through the night in the saved bed (getter bed_time)
 *   restock  - restock(): refills food and tools from the supply chest, worn tools to the return chest (restock_needed)
 *   recovery - respawn, resume and reconnect, so the running script comes back after a death or a disconnect
 * Every feature is a temporary Baritone process, so the running task (#mine, #build, #sel ca, ...) is paused and then
 * continues by itself. None of them acts on its own unless switched on with option(...) or enable(...).
 */
public class SurvivalKitMod implements ClientModInitializer {

    static final EatProcess eat = new EatProcess();
    static final DefenceProcess defence = new DefenceProcess();
    static final SleepProcess sleep = new SleepProcess();
    static final RestockProcess restock = new RestockProcess();
    static final Recovery recovery = new Recovery();
    static final Reconnect reconnectTask = new Reconnect();

    private boolean registered = false;

    @Override
    public void onInitializeClient() {
        Config.load();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("survival")
                        .executes(ctx -> status())
                        .then(ClientCommands.literal("status").executes(ctx -> status()))

                        .then(toggle("eat", v -> cfg.eat = v, "Auto-eat"))
                        .then(toggle("always", v -> cfg.always = v, "Working without a running Baritone task"))
                        .then(toggle("golden", v -> cfg.golden = v, "Golden apple when low on health"))
                        .then(toggle("background", v -> cfg.background = v, "Keep running when the window loses focus"))
                        .then(toggle("debug", v -> Util.debugOn = v, "Debug output"))
                        .then(toggle("defence", v -> cfg.defence = v, "Defence"))
                        .then(toggle("respawn", v -> cfg.respawn = v, "Auto-respawn after dying"))
                        .then(toggle("resume", v -> cfg.resume = v, "Resume the task after dying"))
                        .then(toggle("weather", v -> cfg.weather = v, "Going to bed in thunderstorms"))
                        .then(toggle("reconnect", v -> cfg.reconnect = v, "Reconnecting after a dropped connection"))
                        .then(ClientCommands.literal("report")
                                .executes(ctx -> { Session.print(); return 1; })
                                .then(ClientCommands.literal("reset").executes(ctx -> {
                                    Session.archive();
                                    Session.reset();
                                    Util.msg("Report saved to config/survivalkit-sessions.log and counters reset.");
                                    return 1;
                                })))
                        .then(ClientCommands.literal("chests")
                                .executes(ctx -> { listChests(); return 1; })
                                .then(ClientCommands.literal("forget").executes(ctx -> {
                                    ChestIndex.forget();
                                    Util.msg("Chest index emptied.");
                                    return 1;
                                })))
                        .then(ClientCommands.literal("find")
                                .then(ClientCommands.argument("item", StringArgumentType.greedyString()).executes(ctx -> {
                                    findItem(StringArgumentType.getString(ctx, "item").trim().toLowerCase());
                                    return 1;
                                })))
                        .then(ClientCommands.literal("workitem")
                                .executes(ctx -> { listRules(); return 1; })
                                .then(ClientCommands.literal("list").executes(ctx -> { listRules(); return 1; }))
                                .then(ClientCommands.literal("clear").executes(ctx -> {
                                    cfg.want.clear();
                                    Config.save();
                                    Util.msg("Work items cleared.");
                                    return 1;
                                }))
                                .then(ClientCommands.literal("remove")
                                        .then(ClientCommands.argument("rule", StringArgumentType.greedyString()).executes(ctx -> {
                                            String k = WorkItems.keyOf(StringArgumentType.getString(ctx, "rule"));
                                            Util.msg(cfg.want.remove(k) != null ? "Removed: " + k : "No such work item: " + k);
                                            Config.save();
                                            return 1;
                                        })))
                                .then(ClientCommands.argument("rule", StringArgumentType.greedyString()).executes(ctx -> {
                                    String err = WorkItems.add(StringArgumentType.getString(ctx, "rule"));
                                    if (err != null) Util.msg(err);
                                    return err == null ? 1 : 0;
                                })))
                        .then(toggle("bedplan", v -> cfg.bedPlan = v, "Leave for the bed in time before dusk"))
                        .then(ClientCommands.literal("bedlead")
                                .then(ClientCommands.argument("seconds", IntegerArgumentType.integer(0, 600)).executes(ctx -> {
                                    cfg.bedLead = IntegerArgumentType.getInteger(ctx, "seconds");
                                    Config.save();
                                    Util.msg("Bed trip slack: " + cfg.bedLead + " s on top of the walking time.");
                                    return 1;
                                })))
                        .then(toggle("sleep", v -> cfg.sleep = v, "Auto-sleep")
                                .then(ClientCommands.literal("now").executes(ctx -> {
                                    sleep.startNow();
                                    return 1;
                                })))
                        .then(toggle("restock", v -> cfg.restock = v, "Auto-restock")
                                .then(ClientCommands.literal("now").executes(ctx -> {
                                    restock.startNow();
                                    return 1;
                                })))

                        .then(toggle("walkeat", v -> cfg.walkEat = v, "Eating while Baritone keeps walking"))
                        .then(toggle("topup", v -> cfg.topUp = v, "Top-up when passing the base"))
                        .then(ClientCommands.literal("baseradius")
                                .then(ClientCommands.argument("blocks", IntegerArgumentType.integer(4, 96)).executes(ctx -> {
                                    cfg.baseRadius = IntegerArgumentType.getInteger(ctx, "blocks");
                                    Config.save();
                                    Util.msg("Base radius: " + cfg.baseRadius + " blocks around the chests and the bed.");
                                    return 1;
                                })))
                        .then(ClientCommands.literal("eatbelow")
                                .then(ClientCommands.argument("level", IntegerArgumentType.integer(1, 19)).executes(ctx -> {
                                    cfg.eatBelow = IntegerArgumentType.getInteger(ctx, "level");
                                    Config.save();
                                    Util.msg("Eating when the food bar is at " + cfg.eatBelow + "/20 or lower.");
                                    return 1;
                                })))

                        .then(placeCmd("bed", "bed", b -> b instanceof BedBlock, v -> cfg.bed = v))
                        .then(placeCmd("supply", "supply chest", b -> b instanceof ChestBlock || b instanceof BarrelBlock, v -> cfg.supply = v)
                                .then(ClientCommands.literal("add").executes(ctx -> { supplyAdd(); return 1; }))
                                .then(ClientCommands.literal("remove").executes(ctx -> { supplyRemove(); return 1; }))
                                .then(ClientCommands.literal("list").executes(ctx -> { supplyList(); return 1; })))
                        .then(placeCmd("return", "return chest", b -> b instanceof ChestBlock || b instanceof BarrelBlock, v -> cfg.returnChest = v))

                        .then(ClientCommands.literal("want")
                                .then(ClientCommands.literal("list").executes(ctx -> {
                                    if (cfg.want.isEmpty()) Util.msg("The want-list is empty.");
                                    cfg.want.forEach((k, v) -> Util.msg("want " + k + ": " + v));
                                    return 1;
                                }))
                                .then(ClientCommands.literal("remove")
                                        .then(ClientCommands.argument("item", StringArgumentType.string()).executes(ctx -> {
                                            String k = ruleKey(StringArgumentType.getString(ctx, "item"));
                                            if (cfg.want.remove(k) != null) {
                                                Config.save();
                                                Util.msg("Removed: " + k);
                                            } else {
                                                Util.msg("No such rule: " + k);
                                            }
                                            return 1;
                                        })))
                                .then(ClientCommands.argument("item", StringArgumentType.string())
                                        .then(ClientCommands.argument("count", IntegerArgumentType.integer(1, 2304)).executes(ctx -> {
                                            String k = ruleKey(StringArgumentType.getString(ctx, "item"));
                                            int n = IntegerArgumentType.getInteger(ctx, "count");
                                            cfg.want.put(k, n);
                                            Config.save();
                                            Util.msg("Carry " + n + " x " + k + " (restocks when below half).");
                                            return 1;
                                        }))))));

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft mc) {
        reconnectTask.tick(mc);
        if (mc.player == null) return;
        if (!registered) {
            var control = Util.baritone().getPathingControlManager();
            control.registerProcess(eat);
            control.registerProcess(defence);
            control.registerProcess(sleep);
            control.registerProcess(restock);
            PauseWatch.register(Util.baritone());
            registered = true;
        }
        respawnTick(mc);
        Session.tick(mc, mc.player);
        // the queue waits for a "/survival restock now" line while this is set
        if (restock.busy() || sleep.busy()) System.setProperty("survivalkit.busy", "1");
        else System.clearProperty("survivalkit.busy");
        ChestIndex.tick(mc, mc.player);
        // Clicking onto another monitor would make vanilla open the pause menu (and pause singleplayer worlds).
        if (cfg.background && mc.options.pauseOnLostFocus) mc.options.pauseOnLostFocus = false;
        if (mc.isPaused() && Util.baritoneBusy()) {
            Util.warn("paused", "The game is paused (pause menu in a singleplayer world), so nothing can happen. Close the menu.");
        }
        try {
            sleep.tick();
            restock.tick();
        } catch (Throwable t) {
            t.printStackTrace();
            Util.warn("tick-error", "SurvivalKit hit an error (" + t + "). Details are in the game log.");
        }
    }

    // ------------------------------------------------------------------ respawn

    private int deadTicks;
    private BlockPos lastAlivePos;

    /** Clicks "Respawn" a moment after dying (and again every 5 s if it did not take). */
    private void respawnTick(Minecraft mc) {
        var p = mc.player;
        if (!p.isDeadOrDying()) {
            deadTicks = 0;
            lastAlivePos = p.blockPosition();
            recovery.tick(mc, p);
            return;
        }
        if (deadTicks++ == 0) {
            recovery.onDeath();
            String where = lastAlivePos == null ? "" : " at " + lastAlivePos.toShortString();
            Session.count("deaths");
            Util.warn("died", "You died" + where + ". " + (cfg.respawn
                    ? "Respawning; the Baritone task is gone and your items lie where you died."
                    : "Auto-respawn is off (/survival respawn on)."));
        }
        if (cfg.respawn && deadTicks >= 40 && (deadTicks - 40) % 100 == 0) p.respawn();
    }

    // ------------------------------------------------------------------ command helpers

    private static LiteralArgumentBuilder<FabricClientCommandSource> toggle(String name, Consumer<Boolean> set, String what) {
        return ClientCommands.literal(name)
                .then(ClientCommands.literal("on").executes(ctx -> {
                    set.accept(true);
                    Config.save();
                    Util.msg(what + " on.");
                    return 1;
                }))
                .then(ClientCommands.literal("off").executes(ctx -> {
                    set.accept(false);
                    Config.save();
                    Util.msg(what + " off.");
                    return 1;
                }));
    }

    /** `<name> set` (block you look at), `<name> set x y z`, `<name> clear`. */
    private static LiteralArgumentBuilder<FabricClientCommandSource> placeCmd(
            String name, String label, Predicate<Block> valid, Consumer<int[]> set) {
        return ClientCommands.literal(name)
                .then(ClientCommands.literal("set")
                        .executes(ctx -> {
                            Minecraft mc = Minecraft.getInstance();
                            if (mc.level != null && mc.hitResult instanceof BlockHitResult hit
                                    && valid.test(mc.level.getBlockState(hit.getBlockPos()).getBlock())) {
                                save(set, label, hit.getBlockPos());
                                return 1;
                            }
                            Util.msg("Look at the " + label + " first (or use /survival " + name + " set <x> <y> <z>).");
                            return 0;
                        })
                        .then(ClientCommands.argument("x", IntegerArgumentType.integer())
                                .then(ClientCommands.argument("y", IntegerArgumentType.integer())
                                        .then(ClientCommands.argument("z", IntegerArgumentType.integer())
                                                .executes(ctx -> {
                                                    save(set, label, posOf(ctx));
                                                    return 1;
                                                })))))
                .then(ClientCommands.literal("clear").executes(ctx -> {
                    set.accept(null);
                    Config.save();
                    Util.msg(label + " cleared.");
                    return 1;
                }));
    }

    private static void save(Consumer<int[]> set, String label, BlockPos p) {
        set.accept(new int[]{p.getX(), p.getY(), p.getZ()});
        Config.save();
        Util.msg(label + " saved: " + p.toShortString());
    }

    /** Rule name as typed: `food` is a shortcut for `@food` (the @ can only be typed inside quotes: "@food"). */
    private static String ruleKey(String typed) {
        String k = typed.toLowerCase();
        return k.equals("food") ? "@food" : k;
    }

    private static BlockPos posOf(CommandContext<?> ctx) {
        return new BlockPos(
                IntegerArgumentType.getInteger(ctx, "x"),
                IntegerArgumentType.getInteger(ctx, "y"),
                IntegerArgumentType.getInteger(ctx, "z"));
    }

    // ------------------------------------------------------------------ supply chests, index, work items

    private static BlockPos lookedAtStorage() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.hitResult instanceof BlockHitResult hit
                && ChestIndex.isStorage(mc.level.getBlockState(hit.getBlockPos()).getBlock())) return hit.getBlockPos();
        Util.msg("Look at the chest first.");
        return null;
    }

    private static boolean same(int[] a, BlockPos p) {
        return a != null && a[0] == p.getX() && a[1] == p.getY() && a[2] == p.getZ();
    }

    private static void supplyAdd() {
        BlockPos p = lookedAtStorage();
        if (p == null) return;
        if (cfg.supply == null) { save(v -> cfg.supply = v, "supply chest", p); return; }
        if (same(cfg.supply, p) || cfg.extraSupplies.stream().anyMatch(a -> same(a, p))) { Util.msg("That chest is already a supply chest."); return; }
        cfg.extraSupplies.add(new int[]{p.getX(), p.getY(), p.getZ()});
        Config.save();
        Util.msg("Extra supply chest saved: " + p.toShortString() + " (" + (1 + cfg.extraSupplies.size()) + " in all).");
    }

    private static void supplyRemove() {
        BlockPos p = lookedAtStorage();
        if (p == null) return;
        if (cfg.extraSupplies.removeIf(a -> same(a, p))) {
            Config.save();
            Util.msg("Supply chest removed.");
        } else if (same(cfg.supply, p)) {
            cfg.supply = cfg.extraSupplies.isEmpty() ? null : cfg.extraSupplies.remove(0);
            Config.save();
            Util.msg("Supply chest removed" + (cfg.supply == null ? ": none left." : "; the next one is now the main supply chest."));
        } else {
            Util.msg("That is not a supply chest.");
        }
    }

    private static void supplyList() {
        if (cfg.supply == null) { Util.msg("No supply chest set (/survival supply set)."); return; }
        Util.msg("Supply chests: " + Util.pos(cfg.supply).toShortString()
                + cfg.extraSupplies.stream().map(a -> ", " + Util.pos(a).toShortString()).reduce("", String::concat));
    }

    private static void listChests() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        var list = ChestIndex.nearest(mc, mc.player.blockPosition());
        if (list.isEmpty()) { Util.msg("No chest seen yet. Open a chest and its contents are remembered."); return; }
        for (var e : list) {
            int total = e.items.values().stream().mapToInt(Integer::intValue).sum();
            String top = e.items.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(4)
                    .map(en -> en.getValue() + " " + en.getKey().replace("minecraft:", "")).reduce((a, b) -> a + ", " + b).orElse("empty");
            Util.msg(e.pos().toShortString() + " (" + Math.round(Math.sqrt(e.pos().distSqr(mc.player.blockPosition()))) + " blocks, "
                    + ChestIndex.age(e.time) + "): " + total + " items - " + top);
        }
    }

    private static void findItem(String what) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        boolean any = false;
        for (var e : ChestIndex.nearest(mc, mc.player.blockPosition())) {
            int n = what.equals("food") || what.equals("@food") ? e.food : e.count(what);
            if (n <= 0) continue;
            any = true;
            Util.msg(n + " x " + what + " at " + e.pos().toShortString() + " (" + Math.round(Math.sqrt(e.pos().distSqr(mc.player.blockPosition())))
                    + " blocks, seen " + ChestIndex.age(e.time) + ")");
        }
        if (!any) Util.msg("Not in any chest I have seen. (Only chests you or the bot opened are known.)");
    }

    private static void listRules() {
        if (cfg.want.isEmpty()) { Util.msg("No work items. Example: /survival workitem minecraft:diamond_pickaxe 2 efficiency 5;mending"); return; }
        cfg.want.forEach((k, v) -> Util.msg(v + " x " + k));
    }

    private static int status() {
        Util.msg("eat " + onOff(cfg.eat) + " (when hurt, or food bar at " + cfg.eatBelow + "/20 or lower; golden apple "
                + onOff(cfg.golden) + "), defence " + onOff(cfg.defence)
                + ", sleep " + onOff(cfg.sleep) + ", restock " + onOff(cfg.restock));
        Util.msg("bed: " + (cfg.bed == null ? "not set" : Util.pos(cfg.bed).toShortString())
                + ", supply chest: " + (cfg.supply == null ? "not set" : Util.pos(cfg.supply).toShortString())
                + ", return chest: " + (cfg.returnChest == null ? "not set (worn tools go into the supply chest)" : Util.pos(cfg.returnChest).toShortString()));
        Util.msg("weather bed " + onOff(cfg.weather) + ", reconnect " + onOff(cfg.reconnect) + ", supply chests: "
                + (cfg.supply == null ? 0 : 1 + cfg.extraSupplies.size()) + ", bed pace " + String.format("%.1f", cfg.ticksPerBlock) + " ticks/block");
        Util.msg("Active: " + (cfg.always ? "always" : "only while a Baritone task is running") + ".");
        Util.msg("want-list: " + (cfg.want.isEmpty() ? "empty" : cfg.want));
        return 1;
    }

    private static String onOff(boolean b) {
        return b ? "on" : "off";
    }
}
