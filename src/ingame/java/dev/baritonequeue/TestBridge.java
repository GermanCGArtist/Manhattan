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
 * Commands: ping | state | chat &lt;text or /command&gt; | chatlog [from] | block x y z | count item | entities type radius
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
