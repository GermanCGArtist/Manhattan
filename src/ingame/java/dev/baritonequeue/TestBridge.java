package dev.baritonequeue;

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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

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
 * tests/ingame/bridge.py is the client. It lives in dev.baritonequeue so it can start scripts and read the runner.
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
            case "world": return onGame(() -> openWorld(mc, a[0], a.length > 1 ? Long.parseLong(a[1]) : 20261109L));
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
                    openWorld(mc, "manhattan-benchmark", 20261109L);
                    benchState = BenchState.OPENING_WORLD;
                    benchTicks = 0;
                }
            }
            case OPENING_WORLD -> {
                if (mc.level != null && mc.player != null && mc.getSingleplayerServer() != null) {
                    mc.player.connection.sendCommand("tp @s 100 -60 100 0 0");
                    mc.player.connection.sendCommand("forceload add 90 90 190 110");
                    benchState = BenchState.WAIT_IN_WORLD;
                    benchTicks = 0;
                }
            }
            case WAIT_IN_WORLD -> {
                if (benchTicks >= 30) {
                    if (mc.screen != null) mc.setScreen(null);
                    setupBenchmarkScenario(mc, BENCHMARK.trim());
                    benchState = BenchState.SETUP;
                    benchTicks = 0;
                }
            }
            case SETUP -> {
                String scenario = BENCHMARK.trim();
                if (benchTicks >= 40 && (setupSynced(mc, scenario) || benchTicks >= 100)) {
                    List<String> script = benchmarkScript(scenario);
                    if (script == null) {
                        System.out.println("[testbridge] unknown benchmark scenario: " + BENCHMARK);
                        dev.midtown.api.BenchmarkLogger.emit(
                                dev.midtown.api.EngineSelector.selectedName(), BENCHMARK, false, 0, 0, 0, 0, 0);
                        benchState = BenchState.DONE;
                        benchTicks = 0;
                        return;
                    }
                    String err = QueueMod.runner.start(script);
                    if (err != null) {
                        System.out.println("[testbridge] benchmark script failed to start: " + err);
                        dev.midtown.api.BenchmarkLogger.emit(
                                dev.midtown.api.EngineSelector.selectedName(), BENCHMARK, false, 0, 0, 0, 0, 0);
                        benchState = BenchState.DONE;
                        benchTicks = 0;
                        return;
                    }
                    benchState = BenchState.RUNNING;
                    benchTicks = 0;
                }
            }
            case RUNNING -> {
                if (!QueueMod.runner.running()) {
                    // Runner.emitBenchmark(...) already emitted BENCHMARK_RESULT on script completion/failure
                    benchState = BenchState.DONE;
                    benchTicks = 0;
                } else if (benchTicks >= 20 * 210) {
                    QueueMod.runner.halt("Benchmark timeout");
                    benchState = BenchState.DONE;
                    benchTicks = 0;
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
        List<String> cmds = new ArrayList<>();
        cmds.add("gamemode survival");
        cmds.add("effect clear @s");
        cmds.add("clear @s");
        cmds.add("time set noon");
        cmds.add("weather clear");
        cmds.add("kill @e[type=!player]");
        cmds.add("fill 90 -63 90 190 -62 110 dirt");
        cmds.add("fill 90 -61 90 190 -61 110 grass_block");
        cmds.add("fill 90 -60 90 190 -48 110 air");
        switch (scenario) {
            case "nav_flat_obstacle" -> {
                cmds.add("fill 115 -60 95 115 -59 105 stone");
                cmds.add("fill 130 -62 96 132 -61 104 air");
                cmds.add("fill 145 -61 96 145 -61 104 water");
                cmds.add("fill 160 -61 98 162 -61 102 lava");
            }
            case "nav_pillar_bridge" -> {
                cmds.add("fill 105 -63 96 124 -61 104 air");
                cmds.add("fill 128 -60 100 128 -55 100 stone");
                cmds.add("give @s cobblestone 128");
            }
            case "interrupt_mid_action" -> {
                cmds.add("fill 104 -60 99 108 -59 101 stone");
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
                cmds.add("fill 104 -60 98 107 -59 102 stone");
                cmds.add("setblock 98 -60 100 chest");
                cmds.add("data merge block 98 -60 100 {Items:[{Slot:0b,id:\"minecraft:bread\",count:32},{Slot:1b,id:\"minecraft:diamond_pickaxe\",count:1}]}");
                cmds.add("setblock 98 -60 102 red_bed");
                cmds.add("give @s diamond_pickaxe 1");
                cmds.add("give @s diamond_sword 1");
                cmds.add("give @s bread 16");
                cmds.add("summon minecraft:zombie 110 -60 100");
            }
            default -> { }
        }
        cmds.add("tp @s 100 -60 100 0 0");
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
            case "nav_pillar_bridge" ->
                    mc.level.getBlockState(new BlockPos(128, -55, 100)).is(net.minecraft.world.level.block.Blocks.STONE);
            case "interrupt_mid_action" ->
                    mc.level.getBlockState(new BlockPos(104, -60, 99)).is(net.minecraft.world.level.block.Blocks.STONE);
            case "schem_unsupported_reach" ->
                    mc.level.getBlockState(new BlockPos(104, -59, 99)).is(net.minecraft.world.level.block.Blocks.STONE);
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
            default -> null;
        };
    }

    /** Opens the world, or creates it first: a flat creative world with cheats, no structures. */
    private static String openWorld(Minecraft mc, String name, long seed) {
        if (mc.level != null) return err("already in a world");
        if (mc.getLevelSource().levelExists(name)) {
            mc.createWorldOpenFlows().openWorld(name, () -> mc.setScreen(new TitleScreen()));
            return ok("\"created\":false");
        }
        LevelSettings settings = new LevelSettings(name, GameType.CREATIVE,
                new LevelSettings.DifficultySettings(Difficulty.EASY, false, false), true, WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(seed, false, false),
                WorldPresets::createFlatWorldDimensions, new TitleScreen());
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
