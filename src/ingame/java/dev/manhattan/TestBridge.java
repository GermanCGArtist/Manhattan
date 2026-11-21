package dev.manhattan;

import dev.manhattan.project.GameProjectRuntime;
import dev.manhattan.project.Project;
import dev.manhattan.project.ProjectTask;
import dev.manhattan.project.TaskQueue;
import dev.midtown.api.BenchmarkLogger;
import dev.midtown.api.EngineSelector;
import dev.midtown.body.BodyController;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.tags.FluidTags;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * Test-only bridge (its own jar, manhattan-testbridge, never installed in the AFK profile). It listens on
 * 127.0.0.1 (port 25599, or -Dmanhattan.testbridge.port) for one command per line and answers with one JSON line.
 * tests/ingame/bridge.py is the client. It lives in dev.manhattan so it can start scripts and read the runner.
 *
 * Commands: ping | state | chat &lt;text or /command&gt; | chatlog [from] | block x y z | layers x1 y1 z1 x2 y2 z2 [...] | count item | entities type radius
 * | world name [seed] | leave | runfile path | script | stopscript | editor | screen [close] | quit
 */
public class TestBridge implements ClientModInitializer {
    private static final List<String> chat = Collections.synchronizedList(new ArrayList<>());
    private static GuiMessage lastHead;
    private static Field allMessages;

    private static final String BENCHMARK = System.getProperty("manhattan.benchmark");
    private enum BenchState { IDLE, OPENING_WORLD, WAIT_IN_WORLD, SETUP, RUNNING, DONE }
    private static BenchState benchState = BenchState.IDLE;
    private static int benchTicks = 0;
    private static boolean benchProjectMode = false;
    private static long benchStartMs = 0L;
    private static Project benchProject = null;
    private static boolean benchWallBuilt = false;
    private static boolean benchSelectionClobbered = false;
    private static List<int[]> benchKeepBoxes = List.of();
    private static boolean benchResultEmitted = false;

    @Override
    public void onInitializeClient() {
        int port = Integer.getInteger("manhattan.testbridge.port", 25599);
        ClientTickEvents.END_CLIENT_TICK.register(TestBridge::tick);
        Thread t = new Thread(() -> serve(port), "manhattan-testbridge");
        t.setDaemon(true);
        t.start();
    }

    // ---------------------------------------------------------------- game thread

    private static void tick(Minecraft mc) {
        captureChat(mc);
        // an unfocused window must not pause the test world
        if (mc.screen instanceof PauseScreen && mc.level != null) mc.setScreen(null);
        // a test game never keeps the user's mouse: let go whenever the game grabbed it (this jar exists only in test profiles)
        if (mc.mouseHandler.isMouseGrabbed()) mc.mouseHandler.releaseMouse();
        if (BENCHMARK != null && !BENCHMARK.isBlank()) {
            tickBenchmark(mc);
        }
    }

    /** Copies every new chat line (also the client-only ones mods print) into {@link #chat}. */
    @SuppressWarnings("unchecked")
    private static void captureChat(Minecraft mc) {
        try {
            if (allMessages == null) {
                allMessages = net.minecraft.client.gui.components.ChatComponent.class.getDeclaredField("allMessages");
                allMessages.setAccessible(true);
            }
            List<GuiMessage> all = (List<GuiMessage>) allMessages.get(mc.gui.getChat());
            if (all.isEmpty()) { lastHead = null; return; }
            if (all.get(0) == lastHead) return;
            List<String> fresh = new ArrayList<>();
            for (GuiMessage m : all) {   // newest first
                if (m == lastHead) break;
                fresh.add(m.content().getString());
            }
            Collections.reverse(fresh);
            chat.addAll(fresh);
            lastHead = all.get(0);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    // ---------------------------------------------------------------- socket

    private static void serve(int port) {
        try (ServerSocket ss = new ServerSocket(port, 4, InetAddress.getLoopbackAddress())) {
            System.out.println("[testbridge] listening on 127.0.0.1:" + port);
            while (true) {
                Socket s = ss.accept();
                Thread c = new Thread(() -> client(s), "manhattan-testbridge-client");
                c.setDaemon(true);
                c.start();
            }
        } catch (Exception e) {
            System.out.println("[testbridge] stopped: " + e);
        }
    }

    private static void client(Socket s) {
        try (s; BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true)) {
            String line;
            while ((line = in.readLine()) != null) {
                String reply;
                try {
                    reply = handle(line.strip());
                } catch (Throwable t) {
                    reply = "{\"ok\":false,\"error\":" + q(String.valueOf(t)) + "}";
                }
                out.println(reply);
            }
        } catch (Exception ignored) {
        }
    }

    private static <T> T onGame(Callable<T> c) throws Exception {
        return Minecraft.getInstance().submit(() -> {
            try {
                return c.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).get(20, TimeUnit.SECONDS);
    }

    private static String ok(String fields) {
        return fields.isEmpty() ? "{\"ok\":true}" : "{\"ok\":true," + fields + "}";
    }

    private static String err(String why) {
        return "{\"ok\":false,\"error\":" + q(why) + "}";
    }

    private static String handle(String line) throws Exception {
        String cmd = line.contains(" ") ? line.substring(0, line.indexOf(' ')) : line;
        String rest = line.contains(" ") ? line.substring(line.indexOf(' ') + 1).strip() : "";
        String[] a = rest.isEmpty() ? new String[0] : rest.split("\\s+");
        Minecraft mc = Minecraft.getInstance();
        switch (cmd) {
            case "ping": return ok("\"pong\":true");
            case "state": return onGame(() -> state(mc));
            case "chat": return onGame(() -> {
                if (mc.player == null) return err("not in a world");
                if (rest.startsWith("/")) mc.player.connection.sendCommand(rest.substring(1));
                else mc.player.connection.sendChat(rest);
                return ok("");
            });
            case "chatlog": {
                int from = a.length > 0 ? Integer.parseInt(a[0]) : 0;
                List<String> copy;
                synchronized (chat) { copy = new ArrayList<>(chat.subList(Math.min(from, chat.size()), chat.size())); }
                StringBuilder b = new StringBuilder();
                for (String c : copy) b.append(b.isEmpty() ? "" : ",").append(q(c));
                return ok("\"next\":" + (Math.min(from, chat.size()) + copy.size()) + ",\"lines\":[" + b + "]");
            }
            case "blockstate": {
                BlockPos p = new BlockPos(Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2]));
                var server = mc.getSingleplayerServer();
                if (server == null) return err("no single-player world");
                String st = server.submit(() -> {
                    ServerLevel lvl = server.overworld();
                    if (mc.player != null) {
                        ServerLevel own = server.getLevel(mc.player.level().dimension());
                        if (own != null) lvl = own;
                    }
                    return lvl.getBlockState(p).toString();
                }).get(20, TimeUnit.SECONDS);
                return ok("\"state\":" + q(st));
            }
            case "block": {
                BlockPos p = new BlockPos(Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2]));
                var server = mc.getSingleplayerServer();
                if (server == null) return err("no single-player world");
                String id = server.submit(() -> {
                    ServerLevel lvl = server.overworld();
                    if (mc.player != null) {
                        ServerLevel own = server.getLevel(mc.player.level().dimension());
                        if (own != null) lvl = own;
                    }
                    return BuiltInRegistries.BLOCK.getKey(lvl.getBlockState(p).getBlock()).toString()
                            + (lvl.getFluidState(p).isEmpty() ? "" : "|" + BuiltInRegistries.FLUID.getKey(lvl.getFluidState(p).getType()));
                }).get(20, TimeUnit.SECONDS);
                return ok("\"block\":" + q(id));
            }
            case "layers": {
                // layers x1 y1 z1 x2 y2 z2 [more boxes...]: per y from the lowest to the highest, how many blocks in the
                // boxes are not air ("solid") and how many hold water (any water state). Summed over all the boxes.
                if (a.length < 6 || a.length % 6 != 0) return err("layers needs boxes of six numbers");
                var server = mc.getSingleplayerServer();
                if (server == null) return err("no single-player world");
                int[] v = new int[a.length];
                for (int i = 0; i < a.length; i++) v[i] = Integer.parseInt(a[i]);
                int ylo = Integer.MAX_VALUE, yhi = Integer.MIN_VALUE;
                for (int i = 0; i < v.length; i += 6) {
                    ylo = Math.min(ylo, Math.min(v[i + 1], v[i + 4]));
                    yhi = Math.max(yhi, Math.max(v[i + 1], v[i + 4]));
                }
                final int lo = ylo, hi = yhi;
                String[] res = server.submit(() -> {
                    ServerLevel lvl = server.overworld();
                    if (mc.player != null) {
                        ServerLevel own = server.getLevel(mc.player.level().dimension());
                        if (own != null) lvl = own;
                    }
                    StringBuilder solid = new StringBuilder(), water = new StringBuilder();
                    for (int y = lo; y <= hi; y++) {
                        int ns = 0, nw = 0;
                        for (int i = 0; i < v.length; i += 6) {
                            if (y < Math.min(v[i + 1], v[i + 4]) || y > Math.max(v[i + 1], v[i + 4])) continue;
                            for (int x = Math.min(v[i], v[i + 3]); x <= Math.max(v[i], v[i + 3]); x++)
                                for (int z = Math.min(v[i + 2], v[i + 5]); z <= Math.max(v[i + 2], v[i + 5]); z++) {
                                    BlockPos p = new BlockPos(x, y, z);
                                    if (!lvl.getBlockState(p).isAir()) ns++;
                                    if (lvl.getFluidState(p).is(net.minecraft.tags.FluidTags.WATER)) nw++;
                                }
                        }
                        solid.append(solid.isEmpty() ? "" : ",").append(ns);
                        water.append(water.isEmpty() ? "" : ",").append(nw);
                    }
                    return new String[]{solid.toString(), water.toString()};
                }).get(20, TimeUnit.SECONDS);
                return ok("\"ylo\":" + lo + ",\"solid\":[" + res[0] + "],\"water\":[" + res[1] + "]");
            }
            case "held": return onGame(() -> {
                if (mc.player == null) return err("not in a world");
                return ok("\"item\":" + q(BuiltInRegistries.ITEM.getKey(mc.player.getMainHandItem().getItem()).toString()));
            });
            case "count": return onGame(() -> {
                if (mc.player == null) return err("not in a world");
                String want = a[0].contains(":") ? a[0] : "minecraft:" + a[0];
                int n = 0;
                var inv = mc.player.getInventory();
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    ItemStack st = inv.getItem(i);
                    if (!st.isEmpty() && BuiltInRegistries.ITEM.getKey(st.getItem()).toString().equals(want)) n += st.getCount();
                }
                return ok("\"count\":" + n);
            });
            case "entities": return onGame(() -> {
                if (mc.player == null || mc.level == null) return err("not in a world");
                String want = a[0].contains(":") ? a[0] : "minecraft:" + a[0];
                double r = a.length > 1 ? Double.parseDouble(a[1]) : 32;
                int n = 0;
                for (Entity e : mc.level.entitiesForRendering())
                    if (e.isAlive() && e.distanceTo(mc.player) <= r && BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().equals(want)) n++;
                return ok("\"count\":" + n);
            });
            case "world": return onGame(() -> openWorld(mc, a[0], a.length > 1 ? Long.parseLong(a[1]) : 20261109L, a.length > 2 && a[2].equals("normal")));
            case "leave": return onGame(() -> {
                if (mc.level == null) return ok("\"was\":false");
                mc.disconnectWithSavingScreen();
                mc.setScreen(new TitleScreen());
                return ok("\"was\":true");
            });
            case "runfile": {
                List<String> lines = Files.readAllLines(Path.of(rest), StandardCharsets.UTF_8);
                return onGame(() -> {
                    if (mc.player == null) return err("not in a world");
                    String e = QueueMod.runner.start(lines);
                    return e == null ? ok("") : err(e);
                });
            }
            case "script": return onGame(() -> ok("\"running\":" + QueueMod.runner.running() + ",\"status\":" + q(QueueMod.runner.status())));
            case "stopscript": return onGame(() -> { QueueMod.runner.halt("Stopped by the test bridge."); return ok(""); });
            case "editor": return onGame(() -> {
                ImGuiManager.openShell(Shell.Mode.EDIT);
                Shell.drawer = Shell.Drawer.NONE;
                return ok("");
            });
            case "screen": return onGame(() -> {
                if (a.length > 0 && a[0].equals("close")) mc.setScreen(null);
                return ok("\"screen\":" + q(mc.screen == null ? "" : mc.screen.getClass().getName()));
            });
            case "imgui": return onGame(() -> handleImGui(mc, a));
            case "panels": return onGame(() -> handlePanels(mc, a, rest));
            case "quit": return onGame(() -> {
                if (mc.level != null) mc.disconnectWithSavingScreen();
                mc.stop();
                return ok("");
            });
            default: return err("unknown command: " + cmd);
        }
    }

    private static String state(Minecraft mc) {
        StringBuilder b = new StringBuilder();
        b.append("\"screen\":").append(q(mc.screen == null ? "" : mc.screen.getClass().getName()));
        b.append(",\"inWorld\":").append(mc.player != null && mc.level != null);
        b.append(",\"chat\":").append(chat.size());
        if (mc.player != null && mc.level != null) {
            var p = mc.player;
            b.append(",\"x\":").append(p.getX()).append(",\"y\":").append(p.getY()).append(",\"z\":").append(p.getZ());
            b.append(",\"health\":").append(p.getHealth());
            b.append(",\"food\":").append(p.getFoodData().getFoodLevel());
            b.append(",\"air\":").append(p.getAirSupply());
            b.append(",\"underWater\":").append(p.isUnderWater());
            StringBuilder hb = new StringBuilder();
            for (int i = 0; i < 9; i++) {
                ItemStack st = p.getInventory().getItem(i);
                hb.append(i == 0 ? "" : ",").append(q(st.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(st.getItem()).getPath()));
            }
            b.append(",\"hotbar\":[").append(hb).append("]");
            b.append(",\"gamemode\":").append(q(mc.gameMode == null ? "" : mc.gameMode.getPlayerMode().getName()));
            b.append(",\"dimension\":").append(q(mc.level.dimension().toString()));
            b.append(",\"time\":").append(mc.level.getOverworldClockTime());
        }
        b.append(",\"script\":").append(QueueMod.runner.running());
        return ok(b.toString());
    }

    private static String handleImGui(Minecraft mc, String[] a) throws Exception {
        long win = mc.getWindow().handle();
        if (a.length > 0) {
            switch (a[0]) {
                case "toggle" -> ImGuiManager.toggleShell();
                case "open" -> ImGuiManager.openShell(a.length > 1 && a[1].equalsIgnoreCase("monitor") ? Shell.Mode.MONITOR
                        : a.length > 1 && a[1].equalsIgnoreCase("edit") ? Shell.Mode.EDIT : Shell.lastMode());
                case "close" -> ImGuiManager.closeShell();
                case "drawer" -> Shell.drawer = Shell.Drawer.valueOf(a[1].toUpperCase());
                case "palette" -> {
                    if (a.length > 1) CommandPalette.runFirst(String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)));
                    else CommandPalette.toggle();
                }
                case "scale" -> ImGuiManager.setUiScale(Float.parseFloat(a[1]));
                case "maplayer" -> {   // imgui maplayer on|off: a sample 5x3 grid of 8-block chunks next to (100, 100)
                    if (a.length > 1 && a[1].equals("off")) MapLayers.unregister("test");
                    else MapLayers.register("test", () -> {
                        java.util.List<MapLayers.Region> out = new java.util.ArrayList<>();
                        MapLayers.State[] st = MapLayers.State.values();
                        for (int i = 0; i < 15; i++) {
                            int gx = i % 5, gz = i / 5;
                            MapLayers.State s = i < 6 ? MapLayers.State.DONE : i == 6 ? MapLayers.State.ACTIVE
                                    : i == 9 ? MapLayers.State.FAILED : MapLayers.State.PENDING;
                            out.add(new MapLayers.Region(96 + gx * 8, 104 + gz * 8, 103 + gx * 8, 111 + gz * 8, s,
                                    String.valueOf(i + 1), "sample chunk"));
                        }
                        return out;
                    });
                }
                case "shot" -> ImGuiManager.requestScreenshot(java.nio.file.Path.of(String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length))));
                case "stop" -> ImGuiManager.triggerStop();
                case "key" -> {
                    int key = Integer.parseInt(a[1]);
                    int action = a.length > 2 ? Integer.parseInt(a[2]) : 1;
                    int mods = a.length > 3 ? Integer.parseInt(a[3]) : 0;
                    var m = net.minecraft.client.KeyboardHandler.class.getDeclaredMethod("keyPress", long.class, int.class, net.minecraft.client.input.KeyEvent.class);
                    m.setAccessible(true);
                    m.invoke(mc.keyboardHandler, win, action, new net.minecraft.client.input.KeyEvent(key, 0, mods));
                }
                case "char" -> {
                    int cp = Integer.parseInt(a[1]);
                    var m = net.minecraft.client.KeyboardHandler.class.getDeclaredMethod("charTyped", long.class, net.minecraft.client.input.CharacterEvent.class);
                    m.setAccessible(true);
                    m.invoke(mc.keyboardHandler, win, new net.minecraft.client.input.CharacterEvent(cp));
                }
                case "move" -> {
                    double x = Double.parseDouble(a[1]);
                    double y = Double.parseDouble(a[2]);
                    var move = net.minecraft.client.MouseHandler.class.getDeclaredMethod("onMove", long.class, double.class, double.class);
                    move.setAccessible(true);
                    move.invoke(mc.mouseHandler, win, x, y);
                }
                case "press" -> {
                    double x = Double.parseDouble(a[1]);
                    double y = Double.parseDouble(a[2]);
                    int btn = a.length > 3 ? Integer.parseInt(a[3]) : 0;
                    var move = net.minecraft.client.MouseHandler.class.getDeclaredMethod("onMove", long.class, double.class, double.class);
                    var button = net.minecraft.client.MouseHandler.class.getDeclaredMethod("onButton", long.class, net.minecraft.client.input.MouseButtonInfo.class, int.class);
                    move.setAccessible(true);
                    button.setAccessible(true);
                    move.invoke(mc.mouseHandler, win, x, y);
                    button.invoke(mc.mouseHandler, win, new net.minecraft.client.input.MouseButtonInfo(btn, 0), 1);
                }
                case "release" -> {
                    double x = Double.parseDouble(a[1]);
                    double y = Double.parseDouble(a[2]);
                    int btn = a.length > 3 ? Integer.parseInt(a[3]) : 0;
                    var move = net.minecraft.client.MouseHandler.class.getDeclaredMethod("onMove", long.class, double.class, double.class);
                    var button = net.minecraft.client.MouseHandler.class.getDeclaredMethod("onButton", long.class, net.minecraft.client.input.MouseButtonInfo.class, int.class);
                    move.setAccessible(true);
                    button.setAccessible(true);
                    move.invoke(mc.mouseHandler, win, x, y);
                    button.invoke(mc.mouseHandler, win, new net.minecraft.client.input.MouseButtonInfo(btn, 0), 0);
                }
                case "click" -> {
                    double x = Double.parseDouble(a[1]);
                    double y = Double.parseDouble(a[2]);
                    int btn = a.length > 3 ? Integer.parseInt(a[3]) : 0;
                    var move = net.minecraft.client.MouseHandler.class.getDeclaredMethod("onMove", long.class, double.class, double.class);
                    var button = net.minecraft.client.MouseHandler.class.getDeclaredMethod("onButton", long.class, net.minecraft.client.input.MouseButtonInfo.class, int.class);
                    move.setAccessible(true);
                    button.setAccessible(true);
                    move.invoke(mc.mouseHandler, win, x, y);
                    button.invoke(mc.mouseHandler, win, new net.minecraft.client.input.MouseButtonInfo(btn, 0), 1);
                    button.invoke(mc.mouseHandler, win, new net.minecraft.client.input.MouseButtonInfo(btn, 0), 0);
                }
                case "resize" -> {
                    int w = Integer.parseInt(a[1]);
                    int h = Integer.parseInt(a[2]);
                    org.lwjgl.glfw.GLFW.glfwSetWindowSize(win, w, h);
                }
                case "guiscale" -> {
                    int scale = Integer.parseInt(a[1]);
                    mc.options.guiScale().set(scale);
                    mc.resizeGui();
                }
                case "clickgui" -> {
                    double gx = Double.parseDouble(a[1]);
                    double gy = Double.parseDouble(a[2]);
                    int btn = a.length > 3 ? Integer.parseInt(a[3]) : 0;
                    double wx = ImGuiManager.guiToWindowX(gx);
                    double wy = ImGuiManager.guiToWindowY(gy);
                    var move = net.minecraft.client.MouseHandler.class.getDeclaredMethod("onMove", long.class, double.class, double.class);
                    var button = net.minecraft.client.MouseHandler.class.getDeclaredMethod("onButton", long.class, net.minecraft.client.input.MouseButtonInfo.class, int.class);
                    move.setAccessible(true);
                    button.setAccessible(true);
                    move.invoke(mc.mouseHandler, win, wx, wy);
                    button.invoke(mc.mouseHandler, win, new net.minecraft.client.input.MouseButtonInfo(btn, 0), 1);
                    button.invoke(mc.mouseHandler, win, new net.minecraft.client.input.MouseButtonInfo(btn, 0), 0);
                }
                default -> {
                    return err("unknown imgui subcommand: " + a[0]);
                }
            }
        }
        float[] sr = ImGuiManager.stopButtonRect();
        float[] vr = ImGuiManager.viewportRect();
        float[] fr = ImGuiManager.fittedViewportRect();
        ProgressModel.Snapshot snap = ProgressModel.snapshot();
        long segDone = snap.segments().stream().filter(s -> s.state() == ProgressModel.State.DONE).count();
        long segRun = snap.segments().stream().filter(s -> s.state() == ProgressModel.State.RUNNING).count();
        long segPend = snap.segments().stream().filter(s -> s.state() == ProgressModel.State.PENDING).count();
        return ok("\"initialized\":" + ImGuiManager.isInitialized()
                + ",\"shellOpen\":" + Shell.isOpen()
                + ",\"mode\":" + q(ImGuiManager.layoutLabel())
                + ",\"drawer\":" + q(Shell.drawer.name())
                + ",\"paletteOpen\":" + CommandPalette.isOpen()
                + ",\"paletteLastRun\":" + q(CommandPalette.lastRun())
                + ",\"overlayVisible\":" + Overlay.visible()
                + ",\"active\":" + ImGuiManager.isActive()
                + ",\"mouseGrabbed\":" + mc.mouseHandler.isMouseGrabbed()
                + ",\"focused\":" + ImGuiManager.isFocused()
                + ",\"gameFocused\":" + ImGuiManager.isGameFocused()
                + ",\"dockedActive\":" + ImGuiManager.isDockedViewportActive()
                + ",\"uiScale\":" + ImGuiManager.getUiScale()
                + ",\"framesRendered\":" + ImGuiManager.framesRendered()
                + ",\"displayW\":" + ImGuiManager.displayWidth()
                + ",\"displayH\":" + ImGuiManager.displayHeight()
                + ",\"guiScale\":" + mc.getWindow().getGuiScale()
                + ",\"guiScaledW\":" + mc.getWindow().getGuiScaledWidth()
                + ",\"guiScaledH\":" + mc.getWindow().getGuiScaledHeight()
                + ",\"vpX\":" + vr[0] + ",\"vpY\":" + vr[1] + ",\"vpW\":" + vr[2] + ",\"vpH\":" + vr[3]
                + ",\"fitX\":" + fr[0] + ",\"fitY\":" + fr[1] + ",\"fitW\":" + fr[2] + ",\"fitH\":" + fr[3]
                + ",\"handlerCount\":" + QueueMod.runner.handlerInfos().size()
                + ",\"alertCount\":" + Alerts.list(snap).size()
                + ",\"stopX\":" + sr[0] + ",\"stopY\":" + sr[1] + ",\"stopW\":" + sr[2] + ",\"stopH\":" + sr[3]
                + ",\"runnerRunning\":" + snap.running()
                + ",\"runnerLine\":" + snap.line()
                + ",\"runnerActions\":" + snap.actionsDone()
                + ",\"currentStep\":" + snap.currentStep()
                + ",\"totalSteps\":" + snap.totalSteps()
                + ",\"overallPercent\":" + snap.overallPercent()
                + ",\"segDone\":" + segDone
                + ",\"segRunning\":" + segRun
                + ",\"segPending\":" + segPend
                + ",\"controller\":" + q(snap.controller())
                + ",\"posText\":" + q(snap.posText())
                + ",\"interruptCount\":" + snap.interrupts().size()
                + ",\"picking\":" + QueueMod.picking
                + ",\"uiLastMode\":" + q(Config.cfg.uiLastMode)
                + ",\"lastShot\":" + q(ImGuiManager.lastShot()));
    }

    private static String handlePanels(Minecraft mc, String[] a, String rest) {
        if (a.length > 0) {
            switch (a[0]) {
                case "addtab" -> {
                    String name = a.length > 1 ? a[1] : "extra.txt";
                    String content = a.length > 2 ? rest.substring(rest.indexOf(a[1]) + a[1].length()).stripLeading().replace("\\n", "\n") : "";
                    DockedPanels.addScriptTab(name, content);
                }
                case "selecttab" -> DockedPanels.selectTab(Integer.parseInt(a[1]));
                case "setscript" -> {
                    int idx = rest.indexOf("setscript");
                    String content = idx >= 0 ? rest.substring(idx + "setscript".length()).stripLeading().replace("\\n", "\n") : "";
                    DockedPanels.setScriptText(content);
                }
                case "cursor" -> DockedPanels.setCursor(Integer.parseInt(a[1]));
                case "comment" -> DockedPanels.toggleComment();
                case "complete" -> DockedPanels.acceptTopSuggestion();
                case "check" -> DockedPanels.runTest();
                case "pick" -> DockedPanels.handleImGuiPick(Places.target());
                case "presetinsert" -> {
                    int idx = rest.indexOf("presetinsert");
                    String name = idx >= 0 ? rest.substring(idx + "presetinsert".length()).trim() : "";
                    DockedPanels.insertPresetByName(name);
                }
                case "clearchests" -> dev.survivalkit.Api.clearChestIndex();
                case "indexchest" -> {
                    int x = Integer.parseInt(a[1]);
                    int y = Integer.parseInt(a[2]);
                    int z = Integer.parseInt(a[3]);
                    java.util.Map<String, Integer> items = new java.util.LinkedHashMap<>();
                    int food = 0;
                    if (a.length > 4) {
                        for (String part : a[4].split(",")) {
                            String[] kv = part.split(":");
                            if (kv.length == 2) {
                                String item = kv[0].contains(":") ? kv[0] : "minecraft:" + kv[0];
                                int cnt = Integer.parseInt(kv[1]);
                                items.put(item, cnt);
                                if (kv[0].contains("bread") || kv[0].contains("apple")) food += cnt;
                            }
                        }
                    }
                    String dim = mc.level == null ? "minecraft:overworld" : mc.level.dimension().identifier().toString();
                    dev.survivalkit.Api.recordChest(dim, x, y, z, items, food);
                }
                case "chestfilter" -> {
                    String q = a.length > 1 && !a[1].equals("-") ? a[1] : "";
                    ChestIndexView.Category cat = a.length > 2 ? ChestIndexView.Category.valueOf(a[2].toUpperCase()) : ChestIndexView.Category.ALL;
                    boolean low = a.length > 3 && Boolean.parseBoolean(a[3]);
                    DockedPanels.setChestFilter(q, cat, low);
                }
                case "chestsort" -> {
                    ChestIndexView.SortColumn col = ChestIndexView.SortColumn.valueOf(a[1].toUpperCase());
                    boolean asc = a.length <= 2 || Boolean.parseBoolean(a[2]);
                    DockedPanels.setChestSort(col, asc);
                }
                case "chestshow" -> ChestIndexView.showInWorld(Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]));
                case "chestcopy" -> ChestIndexView.copyPos(Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]));
                case "chestgoto" -> ChestIndexView.goThere(Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]));
                case "capturerole" -> {
                    Config.cfg.captureType = a[1].toLowerCase();
                    Config.save();
                }
                case "capturefetch" -> PlacesView.fetchLookedAt();
                case "captureaim" -> PlacesView.startAimingInWorld();
                case "capturesel" -> Util.msg(Capture.useSelection());
                case "logfilter" -> DockedPanels.setLogFilter(LogBuffer.Filter.valueOf(a[1].toUpperCase()));
                case "logcopy" -> DockedPanels.copySelectedLogs();
                case "logclear" -> {
                    LogBuffer.clear();
                    DockedPanels.clearLogSelection();
                }
                case "logselect" -> {
                    if (a.length > 1) {
                        List<LogBuffer.Entry> entries = LogBuffer.entries(DockedPanels.getLogFilter());
                        if (a[1].contains(",")) {
                            DockedPanels.clearLogSelection();
                            for (String part : a[1].split(",")) {
                                if (part.isBlank()) continue;
                                int idx = Integer.parseInt(part.trim());
                                if (idx >= 0 && idx < entries.size()) {
                                    DockedPanels.selectLogEntry(entries.get(idx).id(), true, false);
                                }
                            }
                        } else {
                            int idx = Integer.parseInt(a[1]);
                            long targetId = (idx >= 0 && idx < entries.size()) ? entries.get(idx).id() : idx;
                            DockedPanels.selectLogEntry(targetId, a.length > 2 && Boolean.parseBoolean(a[2]), a.length > 3 && Boolean.parseBoolean(a[3]));
                        }
                    }
                }
                case "logcopyselected" -> DockedPanels.copySelectedLogs();
                case "logunselect" -> DockedPanels.clearLogSelection();
                default -> {
                    return err("unknown panels subcommand: " + a[0]);
                }
            }
        }
        List<Suggest.Item> sugs = DockedPanels.currentSuggestions();
        List<ChestIndexView.Row> chestRows = DockedPanels.currentChestRows();
        BlockPos hl = ChestIndexView.highlightedChest();
        LogBuffer.Filter lf = DockedPanels.getLogFilter();
        return ok("\"tabCount\":" + DockedPanels.tabCount()
                + ",\"activeTab\":" + q(DockedPanels.activeTabName())
                + ",\"scriptText\":" + q(DockedPanels.currentScriptText())
                + ",\"errorCount\":" + DockedPanels.lastErrorCount()
                + ",\"suggestionCount\":" + sugs.size()
                + ",\"topSuggestion\":" + q(sugs.isEmpty() ? "" : sugs.get(0).text())
                + ",\"runX\":" + DockedPanels.runBtnX + ",\"runY\":" + DockedPanels.runBtnY + ",\"runW\":" + DockedPanels.runBtnW + ",\"runH\":" + DockedPanels.runBtnH
                + ",\"testX\":" + DockedPanels.testBtnX + ",\"testY\":" + DockedPanels.testBtnY + ",\"testW\":" + DockedPanels.testBtnW + ",\"testH\":" + DockedPanels.testBtnH
                + ",\"pickX\":" + DockedPanels.pickBtnX + ",\"pickY\":" + DockedPanels.pickBtnY + ",\"pickW\":" + DockedPanels.pickBtnW + ",\"pickH\":" + DockedPanels.pickBtnH
                + ",\"presetCount\":" + Config.cfg.presets.size()
                + ",\"lastInsertedPreset\":" + q(DockedPanels.lastInsertedPreset())
                + ",\"chestRowCount\":" + chestRows.size()
                + ",\"firstChestItem\":" + q(chestRows.isEmpty() ? "" : chestRows.get(0).itemName())
                + ",\"firstChestCount\":" + (chestRows.isEmpty() ? 0 : chestRows.get(0).count())
                + ",\"firstChestStatus\":" + q(chestRows.isEmpty() ? "" : chestRows.get(0).status())
                + ",\"highlightedChest\":" + q(hl == null ? "" : hl.getX() + "," + hl.getY() + "," + hl.getZ())
                + ",\"lastCopiedPos\":" + q(ChestIndexView.lastCopiedPos())
                + ",\"areaCount\":" + AreasView.list().size()
                + ",\"spotCount\":" + PlacesView.spots().size()
                + ",\"schemCount\":" + PlacesView.schematics().size()
                + ",\"captureRole\":" + q(Capture.type().id)
                + ",\"captureSet\":" + q(Config.cfg.captureSet)
                + ",\"captureEntryCount\":" + Capture.entries().size()
                + ",\"captureAiming\":" + Capture.aiming
                + ",\"logFilter\":" + q(lf.name())
                + ",\"logTotal\":" + LogBuffer.count(LogBuffer.Filter.ALL)
                + ",\"logErrors\":" + LogBuffer.count(LogBuffer.Filter.ERRORS)
                + ",\"logBaritone\":" + LogBuffer.count(LogBuffer.Filter.BARITONE)
                + ",\"logFiltered\":" + LogBuffer.entries(lf).size()
                + ",\"selectedLogCount\":" + DockedPanels.selectedLogCount()
                + ",\"lastCopiedLog\":" + q(DockedPanels.lastCopiedLog()));
    }

    private static void tickBenchmark(Minecraft mc) {
        benchTicks++;
        switch (benchState) {
            case IDLE -> {
                if (mc.level == null && mc.screen instanceof TitleScreen && benchTicks >= 10) {
                    System.out.println("[testbridge] benchmark bootstrap: opening world for scenario=" + BENCHMARK);
                    openWorld(mc, "manhattan-benchmark", 20261109L, false);
                    benchState = BenchState.OPENING_WORLD;
                    benchTicks = 0;
                }
            }
            case OPENING_WORLD -> {
                if (mc.level != null && mc.player != null && mc.getSingleplayerServer() != null) {
                    mc.player.connection.sendCommand("tp @s 100 -60 100 0 0");
                    mc.player.connection.sendCommand("forceload add 80 80 200 200");
                    benchState = BenchState.WAIT_IN_WORLD;
                    benchTicks = 0;
                }
            }
            case WAIT_IN_WORLD -> {
                if (benchTicks >= 40) {
                    if (mc.screen != null) mc.setScreen(null);
                    setupBenchmarkScenario(mc, BENCHMARK.trim());
                    benchState = BenchState.SETUP;
                    benchTicks = 0;
                }
            }
            case SETUP -> {
                String scenario = BENCHMARK.trim();
                if (benchTicks >= 20 && (setupSynced(mc, scenario) || benchTicks >= 20 * 30)) {
                    benchResultEmitted = false;
                    benchStartMs = System.currentTimeMillis();
                    if (isProjectBenchmark(scenario)) {
                        benchProjectMode = true;
                        benchProject = buildProjectBenchmark(mc, scenario);
                        String err = benchProject == null
                                ? "Failed to construct project for scenario: " + scenario
                                : GameProjectRuntime.instance().startQueue(benchProject);
                        if (err != null) {
                            System.out.println("[testbridge] benchmark project failed to start: " + err);
                            emitProjectBenchmark(scenario, false, 0);
                            benchState = BenchState.DONE;
                            benchTicks = 0;
                            return;
                        }
                        benchState = BenchState.RUNNING;
                        benchTicks = 0;
                        return;
                    }
                    benchProjectMode = false;
                    benchProject = null;
                    List<String> script = benchmarkScript(scenario);
                    if (script == null) {
                        System.out.println("[testbridge] unknown benchmark scenario: " + BENCHMARK);
                        BenchmarkLogger.emit(
                                EngineSelector.selectedName(), BENCHMARK, false, 0, 0, 0, 0, 0);
                        benchState = BenchState.DONE;
                        benchTicks = 0;
                        return;
                    }
                    String err = QueueMod.runner.start(script);
                    if (err != null) {
                        System.out.println("[testbridge] benchmark script failed to start: " + err);
                        BenchmarkLogger.emit(
                                EngineSelector.selectedName(), BENCHMARK, false, 0, 0, 0, 0, 0);
                        benchState = BenchState.DONE;
                        benchTicks = 0;
                        return;
                    }
                    benchState = BenchState.RUNNING;
                    benchTicks = 0;
                }
            }
            case RUNNING -> {
                String scenario = BENCHMARK.trim();
                if (benchProjectMode) {
                    if (("chunk_no_selection_clobber".equals(scenario) || "project_clear_trees_zones".equals(scenario))
                            && !sameBoxes(Areas.read(), benchKeepBoxes)) {
                        benchSelectionClobbered = true;
                    }
                    if (!GameProjectRuntime.instance().running()) {
                        boolean pass = verifyProjectBenchmark(mc, scenario, benchProject);
                        long elapsedMs = Math.max(0L, System.currentTimeMillis() - benchStartMs);
                        emitProjectBenchmark(scenario, pass, elapsedMs);
                        benchState = BenchState.DONE;
                        benchTicks = 0;
                    } else if (benchTicks >= 20 * 360) {
                        GameProjectRuntime.instance().stopQueue("Benchmark timeout");
                        long elapsedMs = Math.max(0L, System.currentTimeMillis() - benchStartMs);
                        emitProjectBenchmark(scenario, false, elapsedMs);
                        benchState = BenchState.DONE;
                        benchTicks = 0;
                    }
                } else {
                    if ("midtown_async_replan".equals(scenario) && !benchWallBuilt
                            && mc.player != null && mc.player.getX() >= 112.0) {
                        benchWallBuilt = true;
                        mc.player.connection.sendCommand("fill 118 -60 97 118 -57 103 stone");
                    }
                    if (!QueueMod.runner.running()) {
                        // Runner.emitBenchmark(...) already emitted BENCHMARK_RESULT on script completion/failure
                        benchState = BenchState.DONE;
                        benchTicks = 0;
                    } else if (benchTicks >= 20 * 360) {
                        QueueMod.runner.halt("Benchmark timeout");
                        benchState = BenchState.DONE;
                        benchTicks = 0;
                    }
                }
            }
            case DONE -> {
                if (benchTicks >= 5) {
                    if (mc.level != null) mc.disconnectWithSavingScreen();
                    mc.stop();
                }
            }
        }
    }

    private static void setupBenchmarkScenario(Minecraft mc, String scenario) {
        if (mc.player == null) return;
        benchWallBuilt = false;
        benchSelectionClobbered = false;
        benchKeepBoxes = List.of();
        benchResultEmitted = false;
        var s = mc.getSingleplayerServer();
        if (s != null) {
            boolean advanceTime = "project_pre_dusk_gate".equals(scenario);
            s.execute(() -> {
                var gr = s.getGameRules();
                gr.set(GameRules.SPAWN_MOBS, false, s);
                gr.set(GameRules.SPAWN_MONSTERS, false, s);
                gr.set(GameRules.KEEP_INVENTORY, true, s);
                gr.set(GameRules.ADVANCE_TIME, advanceTime, s);
                gr.set(GameRules.ADVANCE_WEATHER, false, s);
            });
        }
        List<String> cmds = new ArrayList<>();
        cmds.add("gamemode survival");
        cmds.add("difficulty peaceful");
        cmds.add("tick rate 50");
        cmds.add("effect clear @s");
        cmds.add("clear @s");
        cmds.add("time set noon");
        cmds.add("weather clear");
        cmds.add("kill @e[type=!player]");
        cmds.add("fill 90 -63 80 160 -62 130 dirt");
        cmds.add("fill 90 -61 80 160 -61 130 grass_block");
        cmds.add("fill 90 -60 80 160 -56 130 air");
        switch (scenario) {
            case "nav_flat_obstacle" -> {
                cmds.add("fill 115 -60 95 115 -59 105 stone");
                cmds.add("fill 130 -62 96 132 -61 104 air");
                cmds.add("fill 145 -61 96 145 -61 104 water");
                cmds.add("fill 160 -61 98 162 -61 102 lava");
            }
            case "mine_pillars_floating", "clear_pillars_floating" -> {
                cmds.add("fill 90 -55 80 160 -50 130 air");
                cmds.add("fill 106 -60 98 106 -54 98 stone");
                cmds.add("fill 110 -60 103 110 -57 103 stone");
                cmds.add("setblock 108 -55 101 stone");
                cmds.add("setblock 112 -53 97 stone");
                cmds.add("setblock 104 -57 104 stone");
                cmds.add("fill 113 -56 101 113 -55 101 stone");
                cmds.add("give @s diamond_pickaxe 1");
                cmds.add("give @s cobblestone 64");
            }
            case "eat_offhand", "eat_offhand_full_hotbar" -> {
                cmds.add("difficulty easy");   // peaceful never lets the food bar drop
                cmds.add("item replace entity @s weapon.offhand with golden_carrot 20");
                if (scenario.equals("eat_offhand_full_hotbar")) {
                    for (int i = 0; i < 9; i++) cmds.add("item replace entity @s hotbar." + i + " with dirt 64");
                } else {
                    cmds.add("item replace entity @s hotbar.0 with dirt 64");
                    cmds.add("item replace entity @s hotbar.1 with diamond_pickaxe");
                }
                cmds.add("effect give @s minecraft:hunger 8 250 true");
            }
            case "nav_bridge_off_pillar" -> {
                cmds.add("fill 90 -55 80 160 -50 130 air");
                cmds.add("fill 106 -60 98 106 -58 98 stone");
                cmds.add("give @s cobblestone 64");
            }
            case "nav_pillar_up" -> {
                cmds.add("fill 90 -55 80 160 -50 130 air");
                cmds.add("give @s cobblestone 64");
            }
            case "nav_parkour_wall", "mine_parkour_wall" -> {
                // A 1-wide stone walkway along a wall over a 3-deep pit, with 1, 2 and 3 wide gaps
                // and a 1-gap step up. The bot must jump them, not place blocks (it has cobblestone).
                cmds.add("fill 101 -63 96 130 -61 104 air");
                cmds.add("fill 101 -61 100 129 -61 100 stone");
                cmds.add("fill 101 -60 101 129 -58 101 stone");
                cmds.add("setblock 106 -61 100 air");
                cmds.add("fill 110 -61 100 111 -61 100 air");
                cmds.add("fill 115 -61 100 117 -61 100 air");
                cmds.add("setblock 120 -61 100 air");
                cmds.add("fill 121 -60 100 129 -60 100 stone");
                cmds.add("give @s cobblestone 64");
                if (scenario.equals("mine_parkour_wall")) {
                    // A stream in the pit: walking down there is slow, so the jumps are the way across.
                    cmds.add("fill 101 -63 96 130 -63 104 water");
                    cmds.add("give @s diamond_pickaxe 1");
                    cmds.add("setblock 128 -59 100 iron_ore");
                    cmds.add("setblock 129 -59 100 iron_ore");
                }
            }
            case "nav_pillar_bridge" -> {
                cmds.add("fill 105 -63 96 124 -61 104 air");
                cmds.add("fill 128 -60 100 128 -55 100 stone");
                cmds.add("give @s cobblestone 128");
            }
            case "interrupt_mid_action" -> {
                cmds.add("difficulty normal");
                cmds.add("fill 104 -60 99 108 -59 101 stone");
                cmds.add("give @s diamond_helmet 1");
                cmds.add("give @s diamond_chestplate 1");
                cmds.add("give @s diamond_pickaxe 1");
                cmds.add("give @s diamond_sword 1");
                cmds.add("give @s bread 16");
                cmds.add("effect give @s hunger 5 40");
                cmds.add("summon minecraft:zombie 103 -60 104");
            }
            case "water_fill_tight" -> {
                cmds.add("fill 103 -62 99 107 -61 101 water");
                cmds.add("setblock 98 -60 100 chest");
                cmds.add("data merge block 98 -60 100 {Items:[{Slot:0b,id:\"minecraft:dirt\",count:64}]}");
                cmds.add("give @s dirt 16");
            }
            case "schem_unsupported_reach" -> {
                cmds.add("fill 104 -59 99 106 -57 101 stone");
                cmds.add("give @s diamond_pickaxe 1");
                cmds.add("give @s cobblestone 64");
            }
            case "survival_marathon" -> {
                cmds.add("difficulty normal");
                cmds.add("fill 104 -60 98 107 -59 102 stone");
                cmds.add("setblock 98 -60 100 chest");
                cmds.add("data merge block 98 -60 100 {Items:[{Slot:0b,id:\"minecraft:bread\",count:32},{Slot:1b,id:\"minecraft:diamond_pickaxe\",count:1}]}");
                cmds.add("setblock 98 -60 102 red_bed");
                cmds.add("give @s diamond_helmet 1");
                cmds.add("give @s diamond_chestplate 1");
                cmds.add("give @s diamond_pickaxe 1");
                cmds.add("give @s diamond_sword 1");
                cmds.add("give @s bread 16");
                cmds.add("summon minecraft:zombie 110 -60 100");
            }
            case "chunk_clear_2x2_easy" -> {
                cmds.add("fill 104 -60 92 127 -59 115 stone");
                cmds.add("give @s diamond_pickaxe[unbreakable={}] 1");
                cmds.add("give @s diamond_pickaxe 1");
                cmds.add("effect give @s haste 10000 255 true");
            }
            case "chunk_clear_straggler_strip" -> {
                cmds.add("fill 104 -60 96 151 -59 107 stone");
                cmds.add("setblock 150 -58 100 stone");
                cmds.add("give @s diamond_pickaxe[unbreakable={}] 1");
                cmds.add("give @s diamond_pickaxe 1");
                cmds.add("effect give @s haste 10000 255 true");
            }
            case "chunk_clear_serpentine_3x3" -> {
                cmds.add("fill 104 -60 90 133 -59 119 stone");
                cmds.add("give @s diamond_pickaxe[unbreakable={}] 1");
                cmds.add("give @s diamond_pickaxe 1");
                cmds.add("effect give @s haste 10000 255 true");
            }
            case "chunk_clear_nearest_3x2" -> {
                cmds.add("fill 104 -60 92 133 -59 111 stone");
                cmds.add("give @s diamond_pickaxe[unbreakable={}] 1");
                cmds.add("give @s diamond_pickaxe 1");
                cmds.add("effect give @s haste 10000 255 true");
            }
            case "chunk_clear_l_shape" -> {
                cmds.add("fill 104 -60 92 135 -59 103 stone");
                cmds.add("fill 104 -60 104 115 -59 123 stone");
                cmds.add("give @s diamond_pickaxe[unbreakable={}] 1");
                cmds.add("give @s diamond_pickaxe 1");
                cmds.add("effect give @s haste 10000 255 true");
            }
            case "chunk_clear_deep_trench" -> {
                cmds.add("fill 104 -62 96 139 -60 105 stone");
                cmds.add("give @s diamond_pickaxe[unbreakable={}] 1");
                cmds.add("give @s diamond_pickaxe 1");
                cmds.add("effect give @s haste 10000 255 true");
            }
            case "chunk_water_fill_2x1" -> {
                cmds.add("fill 104 -61 96 127 -61 105 water");
                cmds.add("setblock 98 -60 100 chest");
                cmds.add("data merge block 98 -60 100 {Items:[{Slot:0b,id:\"minecraft:dirt\",count:64},{Slot:1b,id:\"minecraft:dirt\",count:64},{Slot:2b,id:\"minecraft:dirt\",count:64},{Slot:3b,id:\"minecraft:dirt\",count:64}]}");
                cmds.add("give @s dirt 64");
            }
            case "chunk_water_fill_canal_3x1" -> {
                cmds.add("fill 104 -62 97 139 -61 104 water");
                cmds.add("setblock 98 -60 100 chest");
                cmds.add("data merge block 98 -60 100 {Items:["
                        + "{Slot:0b,id:\"minecraft:dirt\",count:64},"
                        + "{Slot:1b,id:\"minecraft:dirt\",count:64},"
                        + "{Slot:2b,id:\"minecraft:dirt\",count:64},"
                        + "{Slot:3b,id:\"minecraft:dirt\",count:64},"
                        + "{Slot:4b,id:\"minecraft:dirt\",count:64},"
                        + "{Slot:5b,id:\"minecraft:dirt\",count:64},"
                        + "{Slot:6b,id:\"minecraft:dirt\",count:64},"
                        + "{Slot:7b,id:\"minecraft:dirt\",count:64},"
                        + "{Slot:8b,id:\"minecraft:dirt\",count:64}]}");
                cmds.add("give @s dirt 64");
            }
            case "chunk_flood_2x1" -> {
                cmds.add("fill 104 -61 97 123 -61 104 air");
                cmds.add("fill 100 -61 99 101 -61 100 water");
                cmds.add("give @s water_bucket 2");
                cmds.add("give @s bucket 2");
            }
            case "chunk_drain_2x1" -> {
                cmds.add("fill 104 -61 97 123 -61 104 water");
                cmds.add("give @s dirt 256");
                cmds.add("give @s bucket 2");
            }
            case "chunk_needs_hook" -> {
                cmds.add("fill 104 -61 97 127 -61 104 water");
                cmds.add("setblock 98 -60 100 chest");
                cmds.add("data merge block 98 -60 100 {Items:[{Slot:0b,id:\"minecraft:dirt\",count:64},{Slot:1b,id:\"minecraft:dirt\",count:64},{Slot:2b,id:\"minecraft:dirt\",count:64},{Slot:3b,id:\"minecraft:dirt\",count:64}]}");
            }
            case "chunk_interrupt_survival" -> {
                cmds.add("difficulty normal");
                cmds.add("fill 104 -60 96 127 -59 107 stone");
                cmds.add("give @s diamond_helmet 1");
                cmds.add("give @s diamond_chestplate 1");
                cmds.add("give @s diamond_leggings 1");
                cmds.add("give @s diamond_boots 1");
                cmds.add("give @s diamond_pickaxe[unbreakable={}] 1");
                cmds.add("give @s diamond_pickaxe 1");
                cmds.add("give @s diamond_sword 1");
                cmds.add("give @s bread 16");
                cmds.add("effect give @s hunger 10 40");
                cmds.add("effect give @s resistance 10000 1 true");
                cmds.add("summon minecraft:zombie 108 -60 102");
            }
            case "chunk_protected_transit" -> {
                cmds.add("fill 104 -60 96 127 -59 107 stone");
                cmds.add("give @s diamond_pickaxe[unbreakable={}] 1");
                cmds.add("give @s diamond_pickaxe 1");
                cmds.add("effect give @s haste 10000 255 true");
            }
            case "chunk_job_ledger_resume" -> {
                cmds.add("fill 104 -60 96 127 -59 107 stone");
                cmds.add("fill 98 -60 100 98 -60 100 air");
                cmds.add("give @s diamond_pickaxe[unbreakable={}] 1");
                cmds.add("give @s diamond_pickaxe 1");
                cmds.add("effect give @s haste 10000 255 true");
            }
            case "chunk_disabled_baseline" -> {
                cmds.add("fill 104 -60 96 127 -59 107 stone");
                cmds.add("give @s diamond_pickaxe[unbreakable={}] 1");
                cmds.add("give @s diamond_pickaxe 1");
                cmds.add("effect give @s haste 10000 255 true");
            }
            case "chunk_colossal_4x4" -> {
                cmds.add("fill 104 -60 80 151 -60 127 stone");
                cmds.add("give @s diamond_pickaxe[unbreakable={}] 1");
                cmds.add("give @s diamond_pickaxe 1");
                cmds.add("effect give @s haste 10000 255 true");
            }
            case "project_queue_pipeline" -> {
                cmds.add("fill 104 -62 99 108 -61 101 water");
                cmds.add("setblock 98 -60 100 chest");
                cmds.add("setblock 98 -60 102 chest");
                cmds.add("data merge block 98 -60 102 {Items:["
                        + "{Slot:0b,id:\"minecraft:dirt\",count:64},"
                        + "{Slot:1b,id:\"minecraft:dirt\",count:64},"
                        + "{Slot:2b,id:\"minecraft:dirt\",count:64},"
                        + "{Slot:3b,id:\"minecraft:dirt\",count:64}]}");
                cmds.add("setblock 98 -60 104 red_bed");
                cmds.add("give @s diamond_shovel 1");
                cmds.add("give @s diamond_pickaxe 1");
                cmds.add("give @s dirt 16");
            }
            case "project_script_params" -> {
                cmds.add("fill 104 -60 96 106 -59 98 dirt");
                cmds.add("fill 110 -60 96 112 -59 98 dirt");
                cmds.add("setblock 98 -60 100 chest");
                cmds.add("give @s diamond_pickaxe 1");
                cmds.add("give @s diamond_shovel 1");
            }
            case "project_batched_logistics" -> {
                cmds.add("difficulty easy");
                cmds.add("fill 104 -60 97 109 -59 103 stone");
                cmds.add("setblock 98 -60 100 chest");
                cmds.add("setblock 98 -60 102 chest");
                cmds.add("data merge block 98 -60 102 {Items:["
                        + "{Slot:0b,id:\"minecraft:diamond_pickaxe\",count:1},"
                        + "{Slot:1b,id:\"minecraft:diamond_pickaxe\",count:1},"
                        + "{Slot:2b,id:\"minecraft:bread\",count:32}]}");
                cmds.add("setblock 98 -60 104 chest");
                cmds.add("item replace entity @s hotbar.0 with diamond_pickaxe[damage=1550] 1");
                for (int i = 1; i < 9; i++) {
                    cmds.add("item replace entity @s hotbar." + i + " with cobblestone 64");
                }
                for (int i = 0; i < 25; i++) {
                    cmds.add("item replace entity @s inventory." + i + " with cobblestone 64");
                }
                cmds.add("effect give @s minecraft:hunger 5 60 true");
            }
            case "project_pre_dusk_gate" -> {
                cmds.add("time set 11800");
                cmds.add("setblock 98 -60 102 red_bed");
                cmds.add("fill 115 -60 99 117 -60 101 stone");
                cmds.add("give @s diamond_pickaxe 1");
            }
            case "project_partial_stop_gate" -> {
                cmds.add("fill 105 -62 98 108 -59 101 bedrock");
                cmds.add("fill 106 -61 99 107 -61 100 water");
                cmds.add("fill 112 -60 99 112 -60 101 dirt");
                cmds.add("give @s dirt 64");
                cmds.add("give @s diamond_shovel 1");
            }
            case "midtown_async_replan" -> {
                // Wall is placed dynamically during RUNNING once player X >= 112.0
            }
            case "midtown_body_interrupt" -> {
                cmds.add("difficulty normal");
                cmds.add("fill 104 -60 98 107 -59 102 stone");
                cmds.add("give @s diamond_helmet 1");
                cmds.add("give @s diamond_chestplate 1");
                cmds.add("give @s diamond_leggings 1");
                cmds.add("give @s diamond_boots 1");
                cmds.add("give @s diamond_sword 1");
                cmds.add("give @s diamond_pickaxe 1");
                cmds.add("give @s bread 16");
                cmds.add("effect give @s minecraft:hunger 8 40 true");
                cmds.add("summon minecraft:zombie 108 -60 100");
            }
            case "chunk_no_selection_clobber" -> {
                cmds.add("fill 104 -60 92 127 -59 115 stone");
                cmds.add("give @s diamond_pickaxe[unbreakable={}] 1");
                cmds.add("give @s diamond_pickaxe 1");
                cmds.add("effect give @s haste 10000 255 true");
            }
            case "project_clear_trees_zones" -> {
                cmds.add("fill 100 -60 92 122 -45 112 air");
                // Tree 1 at (107, 100): place canopy before trunk so log column is preserved
                cmds.add("fill 105 -56 98 109 -54 102 oak_leaves");
                cmds.add("fill 107 -60 100 107 -55 100 oak_log");
                // Tree 2 at (114, 103): place canopy before trunk so log column is preserved
                cmds.add("fill 112 -55 101 116 -53 105 oak_leaves");
                cmds.add("fill 114 -60 103 114 -54 103 oak_log");
                cmds.add("setblock 98 -60 100 chest");
                cmds.add("give @s diamond_axe 1");
                cmds.add("give @s diamond_shovel 1");
                cmds.add("give @s dirt 64");
                cmds.add("effect give @s haste 10000 255 true");
            }
            default -> { }
        }
        cmds.add("tp @s 100 -60 100 0 0");
        if (scenario.equals("nav_bridge_off_pillar")) cmds.add("tp @s 106.5 -57 98.5 0 0");
        for (String c : cmds) {
            mc.player.connection.sendCommand(c);
        }
    }

    private static boolean setupSynced(Minecraft mc, String scenario) {
        if (mc.level == null) return false;
        return switch (scenario) {
            case "survival_marathon", "water_fill_tight" ->
                    mc.level.getBlockState(new BlockPos(98, -60, 100)).is(net.minecraft.world.level.block.Blocks.CHEST);
            case "nav_flat_obstacle" ->
                    mc.level.getBlockState(new BlockPos(115, -60, 100)).is(net.minecraft.world.level.block.Blocks.STONE);
            case "eat_offhand", "eat_offhand_full_hotbar" ->
                    mc.player != null && mc.player.getOffhandItem().is(net.minecraft.world.item.Items.GOLDEN_CARROT);
            case "nav_bridge_off_pillar" ->
                    mc.player != null && mc.player.blockPosition().equals(new BlockPos(106, -57, 98)) && mc.player.onGround();
            case "nav_pillar_up" ->
                    mc.player != null && mc.player.blockPosition().equals(new BlockPos(100, -60, 100))
                            && mc.level.getBlockState(new BlockPos(100, -61, 100)).is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK);
            case "mine_pillars_floating", "clear_pillars_floating" ->
                    mc.level.getBlockState(new BlockPos(112, -53, 97)).is(net.minecraft.world.level.block.Blocks.STONE);
            case "nav_parkour_wall", "mine_parkour_wall" ->
                    mc.level.getBlockState(new BlockPos(125, -60, 100)).is(net.minecraft.world.level.block.Blocks.STONE)
                            && mc.player != null && mc.player.blockPosition().equals(new BlockPos(100, -60, 100));
            case "nav_pillar_bridge" ->
                    mc.level.getBlockState(new BlockPos(128, -55, 100)).is(net.minecraft.world.level.block.Blocks.STONE);
            case "interrupt_mid_action" ->
                    mc.level.getBlockState(new BlockPos(104, -60, 99)).is(net.minecraft.world.level.block.Blocks.STONE);
            case "schem_unsupported_reach" ->
                    mc.level.getBlockState(new BlockPos(104, -59, 99)).is(net.minecraft.world.level.block.Blocks.STONE);
            case "chunk_clear_2x2_easy", "chunk_clear_nearest_3x2", "chunk_clear_l_shape" ->
                    mc.level.getBlockState(new BlockPos(104, -60, 92)).is(net.minecraft.world.level.block.Blocks.STONE);
            case "chunk_clear_straggler_strip" ->
                    mc.level.getBlockState(new BlockPos(150, -58, 100)).is(net.minecraft.world.level.block.Blocks.STONE);
            case "chunk_clear_serpentine_3x3" ->
                    mc.level.getBlockState(new BlockPos(104, -60, 90)).is(net.minecraft.world.level.block.Blocks.STONE);
            case "chunk_clear_deep_trench" ->
                    mc.level.getBlockState(new BlockPos(104, -62, 96)).is(net.minecraft.world.level.block.Blocks.STONE);
            case "chunk_water_fill_2x1" ->
                    mc.level.getBlockState(new BlockPos(98, -60, 100)).is(net.minecraft.world.level.block.Blocks.CHEST)
                            && mc.level.getFluidState(new BlockPos(104, -61, 96)).is(net.minecraft.tags.FluidTags.WATER);
            case "chunk_water_fill_canal_3x1" ->
                    mc.level.getBlockState(new BlockPos(98, -60, 100)).is(net.minecraft.world.level.block.Blocks.CHEST)
                            && mc.level.getFluidState(new BlockPos(104, -62, 97)).is(net.minecraft.tags.FluidTags.WATER);
            case "chunk_flood_2x1" ->
                    mc.level.getBlockState(new BlockPos(104, -61, 97)).isAir()
                            && mc.level.getFluidState(new BlockPos(100, -61, 99)).is(net.minecraft.tags.FluidTags.WATER);
            case "chunk_drain_2x1" ->
                    mc.level.getFluidState(new BlockPos(104, -61, 97)).is(net.minecraft.tags.FluidTags.WATER);
            case "chunk_needs_hook" ->
                    mc.level.getBlockState(new BlockPos(98, -60, 100)).is(net.minecraft.world.level.block.Blocks.CHEST)
                            && mc.level.getFluidState(new BlockPos(104, -61, 97)).is(net.minecraft.tags.FluidTags.WATER);
            case "chunk_interrupt_survival", "chunk_protected_transit", "chunk_job_ledger_resume", "chunk_disabled_baseline" ->
                    mc.level.getBlockState(new BlockPos(104, -60, 96)).is(net.minecraft.world.level.block.Blocks.STONE);
            case "chunk_colossal_4x4" ->
                    mc.level.getBlockState(new BlockPos(104, -60, 80)).is(Blocks.STONE);
            case "project_queue_pipeline" ->
                    mc.level.getBlockState(new BlockPos(98, -60, 100)).is(Blocks.CHEST)
                            && mc.level.getBlockState(new BlockPos(98, -60, 102)).is(Blocks.CHEST)
                            && mc.level.getBlockState(new BlockPos(98, -60, 104)).is(Blocks.RED_BED)
                            && mc.level.getFluidState(new BlockPos(104, -62, 99)).is(FluidTags.WATER);
            case "project_script_params" ->
                    mc.level.getBlockState(new BlockPos(98, -60, 100)).is(Blocks.CHEST)
                            && mc.level.getBlockState(new BlockPos(104, -60, 96)).is(Blocks.DIRT)
                            && mc.level.getBlockState(new BlockPos(110, -60, 96)).is(Blocks.DIRT);
            case "project_batched_logistics" ->
                    mc.level.getBlockState(new BlockPos(98, -60, 100)).is(Blocks.CHEST)
                            && mc.level.getBlockState(new BlockPos(98, -60, 102)).is(Blocks.CHEST)
                            && mc.level.getBlockState(new BlockPos(98, -60, 104)).is(Blocks.CHEST)
                            && mc.level.getBlockState(new BlockPos(104, -60, 97)).is(Blocks.STONE)
                            && mc.player != null && !mc.player.getInventory().getItem(0).isEmpty();
            case "project_pre_dusk_gate" ->
                    mc.level.getBlockState(new BlockPos(98, -60, 102)).is(Blocks.RED_BED)
                            && mc.level.getBlockState(new BlockPos(115, -60, 99)).is(Blocks.STONE)
                            && (mc.level.getOverworldClockTime() % 24000L) >= 11700L;
            case "project_partial_stop_gate" ->
                    mc.level.getBlockState(new BlockPos(105, -59, 98)).is(Blocks.BEDROCK)
                            && mc.level.getFluidState(new BlockPos(106, -61, 99)).is(FluidTags.WATER)
                            && mc.level.getBlockState(new BlockPos(112, -60, 99)).is(Blocks.DIRT);
            case "midtown_async_replan" ->
                    mc.player != null && mc.player.blockPosition().equals(new BlockPos(100, -60, 100));
            case "midtown_body_interrupt" ->
                    mc.level.getBlockState(new BlockPos(104, -60, 98)).is(Blocks.STONE);
            case "chunk_no_selection_clobber" ->
                    mc.level.getBlockState(new BlockPos(104, -60, 92)).is(Blocks.STONE);
            case "project_clear_trees_zones" ->
                    mc.level.getBlockState(new BlockPos(98, -60, 100)).is(Blocks.CHEST)
                            && mc.level.getBlockState(new BlockPos(107, -60, 100)).is(Blocks.OAK_LOG)
                            && mc.level.getBlockState(new BlockPos(107, -55, 100)).is(Blocks.OAK_LOG)
                            && mc.level.getBlockState(new BlockPos(105, -56, 98)).is(Blocks.OAK_LEAVES)
                            && mc.level.getBlockState(new BlockPos(114, -60, 103)).is(Blocks.OAK_LOG)
                            && mc.level.getBlockState(new BlockPos(114, -54, 103)).is(Blocks.OAK_LOG)
                            && mc.level.getBlockState(new BlockPos(116, -53, 105)).is(Blocks.OAK_LEAVES);
            default -> true;
        };
    }

    private static List<String> benchmarkScript(String scenario) {
        return switch (scenario) {
            case "nav_flat_obstacle" -> List.of(
                    "timeout(180)",
                    "disable()",
                    "goto(pos(180, -60, 100))"
            );
            case "eat_offhand", "eat_offhand_full_hotbar" -> List.of(
                    "timeout(90)",
                    "disable()",
                    "wait(9)",
                    "let before = food",
                    "let dirt = count(\"dirt\")",
                    "log(\"EAT_TEST before=\", before, \" dirt=\", dirt)",
                    "if before >= 20",
                    "  error(\"not hungry: food \" + str(before))",
                    "end",
                    "eat()",
                    "log(\"EAT_TEST after=\", food, \" dirt=\", count(\"dirt\"))",
                    "if food <= before",
                    "  error(\"did not eat: food \" + str(food))",
                    "end",
                    "if count(\"dirt\") != dirt",
                    "  error(\"dirt count changed (placed a block?)\")",
                    "end"
            );
            case "nav_bridge_off_pillar" -> List.of(
                    "timeout(120)",
                    "disable()",
                    "goto(pos(106, -54, 100))"
            );
            case "nav_pillar_up" -> List.of(
                    "timeout(120)",
                    "disable()",
                    "goto(pos(108, -54, 100))"
            );
            case "mine_pillars_floating" -> List.of(
                    "timeout(240)",
                    "disable()",
                    "select(pos(103, -60, 96), pos(114, -52, 105))",
                    "mine(\"stone\")",
                    "left = count_blocks(\"stone\")",
                    "log(\"PILLAR_TEST stone_left=\", left)",
                    "if left > 0",
                    "  error(\"stone left: \" + str(left))",
                    "end"
            );
            case "clear_pillars_floating" -> List.of(
                    "timeout(240)",
                    "disable()",
                    "select(pos(103, -60, 96), pos(114, -52, 105))",
                    "clear_area()",
                    "left = count_blocks(\"stone\")",
                    "log(\"PILLAR_TEST stone_left=\", left)",
                    "if left > 0",
                    "  error(\"stone left: \" + str(left))",
                    "end"
            );
            case "nav_parkour_wall" -> List.of(
                    "timeout(120)",
                    "disable()",
                    "goto(pos(127, -59, 100))",
                    "log(\"PARKOUR_TEST cobble=\", count(\"cobblestone\"))",
                    "if count(\"cobblestone\") < 64",
                    "  error(\"placed blocks instead of jumping: cobblestone \" + str(count(\"cobblestone\")))",
                    "end"
            );
            case "mine_parkour_wall" -> List.of(
                    "timeout(150)",
                    "disable()",
                    "select(pos(127, -59, 99), pos(129, -58, 101))",
                    "mine(\"iron_ore\")",
                    "left = count_blocks(\"iron_ore\")",
                    "log(\"PARKOUR_TEST ore_left=\", left, \" cobble=\", count(\"cobblestone\"))",
                    "if left > 0",
                    "  error(\"iron ore left: \" + str(left))",
                    "end",
                    "if count(\"cobblestone\") < 64",
                    "  error(\"placed blocks instead of jumping: cobblestone \" + str(count(\"cobblestone\")))",
                    "end"
            );
            case "nav_pillar_bridge" -> List.of(
                    "timeout(180)",
                    "disable()",
                    "goto(pos(128, -54, 100))"
            );
            case "interrupt_mid_action" -> List.of(
                    "timeout(180)",
                    "disable()",
                    "on mobs_near(12) > 0",
                    "  defend()",
                    "end",
                    "on food < 14",
                    "  eat()",
                    "end",
                    "select(pos(104, -60, 99), pos(108, -59, 101))",
                    "clear_area()",
                    "goto(pos(100, -60, 100))"
            );
            case "water_fill_tight" -> List.of(
                    "timeout(180)",
                    "disable()",
                    "select(pos(103, -62, 99), pos(107, -61, 101))",
                    "fill_water(\"dirt\", pos(98, -60, 100))"
            );
            case "schem_unsupported_reach" -> List.of(
                    "timeout(180)",
                    "disable()",
                    "goto(pos(105, -56, 100))",
                    "select(pos(104, -59, 99), pos(106, -57, 101))",
                    "clear_area()"
            );
            case "survival_marathon" -> List.of(
                    "timeout(200)",
                    "disable()",
                    "option(\"survival.bed\", pos(98, -60, 102))",
                    "option(\"survival.supply\", pos(98, -60, 100))",
                    "on mobs_near(12) > 0",
                    "  defend()",
                    "end",
                    "on food < 14",
                    "  eat()",
                    "end",
                    "select(pos(104, -60, 98), pos(107, -59, 102))",
                    "clear_area()",
                    "dump(pos(98, -60, 100))",
                    "goto(pos(100, -60, 100))"
            );
            case "chunk_clear_2x2_easy" -> List.of(
                    "timeout(200)",
                    "disable()",
                    "option(\"chunk.size\", 12)",
                    "select(pos(104, -60, 92), pos(127, -59, 115))",
                    "clear_area()"
            );
            case "chunk_clear_straggler_strip" -> List.of(
                    "timeout(200)",
                    "disable()",
                    "option(\"chunk.size\", 16)",
                    "select(pos(104, -60, 96), pos(151, -58, 107))",
                    "clear_area()"
            );
            case "chunk_clear_serpentine_3x3" -> List.of(
                    "timeout(200)",
                    "disable()",
                    "option(\"chunk.size\", 10)",
                    "option(\"chunk.order\", \"serpentine\")",
                    "select(pos(104, -60, 90), pos(133, -59, 119))",
                    "clear_area()"
            );
            case "chunk_clear_nearest_3x2" -> List.of(
                    "timeout(200)",
                    "disable()",
                    "option(\"chunk.size\", 10)",
                    "option(\"chunk.order\", \"nearest\")",
                    "select(pos(104, -60, 92), pos(133, -59, 111))",
                    "clear_area()"
            );
            case "chunk_clear_l_shape" -> List.of(
                    "timeout(200)",
                    "disable()",
                    "option(\"chunk.size\", 12)",
                    "select(pos(104, -60, 92), pos(135, -59, 103))",
                    "add_box(pos(104, -60, 104), pos(115, -59, 123))",
                    "clear_area()"
            );
            case "chunk_clear_deep_trench" -> List.of(
                    "timeout(200)",
                    "disable()",
                    "option(\"chunk.size\", 12)",
                    "select(pos(104, -62, 96), pos(139, -60, 105))",
                    "clear_area()"
            );
            case "chunk_water_fill_2x1" -> List.of(
                    "timeout(200)",
                    "disable()",
                    "option(\"chunk.size\", 12)",
                    "select(pos(104, -61, 96), pos(127, -61, 105))",
                    "fill_water(\"dirt\", pos(98, -60, 100))"
            );
            case "chunk_water_fill_canal_3x1" -> List.of(
                    "timeout(200)",
                    "disable()",
                    "option(\"chunk.size\", 12)",
                    "select(pos(104, -62, 97), pos(139, -61, 104))",
                    "fill_water(\"dirt\", pos(98, -60, 100))"
            );
            case "chunk_flood_2x1" -> List.of(
                    "timeout(200)",
                    "disable()",
                    "option(\"chunk.size\", 10)",
                    "select(pos(104, -61, 97), pos(123, -61, 104))",
                    "flood()"
            );
            case "chunk_drain_2x1" -> List.of(
                    "timeout(200)",
                    "disable()",
                    "option(\"chunk.size\", 10)",
                    "select(pos(104, -61, 97), pos(123, -61, 104))",
                    "drain()"
            );
            case "chunk_needs_hook" -> List.of(
                    "timeout(200)",
                    "disable()",
                    "on chunk_needs",
                    "  take(pos(98, -60, 100), \"dirt\", 64)",
                    "end",
                    "option(\"chunk.size\", 12)",
                    "select(pos(104, -61, 97), pos(127, -61, 104))",
                    "fill_water(\"dirt\", pos(98, -60, 100))"
            );
            case "chunk_interrupt_survival" -> List.of(
                    "timeout(200)",
                    "disable()",
                    "on mobs_near(12) > 0",
                    "  defend()",
                    "end",
                    "on food < 14",
                    "  eat()",
                    "end",
                    "option(\"chunk.size\", 12)",
                    "select(pos(104, -60, 96), pos(127, -59, 107))",
                    "clear_area()"
            );
            case "chunk_protected_transit" -> List.of(
                    "timeout(200)",
                    "disable()",
                    "option(\"chunk.size\", 12)",
                    "select(pos(104, -60, 96), pos(127, -59, 107))",
                    "clear_area()",
                    "goto(pos(100, -60, 100))"
            );
            case "chunk_job_ledger_resume" -> List.of(
                    "timeout(200)",
                    "disable()",
                    "job \"chunked_trench\"",
                    "  option(\"chunk.size\", 12)",
                    "  select(pos(104, -60, 96), pos(127, -59, 107))",
                    "  clear_area()",
                    "end"
            );
            case "chunk_disabled_baseline" -> List.of(
                    "timeout(200)",
                    "disable()",
                    "option(\"chunk.enabled\", false)",
                    "select(pos(104, -60, 96), pos(127, -59, 107))",
                    "clear_area()"
            );
            case "chunk_colossal_4x4" -> List.of(
                    "timeout(200)",
                    "disable()",
                    "option(\"chunk.size\", 12)",
                    "select(pos(104, -60, 80), pos(151, -60, 127))",
                    "clear_area()"
            );
            case "midtown_async_replan" -> List.of(
                    "timeout(120)",
                    "disable()",
                    "goto(pos(140, -60, 100))"
            );
            default -> null;
        };
    }

    private static boolean isProjectBenchmark(String scenario) {
        return switch (scenario) {
            case "project_queue_pipeline",
                 "project_script_params",
                 "project_batched_logistics",
                 "project_pre_dusk_gate",
                 "project_partial_stop_gate",
                 "midtown_body_interrupt",
                 "chunk_no_selection_clobber",
                 "project_clear_trees_zones" -> true;
            default -> false;
        };
    }

    private static void saveBenchmarkArea(String name, int x1, int y1, int z1, int x2, int y2, int z2) {
        Areas.setSingleBox(new BlockPos(x1, y1, z1), new BlockPos(x2, y2, z2));
        Areas.save(name);
    }

    private static Project buildProjectBenchmark(Minecraft mc, String scenario) {
        String id = "bench_" + scenario + "_" + (System.currentTimeMillis() % 100000L);
        Project p = new Project(id, scenario, "manhattan-benchmark");
        p.ensureInitialized();

        switch (scenario) {
            case "project_queue_pipeline" -> {
                saveBenchmarkArea("pond_test", 104, -62, 99, 108, -61, 101);
                Areas.clearSelection();
                p.places.dumpChest = new int[]{98, -60, 100};
                p.places.scaffoldChest = new int[]{98, -60, 102};
                p.places.materialChests.add(new int[]{98, -60, 102});
                p.places.bed = new int[]{98, -60, 104};

                ProjectTask t1 = new ProjectTask("t_fill", "Fill pond", ProjectTask.Kind.FILL_WATER);
                t1.areaName = "pond_test";
                t1.expand = 1;
                t1.block = "dirt";
                t1.overrides.allowBreak = false;
                t1.overrides.allowPlace = false;

                ProjectTask t2 = new ProjectTask("t_clear", "Clear pond", ProjectTask.Kind.CLEAR_AREA);
                t2.areaName = "pond_test";
                t2.dumpAfter = true;

                ProjectTask t3 = new ProjectTask("t_dump", "Dump items", ProjectTask.Kind.DUMP);

                p.tasks.add(t1);
                p.tasks.add(t2);
                p.tasks.add(t3);
            }
            case "project_script_params" -> {
                saveBenchmarkArea("mound_a", 104, -60, 96, 106, -59, 98);
                saveBenchmarkArea("mound_b", 111, -60, 97, 111, -59, 97);
                Areas.clearSelection();
                p.places.dumpChest = new int[]{98, -60, 100};

                String inline = "area(target_area); if expand_by != 0 then expand_selection(expand_by, 0, expand_by) end; clear_area(); dump(dump_chest)";

                ProjectTask t1 = new ProjectTask("t_mound_a", "Clear mound A", ProjectTask.Kind.SCRIPT);
                t1.inlineScript = inline;
                t1.params.put("target_area", "mound_a");
                t1.params.put("expand_by", 0);

                ProjectTask t2 = new ProjectTask("t_mound_b", "Clear mound B", ProjectTask.Kind.SCRIPT);
                t2.inlineScript = inline;
                t2.params.put("target_area", "mound_b");
                t2.params.put("expand_by", 1);

                p.tasks.add(t1);
                p.tasks.add(t2);
            }
            case "project_batched_logistics" -> {
                saveBenchmarkArea("logistics_stone", 104, -60, 97, 109, -59, 103);
                Areas.clearSelection();
                p.places.dumpChest = new int[]{98, -60, 100};
                p.places.toolChest = new int[]{98, -60, 102};
                p.places.returnChest = new int[]{98, -60, 104};

                p.survival.batchBaseTrips = true;
                p.survival.itemSaver = true;
                p.survival.itemSaverThreshold = 50;
                p.survival.dumpFreeSlotsBelow = 3;
                p.survival.want.put("diamond_pickaxe", 1);
                p.survival.want.put("bread", 8);

                ProjectTask t1 = new ProjectTask("t_clear", "Clear stone", ProjectTask.Kind.CLEAR_AREA);
                t1.areaName = "logistics_stone";
                p.tasks.add(t1);
            }
            case "project_pre_dusk_gate" -> {
                saveBenchmarkArea("pre_dusk_stone", 115, -60, 99, 117, -60, 101);
                Areas.clearSelection();
                p.places.bed = new int[]{98, -60, 102};
                p.survival.sleepEnabled = true;
                p.survival.preDuskGate = true;
                p.survival.preDuskMarginTicks = 1200;

                ProjectTask t1 = new ProjectTask("t_clear", "Clear stone after sleep", ProjectTask.Kind.CLEAR_AREA);
                t1.areaName = "pre_dusk_stone";
                p.tasks.add(t1);
            }
            case "project_partial_stop_gate" -> {
                saveBenchmarkArea("sealed_water", 106, -61, 99, 107, -61, 100);
                saveBenchmarkArea("untouched_dirt", 112, -60, 99, 112, -60, 101);
                Areas.clearSelection();

                ProjectTask t1 = new ProjectTask("t_fill", "Sealed water fill", ProjectTask.Kind.FILL_WATER);
                t1.areaName = "sealed_water";
                t1.block = "dirt";
                t1.partialPolicy = ProjectTask.PartialPolicy.STOP;
                t1.partialThreshold = 0;

                ProjectTask t2 = new ProjectTask("t_clear", "Untouched dirt clear", ProjectTask.Kind.CLEAR_AREA);
                t2.areaName = "untouched_dirt";

                p.tasks.add(t1);
                p.tasks.add(t2);
            }
            case "midtown_body_interrupt" -> {
                saveBenchmarkArea("body_interrupt_stone", 104, -60, 98, 107, -59, 102);
                Areas.clearSelection();
                p.survival.defendEnabled = true;
                p.survival.defendRange = 10.0;
                p.survival.eatEnabled = true;

                ProjectTask t1 = new ProjectTask("t_clear", "Clear stone under attack", ProjectTask.Kind.CLEAR_AREA);
                t1.areaName = "body_interrupt_stone";
                p.tasks.add(t1);
            }
            case "chunk_no_selection_clobber" -> {
                Config.cfg.chunkEnabled = true;
                Config.cfg.chunkSize = 12;
                saveBenchmarkArea("work_area", 104, -60, 92, 127, -59, 115);
                saveBenchmarkArea("keep_me", 90, -60, 90, 92, -60, 92);
                Areas.load("keep_me");
                benchKeepBoxes = Areas.read();

                ProjectTask t1 = new ProjectTask("t_clear", "Chunked work area", ProjectTask.Kind.CLEAR_AREA);
                t1.areaName = "work_area";
                t1.collectScaffoldAfter = false;
                p.tasks.add(t1);
            }
            case "project_clear_trees_zones" -> {
                saveBenchmarkArea("woods_test", 104, -60, 96, 118, -48, 108);
                saveBenchmarkArea("keep_me", 90, -60, 90, 92, -60, 92);
                Areas.load("keep_me");
                benchKeepBoxes = Areas.read();

                p.places.dumpChest = new int[]{98, -60, 100};
                p.survival.treeZoneDiameter = 9;
                p.survival.showTreeZones = true;

                ProjectTask t1 = new ProjectTask("t_trees", "Clear trees with zones", ProjectTask.Kind.CLEAR_TREES);
                t1.areaName = "woods_test";
                p.tasks.add(t1);
            }
            default -> {
                return null;
            }
        }
        p.ensureInitialized();
        return p;
    }

    private static boolean verifyProjectBenchmark(Minecraft mc, String scenario, Project project) {
        if (mc.level == null || project == null) return false;
        GameProjectRuntime rt = GameProjectRuntime.instance();
        return switch (scenario) {
            case "project_queue_pipeline" ->
                    isBoxAirNoFluid(mc, 104, -62, 99, 108, -61, 101)
                            && rt.taskState("t_fill") == TaskQueue.TaskLedger.State.DONE
                            && rt.taskState("t_clear") == TaskQueue.TaskLedger.State.DONE
                            && rt.taskState("t_dump") == TaskQueue.TaskLedger.State.DONE
                            && EngineSelector.current().allowBreak()
                            && EngineSelector.current().allowPlace();
            case "project_script_params" ->
                    isBoxAirNoFluid(mc, 104, -60, 96, 106, -59, 98)
                            && isBoxAirNoFluid(mc, 110, -60, 96, 112, -59, 98)
                            && rt.taskState("t_mound_a") == TaskQueue.TaskLedger.State.DONE
                            && rt.taskState("t_mound_b") == TaskQueue.TaskLedger.State.DONE;
            case "project_batched_logistics" ->
                    isBoxAirNoFluid(mc, 104, -60, 97, 109, -59, 103)
                            && rt.taskState("t_clear") == TaskQueue.TaskLedger.State.DONE;
            case "project_pre_dusk_gate" ->
                    isBoxAirNoFluid(mc, 115, -60, 99, 117, -60, 101)
                            && rt.taskState("t_clear") == TaskQueue.TaskLedger.State.DONE;
            case "project_partial_stop_gate" -> {
                TaskQueue.TaskLedger.State s1 = rt.taskState("t_fill");
                TaskQueue.TaskLedger.State s2 = rt.taskState("t_clear");
                yield s1 == TaskQueue.TaskLedger.State.FAILED
                        && (s2 == null || s2 == TaskQueue.TaskLedger.State.PENDING)
                        && isBoxBlock(mc, 112, -60, 99, 112, -60, 101, Blocks.DIRT);
            }
            case "midtown_body_interrupt" -> {
                int zombies = 0;
                if (mc.player != null) {
                    for (Entity e : mc.level.entitiesForRendering()) {
                        if (e.isAlive() && e.getType() == EntityType.ZOMBIE && e.distanceTo(mc.player) <= 48.0) {
                            zombies++;
                        }
                    }
                }
                yield mc.player != null && mc.player.isAlive() && mc.player.getHealth() > 0f
                        && zombies == 0
                        && isBoxAirNoFluid(mc, 104, -60, 98, 107, -59, 102)
                        && BodyController.INSTANCE.currentOwner() == null
                        && rt.taskState("t_clear") == TaskQueue.TaskLedger.State.DONE;
            }
            case "chunk_no_selection_clobber" ->
                    !benchSelectionClobbered
                            && sameBoxes(Areas.read(), benchKeepBoxes)
                            && isBoxAirNoFluid(mc, 104, -60, 92, 127, -59, 115)
                            && rt.taskState("t_clear") == TaskQueue.TaskLedger.State.DONE;
            case "project_clear_trees_zones" -> {
                boolean noOakLogs = hasNoBlockOnServer(mc, 104, -60, 96, 118, -48, 108, Blocks.OAK_LOG);
                boolean keepMeOk = !benchSelectionClobbered && sameBoxes(Areas.read(), benchKeepBoxes);
                List<ScaffoldZones.Zone> zones = ScaffoldZones.all();
                boolean zonesClean = !zones.isEmpty();
                for (ScaffoldZones.Zone z : zones) {
                    if (z.current || z.pending) {
                        zonesClean = false;
                        break;
                    }
                }
                boolean allowedZoneCleared = dev.buildtools.Api.scaffoldAllowedZone == null;
                boolean autoSweepRestored = !dev.buildtools.TreeZoneOptions.isAutoSweepSuppressed();
                boolean bodyIdle = BodyController.INSTANCE.currentOwner() == null;
                boolean taskDone = rt.taskState("t_trees") == TaskQueue.TaskLedger.State.DONE;
                System.out.println("[testbridge] project_clear_trees_zones verify: noOakLogs=" + noOakLogs
                        + " keepMeOk=" + keepMeOk
                        + " zonesCount=" + zones.size()
                        + " zonesClean=" + zonesClean
                        + " allowedZoneCleared=" + allowedZoneCleared
                        + " autoSweepRestored=" + autoSweepRestored
                        + " bodyIdle=" + bodyIdle
                        + " taskDone=" + taskDone);
                yield noOakLogs && keepMeOk && zonesClean && allowedZoneCleared
                        && autoSweepRestored && bodyIdle && taskDone;
            }
            default -> false;
        };
    }

    private static void emitProjectBenchmark(String scenario, boolean pass, long elapsedMs) {
        if (benchResultEmitted) return;
        benchResultEmitted = true;
        BenchmarkLogger.emit(
                EngineSelector.selectedName(),
                scenario,
                pass,
                Math.max(0L, elapsedMs),
                0,
                0,
                0,
                0
        );
    }

    private static boolean isBoxAirNoFluid(Minecraft mc, int x1, int y1, int z1, int x2, int y2, int z2) {
        if (mc.level == null) return false;
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!mc.level.getBlockState(p).isAir() || !mc.level.getFluidState(p).isEmpty()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean isBoxBlock(Minecraft mc, int x1, int y1, int z1, int x2, int y2, int z2, Block expected) {
        if (mc.level == null) return false;
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    if (!mc.level.getBlockState(new BlockPos(x, y, z)).is(expected)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean hasNoBlockOnServer(Minecraft mc, int x1, int y1, int z1, int x2, int y2, int z2, Block forbidden) {
        var server = mc.getSingleplayerServer();
        if (server == null) return false;
        try {
            return server.submit(() -> {
                ServerLevel lvl = server.overworld();
                if (mc.player != null) {
                    ServerLevel own = server.getLevel(mc.player.level().dimension());
                    if (own != null) lvl = own;
                }
                for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
                    for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                        for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                            if (lvl.getBlockState(new BlockPos(x, y, z)).is(forbidden)) {
                                return false;
                            }
                        }
                    }
                }
                return true;
            }).get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.out.println("[testbridge] server block check failed: " + e);
            return false;
        }
    }

    private static boolean sameBoxes(List<int[]> a, List<int[]> b) {
        if (a == null || b == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!java.util.Arrays.equals(a.get(i), b.get(i))) return false;
        }
        return true;
    }

    /** Opens the world, or creates it first: a flat creative world with cheats, no structures. */
    private static String openWorld(Minecraft mc, String name, long seed, boolean normal) {
        if (mc.level != null) return err("already in a world");
        if (mc.getLevelSource().levelExists(name)) {
            mc.createWorldOpenFlows().openWorld(name, () -> mc.setScreen(new TitleScreen()));
            return ok("\"created\":false");
        }
        LevelSettings settings = new LevelSettings(name, GameType.CREATIVE,
                new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(seed, false, false),
                normal ? WorldPresets::createNormalWorldDimensions : WorldPresets::createFlatWorldDimensions, new TitleScreen());
        return ok("\"created\":true");
    }

    // ---------------------------------------------------------------- json

    private static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }
}
