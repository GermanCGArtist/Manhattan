package dev.manhattan;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.manhattan.project.GameProjectRuntime;
import dev.manhattan.project.PriorityScheduler;
import dev.manhattan.project.Project;
import dev.manhattan.project.ProjectTask;
import dev.manhattan.project.TaskQueue;
import dev.midtown.api.Area;
import dev.midtown.api.BlockFilter;
import dev.midtown.api.EngineSelector;
import dev.midtown.api.EngineStatus;
import dev.midtown.api.RuntimeBus;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import static dev.manhattan.Config.cfg;

/**
 * Baritone scripts: a small programming language whose lines can be Baritone commands (#sel ca), and whose functions
 * reach the helpers that used to be separate addons (fill_water, take, dump, sleep, restock, unload ...).
 * Edit it on screen (key J, changeable in the controls); start it with Run or /queue run.
 */
public class QueueMod implements ClientModInitializer {
    static final Runner runner = new Runner();
    static final ChestProcess chest = new ChestProcess();
    static final ClearProcess clear = new ClearProcess();
    static final SchemProcess schem = new SchemProcess();
    static KeyMapping open, capture;
    private boolean registered = false;
    private int noPlayer, areaTick;

    /** "Pick block" from the editor: the editor is closed until the player presses the key while looking at a block. */
    static boolean picking;

    public static GameProjectRuntime projects() {
        return GameProjectRuntime.instance();
    }

    @Override
    public void onInitializeClient() {
        dev.midtown.api.EngineSelector.register("midtown", dev.midtown.exec.MidtownEngine::new);
        Config.load();
        dev.manhattan.fleet.FleetClient.instance().initialize(
                dev.manhattan.fleet.FleetMode.parse(cfg.fleetMode), cfg.fleetTags);
        dev.midtown.exec.MidtownEngine.configureDeathStorage(projectConfigDir().resolve("manhattan-deaths"), QueueMod::projectWorld);
        LevelRenderEvents.BEFORE_GIZMOS.register(ctx -> {
            Areas.renderWireframe();
            WorldOverlay.render();
            PathRender.render();
        });
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("baritonequeue", "main"));
        open = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.baritonequeue.open", 74 /* GLFW_KEY_J */, category));
        capture = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.baritonequeue.capture", 75 /* GLFW_KEY_K */, category));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> SchemCmd.register(dispatcher));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> registerProjectCommands(dispatcher));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("queue")
                        .executes(ctx -> list())
                        .then(ClientCommands.literal("open").executes(ctx -> {
                            Minecraft mc = Minecraft.getInstance();
                            mc.execute(() -> {   // after the chat screen has closed
                                ImGuiManager.openShell(Shell.Mode.EDIT);
                                Shell.drawer = Shell.Drawer.NONE;
                            });
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
                        .then(ClientCommands.literal("capture")
                                .executes(ctx -> {
                                    Minecraft mc = Minecraft.getInstance();
                                    mc.execute(() -> {
                                        ImGuiManager.openShell(Shell.lastMode());
                                        Shell.drawer = Shell.Drawer.CAPTURE;
                                    });
                                    return 1;
                                })
                                .then(ClientCommands.literal("dir")
                                        .executes(ctx -> {
                                            Util.msg("Capture sheets go to " + Capture.dir() + ". /queue capture dir <folder> changes it, /queue capture dir default puts it back.");
                                            return 1;
                                        })
                                        .then(ClientCommands.argument("folder", StringArgumentType.greedyString()).executes(ctx -> {
                                            String d = StringArgumentType.getString(ctx, "folder").trim();
                                            if (d.startsWith("\"") && d.endsWith("\"") && d.length() > 1) d = d.substring(1, d.length() - 1);
                                            Capture.save();
                                            cfg.captureDir = d.equalsIgnoreCase("default") ? "" : d;
                                            Config.save();
                                            Capture.save();
                                            Util.msg("Capture sheets now go to " + Capture.dir() + ".");
                                            return 1;
                                        }))))
                        .then(ClientCommands.literal("run").executes(ctx -> run()))
                        .then(ClientCommands.literal("start").executes(ctx -> run()))
                        .then(ClientCommands.literal("stop").executes(ctx -> {
                            if (runner.running()) runner.halt("Script stopped. /queue run starts it again from the top.");
                            else Util.msg("The script is not running.");
                            return 1;
                        }))
                        .then(ClientCommands.literal("skip").executes(ctx -> { runner.skip(); return 1; }))
                        // the job ledger (what earlier runs finished) of the running script, or of the editor's tab
                        .then(ClientCommands.literal("jobs").executes(ctx -> {
                            Jobs.useCommandScript(runner.running());
                            Jobs.describe().forEach(Util::msg);
                            return 1;
                        }))
                        .then(ClientCommands.literal("reset")
                                .executes(ctx -> ledgerChange(null, false))
                                .then(ClientCommands.argument("job", StringArgumentType.greedyString())
                                        .executes(ctx -> ledgerChange(StringArgumentType.getString(ctx, "job"), false))))
                        .then(ClientCommands.literal("retry")
                                .then(ClientCommands.argument("job", StringArgumentType.greedyString())
                                        .executes(ctx -> ledgerChange(StringArgumentType.getString(ctx, "job"), true))))
                        .then(ClientCommands.literal("remove")
                                .then(ClientCommands.argument("number", IntegerArgumentType.integer(1)).executes(ctx -> {
                                    int n = IntegerArgumentType.getInteger(ctx, "number");
                                    if (n > cfg.script.size()) { Util.msg("There is no line " + n + "."); return 0; }
                                    Util.msg("Removed: " + cfg.script.remove(n - 1));
                                    Config.save();
                                    return 1;
                                })))
                        .then(ClientCommands.literal("overlay")
                                .executes(ctx -> {
                                    Util.msg("World overlay: " + (cfg.showWorldOverlay ? "ON" : "OFF")
                                            + " (beds=" + (cfg.showOverlayBeds ? "on" : "off")
                                            + ", chests=" + (cfg.showOverlayChests ? "on" : "off")
                                            + ", safe=" + (cfg.showOverlaySafeAreas ? "on" : "off")
                                            + ", areas=" + (cfg.showOverlayTaskAreas ? "on" : "off")
                                            + ", fill=" + (cfg.showOverlayAreaFill ? "on" : "off")
                                            + ", labels=" + (cfg.showOverlayLabels ? "on" : "off")
                                            + ", xray=" + (cfg.showOverlayThroughWalls ? "on" : "off")
                                            + ", problems=" + (cfg.showOverlayProblemsOnly ? "on" : "off") + ")");
                                    WorldOverlay.checkReport().forEach(Util::msg);
                                    return 1;
                                })
                                .then(ClientCommands.literal("on").executes(ctx -> setOverlayMaster(true)))
                                .then(ClientCommands.literal("off").executes(ctx -> setOverlayMaster(false)))
                                .then(ClientCommands.literal("toggle").executes(ctx -> setOverlayMaster(!cfg.showWorldOverlay)))
                                .then(ClientCommands.literal("check").executes(ctx -> {
                                    WorldOverlay.checkReport().forEach(Util::msg);
                                    return 1;
                                }))
                                .then(ClientCommands.literal("reset").executes(ctx -> {
                                    WorldOverlay.resetHidden();
                                    Util.msg("All hidden overlay elements are now visible again.");
                                    return 1;
                                }))
                                .then(ClientCommands.literal("hide")
                                        .then(ClientCommands.argument("name", StringArgumentType.greedyString()).executes(ctx -> {
                                            String name = StringArgumentType.getString(ctx, "name");
                                            int n = WorldOverlay.setVisibleByName(name, false);
                                            Util.msg("Hidden " + n + " overlay element(s) matching '" + name + "'.");
                                            return 1;
                                        })))
                                .then(ClientCommands.literal("show")
                                        .then(ClientCommands.argument("name", StringArgumentType.greedyString()).executes(ctx -> {
                                            String name = StringArgumentType.getString(ctx, "name");
                                            int n = WorldOverlay.setVisibleByName(name, true);
                                            Util.msg("Shown " + n + " overlay element(s) matching '" + name + "'.");
                                            return 1;
                                        })))
                                .then(ClientCommands.literal("beds")
                                        .executes(ctx -> toggleOverlayLayer("beds", null))
                                        .then(ClientCommands.literal("on").executes(ctx -> toggleOverlayLayer("beds", true)))
                                        .then(ClientCommands.literal("off").executes(ctx -> toggleOverlayLayer("beds", false))))
                                .then(ClientCommands.literal("chests")
                                        .executes(ctx -> toggleOverlayLayer("chests", null))
                                        .then(ClientCommands.literal("on").executes(ctx -> toggleOverlayLayer("chests", true)))
                                        .then(ClientCommands.literal("off").executes(ctx -> toggleOverlayLayer("chests", false))))
                                .then(ClientCommands.literal("safe")
                                        .executes(ctx -> toggleOverlayLayer("safe", null))
                                        .then(ClientCommands.literal("on").executes(ctx -> toggleOverlayLayer("safe", true)))
                                        .then(ClientCommands.literal("off").executes(ctx -> toggleOverlayLayer("safe", false))))
                                .then(ClientCommands.literal("areas")
                                        .executes(ctx -> toggleOverlayLayer("areas", null))
                                        .then(ClientCommands.literal("on").executes(ctx -> toggleOverlayLayer("areas", true)))
                                        .then(ClientCommands.literal("off").executes(ctx -> toggleOverlayLayer("areas", false))))
                                .then(ClientCommands.literal("fill")
                                        .executes(ctx -> toggleOverlayLayer("fill", null))
                                        .then(ClientCommands.literal("on").executes(ctx -> toggleOverlayLayer("fill", true)))
                                        .then(ClientCommands.literal("off").executes(ctx -> toggleOverlayLayer("fill", false))))
                                .then(ClientCommands.literal("labels")
                                        .executes(ctx -> toggleOverlayLayer("labels", null))
                                        .then(ClientCommands.literal("on").executes(ctx -> toggleOverlayLayer("labels", true)))
                                        .then(ClientCommands.literal("off").executes(ctx -> toggleOverlayLayer("labels", false))))
                                .then(ClientCommands.literal("xray")
                                        .executes(ctx -> toggleOverlayLayer("xray", null))
                                        .then(ClientCommands.literal("on").executes(ctx -> toggleOverlayLayer("xray", true)))
                                        .then(ClientCommands.literal("off").executes(ctx -> toggleOverlayLayer("xray", false))))
                                .then(ClientCommands.literal("problems")
                                        .executes(ctx -> toggleOverlayLayer("problems", null))
                                        .then(ClientCommands.literal("on").executes(ctx -> toggleOverlayLayer("problems", true)))
                                        .then(ClientCommands.literal("off").executes(ctx -> toggleOverlayLayer("problems", false)))))
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
                                .then(ClientCommands.literal("move")
                                        .then(ClientCommands.argument("name", StringArgumentType.word())
                                                .then(ClientCommands.argument("dx", IntegerArgumentType.integer())
                                                        .then(ClientCommands.argument("dy", IntegerArgumentType.integer())
                                                                .then(ClientCommands.argument("dz", IntegerArgumentType.integer()).executes(ctx -> {
                                                                    String err = Areas.move(StringArgumentType.getString(ctx, "name"), IntegerArgumentType.getInteger(ctx, "dx"),
                                                                            IntegerArgumentType.getInteger(ctx, "dy"), IntegerArgumentType.getInteger(ctx, "dz"));
                                                                    if (err != null) Util.msg(err);
                                                                    return err == null ? 1 : 0;
                                                                }))))))
                                .then(ClientCommands.literal("delete")
                                        .then(ClientCommands.argument("name", StringArgumentType.word()).executes(ctx -> {
                                            String n = StringArgumentType.getString(ctx, "name");
                                            Util.msg(Areas.delete(n) ? "Deleted area '" + n + "'." : "There is no saved area '" + n + "'.");
                                            return 1;
                                        })))
                                .then(ClientCommands.literal("pos1")
                                        .executes(ctx -> {
                                            var p = Minecraft.getInstance().player;
                                            if (p == null) return 0;
                                            var pos = p.blockPosition();
                                            Areas.setPos1(pos);
                                            Util.msg("pos1 set to " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                                                    + (Areas.any() ? " (" + Areas.describe(Areas.read()) + ")" : "") + ".");
                                            return 1;
                                        })
                                        .then(ClientCommands.argument("x", IntegerArgumentType.integer())
                                                .then(ClientCommands.argument("y", IntegerArgumentType.integer())
                                                        .then(ClientCommands.argument("z", IntegerArgumentType.integer()).executes(ctx -> {
                                                            var pos = new net.minecraft.core.BlockPos(
                                                                    IntegerArgumentType.getInteger(ctx, "x"),
                                                                    IntegerArgumentType.getInteger(ctx, "y"),
                                                                    IntegerArgumentType.getInteger(ctx, "z"));
                                                            Areas.setPos1(pos);
                                                            Util.msg("pos1 set to " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                                                                    + (Areas.any() ? " (" + Areas.describe(Areas.read()) + ")" : "") + ".");
                                                            return 1;
                                                        })))))
                                .then(ClientCommands.literal("pos2")
                                        .executes(ctx -> {
                                            var p = Minecraft.getInstance().player;
                                            if (p == null) return 0;
                                            var pos = p.blockPosition();
                                            Areas.setPos2(pos);
                                            Util.msg("pos2 set to " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                                                    + (Areas.any() ? " (" + Areas.describe(Areas.read()) + ")" : "") + ".");
                                            return 1;
                                        })
                                        .then(ClientCommands.argument("x", IntegerArgumentType.integer())
                                                .then(ClientCommands.argument("y", IntegerArgumentType.integer())
                                                        .then(ClientCommands.argument("z", IntegerArgumentType.integer()).executes(ctx -> {
                                                            var pos = new net.minecraft.core.BlockPos(
                                                                    IntegerArgumentType.getInteger(ctx, "x"),
                                                                    IntegerArgumentType.getInteger(ctx, "y"),
                                                                    IntegerArgumentType.getInteger(ctx, "z"));
                                                            Areas.setPos2(pos);
                                                            Util.msg("pos2 set to " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                                                                    + (Areas.any() ? " (" + Areas.describe(Areas.read()) + ")" : "") + ".");
                                                            return 1;
                                                        })))))
                                .then(ClientCommands.literal("set")
                                        .then(ClientCommands.argument("x1", IntegerArgumentType.integer())
                                                .then(ClientCommands.argument("y1", IntegerArgumentType.integer())
                                                        .then(ClientCommands.argument("z1", IntegerArgumentType.integer())
                                                                .then(ClientCommands.argument("x2", IntegerArgumentType.integer())
                                                                        .then(ClientCommands.argument("y2", IntegerArgumentType.integer())
                                                                                .then(ClientCommands.argument("z2", IntegerArgumentType.integer()).executes(ctx -> {
                                                                                    var a = new net.minecraft.core.BlockPos(
                                                                                            IntegerArgumentType.getInteger(ctx, "x1"),
                                                                                            IntegerArgumentType.getInteger(ctx, "y1"),
                                                                                            IntegerArgumentType.getInteger(ctx, "z1"));
                                                                                    var b = new net.minecraft.core.BlockPos(
                                                                                            IntegerArgumentType.getInteger(ctx, "x2"),
                                                                                            IntegerArgumentType.getInteger(ctx, "y2"),
                                                                                            IntegerArgumentType.getInteger(ctx, "z2"));
                                                                                    Areas.setSingleBox(a, b);
                                                                                    Util.msg("Selection set: " + Areas.describe(Areas.read()) + ".");
                                                                                    return 1;
                                                                                }))))))))
                                .then(ClientCommands.literal("clear").executes(ctx -> {
                                    Areas.clearSelection();
                                    Util.msg("Selection cleared.");
                                    return 1;
                                })))
                        .then(ClientCommands.literal("move")   // the whole setup: every saved area, saved chest and Litematica placement
                                .then(ClientCommands.argument("dx", IntegerArgumentType.integer())
                                        .then(ClientCommands.argument("dy", IntegerArgumentType.integer())
                                                .then(ClientCommands.argument("dz", IntegerArgumentType.integer()).executes(ctx -> {
                                                    int dx = IntegerArgumentType.getInteger(ctx, "dx"), dy = IntegerArgumentType.getInteger(ctx, "dy"), dz = IntegerArgumentType.getInteger(ctx, "dz");
                                                    if (!cfg.areas.isEmpty()) Areas.move("all", dx, dy, dz);
                                                    int chests = Places.moveChests(dx, dy, dz);
                                                    if (chests > 0) Util.msg("Moved " + chests + " saved chest" + (chests == 1 ? "" : "s") + ".");
                                                    String err = SchemSource.move(null, dx, dy, dz);
                                                    if (err != null) Util.msg(err);
                                                    Util.msg("Positions written into scripts as pos(...) do not move: add " + dx + " " + dy + " " + dz + " to them.");
                                                    return 1;
                                                })))))
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
                        .then(ClientCommands.literal("ui")
                                .executes(ctx -> {
                                    Util.msg("The workspace (key J) opens in '" + cfg.uiLastMode + "'. /queue ui <edit|monitor|close> switches it.");
                                    return 1;
                                })
                                .then(ClientCommands.literal("edit").executes(ctx -> {
                                    Minecraft mc = Minecraft.getInstance();
                                    mc.execute(() -> ImGuiManager.openShell(Shell.Mode.EDIT));
                                    return 1;
                                }))
                                .then(ClientCommands.literal("monitor").executes(ctx -> {
                                    Minecraft mc = Minecraft.getInstance();
                                    mc.execute(() -> ImGuiManager.openShell(Shell.Mode.MONITOR));
                                    return 1;
                                }))
                                .then(ClientCommands.literal("close").executes(ctx -> {
                                    Minecraft mc = Minecraft.getInstance();
                                    mc.execute(ImGuiManager::closeShell);
                                    return 1;
                                })))
                        .then(ClientCommands.literal("deaths")
                                .executes(ctx -> listDeaths())
                                .then(ClientCommands.literal("clear").executes(ctx -> {
                                    dev.midtown.exec.MidtownEngine.clearDeaths();
                                    Util.msg("Cleared remembered death hazards for this world.");
                                    return 1;
                                })))
                        .then(ClientCommands.literal("clear").executes(ctx -> {
                            cfg.script.clear();
                            Config.save();
                            Util.msg("Script cleared.");
                            return 1;
                        }))));

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        // The run status, aiming hints and error toasts over the game are drawn by Overlay (Dear ImGui).
    }

    /** After aiming in the world (Pick block, Capture): reopen the workspace if aiming closed it. */
    private static void backFromAiming(Shell.Drawer drawer) {
        if (Shell.suspended()) Shell.resume(drawer);
        else if (Shell.isOpen() && drawer != null) Shell.drawer = drawer;
    }

    /** /queue reset [job] and /queue retry <job>: never while a script runs (it reads and writes the same ledger). */
    private static int ledgerChange(String job, boolean retry) {
        if (runner.running()) {
            Util.msg("A script is running: stop it first (/queue stop), then " + (retry ? "retry" : "reset") + ".");
            return 0;
        }
        Jobs.useCommandScript(false);
        String j = job == null ? null : job.trim();   // a job name, or any ledger id prefix (/queue jobs shows them)
        Util.msg((retry ? Jobs.retry(j) : Jobs.reset(j)) + " (" + Jobs.ledger().path().getFileName() + ")");
        return 1;
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
        cfg.areas.forEach((k, v) -> {
            var meta = Areas.meta(k);
            String info = (meta != null ? " [" + meta.owner + ", " + meta.role + "]" : "");
            Util.msg(k + info + ": " + Areas.describe(v));
        });
        if (!cfg.lastArea.isEmpty()) Util.msg("last (remembered by itself): " + Areas.describe(cfg.lastArea));
        return 1;
    }

    private static int list() {
        if (cfg.script.isEmpty()) Util.msg("The script is empty. Open the editor (key J).");
        for (int i = 0; i < cfg.script.size(); i++) Util.msg((i + 1) + ". " + cfg.script.get(i));
        Util.msg(runner.status());
        return 1;
    }

    private static int listDeaths() {
        Minecraft mc = Minecraft.getInstance();
        String dim = (mc != null && mc.level != null) ? mc.level.dimension().identifier().toString() : null;
        var sites = dev.midtown.exec.MidtownEngine.recentDeathSites(dim);
        if (sites.isEmpty()) {
            Util.msg("No recent death hazards remembered.");
            return 1;
        }
        long now = System.currentTimeMillis();
        Util.msg("Remembered death hazards (" + sites.size() + "):");
        for (var r : sites) {
            long ageMin = Math.max(0L, (now - r.timestampMs) / 60_000L);
            Util.msg("  (" + r.x + ", " + r.y + ", " + r.z + ") " + ageMin + "m ago (avoidance r=" + r.radiusXZ + ")");
        }
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
        ScaffoldZones.syncWorld(mc);
        if (mc.player == null) {
            picking = false;
            if (Shell.isOpen()) ImGuiManager.closeShell();   // left the world: the title screen gets the whole window
            Capture.tick(mc);   // ends aiming
            // a dropped connection that the reconnect helper brings back: keep the script and carry on afterwards
            if (runner.running() && (RuntimeBus.isRecovering() || ++noPlayer < 60)) runner.holdForReconnect();
            else runner.reset();
            if (GameProjectRuntime.instance().running() && !RuntimeBus.isRecovering() && noPlayer >= 60) {
                GameProjectRuntime.instance().stopQueue("Disconnected.");
            }
            return;
        }
        noPlayer = 0;
        if (++areaTick % 20 == 0 && registered) Areas.remember();
        if (mc.level != null) Areas.tick(mc);
        HotbarTools.tick(mc);
        if (!registered) {
            if (!cfg.helpersManual) helpersManual();
            registered = true;
        }
        while (open.consumeClick()) {
            if (Capture.aiming) {
                Places.Target t = Places.target();
                if (t == null) {   // the sky: back to the workspace
                    Capture.aiming = false;
                    Capture.corner1 = null;
                    backFromAiming(Shell.Drawer.CAPTURE);
                    continue;
                }
                String r = Capture.capture(t);
                Util.msg(r);
                boolean waiting = Capture.corner1 != null || r.startsWith("Pick");
                if (!cfg.captureRapid && !waiting) {
                    Capture.aiming = false;
                    backFromAiming(Shell.Drawer.CAPTURE);
                }
            } else if (picking) {
                picking = false;
                DockedPanels.handleImGuiPick(Places.target());
                backFromAiming(null);
            } else if (mc.gui.screen() == null || ImGuiManager.isDockedViewportActive()) {
                ImGuiManager.toggleShell();
            }
        }
        while (capture.consumeClick()) {
            if (Capture.aiming) {
                Capture.aiming = false;
                backFromAiming(Shell.Drawer.CAPTURE);
            } else if (mc.gui.screen() == null || ImGuiManager.isDockedViewportActive()) {
                if (!Shell.isOpen()) ImGuiManager.openShell(Shell.lastMode());
                Shell.drawer = Shell.Drawer.CAPTURE;
            }
        }
        Capture.tick(mc);
        ChestIndexView.tickHighlight();
        chest.tick();
        clear.tick();
        schem.tick();
        dev.midtown.api.EngineSelector.current().tick();
        if (PauseWatch.isPaused()) return;   // #pause: the script and project scheduler hold still
        try {
            runner.tick();
        } catch (Throwable t) {
            t.printStackTrace();
            Util.warn("tick-error", "The script runner hit an error (" + t + "). Details are in the game log.");
            runner.reset();
        }
        try {
            dev.manhattan.fleet.FleetClient.instance().tick(mc);
        } catch (Throwable t) {
            t.printStackTrace();
        }
        try {
            GameProjectRuntime.instance().tick(mc);
        } catch (Throwable t) {
            t.printStackTrace();
            Util.warn("project-tick-error", "The project scheduler hit an error (" + t + "). Details are in the game log.");
            GameProjectRuntime.instance().stopQueue("Tick error: " + t);
        }
    }

    // ------------------------------------------------------------------ /project command tree

    private static void registerProjectCommands(
            com.mojang.brigadier.CommandDispatcher<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommands.literal("project")
                .executes(ctx -> projectInfo())
                .then(ClientCommands.literal("info").executes(ctx -> projectInfo()))
                .then(ClientCommands.literal("list").executes(ctx -> {
                    GameProjectRuntime rt = GameProjectRuntime.instance();
                    List<String> ids = rt.projectIds();
                    Project active = rt.activeProject();
                    String activeId = active != null ? active.id : null;
                    if (ids.isEmpty()) {
                        Util.msg("No projects saved yet.");
                        return 1;
                    }
                    Util.msg("Projects (" + ids.size() + "):");
                    for (String id : ids) {
                        Util.msg((id.equals(activeId) ? "  * " : "    ") + id);
                    }
                    return 1;
                }))
                .then(ClientCommands.literal("select")
                        .then(ClientCommands.argument("id", StringArgumentType.word()).executes(ctx -> {
                            String id = StringArgumentType.getString(ctx, "id");
                            GameProjectRuntime rt = GameProjectRuntime.instance();
                            rt.selectProject(id);
                            Project p = rt.activeProject();
                            Util.msg("Active project: " + (p != null ? p.name + " (" + p.id + ")" : id));
                            return 1;
                        })))
                .then(ClientCommands.literal("create")
                        .then(ClientCommands.argument("id", StringArgumentType.word())
                                .executes(ctx -> {
                                    String id = StringArgumentType.getString(ctx, "id");
                                    Project p = GameProjectRuntime.instance().createProject(id, id);
                                    Util.msg("Created project '" + p.name + "' (" + p.id + ").");
                                    return 1;
                                })
                                .then(ClientCommands.argument("name", StringArgumentType.greedyString()).executes(ctx -> {
                                    String id = StringArgumentType.getString(ctx, "id");
                                    String name = StringArgumentType.getString(ctx, "name");
                                    Project p = GameProjectRuntime.instance().createProject(id, name);
                                    Util.msg("Created project '" + p.name + "' (" + p.id + ").");
                                    return 1;
                                }))))
                .then(ClientCommands.literal("delete")
                        .then(ClientCommands.argument("id", StringArgumentType.word()).executes(ctx -> {
                            String id = StringArgumentType.getString(ctx, "id");
                            boolean ok = GameProjectRuntime.instance().deleteProject(id);
                            Util.msg(ok ? "Deleted project '" + id + "'." : "Could not delete project '" + id + "'.");
                            return ok ? 1 : 0;
                        })))
                .then(ClientCommands.literal("set")
                        .then(projectSetCmd("bed"))
                        .then(projectSetCmd("tool"))
                        .then(projectSetCmd("return"))
                        .then(projectSetCmd("dump"))
                        .then(projectSetCmd("scaffold")))
                .then(ClientCommands.literal("queue")
                        .executes(ctx -> projectQueueList())
                        .then(ClientCommands.literal("list").executes(ctx -> projectQueueList()))
                        .then(ClientCommands.literal("add")
                                .then(ClientCommands.argument("kind", StringArgumentType.word())
                                        .executes(ctx -> projectQueueAdd(StringArgumentType.getString(ctx, "kind"), null))
                                        .then(ClientCommands.argument("target", StringArgumentType.greedyString())
                                                .executes(ctx -> projectQueueAdd(
                                                        StringArgumentType.getString(ctx, "kind"),
                                                        StringArgumentType.getString(ctx, "target"))))))
                        .then(ClientCommands.literal("move")
                                .then(ClientCommands.argument("from", IntegerArgumentType.integer(1))
                                        .then(ClientCommands.argument("to", IntegerArgumentType.integer(1)).executes(ctx -> {
                                            int from = IntegerArgumentType.getInteger(ctx, "from") - 1;
                                            int to = IntegerArgumentType.getInteger(ctx, "to") - 1;
                                            GameProjectRuntime.instance().moveTask(from, to);
                                            Util.msg("Moved task #" + (from + 1) + " to #" + (to + 1) + ".");
                                            return 1;
                                        }))))
                        .then(ClientCommands.literal("remove")
                                .then(ClientCommands.argument("taskId", StringArgumentType.word()).executes(ctx -> {
                                    String id = StringArgumentType.getString(ctx, "taskId");
                                    GameProjectRuntime.instance().removeTask(id);
                                    Util.msg("Removed task '" + id + "'.");
                                    return 1;
                                })))
                        .then(ClientCommands.literal("reset")
                                .executes(ctx -> {
                                    GameProjectRuntime.instance().resetQueue(null);
                                    Util.msg("Project queue ledger reset.");
                                    return 1;
                                })
                                .then(ClientCommands.argument("taskId", StringArgumentType.word()).executes(ctx -> {
                                    String id = StringArgumentType.getString(ctx, "taskId");
                                    GameProjectRuntime.instance().resetQueue(id);
                                    Util.msg("Reset task '" + id + "'.");
                                    return 1;
                                })))
                        .then(ClientCommands.literal("retry")
                                .executes(ctx -> {
                                    GameProjectRuntime.instance().retryTask(null);
                                    Util.msg("Retried all deferred/failed project tasks.");
                                    return 1;
                                })
                                .then(ClientCommands.argument("taskId", StringArgumentType.word()).executes(ctx -> {
                                    String id = StringArgumentType.getString(ctx, "taskId");
                                    GameProjectRuntime.instance().retryTask(id);
                                    Util.msg("Retried task '" + id + "'.");
                                    return 1;
                                })))
                        .then(ClientCommands.literal("skip")
                                .executes(ctx -> {
                                    GameProjectRuntime.instance().skipCurrent();
                                    return 1;
                                })
                                .then(ClientCommands.argument("taskId", StringArgumentType.word()).executes(ctx -> {
                                    String id = StringArgumentType.getString(ctx, "taskId");
                                    GameProjectRuntime.instance().skipTask(id);
                                    Util.msg("Skipped task '" + id + "'.");
                                    return 1;
                                })))
                        .then(ClientCommands.literal("run").executes(ctx -> {
                            String err = GameProjectRuntime.instance().startQueue();
                            if (err != null) {
                                Util.msg(err);
                                return 0;
                            }
                            return 1;
                        }))
                        .then(ClientCommands.literal("stop").executes(ctx -> {
                            GameProjectRuntime.instance().stopQueue("Stopped via /project queue stop.");
                            return 1;
                        })))
                .then(ClientCommands.literal("layers")
                        .executes(ctx -> projectLayersList())
                        .then(ClientCommands.literal("list").executes(ctx -> projectLayersList()))
                        .then(ClientCommands.literal("add")
                                .then(ClientCommands.argument("id", StringArgumentType.word())
                                        .executes(ctx -> {
                                            String id = StringArgumentType.getString(ctx, "id");
                                            boolean ok = GameProjectRuntime.instance().addProjectLayer(id);
                                            Util.msg(ok ? "Added project '" + id + "' to priority layers."
                                                    : "Could not add project '" + id + "' (missing or already queued).");
                                            return ok ? 1 : 0;
                                        })
                                        .then(ClientCommands.argument("position", IntegerArgumentType.integer(1)).executes(ctx -> {
                                            String id = StringArgumentType.getString(ctx, "id");
                                            int pos = IntegerArgumentType.getInteger(ctx, "position") - 1;
                                            boolean ok = GameProjectRuntime.instance().insertProjectLayer(pos, id);
                                            Util.msg(ok ? "Inserted project '" + id + "' at layer #" + (pos + 1) + "."
                                                    : "Could not insert project '" + id + "' (missing or already queued).");
                                            return ok ? 1 : 0;
                                        }))))
                        .then(ClientCommands.literal("remove")
                                .then(ClientCommands.argument("id", StringArgumentType.word()).executes(ctx -> {
                                    String id = StringArgumentType.getString(ctx, "id");
                                    boolean ok = GameProjectRuntime.instance().removeProjectLayer(id);
                                    Util.msg(ok ? "Removed project '" + id + "' from priority layers."
                                            : "Project '" + id + "' is not in priority layers.");
                                    return ok ? 1 : 0;
                                })))
                        .then(ClientCommands.literal("move")
                                .then(ClientCommands.argument("from", IntegerArgumentType.integer(1))
                                        .then(ClientCommands.argument("to", IntegerArgumentType.integer(1)).executes(ctx -> {
                                            int from = IntegerArgumentType.getInteger(ctx, "from") - 1;
                                            int to = IntegerArgumentType.getInteger(ctx, "to") - 1;
                                            boolean ok = GameProjectRuntime.instance().moveProjectLayer(from, to);
                                            Util.msg(ok ? "Moved layer L" + (from + 1) + " to L" + (to + 1) + "."
                                                    : "Invalid layer positions.");
                                            return ok ? 1 : 0;
                                        }))))
                        .then(ClientCommands.literal("top")
                                .then(ClientCommands.argument("id", StringArgumentType.word()).executes(ctx -> {
                                    String id = StringArgumentType.getString(ctx, "id");
                                    boolean ok = GameProjectRuntime.instance().moveProjectLayerToTop(id);
                                    Util.msg(ok ? "Promoted project '" + id + "' to Layer 1."
                                            : "Project '" + id + "' is not in priority layers.");
                                    return ok ? 1 : 0;
                                })))
                        .then(ClientCommands.literal("enable")
                                .then(ClientCommands.argument("id", StringArgumentType.word()).executes(ctx -> {
                                    String id = StringArgumentType.getString(ctx, "id");
                                    boolean ok = GameProjectRuntime.instance().setProjectLayerEnabled(id, true);
                                    Util.msg(ok ? "Enabled layer '" + id + "'." : "Project '" + id + "' is not in priority layers.");
                                    return ok ? 1 : 0;
                                })))
                        .then(ClientCommands.literal("disable")
                                .then(ClientCommands.argument("id", StringArgumentType.word()).executes(ctx -> {
                                    String id = StringArgumentType.getString(ctx, "id");
                                    boolean ok = GameProjectRuntime.instance().setProjectLayerEnabled(id, false);
                                    Util.msg(ok ? "Disabled layer '" + id + "'." : "Project '" + id + "' is not in priority layers.");
                                    return ok ? 1 : 0;
                                })))
                        .then(ClientCommands.literal("run").executes(ctx -> {
                            String err = GameProjectRuntime.instance().startLayerQueue();
                            if (err != null) {
                                Util.msg(err);
                                return 0;
                            }
                            return 1;
                        }))
                        .then(ClientCommands.literal("stop").executes(ctx -> {
                            GameProjectRuntime.instance().stopQueue("Stopped via /project layers stop.");
                            return 1;
                        }))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> projectSetCmd(String place) {
        return ClientCommands.literal(place)
                .executes(ctx -> {
                    Places.Target t = Places.target();
                    if (t == null) {
                        Util.msg("Look at a block first, or pass coordinates: /project set " + place + " <x> <y> <z>.");
                        return 0;
                    }
                    return setProjectPlace(place, new int[]{t.pos().x(), t.pos().y(), t.pos().z()});
                })
                .then(ClientCommands.argument("x", IntegerArgumentType.integer())
                        .then(ClientCommands.argument("y", IntegerArgumentType.integer())
                                .then(ClientCommands.argument("z", IntegerArgumentType.integer()).executes(ctx ->
                                        setProjectPlace(place, new int[]{
                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                IntegerArgumentType.getInteger(ctx, "y"),
                                                IntegerArgumentType.getInteger(ctx, "z")})))));
    }

    private static int setProjectPlace(String place, int[] coords) {
        GameProjectRuntime rt = GameProjectRuntime.instance();
        Project p = rt.activeProject();
        if (p == null) {
            Util.msg("No active project.");
            return 0;
        }
        p.ensureInitialized();
        switch (place) {
            case "bed" -> p.places.bed = coords;
            case "tool" -> p.places.toolChest = coords;
            case "return" -> p.places.returnChest = coords;
            case "dump" -> p.places.dumpChest = coords;
            case "scaffold" -> p.places.scaffoldChest = coords;
            default -> {
                Util.msg("Unknown place '" + place + "'.");
                return 0;
            }
        }
        rt.saveActive();
        Util.msg("Project '" + p.id + "' " + place + " set to " + coords[0] + " " + coords[1] + " " + coords[2] + ".");
        return 1;
    }

    private static int projectInfo() {
        GameProjectRuntime rt = GameProjectRuntime.instance();
        Project p = rt.activeProject();
        if (p == null) {
            Util.msg("No active project.");
            return 1;
        }
        PriorityScheduler.Telemetry tel = rt.telemetry();
        Util.msg("Project: " + p.name + " (" + p.id + ") | " + (tel.running() ? "RUNNING [" + tel.lane() + "] " + tel.detail() : "IDLE"));
        Util.msg("  bed=" + fmtPos(p.places.bed)
                + ", tool=" + fmtPos(p.places.toolChest)
                + ", return=" + fmtPos(p.places.returnChest)
                + ", dump=" + fmtPos(p.places.dumpChest)
                + ", scaffold=" + fmtPos(p.places.scaffoldChest)
                + ", materials=" + p.places.materialChests.size());
        Util.msg("  tasks: " + p.tasks.size() + " (" + tel.doneTasks() + "/" + tel.totalTasks() + " done)");
        return 1;
    }

    private static String fmtPos(int[] p) {
        return p == null || p.length < 3 ? "unset" : p[0] + " " + p[1] + " " + p[2];
    }

    private static int projectQueueList() {
        GameProjectRuntime rt = GameProjectRuntime.instance();
        Project p = rt.activeProject();
        if (p == null || p.tasks.isEmpty()) {
            Util.msg("Project queue is empty.");
            return 1;
        }
        Util.msg("Project '" + p.id + "' queue (" + p.tasks.size() + " tasks):");
        for (int i = 0; i < p.tasks.size(); i++) {
            ProjectTask t = p.tasks.get(i);
            TaskQueue.TaskLedger.State st = rt.taskState(t.id);
            Util.msg("  " + (i + 1) + ". [" + st + "] " + t.id + " " + t.displayLabel()
                    + (t.enabled ? "" : " (disabled)"));
        }
        return 1;
    }

    private static int projectLayersList() {
        GameProjectRuntime rt = GameProjectRuntime.instance();
        var pq = rt.projectQueue();
        var layers = pq != null ? pq.snapshot() : List.<dev.manhattan.project.ProjectQueue.Layer>of();
        if (layers.isEmpty()) {
            Util.msg("Project layers queue is empty. Use /project layers add <id>.");
            return 1;
        }
        Util.msg("Project priority layers (" + layers.size() + ", top gets bot capacity first):");
        for (int i = 0; i < layers.size(); i++) {
            var layer = layers.get(i);
            var st = rt.layerStatus(layer);
            int[] prog = rt.projectProgress(layer.projectId);
            Util.msg("  L" + (i + 1) + ". [" + st + "] " + layer.projectId + " (" + prog[0] + "/" + prog[1] + " done)"
                    + (layer.enabled ? "" : " (disabled)"));
        }
        return 1;
    }

    private static int projectQueueAdd(String rawKind, String target) {
        ProjectTask.Kind kind;
        try {
            kind = ProjectTask.Kind.valueOf(rawKind.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            Util.msg("Unknown task kind '" + rawKind + "'. Expected: fill_water, clear_area, clear_trees, schem_build, flood, drain, mine, dump, restock, script.");
            return 0;
        }
        ProjectTask task = new ProjectTask(null, null, kind);
        if (target != null && !target.isBlank()) {
            String t = target.trim();
            switch (kind) {
                case SCHEM_BUILD -> task.placement = t;
                case SCRIPT -> task.scriptPath = t;
                case MINE -> {
                    for (String part : t.split("\\s+")) {
                        if (!part.isBlank()) task.mineBlocks.add(part);
                    }
                }
                default -> task.areaName = t;
            }
        }
        GameProjectRuntime.instance().enqueueTask(task);
        Util.msg("Enqueued " + task.id + ": " + task.displayLabel());
        return 1;
    }

    // ------------------------------------------------------------------ package-private bridge for GameProjectRuntime

    public static String projectWorld() {
        try {
            return Capture.world();
        } catch (Throwable ignored) {
            return "default";
        }
    }

    public static Path projectConfigDir() {
        try {
            return FabricLoader.getInstance().getConfigDir();
        } catch (Throwable ignored) {
            return Path.of("config");
        }
    }

    public static Path projectGameDir() {
        try {
            return FabricLoader.getInstance().getGameDir();
        } catch (Throwable ignored) {
            return Path.of(".");
        }
    }

    public static String projectCaptureText() {
        try {
            Path f = Capture.file();
            if (Files.isRegularFile(f)) {
                return Files.readString(f, StandardCharsets.UTF_8);
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    public static void projectLog(String message) {
        try {
            Util.msg(message);
        } catch (Throwable ignored) {
        }
    }

    public static Object projectGetOption(String key) {
        try {
            return Modules.get(key, 0);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void projectSetOption(String key, Object value) {
        try {
            Modules.set(key, value, 0);
        } catch (Throwable ignored) {
        }
    }

    public static void projectSetPosOption(String key, int[] coords) {
        try {
            Object val = (coords == null || coords.length < 3) ? null : new Values.Pos(coords[0], coords[1], coords[2]);
            Modules.set(key, val, 0);
        } catch (Throwable ignored) {
        }
    }

    public static Map<Integer, String> projectSnapshotHotbarPins() {
        return new java.util.LinkedHashMap<>(HotbarTools.pinned);
    }

    public static void projectRestoreHotbarPins(Map<Integer, String> pins) {
        HotbarTools.pinned.clear();
        if (pins != null) HotbarTools.pinned.putAll(pins);
    }

    public static void projectPinHotbar(int zeroBasedSlot, String item) {
        if (zeroBasedSlot >= 0 && zeroBasedSlot < 9 && item != null && !item.isBlank()) {
            HotbarTools.pinned.put(zeroBasedSlot, item.toLowerCase(Locale.ROOT));
        }
    }

    public static boolean projectRunnerRunning() {
        return runner.running();
    }

    public static String projectRunnerStart(
            List<String> lines,
            String scriptName,
            Map<String, Object> params,
            Map<String, Object> places) {
        return runner.startWithParams(lines, scriptName, params, places);
    }

    public static String projectRunnerLastFailure() {
        return runner.lastFailure();
    }

    public static void projectRunnerSuspend() {
        runner.suspendExternal();
    }

    public static void projectRunnerResume() {
        runner.resumeExternal();
    }

    public static void projectRunnerCancel() {
        runner.reset();
    }

    public static List<String> projectReadScript(String scriptPath) {
        if (scriptPath == null || scriptPath.isBlank()) return null;
        Path p = Path.of(scriptPath.trim());
        List<Path> candidates = new ArrayList<>();
        if (p.isAbsolute()) {
            candidates.add(p);
        } else {
            Path configDir = projectConfigDir();
            candidates.add(configDir.resolve("manhattan-scripts").resolve(scriptPath));
            candidates.add(configDir.resolve("manhattan-scripts").resolve(scriptPath + ".txt"));
            candidates.add(Path.of("scripts").resolve(scriptPath));
            candidates.add(Path.of("scripts").resolve(scriptPath + ".txt"));
            try {
                Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
                candidates.add(gameDir.resolve("scripts").resolve(scriptPath));
                candidates.add(gameDir.resolve("scripts").resolve(scriptPath + ".txt"));
            } catch (Throwable ignored) {
            }
            candidates.add(p);
        }
        for (Path cand : candidates) {
            try {
                if (Files.isRegularFile(cand)) {
                    return Files.readAllLines(cand, StandardCharsets.UTF_8);
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** Opens and activates the project's persistent Ledger under config/manhattan-jobs/<world>_project_<projectId>.json. */
    public static TaskQueue.TaskLedger projectOpenLedger(String world, String projectId) {
        String safeWorld = world == null || world.isBlank() ? "default" : world.replaceAll("[^A-Za-z0-9._-]+", "_");
        String safeId = projectId == null || projectId.isBlank() ? "default" : projectId.replaceAll("[^A-Za-z0-9_.-]+", "_");
        Path file = Ledger.fileFor(projectConfigDir(), safeWorld, "project_" + safeId);
        Ledger backing = Ledger.load(file);
        Jobs.useLedger(backing);
        return new ProjectLedgerAdapter(backing);
    }

    private static final class ProjectLedgerAdapter implements TaskQueue.TaskLedger {
        private final Ledger backing;

        ProjectLedgerAdapter(Ledger backing) {
            this.backing = backing;
        }

        private static State toTaskState(Ledger.State s) {
            if (s == null) return State.PENDING;
            return switch (s) {
                case PENDING -> State.PENDING;
                case RUNNING -> State.RUNNING;
                case DONE -> State.DONE;
                case DEFERRED -> State.DEFERRED;
                case FAILED -> State.FAILED;
            };
        }

        private static Ledger.State toLedgerState(State s) {
            if (s == null) return Ledger.State.PENDING;
            return switch (s) {
                case PENDING -> Ledger.State.PENDING;
                case RUNNING -> Ledger.State.RUNNING;
                case DONE -> Ledger.State.DONE;
                case DEFERRED -> Ledger.State.DEFERRED;
                case FAILED -> Ledger.State.FAILED;
            };
        }

        @Override
        public EntryView get(String id) {
            if (id == null) return null;
            Ledger.Entry e = backing.get(id);
            if (e == null) return null;
            return new EntryView(id, toTaskState(e.state), e.tries, e.left, e.reason, e.time);
        }

        @Override
        public EntryView set(String id, State state, int tries, int left, String reason) {
            Ledger.Entry e = backing.set(id, toLedgerState(state), tries, left, reason, null);
            return new EntryView(id, toTaskState(e.state), e.tries, e.left, e.reason, e.time);
        }

        @Override
        public int reset(String idOrNull) {
            return backing.reset(idOrNull);
        }

        @Override
        public int retry(String idOrNull) {
            if (idOrNull == null) {
                int total = 0;
                for (String id : backing.ids(null, null)) {
                    Ledger.Entry e = backing.get(id);
                    if (e != null && (e.state == Ledger.State.DEFERRED || e.state == Ledger.State.FAILED || e.state == Ledger.State.RUNNING)) {
                        backing.set(id, Ledger.State.PENDING, 0, e.left, null, null);
                        total++;
                    }
                }
                return total;
            }
            return backing.retry(idOrNull);
        }

        @Override
        public List<String> ids(State stateOrNull) {
            return backing.ids(null, stateOrNull == null ? null : toLedgerState(stateOrNull));
        }
    }

    /** Wraps a dev.manhattan.Actions.Action as a PriorityScheduler.ActionHandle. */
    private static PriorityScheduler.ActionHandle wrapAction(
            Actions.Action action,
            IntSupplier remainingSupplier,
            Supplier<Map<String, Integer>> deficitSupplier) {
        if (action == null) return null;
        return new PriorityScheduler.ActionHandle() {
            private boolean started;

            private void ensureStarted() {
                if (!started) {
                    started = true;
                    if (!action.done) {
                        action.start();
                    }
                }
            }

            @Override
            public boolean isDone() {
                ensureStarted();
                return action.done;
            }

            @Override
            public String error() {
                return action.error;
            }

            @Override
            public int remainingWork() {
                if (remainingSupplier != null) {
                    return Math.max(0, remainingSupplier.getAsInt());
                }
                if (action instanceof ChunkRunner cr) {
                    return cr.remainingWork();
                }
                if (action.result instanceof Double d) {
                    return Math.max(0, (int) Math.round(d));
                }
                return 0;
            }

            @Override
            public void cancel() {
                action.cancel();
                action.done = true;
            }

            @Override
            public void suspend() {
                action.suspend();
            }

            @Override
            public void resume() {
                action.resume();
            }

            @Override
            public void tick() {
                ensureStarted();
                if (!action.done) {
                    action.ticks++;
                    action.tick();
                }
            }

            @Override
            public Map<String, Integer> materialDeficit() {
                if (deficitSupplier != null) {
                    Map<String, Integer> d = deficitSupplier.get();
                    return d != null ? d : Map.of();
                }
                if (action instanceof ChunkRunner cr) {
                    return cr.latestDeficit();
                }
                return Map.of();
            }
        };
    }

    private static boolean hasActiveFleetLease() {
        dev.manhattan.fleet.FleetClient fc = dev.manhattan.fleet.FleetClient.instance();
        if (!fc.isWorkerActive()) return false;
        dev.manhattan.fleet.FleetProtocol.WorkGrant grant = fc.activeGrant();
        return grant != null && !grant.idle && grant.jobId != null;
    }

    private static void attachFleetLease(ChunkRunner runner) {
        dev.manhattan.fleet.FleetClient fc = dev.manhattan.fleet.FleetClient.instance();
        if (fc.isWorkerActive()) {
            dev.manhattan.fleet.FleetProtocol.WorkGrant grant = fc.activeGrant();
            if (grant != null && !grant.idle && grant.jobId != null) {
                runner.withLeaseProvider(fc.createLeaseProvider(grant.jobId));
            }
        }
    }

    private static int effectiveChunkSize() {
        Project p = GameProjectRuntime.instance().runningProject();
        if (p != null && p.chunkSize > 0) return Math.max(8, Math.min(128, p.chunkSize));
        return cfg.chunkSize;
    }

    private static int effectiveChunkSizeY() {
        Project p = GameProjectRuntime.instance().runningProject();
        if (p != null && p.chunkSizeY > 0) return Math.max(4, Math.min(320, p.chunkSizeY));
        return cfg.chunkSizeY;
    }

    /** Computes canonical Fleet Server chunk specs for all enabled spatial tasks in a Project. */
    public static List<dev.manhattan.fleet.FleetProtocol.ChunkSpec> collectProjectChunkSpecs(Project project) {
        return collectProjectChunkSpecs(project, null);
    }

    /** Computes canonical Fleet Server chunk specs and optionally records diagnostic warnings for any 0-chunk tasks. */
    public static List<dev.manhattan.fleet.FleetProtocol.ChunkSpec> collectProjectChunkSpecs(
            Project project,
            List<String> diagnostics
    ) {
        List<dev.manhattan.fleet.FleetProtocol.ChunkSpec> out = new ArrayList<>();
        if (project == null || project.tasks == null) return out;
        int effectiveSize = project.chunkSize > 0 ? Math.max(8, Math.min(128, project.chunkSize)) : cfg.chunkSize;
        int effectiveSizeY = project.chunkSizeY > 0 ? Math.max(4, Math.min(320, project.chunkSizeY)) : cfg.chunkSizeY;
        project.chunkSize = effectiveSize;
        project.chunkSizeY = effectiveSizeY;
        for (ProjectTask task : project.tasks) {
            if (task == null || !task.enabled || task.kind == null) continue;
            List<int[]> boxes = null;
            int chunkSizeY = 0;
            int gap = -1;
            if (task.kind == ProjectTask.Kind.CLEAR_AREA) {
                boxes = resolveProjectBoxes(task);
                chunkSizeY = effectiveSizeY;
                gap = cfg.chunkGap;
            } else if (task.kind == ProjectTask.Kind.FILL_WATER
                    || task.kind == ProjectTask.Kind.DRAIN
                    || task.kind == ProjectTask.Kind.FLOOD
                    || task.kind == ProjectTask.Kind.CLEAR_TREES
                    || task.kind == ProjectTask.Kind.MINE) {
                boxes = resolveProjectBoxes(task);
            } else if (task.kind == ProjectTask.Kind.SCHEM_BUILD) {
                String[] err = new String[1];
                List<SchemSource.Placement> pls = SchemCmd.select(
                        task.placement == null || task.placement.isBlank() ? null : task.placement, err);
                if (pls != null && !pls.isEmpty()) {
                    boxes = SchemProcess.placementBounds(pls, err);
                    if ((boxes == null || boxes.isEmpty()) && diagnostics != null) {
                        diagnostics.add(task.displayLabel() + ": " + (err[0] != null ? err[0] : "schematic has 0 buildable bounds"));
                    }
                } else if (diagnostics != null) {
                    diagnostics.add(task.displayLabel() + ": " + (err[0] != null ? err[0] : "placement not found"));
                }
            }
            if (boxes == null || boxes.isEmpty()) {
                if (diagnostics != null && task.kind != ProjectTask.Kind.SCHEM_BUILD
                        && task.kind != ProjectTask.Kind.DUMP && task.kind != ProjectTask.Kind.RESTOCK && task.kind != ProjectTask.Kind.SCRIPT) {
                    diagnostics.add(task.displayLabel() + ": area '" + (task.areaName == null ? "" : task.areaName) + "' has no boxes");
                }
                continue;
            }
            if (task.areaName != null && !task.areaName.isBlank() && project.places != null) {
                project.places.ensureInitialized();
                project.places.areas.put(task.areaName, boxes);
            }
            List<int[]> effectiveBoxes = task.expand == 0 ? boxes : expandBoxesHorizontal(boxes, task.expand);
            List<ChunkGrid.ColumnBox> cols = ChunkGrid.split(effectiveBoxes, effectiveSize, chunkSizeY, gap);
            for (ChunkGrid.ColumnBox c : cols) {
                String key = ChunkRunner.chunkKey(task.id, c);
                BlockPos center = c.centerXZ(c.minY());
                out.add(new dev.manhattan.fleet.FleetProtocol.ChunkSpec(
                        key, c.gridX(), c.gridZ(), center.getX(), center.getZ()));
            }
        }
        return out;
    }

    private static List<int[]> expandBoxesHorizontal(List<int[]> boxes, int delta) {
        List<int[]> out = new ArrayList<>(boxes.size());
        for (int[] b : boxes) {
            if (b == null || b.length < 6) continue;
            int minX = Math.min(b[0], b[3]) - delta;
            int minY = Math.min(b[1], b[4]);
            int minZ = Math.min(b[2], b[5]) - delta;
            int maxX = Math.max(b[0], b[3]) + delta;
            int maxY = Math.max(b[1], b[4]);
            int maxZ = Math.max(b[2], b[5]) + delta;
            if (minX <= maxX && minZ <= maxZ) {
                out.add(new int[]{minX, minY, minZ, maxX, maxY, maxZ});
            }
        }
        return out;
    }

    private static List<int[]> resolveProjectBoxes(ProjectTask task) {
        if (task.areaName != null && !task.areaName.isBlank()) {
            List<int[]> saved = cfg.areas.get(task.areaName);
            if (saved != null && !saved.isEmpty()) return saved;
        }
        return List.of();
    }

    /** Imports embedded areas from a Fleet Server Project into local memory so workers resolve task areas automatically. */
    public static void importFleetAreas(Map<String, List<int[]>> areas) {
        if (areas == null || areas.isEmpty()) return;
        for (Map.Entry<String, List<int[]>> e : areas.entrySet()) {
            if (e.getKey() != null && !e.getKey().isBlank() && e.getValue() != null && !e.getValue().isEmpty()) {
                cfg.areas.put(e.getKey(), new ArrayList<>(e.getValue()));
            }
        }
    }

    /** Imports embedded Litematica schematics from a Fleet Server Project so workers need no local Litematica files. */
    public static void importFleetSchematics(
            List<Project.EmbeddedSchematic> embedded,
            Map<String, String> sharedFiles
    ) {
        SchemSource.setFleetEmbedded(embedded, sharedFiles);
    }

    /**
     * Clones a Project for Fleet submission and auto-embeds any Litematica schematics, arrays, and assemblies
     * referenced by enabled {@link ProjectTask.Kind#SCHEM_BUILD} tasks so workers need no local Litematica files.
     * Shared {@code .litematic} files (e.g. repeated in Vitruvius arrays) are deduplicated by content hash.
     */
    public static Project prepareProjectForFleetSubmit(Project source) {
        if (source == null) return null;
        String json = dev.manhattan.fleet.FleetProtocol.GSON.toJson(source);
        Project copy = dev.manhattan.fleet.FleetProtocol.GSON.fromJson(json, Project.class);
        if (copy == null) return source;
        copy.ensureInitialized();
        copy.embeddedSchematics.clear();
        copy.embeddedSchematicFiles.clear();

        java.util.Set<String> embeddedFingerprints = new java.util.HashSet<>();
        for (ProjectTask task : copy.tasks) {
            if (task == null || !task.enabled || task.kind != ProjectTask.Kind.SCHEM_BUILD) continue;
            String[] err = new String[1];
            List<SchemSource.Placement> pls = SchemCmd.select(
                    task.placement == null || task.placement.isBlank() ? null : task.placement, err);
            if (pls == null || pls.isEmpty()) continue;
            for (SchemSource.Placement pl : pls) {
                String fp = SchemSource.placementFingerprint(pl);
                if (!embeddedFingerprints.add(fp)) continue;
                Project.EmbeddedSchematic es = SchemSource.exportEmbedded(pl, copy.embeddedSchematicFiles);
                if (es != null) {
                    copy.embeddedSchematics.add(es);
                }
            }
        }
        return copy;
    }

    public static PriorityScheduler.ActionHandle projectCreateClearAreaHandle(
            int layers, List<int[]> boxes, String taskId) {
        Shaft.current = Shaft.find(boxes);
        if (Shaft.current != null) {
            Util.msg("Stair shaft '" + Shaft.current.name() + "' stays standing; its steps are carved as the hole gets deeper.");
        }
        int cs = effectiveChunkSize();
        int csy = effectiveChunkSizeY();
        Actions.Action act;
        if (hasActiveFleetLease() || ChunkGrid.shouldChunk(boxes, cs, csy, cfg.chunkGap, cfg.chunkEnabled)) {
            act = new ChunkRunner(new ChunkRunner.ChunkWorkload() {
                @Override public String actionKind() { return "clear_area"; }
                @Override public boolean usesSelectionNarrowing() { return false; }
                @Override public boolean skipWhenEmpty() { return true; }
                @Override public List<int[]> fullBounds() { return boxes; }
                @Override public Actions.Action createChunkAction(ChunkGrid.ColumnBox chunk, List<int[]> clippedBoxes) {
                    return GameBuiltins.clearFeature(layers, clippedBoxes);
                }
                @Override public int countRemaining(ChunkGrid.ColumnBox chunk, List<int[]> clippedBoxes) {
                    Minecraft mc = Minecraft.getInstance();
                    return mc == null || mc.level == null ? 0 : ClearProcess.countSolid(mc.level, clippedBoxes);
                }
            }, cs, ChunkGrid.OrderStrategy.parse(cfg.chunkOrder)).withGeometry(csy, cfg.chunkGap).withTaskPrefix(taskId);
            attachFleetLease((ChunkRunner) act);
        } else {
            act = GameBuiltins.clearFeature(layers, boxes);
        }
        return wrapAction(act, () -> {
            Minecraft mc = Minecraft.getInstance();
            return mc == null || mc.level == null ? 0 : ClearProcess.countSolid(mc.level, boxes);
        }, null);
    }

    public static PriorityScheduler.ActionHandle projectCreateFillWaterHandle(
            String blockName, List<int[]> boxes, BlockPos materialChestPos, String taskId) {
        Item it = (blockName == null || blockName.isBlank()) ? null : ChestOp.itemOf(blockName);
        if (blockName != null && !blockName.isBlank() && it == null) {
            Actions.Action failAct = new Actions.Wait(0.05);
            failAct.fail("Unknown block item '" + blockName + "' for fill_water task.");
            return wrapAction(failAct, () -> 0, null);
        }
        String resolvedName = it == null ? null : ChestOp.name(it);
        String chestLabel = materialChestPos == null ? null : "at " + materialChestPos.toShortString();
        int cs = effectiveChunkSize();
        Actions.Action act;
        if (hasActiveFleetLease() || ChunkGrid.shouldChunk(boxes, cs, cfg.chunkEnabled)) {
            act = new ChunkRunner(new ChunkRunner.ChunkWorkload() {
                @Override public String actionKind() { return "fill_water"; }
                @Override public boolean usesSelectionNarrowing() { return false; }
                @Override public List<int[]> fullBounds() { return boxes; }
                @Override public Actions.Action createChunkAction(ChunkGrid.ColumnBox chunk, List<int[]> clippedBoxes) {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc != null && mc.level != null && dev.buildtools.TerminatorProcess.countWater(mc.level, clippedBoxes) == 0) {
                        return new Actions.Now(() -> 0.0);
                    }
                    return GameBuiltins.fillWaterChunkAction(it, resolvedName, materialChestPos, chestLabel, 512, clippedBoxes);
                }
                @Override public int countRemaining(ChunkGrid.ColumnBox chunk, List<int[]> clippedBoxes) {
                    Minecraft mc = Minecraft.getInstance();
                    return mc == null || mc.level == null ? 0 : dev.buildtools.TerminatorProcess.countWater(mc.level, clippedBoxes);
                }
                @Override public Map<String, Integer> requiredItems(ChunkGrid.ColumnBox chunk, List<int[]> clippedBoxes) {
                    if (resolvedName == null) return Map.of();
                    Minecraft mc = Minecraft.getInstance();
                    int waterCount = mc == null || mc.level == null ? 0 : dev.buildtools.TerminatorProcess.countWater(mc.level, clippedBoxes);
                    return waterCount > 0 ? Map.of(resolvedName, waterCount) : Map.of();
                }
            }, cs, ChunkGrid.OrderStrategy.parse(cfg.chunkOrder)).withTaskPrefix(taskId);
            attachFleetLease((ChunkRunner) act);
        } else {
            act = GameBuiltins.fillWaterChunkAction(it, resolvedName, materialChestPos, chestLabel, 512, boxes);
        }
        return wrapAction(act, () -> {
            Minecraft mc = Minecraft.getInstance();
            return mc == null || mc.level == null ? 0 : dev.buildtools.TerminatorProcess.countWater(mc.level, boxes);
        }, () -> {
            if (act instanceof ChunkRunner cr) return cr.latestDeficit();
            if (resolvedName != null && dev.buildtools.Api.fillRanOut()) {
                Minecraft mc = Minecraft.getInstance();
                int left = mc == null || mc.level == null ? 0 : dev.buildtools.TerminatorProcess.countWater(mc.level, boxes);
                if (left > 0) return Map.of(resolvedName, Math.min(left, 512));
            }
            return Map.of();
        });
    }

    public static PriorityScheduler.ActionHandle projectCreateDrainHandle(List<int[]> boxes, String taskId) {
        int cs = effectiveChunkSize();
        Actions.Action act;
        if (hasActiveFleetLease() || ChunkGrid.shouldChunk(boxes, cs, cfg.chunkEnabled)) {
            act = new ChunkRunner(new ChunkRunner.ChunkWorkload() {
                @Override public String actionKind() { return "drain"; }
                @Override public boolean usesSelectionNarrowing() { return false; }
                @Override public List<int[]> fullBounds() { return boxes; }
                @Override public Actions.Action createChunkAction(ChunkGrid.ColumnBox chunk, List<int[]> clippedBoxes) {
                    return GameBuiltins.drainFeature(clippedBoxes);
                }
                @Override public int countRemaining(ChunkGrid.ColumnBox chunk, List<int[]> clippedBoxes) {
                    Minecraft mc = Minecraft.getInstance();
                    return mc == null || mc.level == null ? 0 : dev.buildtools.DrainProcess.countFluid(mc.level, clippedBoxes);
                }
            }, cs, ChunkGrid.OrderStrategy.parse(cfg.chunkOrder)).withTaskPrefix(taskId);
            attachFleetLease((ChunkRunner) act);
        } else {
            act = GameBuiltins.drainFeature(boxes);
        }
        return wrapAction(act, () -> {
            Minecraft mc = Minecraft.getInstance();
            return mc == null || mc.level == null ? 0 : dev.buildtools.DrainProcess.countFluid(mc.level, boxes);
        }, null);
    }

    public static PriorityScheduler.ActionHandle projectCreateFloodHandle(List<int[]> boxes) {
        return wrapAction(GameBuiltins.floodFeature(boxes), () -> 0, null);
    }

    public static PriorityScheduler.ActionHandle projectCreateSchemBuildHandle(
            String placement, String modeRaw, String missingRaw, String taskId) {
        Schem.Mode mode = Schem.Mode.of(modeRaw);
        if (mode == null) mode = Schem.Mode.ADD;
        schem.skipMissing(!"strict".equalsIgnoreCase(missingRaw));
        String[] err = new String[1];
        List<SchemSource.Placement> pls = SchemCmd.select(placement == null || placement.isBlank() ? null : placement, err);
        if (pls == null || pls.isEmpty()) {
            Actions.Action failAct = new Actions.Wait(0.05);
            failAct.fail(err[0] != null ? err[0] : "No Litematica placement found for '" + placement + "'.");
            return wrapAction(failAct, () -> 0, null);
        }
        Schem.Mode finalMode = mode;
        SchemProcess.prepare(pls, finalMode);
        List<int[]> bounds = SchemProcess.placementBounds(pls);
        Actions.Action act;
        if (hasActiveFleetLease() || ChunkGrid.shouldChunk(bounds, cfg.chunkSize, cfg.chunkEnabled)) {
            act = new ChunkRunner(new ChunkRunner.ChunkWorkload() {
                @Override public String actionKind() { return "schem_build"; }
                @Override public boolean usesSelectionNarrowing() { return false; }
                @Override public List<int[]> fullBounds() { return bounds; }
                @Override public void onChunkStart(ChunkGrid.ColumnBox chunk, List<int[]> clippedBoxes) {
                    schem.setChunkBounds(chunk);
                }
                @Override public void onChunkEnd(ChunkGrid.ColumnBox chunk) {
                    schem.setChunkBounds(null);
                }
                @Override public Actions.Action createChunkAction(ChunkGrid.ColumnBox chunk, List<int[]> clippedBoxes) {
                    return GameBuiltins.schemFeature(pls, finalMode, chunk);
                }
                @Override public int countRemaining(ChunkGrid.ColumnBox chunk, List<int[]> clippedBoxes) {
                    Minecraft mc = Minecraft.getInstance();
                    return mc == null || mc.level == null ? 0 : SchemProcess.countRemainingIn(mc.level, chunk);
                }
                @Override public Map<String, Integer> requiredItems(ChunkGrid.ColumnBox chunk, List<int[]> clippedBoxes) {
                    Minecraft mc = Minecraft.getInstance();
                    return mc == null || mc.level == null ? Map.of() : SchemProcess.requiredItemsIn(mc.level, chunk);
                }
            }).withTaskPrefix(taskId);
            attachFleetLease((ChunkRunner) act);
        } else {
            schem.setChunkBounds(null);
            act = GameBuiltins.schemFeature(pls, finalMode, null);
        }
        final long[] lastDeficitMs = {0L};
        final java.util.concurrent.atomic.AtomicReference<Map<String, Integer>> cachedDeficit =
                new java.util.concurrent.atomic.AtomicReference<>(Map.of());
        return wrapAction(act, () -> {
            if (act instanceof ChunkRunner cr) return cr.remainingWork();
            return (int) Math.round(Math.max(0.0, schem.result()));
        }, () -> {
            long now = System.currentTimeMillis();
            if (now - lastDeficitMs[0] < 1000L) return cachedDeficit.get();
            lastDeficitMs[0] = now;
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.level == null || mc.player == null) {
                cachedDeficit.set(Map.of());
                return Map.of();
            }
            ChunkGrid.ColumnBox box = (act instanceof ChunkRunner cr) ? cr.currentChunk() : null;
            Map<String, Integer> required = SchemProcess.requiredItemsIn(mc.level, box);
            if (required.isEmpty()) {
                cachedDeficit.set(Map.of());
                return Map.of();
            }
            int freeSlots = 0;
            var inv = mc.player.getInventory();
            for (int i = 0; i < 36; i++) {
                if (inv.getItem(i).isEmpty()) freeSlots++;
            }
            int budgetSlots = Math.max(1, freeSlots - 1);
            Map<String, Integer> deficit = new java.util.LinkedHashMap<>();
            for (var e : required.entrySet()) {
                if (budgetSlots <= 0) break;
                String itemName = e.getKey();
                int need = e.getValue() == null ? 0 : e.getValue();
                if (itemName == null || itemName.isBlank() || need <= 0) continue;
                Item item = ChestOp.itemOf(itemName);
                if (item == null) continue;
                int have = 0;
                for (int i = 0; i < 36; i++) {
                    var st = inv.getItem(i);
                    if (!st.isEmpty() && st.is(item)) have += st.getCount();
                }
                if (have > 0) continue; // still have stock on hand; place it before making a base trip
                int maxStack = Math.max(1, item.getDefaultMaxStackSize());
                int maxForItem = Math.min(need, Math.min(maxStack * 4, maxStack * budgetSlots));
                if (maxForItem > 0) {
                    deficit.put(itemName, maxForItem);
                    int slotsUsed = (maxForItem + maxStack - 1) / maxStack;
                    budgetSlots -= slotsUsed;
                }
            }
            cachedDeficit.set(deficit);
            return deficit;
        });
    }

    public static PriorityScheduler.ActionHandle projectCreateMineHandle(List<String> mineBlocks, List<int[]> boxes) {
        if (mineBlocks == null || mineBlocks.isEmpty()) {
            Actions.Action failAct = new Actions.Wait(0.05);
            failAct.fail("MINE task requires at least one block in mineBlocks.");
            return wrapAction(failAct, () -> 0, null);
        }
        BlockFilter filter = BlockFilter.ofNames(mineBlocks.toArray(new String[0]));
        Area area = null;
        if (boxes != null && !boxes.isEmpty()) {
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (int[] b : boxes) {
                minX = Math.min(minX, Math.min(b[0], b[3]));
                minY = Math.min(minY, Math.min(b[1], b[4]));
                minZ = Math.min(minZ, Math.min(b[2], b[5]));
                maxX = Math.max(maxX, Math.max(b[0], b[3]));
                maxY = Math.max(maxY, Math.max(b[1], b[4]));
                maxZ = Math.max(maxZ, Math.max(b[2], b[5]));
            }
            area = new Area(minX, minY, minZ, maxX, maxY, maxZ);
        }
        Area finalArea = area;
        return new PriorityScheduler.ActionHandle() {
            private boolean started, done;
            private String error;
            private int idleTicks;

            private void startMine() {
                if (finalArea != null) EngineSelector.current().mine(filter, finalArea);
                else EngineSelector.current().mine(filter);
                started = true;
                idleTicks = 0;
            }

            @Override public boolean isDone() { return done; }
            @Override public String error() { return error; }
            @Override public int remainingWork() { return 0; }
            @Override public void cancel() { EngineSelector.current().cancel(); done = true; }
            @Override public void suspend() { EngineSelector.current().cancel(); }
            @Override public void resume() { if (!done) startMine(); }

            @Override
            public void tick() {
                if (done) return;
                if (!started) {
                    startMine();
                    return;
                }
                EngineStatus st = EngineSelector.current().status();
                if (st == EngineStatus.DONE) {
                    done = true;
                } else if (st == EngineStatus.FAILED) {
                    done = true;
                } else if (!EngineSelector.current().isBusy()) {
                    if (++idleTicks > 20) done = true;
                } else {
                    idleTicks = 0;
                }
            }
        };
    }

    public static PriorityScheduler.ActionHandle projectCreateDumpHandle(BlockPos chestPos) {
        if (chestPos == null) {
            Actions.Action failAct = new Actions.Wait(0.05);
            failAct.fail("No dump chest configured on project.");
            return wrapAction(failAct, () -> 0, null);
        }
        ChestOp op = new ChestOp("at " + chestPos.toShortString(), ChestOp.Kind.DUMP, null, -1, false);
        return wrapAction(new Actions.Chest(op, chestPos), () -> 0, null);
    }

    public static PriorityScheduler.ActionHandle projectCreateChestPutHandle(BlockPos chestPos, Item item, int count, boolean soft) {
        ChestOp op = new ChestOp("at " + chestPos.toShortString(), ChestOp.Kind.PUT, item, count, false, soft);
        return wrapAction(new Actions.Chest(op, chestPos), () -> 0, null);
    }

    public static PriorityScheduler.ActionHandle projectCreateChestGetHandle(BlockPos chestPos, Item item, int count, boolean upto, boolean soft) {
        ChestOp op = new ChestOp("at " + chestPos.toShortString(), ChestOp.Kind.GET, item, count, upto, soft);
        return wrapAction(new Actions.Chest(op, chestPos), () -> 0, null);
    }

    public static Item projectResolveItem(String name) {
        return name == null ? null : ChestOp.itemOf(name);
    }

    public static PriorityScheduler.ActionHandle projectCreateCollectScaffoldHandle() {
        Actions.Action act = new Actions.Feature(
                dev.buildtools.Api::collectScaffoldNow,
                dev.buildtools.Api::collectingScaffold,
                dev.buildtools.Api::stopCollectingScaffold,
                () -> true);
        return wrapAction(act, () -> 0, null);
    }

    public static PriorityScheduler.ActionHandle projectCreatePickupHandle() {
        Actions.Action act = new Actions.Feature(
                () -> { dev.loadchest.LoadChestMod.pickupNow(); return null; },
                dev.loadchest.LoadChestMod::pickingUp,
                null,
                () -> true);
        return wrapAction(act, () -> 0, null);
    }

    public static PriorityScheduler.ActionHandle projectCreateFeatureHandle(
            Supplier<String> startFn,
            java.util.function.BooleanSupplier busyFn,
            Runnable stopFn) {
        Actions.Action act = new Actions.Feature(startFn, busyFn, stopFn, () -> true);
        return wrapAction(act, () -> 0, null);
    }

    public static PriorityScheduler.ActionHandle projectStartClearTrees(
            List<int[]> explicitBoxes,
            String taskId,
            int[] dumpChestCoords,
            boolean allowMidRunDump,
            Integer treeZoneDiameterOverride) {
        BlockPos chest = (dumpChestCoords != null && dumpChestCoords.length >= 3)
                ? new BlockPos(dumpChestCoords[0], dumpChestCoords[1], dumpChestCoords[2])
                : null;
        String chestText = chest == null ? null : "at " + chest.toShortString();
        return new PriorityScheduler.ActionHandle() {
            private Fiber fiber;
            private boolean started;
            private boolean done;
            private boolean suspended;
            private String error;
            private Integer prevDiameter;
            private boolean diameterRestored;

            private void restoreDiameter() {
                if (prevDiameter != null && !diameterRestored) {
                    diameterRestored = true;
                    dev.buildtools.TreeZoneOptions.setDiameter(prevDiameter);
                }
            }

            private void ensureStarted() {
                if (started) return;
                started = true;
                if (treeZoneDiameterOverride != null && treeZoneDiameterOverride > 0) {
                    prevDiameter = dev.buildtools.TreeZoneOptions.setDiameter(treeZoneDiameterOverride);
                }
                Interp[] holder = new Interp[1];
                fiber = new Fiber("project-clear-trees", () -> {
                    try {
                        TreeClear.run(holder[0], 1, chest, chestText, explicitBoxes, taskId, allowMidRunDump);
                    } finally {
                        restoreDiameter();
                    }
                });
                holder[0] = new Interp(null, fiber);
            }

            private void finishWithError(String err) {
                error = err;
                done = true;
                restoreDiameter();
            }

            @Override
            public boolean isDone() {
                return done;
            }

            @Override
            public String error() {
                return error;
            }

            @Override
            public int remainingWork() {
                return TreeClear.lastRemainingTrees();
            }

            @Override
            public void cancel() {
                if (done) return;
                done = true;
                if (fiber != null && fiber.action != null && !fiber.action.done) {
                    try {
                        fiber.action.cancel();
                    } catch (Throwable ignored) {
                    }
                }
                if (fiber != null) fiber.kill();
                restoreDiameter();
            }

            @Override
            public void suspend() {
                if (done || suspended) return;
                suspended = true;
                if (fiber != null && fiber.action != null && !fiber.action.done) {
                    fiber.action.suspend();
                }
            }

            @Override
            public void resume() {
                if (done || !suspended) return;
                suspended = false;
                if (fiber != null && fiber.action != null && !fiber.action.done) {
                    fiber.action.resume();
                }
            }

            @Override
            public void tick() {
                if (done || suspended) return;
                ensureStarted();
                if (fiber.action != null) {
                    if (!fiber.action.done) {
                        fiber.action.ticks++;
                        fiber.action.tick();
                        if (!fiber.action.done) return;
                    }
                }
                if (fiber.done) {
                    if (fiber.failure != null) {
                        finishWithError(fiber.failure instanceof Values.ScriptError se
                                ? se.getMessage()
                                : String.valueOf(fiber.failure));
                    } else {
                        finishWithError(null);
                    }
                    return;
                }
                if (!fiber.resume()) {
                    finishWithError("clear_trees timed out waiting for fiber.");
                    return;
                }
                if (fiber.done) {
                    if (fiber.failure != null) {
                        finishWithError(fiber.failure instanceof Values.ScriptError se
                                ? se.getMessage()
                                : String.valueOf(fiber.failure));
                    } else {
                        finishWithError(null);
                    }
                    return;
                }
                if (fiber.wanted != null) {
                    Supplier<Actions.Action> factory = fiber.wanted;
                    fiber.wanted = null;
                    try {
                        Actions.Action a = factory.get();
                        fiber.action = a;
                        a.start();
                    } catch (Throwable t) {
                        Actions.Action failed = new Actions.Now(() -> null);
                        failed.fail("Failed to start '" + fiber.actionText + "': " + t.getMessage());
                        fiber.action = failed;
                    }
                }
            }
        };
    }

    public static void projectApplySelection(List<int[]> boxes) {
        if (boxes == null || boxes.isEmpty()) Areas.clearSelection();
        else Areas.apply(boxes);
    }

    private static int setOverlayMaster(boolean on) {
        cfg.showWorldOverlay = on;
        Config.save();
        Util.msg("World overlay " + (on ? "ON" : "OFF") + ".");
        return 1;
    }

    private static int toggleOverlayLayer(String layer, Boolean value) {
        boolean next = switch (layer) {
            case "beds" -> cfg.showOverlayBeds = (value != null ? value : !cfg.showOverlayBeds);
            case "chests" -> cfg.showOverlayChests = (value != null ? value : !cfg.showOverlayChests);
            case "safe" -> cfg.showOverlaySafeAreas = (value != null ? value : !cfg.showOverlaySafeAreas);
            case "areas" -> cfg.showOverlayTaskAreas = (value != null ? value : !cfg.showOverlayTaskAreas);
            case "fill" -> cfg.showOverlayAreaFill = (value != null ? value : !cfg.showOverlayAreaFill);
            case "labels" -> cfg.showOverlayLabels = (value != null ? value : !cfg.showOverlayLabels);
            case "xray" -> cfg.showOverlayThroughWalls = (value != null ? value : !cfg.showOverlayThroughWalls);
            case "problems" -> cfg.showOverlayProblemsOnly = (value != null ? value : !cfg.showOverlayProblemsOnly);
            default -> false;
        };
        Config.save();
        Util.msg("World overlay " + layer + ": " + (next ? "ON" : "OFF") + ".");
        return 1;
    }
}
