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
            case "editor": return onGame(() -> { mc.setScreen(new QueueScreen()); return ok(""); });
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
                case "toggle" -> ImGuiManager.toggleSpike();
                case "open" -> ImGuiManager.setSpikeOpen(true);
                case "close" -> ImGuiManager.setSpikeOpen(false);
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
                case "fullscreen" -> {
                    if (a.length > 1) ImGuiManager.setFullscreenGame(Boolean.parseBoolean(a[1]));
                    else ImGuiManager.toggleFullscreenGame();
                }
                case "resetlayout" -> ImGuiManager.resetLayout();
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
        float[] wr = ImGuiManager.windowRect();
        float[] sr = ImGuiManager.stopButtonRect();
        float[] vr = ImGuiManager.viewportRect();
        float[] fr = ImGuiManager.fullscreenButtonRect();
        float[] rr = ImGuiManager.resetButtonRect();
        ProgressModel.Snapshot snap = ProgressModel.snapshot();
        long segDone = snap.segments().stream().filter(s -> s.state() == ProgressModel.State.DONE).count();
        long segRun = snap.segments().stream().filter(s -> s.state() == ProgressModel.State.RUNNING).count();
        long segPend = snap.segments().stream().filter(s -> s.state() == ProgressModel.State.PENDING).count();
        return ok("\"initialized\":" + ImGuiManager.isInitialized()
                + ",\"spikeOpen\":" + ImGuiManager.isSpikeOpen()
                + ",\"focused\":" + ImGuiManager.isFocused()
                + ",\"gameFocused\":" + ImGuiManager.isGameFocused()
                + ",\"fullscreenGame\":" + ImGuiManager.isFullscreenGame()
                + ",\"dockedActive\":" + ImGuiManager.isDockedViewportActive()
                + ",\"layoutLabel\":" + q(ImGuiManager.layoutLabel())
                + ",\"layoutResets\":" + ImGuiManager.layoutResetCount()
                + ",\"framesRendered\":" + ImGuiManager.framesRendered()
                + ",\"displayW\":" + ImGuiManager.displayWidth()
                + ",\"displayH\":" + ImGuiManager.displayHeight()
                + ",\"guiScale\":" + mc.getWindow().getGuiScale()
                + ",\"guiScaledW\":" + mc.getWindow().getGuiScaledWidth()
                + ",\"guiScaledH\":" + mc.getWindow().getGuiScaledHeight()
                + ",\"vpX\":" + vr[0] + ",\"vpY\":" + vr[1] + ",\"vpW\":" + vr[2] + ",\"vpH\":" + vr[3]
                + ",\"fsX\":" + fr[0] + ",\"fsY\":" + fr[1] + ",\"fsW\":" + fr[2] + ",\"fsH\":" + fr[3]
                + ",\"resetX\":" + rr[0] + ",\"resetY\":" + rr[1] + ",\"resetW\":" + rr[2] + ",\"resetH\":" + rr[3]
                + ",\"winX\":" + wr[0] + ",\"winY\":" + wr[1] + ",\"winW\":" + wr[2] + ",\"winH\":" + wr[3]
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
                + ",\"uiMode\":" + q(Config.cfg.uiMode)
                + ",\"note\":" + q(ImGuiManager.noteText()));
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
                    DockedPanels.setCurrentScriptText(content);
                }
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
