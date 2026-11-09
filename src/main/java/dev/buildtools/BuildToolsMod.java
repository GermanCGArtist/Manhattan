package dev.buildtools;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

import static dev.buildtools.Config.cfg;

/**
 * Manhattan's building helpers (formerly the BuildTools addon), started from the script:
 *   drain      - drain(): plugs flowing water that makes #build / #sel ca pause, then resumes the builder
 *   fill       - flood(): fills the #sel selection with water using buckets (after sealing the open sides)
 *   terminator - fill_water(): fills the water in the selection with blocks, from the bottom up (dives if needed)
 *   scaffold   - collect_scaffold(): picks the scaffolding blocks back up
 *   escape     - gets out of holes Baritone got stuck in
 *   safe areas - protect(): no breaking or placing inside (RegionRules, Guard)
 * The /buildtools command stays for checking and changing things by hand.
 */
public class BuildToolsMod implements ClientModInitializer {

    static final DrainProcess drain = new DrainProcess();
    static final FillProcess fill = new FillProcess();
    static final EscapeProcess escape = new EscapeProcess();
    static final ScaffoldProcess scaffold = new ScaffoldProcess();
    static final TerminatorProcess terminator = new TerminatorProcess();
    private boolean busyMarked;

    private boolean registered = false;
    private int syncTick;

    @Override
    public void onInitializeClient() {
        Config.load();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
                dispatcher.register(ClientCommands.literal("buildtools")

                        .executes(ctx -> status())
                        .then(ClientCommands.literal("status").executes(ctx -> status()))

                        .then(ClientCommands.literal("drain")
                                .then(ClientCommands.literal("on").executes(ctx -> {
                                    cfg.drainAuto = true;
                                    Config.save();
                                    Util.msg("Auto-drain on: when the builder pauses because of water, it is removed and the builder resumes.");
                                    return 1;
                                }))
                                .then(ClientCommands.literal("off").executes(ctx -> {
                                    cfg.drainAuto = false;
                                    Config.save();
                                    Util.msg("Auto-drain off.");
                                    return 1;
                                }))
                                .then(ClientCommands.literal("now").executes(ctx -> {
                                    drain.startNow();
                                    return 1;
                                })))

                        .then(ClientCommands.literal("dam")
                                .then(ClientCommands.literal("on").executes(ctx -> {
                                    cfg.dam = true;
                                    Config.save();
                                    Util.msg("Dam on: with a selection, water just outside its edge is plugged too.");
                                    return 1;
                                }))
                                .then(ClientCommands.literal("off").executes(ctx -> {
                                    cfg.dam = false;
                                    Config.save();
                                    Util.msg("Dam off: only water inside the selection is touched.");
                                    return 1;
                                })))

                        .then(ClientCommands.literal("escape")
                                .then(ClientCommands.literal("on").executes(ctx -> {
                                    cfg.escape = true;
                                    Config.save();
                                    Util.msg("Water escape on: when Baritone is stuck in water, I swim to dry land.");
                                    return 1;
                                }))
                                .then(ClientCommands.literal("off").executes(ctx -> {
                                    cfg.escape = false;
                                    Config.save();
                                    Util.msg("Water escape off.");
                                    return 1;
                                })))

                        .then(ClientCommands.literal("terminator")
                                .executes(ctx -> { String e = terminator.startNow(null); if (e != null) Util.msg(e); return 1; })
                                .then(ClientCommands.literal("stop").executes(ctx -> {
                                    terminator.cancel();
                                    Util.msg("Terminator mode stopped.");
                                    return 1;
                                }))
                                .then(ClientCommands.argument("item", StringArgumentType.word()).executes(ctx -> {
                                    String w = StringArgumentType.getString(ctx, "item").toLowerCase();
                                    var id = net.minecraft.resources.Identifier.tryParse(w.contains(":") ? w : "minecraft:" + w);
                                    if (id == null || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(id)) {
                                        Util.msg("'" + w + "' is not an item name.");
                                        return 0;
                                    }
                                    String e = terminator.startNow(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(id));
                                    if (e != null) Util.msg(e);
                                    return 1;
                                })))

                        .then(ClientCommands.literal("scaffold")
                                .executes(ctx -> scaffoldStatus())
                                .then(ClientCommands.literal("collect").executes(ctx -> { scaffold.startNow(); return 1; }))
                                .then(ClientCommands.literal("list").executes(ctx -> scaffoldStatus()))
                                .then(ClientCommands.literal("forget").executes(ctx -> {
                                    Scaffold.clear();
                                    Util.msg("Forgot all recorded scaffolding (nothing was removed from the world).");
                                    return 1;
                                }))
                                .then(ClientCommands.literal("record")
                                        .then(ClientCommands.literal("on").executes(ctx -> {
                                            cfg.scaffoldRecord = true;
                                            Config.save();
                                            Util.msg("Recording the throwaway blocks Baritone places.");
                                            return 1;
                                        }))
                                        .then(ClientCommands.literal("off").executes(ctx -> {
                                            cfg.scaffoldRecord = false;
                                            Config.save();
                                            Util.msg("Not recording scaffolding.");
                                            return 1;
                                        })))
                                .then(ClientCommands.literal("auto")
                                        .then(ClientCommands.literal("on").executes(ctx -> {
                                            cfg.scaffoldAuto = true;
                                            Config.save();
                                            Util.msg("Auto-collect on: with " + cfg.scaffoldBatch + " or more scaffolding blocks near you while Baritone works, I take them down.");
                                            return 1;
                                        }))
                                        .then(ClientCommands.literal("off").executes(ctx -> {
                                            cfg.scaffoldAuto = false;
                                            Config.save();
                                            Util.msg("Auto-collect off: use /buildtools scaffold collect.");
                                            return 1;
                                        })))
                                .then(ClientCommands.literal("batch")
                                        .then(ClientCommands.argument("count", IntegerArgumentType.integer(3, 500)).executes(ctx -> {
                                            cfg.scaffoldBatch = IntegerArgumentType.getInteger(ctx, "count");
                                            Config.save();
                                            Util.msg("Auto-collect starts at " + cfg.scaffoldBatch + " scaffolding blocks near you.");
                                            return 1;
                                        }))))

                        .then(ClientCommands.literal("fill").executes(ctx -> {
                            fill.startNow();
                            return 1;
                        }))

                        // ---- safe areas
                        .then(ClientCommands.literal("protect")
                                .then(ClientCommands.literal("on").executes(ctx -> {
                                    cfg.protect = true;
                                    Config.save();
                                    Util.msg("Safe areas on: Baritone can't break or place blocks inside them.");
                                    return 1;
                                }))
                                .then(ClientCommands.literal("off").executes(ctx -> {
                                    cfg.protect = false;
                                    Config.save();
                                    Util.msg("Safe areas off (they are kept, just not enforced).");
                                    return 1;
                                }))
                                .then(ClientCommands.literal("mining")
                                        .then(ClientCommands.argument("name", StringArgumentType.word())
                                                .then(ClientCommands.literal("allow").executes(ctx -> zoneFlag(ctx, "mining", true)))
                                                .then(ClientCommands.literal("deny").executes(ctx -> zoneFlag(ctx, "mining", false)))))
                                .then(ClientCommands.literal("bridging")
                                        .then(ClientCommands.argument("name", StringArgumentType.word())
                                                .then(ClientCommands.literal("allow").executes(ctx -> zoneFlag(ctx, "bridging", true)))
                                                .then(ClientCommands.literal("deny").executes(ctx -> zoneFlag(ctx, "bridging", false)))))
                                .then(ClientCommands.literal("reach")
                                        .then(ClientCommands.argument("name", StringArgumentType.word())
                                                .then(ClientCommands.literal("on").executes(ctx -> zoneFlag(ctx, "reach", true)))
                                                .then(ClientCommands.literal("off").executes(ctx -> zoneFlag(ctx, "reach", false)))))
                                .then(ClientCommands.literal("reachradius")
                                        .then(ClientCommands.argument("blocks", IntegerArgumentType.integer(1, 12)).executes(ctx -> {
                                            cfg.reachBlocks = IntegerArgumentType.getInteger(ctx, "blocks");
                                            Config.save();
                                            Util.msg("\"Able to reach\" now means within " + cfg.reachBlocks + " blocks of a safe area.");
                                            return 1;
                                        })))
                                .then(ClientCommands.literal("avoid")
                                        .then(ClientCommands.literal("on").executes(ctx -> {
                                            cfg.avoidBlocks = true;
                                            Config.save();
                                            Util.msg("Baritone now plans around chests, hoppers, beds, furnaces and redstone parts (it never breaks them).");
                                            return 1;
                                        }))
                                        .then(ClientCommands.literal("off").executes(ctx -> {
                                            cfg.avoidBlocks = false;
                                            Config.save();
                                            Util.msg("Baritone may plan through chests, hoppers etc. again (only safe areas stop it).");
                                            return 1;
                                        })))
                                .then(ClientCommands.literal("trim")
                                        .then(ClientCommands.literal("on").executes(ctx -> {
                                            cfg.trim = true;
                                            Config.save();
                                            Util.msg("Selections are cut around safe areas.");
                                            return 1;
                                        }))
                                        .then(ClientCommands.literal("off").executes(ctx -> {
                                            cfg.trim = false;
                                            Config.save();
                                            Util.msg("Selections are left as they are.");
                                            return 1;
                                        })))
                                .then(ClientCommands.literal("list").executes(ctx -> {
                                    if (cfg.zones.isEmpty()) Util.msg("No safe areas yet.");
                                    cfg.zones.forEach(z -> Util.msg(z.describe()));
                                    return 1;
                                }))
                                .then(ClientCommands.literal("add")
                                        .executes(ctx -> Zones.addSelection(null))
                                        .then(ClientCommands.argument("name", StringArgumentType.word())
                                                .executes(ctx -> Zones.addSelection(StringArgumentType.getString(ctx, "name")))))
                                .then(ClientCommands.literal("pos1").executes(ctx -> Zones.setCorner(1)))
                                .then(ClientCommands.literal("pos2").executes(ctx -> Zones.setCorner(2)))
                                .then(ClientCommands.literal("save")
                                        .executes(ctx -> Zones.saveCorners(null))
                                        .then(ClientCommands.argument("name", StringArgumentType.word())
                                                .executes(ctx -> Zones.saveCorners(StringArgumentType.getString(ctx, "name")))))
                                .then(ClientCommands.literal("box")
                                        .then(ClientCommands.argument("x1", IntegerArgumentType.integer())
                                        .then(ClientCommands.argument("y1", IntegerArgumentType.integer())
                                        .then(ClientCommands.argument("z1", IntegerArgumentType.integer())
                                        .then(ClientCommands.argument("x2", IntegerArgumentType.integer())
                                        .then(ClientCommands.argument("y2", IntegerArgumentType.integer())
                                        .then(ClientCommands.argument("z2", IntegerArgumentType.integer())
                                                .executes(ctx -> box(ctx, null))
                                                .then(ClientCommands.argument("name", StringArgumentType.word())
                                                        .executes(ctx -> box(ctx, StringArgumentType.getString(ctx, "name")))))))))))
                                .then(ClientCommands.literal("here")
                                        .then(ClientCommands.argument("radius", IntegerArgumentType.integer(1, 64))
                                                .executes(ctx -> Zones.addAroundPlayer(IntegerArgumentType.getInteger(ctx, "radius"), null))
                                                .then(ClientCommands.argument("name", StringArgumentType.word())
                                                        .executes(ctx -> Zones.addAroundPlayer(IntegerArgumentType.getInteger(ctx, "radius"),
                                                                StringArgumentType.getString(ctx, "name"))))))
                                .then(ClientCommands.literal("sites")
                                        .executes(ctx -> Zones.addSites(3))
                                        .then(ClientCommands.argument("radius", IntegerArgumentType.integer(1, 32))
                                                .executes(ctx -> Zones.addSites(IntegerArgumentType.getInteger(ctx, "radius")))))
                                .then(ClientCommands.literal("remove")
                                        .then(ClientCommands.argument("name", StringArgumentType.word())
                                                .executes(ctx -> Zones.remove(StringArgumentType.getString(ctx, "name")))))
                                .then(ClientCommands.literal("clear").executes(ctx -> {
                                    cfg.zones.clear();
                                    Config.save();
                                    Util.msg("All safe areas removed.");
                                    return 1;
                                })))

                        .then(ClientCommands.literal("stop").executes(ctx -> {
                            drain.cancel();
                            fill.cancel();
                            scaffold.cancel();
                            terminator.cancel();
                            Util.msg("Stopped.");
                            return 1;
                        })));
        });

        Guard.register();
        Scaffold.register();
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft mc) {
        if (mc.player == null) { RegionRules.release(); return; }
        if (!registered) {
            var control = Util.baritone().getPathingControlManager();
            control.registerProcess(drain);
            control.registerProcess(fill);
            control.registerProcess(escape);
            control.registerProcess(scaffold);
            control.registerProcess(terminator);
            PauseWatch.register(Util.baritone());
            registered = true;
        }
        if (PauseWatch.paused) return;   // #pause: nothing starts or continues
        try {
            drain.tick();
            fill.tick();
            escape.tick();
            scaffold.tick();
            terminator.tick();
            // a queue line like "/buildtools terminator" waits while this is set
            if (scaffold.busy() || terminator.busy()) { System.setProperty("buildtools.busy", "1"); busyMarked = true; }
            else if (busyMarked) { System.clearProperty("buildtools.busy"); busyMarked = false; }
            RegionRules.tick(mc.player);   // every tick: a path planned in between could already go through a safe area
            if (++syncTick % 20 == 0) {
                Avoid.sync();
                if (cfg.protect && cfg.trim) Zones.trimSelections();
            }
            Guard.tick();
        } catch (Throwable t) {
            t.printStackTrace();
            Util.warn("tick-error", "BuildTools hit an error (" + t + "). Details are in the game log.");
        }
    }

    /** protect mining|bridging|reach <name> ... */
    private static int zoneFlag(com.mojang.brigadier.context.CommandContext<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> c,
                                String what, boolean on) {
        String name = StringArgumentType.getString(c, "name");
        for (Config.Zone z : cfg.zones) {
            if (!z.name.equalsIgnoreCase(name)) continue;
            switch (what) {
                case "mining" -> z.allowBreak = on;
                case "bridging" -> z.allowPlace = on;
                default -> z.reach = on;
            }
            Config.save();
            Util.msg(z.describe());
            return 1;
        }
        Util.msg("No safe area called '" + name + "'. /buildtools protect list shows them.");
        return 0;
    }

    private static int box(com.mojang.brigadier.context.CommandContext<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> c, String name) {
        return Zones.addBox(IntegerArgumentType.getInteger(c, "x1"), IntegerArgumentType.getInteger(c, "y1"), IntegerArgumentType.getInteger(c, "z1"),
                IntegerArgumentType.getInteger(c, "x2"), IntegerArgumentType.getInteger(c, "y2"), IntegerArgumentType.getInteger(c, "z2"), name, false);
    }

    private static int scaffoldStatus() {
        var p = Minecraft.getInstance().player;
        Util.msg("Scaffolding recorded: " + Scaffold.count() + (p == null ? "" : " (" + Scaffold.near(p, 40) + " within 40 blocks of you)")
                + ". Recording " + (cfg.scaffoldRecord ? "on" : "off") + ", auto-collect " + (cfg.scaffoldAuto ? "on at " + cfg.scaffoldBatch : "off")
                + ", " + (scaffold.busy() ? "collecting now" : "idle") + ". /buildtools scaffold collect starts a sweep.");
        return 1;
    }

    private static int status() {
        Util.msg("safe areas " + (cfg.protect ? "on" : "off") + " (" + cfg.zones.size() + ")");
        Util.msg("auto-drain " + (cfg.drainAuto ? "on" : "off") + ", dam " + (cfg.dam ? "on" : "off")
                + ", drain " + (drain.busy() ? "working" : "idle") + ", fill " + (fill.busy() ? "working" : "idle"));
        return 1;
    }
}
