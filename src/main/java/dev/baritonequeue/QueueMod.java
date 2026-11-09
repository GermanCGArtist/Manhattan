package dev.baritonequeue;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import static dev.baritonequeue.Config.cfg;

/**
 * Baritone scripts: a small programming language whose lines can be Baritone commands (#sel ca), and whose functions
 * reach the helpers that used to be separate addons (fill_water, take, dump, sleep, restock, unload ...).
 * Edit it on screen (key J, changeable in the controls); start it with Run or /queue run.
 */
public class QueueMod implements ClientModInitializer {
    static final Runner runner = new Runner();
    static final ChestProcess chest = new ChestProcess();
    static final ClearProcess clear = new ClearProcess();
    static KeyMapping open;
    private boolean registered = false;
    private int noPlayer, areaTick;

    /** "Pick block" from the editor: the editor is closed until the player presses the key while looking at a block. */
    static boolean picking;

    @Override
    public void onInitializeClient() {
        Config.load();
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("baritonequeue", "main"));
        open = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.baritonequeue.open", 74 /* GLFW_KEY_J */, category));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("queue")
                        .executes(ctx -> list())
                        .then(ClientCommands.literal("open").executes(ctx -> {
                            Minecraft mc = Minecraft.getInstance();
                            mc.execute(() -> mc.setScreen(new QueueScreen()));   // after the chat screen has closed
                            return 1;
                        }))
                        .then(ClientCommands.literal("add")
                                .then(ClientCommands.argument("line", StringArgumentType.greedyString()).executes(ctx -> {
                                    String c = StringArgumentType.getString(ctx, "line");
                                    if (c.isBlank()) return 0;
                                    cfg.script.add(c);
                                    Config.save();
                                    Util.msg("Added line " + cfg.script.size() + ": " + c);
                                    return 1;
                                })))
                        .then(ClientCommands.literal("list").executes(ctx -> list()))
                        .then(ClientCommands.literal("run").executes(ctx -> run()))
                        .then(ClientCommands.literal("start").executes(ctx -> run()))
                        .then(ClientCommands.literal("stop").executes(ctx -> {
                            if (runner.running()) runner.halt("Script stopped. /queue run starts it again from the top.");
                            else Util.msg("The script is not running.");
                            return 1;
                        }))
                        .then(ClientCommands.literal("skip").executes(ctx -> { runner.skip(); return 1; }))
                        .then(ClientCommands.literal("remove")
                                .then(ClientCommands.argument("number", IntegerArgumentType.integer(1)).executes(ctx -> {
                                    int n = IntegerArgumentType.getInteger(ctx, "number");
                                    if (n > cfg.script.size()) { Util.msg("There is no line " + n + "."); return 0; }
                                    Util.msg("Removed: " + cfg.script.remove(n - 1));
                                    Config.save();
                                    return 1;
                                })))
                        .then(ClientCommands.literal("chest")
                                .executes(ctx -> chestList())
                                .then(ClientCommands.literal("list").executes(ctx -> chestList()))
                                .then(ClientCommands.literal("set")
                                        .then(ClientCommands.argument("name", StringArgumentType.word()).executes(ctx -> {
                                            Places.Target t = Places.target();
                                            if (t == null) { Util.msg("Look at a chest (or any block) first."); return 0; }
                                            String err = Places.saveChest(StringArgumentType.getString(ctx, "name"), t.pos());
                                            Util.msg(err != null ? err : "Saved chest '" + StringArgumentType.getString(ctx, "name").toLowerCase() + "' at " + t.pos() + ". In a script: chest(\"" + StringArgumentType.getString(ctx, "name").toLowerCase() + "\").");
                                            return err == null ? 1 : 0;
                                        })))
                                .then(ClientCommands.literal("add")
                                        .then(ClientCommands.argument("name", StringArgumentType.word())
                                        .then(ClientCommands.argument("x", IntegerArgumentType.integer())
                                        .then(ClientCommands.argument("y", IntegerArgumentType.integer())
                                        .then(ClientCommands.argument("z", IntegerArgumentType.integer()).executes(ctx -> {
                                            String err = Places.saveChest(StringArgumentType.getString(ctx, "name"), new Values.Pos(
                                                    IntegerArgumentType.getInteger(ctx, "x"), IntegerArgumentType.getInteger(ctx, "y"), IntegerArgumentType.getInteger(ctx, "z")));
                                            Util.msg(err != null ? err : "Saved chest '" + StringArgumentType.getString(ctx, "name").toLowerCase() + "'.");
                                            return err == null ? 1 : 0;
                                        }))))))
                                .then(ClientCommands.literal("remove")
                                        .then(ClientCommands.argument("name", StringArgumentType.word()).executes(ctx -> {
                                            String n = StringArgumentType.getString(ctx, "name").toLowerCase();
                                            Util.msg(cfg.chests.remove(n) != null ? "Removed chest '" + n + "'." : "There is no chest '" + n + "'.");
                                            Config.save();
                                            return 1;
                                        }))))
                        .then(ClientCommands.literal("area")
                                .executes(ctx -> areaList())
                                .then(ClientCommands.literal("list").executes(ctx -> areaList()))
                                .then(ClientCommands.literal("save")
                                        .then(ClientCommands.argument("name", StringArgumentType.word()).executes(ctx -> {
                                            String err = Areas.save(StringArgumentType.getString(ctx, "name"));
                                            if (err != null) Util.msg(err);
                                            return err == null ? 1 : 0;
                                        })))
                                .then(ClientCommands.literal("load")
                                        .then(ClientCommands.argument("name", StringArgumentType.word()).executes(ctx -> {
                                            String err = Areas.load(StringArgumentType.getString(ctx, "name"));
                                            if (err != null) Util.msg(err);
                                            return err == null ? 1 : 0;
                                        })))
                                .then(ClientCommands.literal("delete")
                                        .then(ClientCommands.argument("name", StringArgumentType.word()).executes(ctx -> {
                                            String n = StringArgumentType.getString(ctx, "name");
                                            Util.msg(Areas.delete(n) ? "Deleted area '" + n + "'." : "There is no saved area '" + n + "'.");
                                            return 1;
                                        }))))
                        .then(ClientCommands.literal("stall")
                                .executes(ctx -> {
                                    Util.msg(cfg.stallMinutes > 0
                                            ? "A Baritone task with no progress for " + cfg.stallMinutes + " min is skipped. /queue stall <minutes|off> changes it."
                                            : "Stall check is off. /queue stall <minutes> turns it on.");
                                    return 1;
                                })
                                .then(ClientCommands.literal("off").executes(ctx -> {
                                    cfg.stallMinutes = 0;
                                    Config.save();
                                    Util.msg("Stall check off: a stuck task stays stuck until you /queue skip it.");
                                    return 1;
                                }))
                                .then(ClientCommands.argument("minutes", IntegerArgumentType.integer(1, 600)).executes(ctx -> {
                                    cfg.stallMinutes = IntegerArgumentType.getInteger(ctx, "minutes");
                                    Config.save();
                                    Util.msg("A Baritone task with no progress for " + cfg.stallMinutes + " min is now skipped.");
                                    return 1;
                                })))
                        .then(ClientCommands.literal("clear").executes(ctx -> {
                            cfg.script.clear();
                            Config.save();
                            Util.msg("Script cleared.");
                            return 1;
                        }))));

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);

        // The progress on the game screen while the script runs (hidden with F1, and while the editor is open),
        // and the hint while a block is being picked for the editor.
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("baritonequeue", "progress"), (g, delta) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.options.hideGui || mc.screen instanceof QueueScreen) return;
            int sw = mc.getWindow().getGuiScaledWidth();
            if (picking) {
                Places.Target t = Places.target();
                String a = "Look at a block and press " + open.getTranslatedKeyMessage().getString() + " to put its position into the script.";
                String b = t == null ? "(Press it looking at the sky to go back without one.)" : "Looking at: " + t.name() + " at " + t.pos();
                int w = Math.max(mc.font.width(a), mc.font.width(b)) + 12;
                g.fill(sw / 2 - w / 2, 28, sw / 2 + w / 2, 54, 0xAA000000);
                g.centeredText(mc.font, a, sw / 2, 32, 0xFFFFFF60);
                g.centeredText(mc.font, b, sw / 2, 43, 0xFFE0E0E0);
                return;
            }
            if (!runner.running()) return;
            int w = Math.min(300, sw - 20);
            int x = (sw - w) / 2;
            g.fill(x - 4, 2, x + w + 4, 6 + ProgressView.HEIGHT, 0x99000000);   // readable against any sky
            ProgressView.draw(g, mc.font, x, 6, w);
        });
    }

    private static int run() {
        String err = runner.start(cfg.script);
        if (err != null) Util.msg(err);
        return err == null ? 1 : 0;
    }

    private static int chestList() {
        if (cfg.chests.isEmpty()) Util.msg("No saved chests. In a script, use positions: let dirt = pos(x, y, z) - the editor's Pick block button fills them in.");
        cfg.chests.forEach((k, v) -> Util.msg(k + ": " + v[0] + " " + v[1] + " " + v[2] + "   (in a script: chest(\"" + k + "\"))"));
        return 1;
    }

    private static int areaList() {
        if (cfg.areas.isEmpty()) Util.msg("No saved areas. Select the area (#sel pos1 / pos2), then /queue area save <name>. In a script: area(\"name\").");
        cfg.areas.forEach((k, v) -> Util.msg(k + ": " + Areas.describe(v)));
        if (!cfg.lastArea.isEmpty()) Util.msg("last (remembered by itself): " + Areas.describe(cfg.lastArea));
        return 1;
    }

    private static int list() {
        if (cfg.script.isEmpty()) Util.msg("The script is empty. Open the editor (key J).");
        for (int i = 0; i < cfg.script.size(); i++) Util.msg((i + 1) + ". " + cfg.script.get(i));
        Util.msg(runner.status());
        return 1;
    }

    /**
     * Once: the helpers stop acting by themselves; the script calls them (eat(), sleep(), dump() ...). Respawn,
     * resume after death and reconnect stay as they were: they only bring the running script back.
     */
    private static void helpersManual() {
        String[] off = {"survival.eat", "survival.defence", "survival.sleep", "survival.restock",
                "buildtools.drainAuto", "buildtools.escape", "buildtools.scaffoldAuto", "loadchest.auto", "loadchest.pickup"};
        for (String k : off) {
            try {
                Modules.set(k, false, 0);
            } catch (RuntimeException e) {
                e.printStackTrace();
            }
        }
        cfg.helpersManual = true;
        Config.save();
        Util.msg("The helpers no longer act by themselves (eating, sleeping, fighting, restocking, draining, water escape, "
                + "scaffolding, emptying, pickup). Call them from the script, or switch one on: option(\"survival.eat\", true).");
    }

    private void tick(Minecraft mc) {
        Util.flush();
        if (mc.player == null) {
            picking = false;
            // a dropped connection that the reconnect helper brings back: keep the script and carry on afterwards
            if (runner.running() && (System.getProperty("survivalkit.recovering") != null || ++noPlayer < 60)) runner.holdForReconnect();
            else runner.reset();
            return;
        }
        noPlayer = 0;
        if (++areaTick % 20 == 0 && registered) Areas.remember();
        HotbarTools.tick(mc);
        if (!registered) {
            if (!cfg.helpersManual) helpersManual();
            PauseWatch.register(Util.baritone());
            Util.baritone().getPathingControlManager().registerProcess(chest);
            Util.baritone().getPathingControlManager().registerProcess(clear);
            registered = true;
        }
        while (open.consumeClick()) {
            if (picking) {
                picking = false;
                Places.Target t = Places.target();
                QueueScreen.pickResult(t);
                mc.setScreen(new QueueScreen());
            } else if (mc.screen == null) {
                mc.setScreen(new QueueScreen());
            }
        }
        clear.tick();
        if (PauseWatch.paused) return;   // #pause: the script holds still
        try {
            runner.tick();
        } catch (Throwable t) {
            t.printStackTrace();
            Util.warn("tick-error", "The script runner hit an error (" + t + "). Details are in the game log.");
            runner.reset();
        }
    }
}
