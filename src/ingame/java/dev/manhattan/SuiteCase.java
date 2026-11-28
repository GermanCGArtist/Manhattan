package dev.manhattan;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * One generated case of the test suite (docs/TEST_SUITE.md, tests/suite/). {@code -Dmanhattan.suite.case=<file>}
 * names a JSON file written by tests/suite/generate.py:
 *
 * <pre>
 * {"id": "clear_box/1a2b3c", "limit_s": 240, "max_deaths": 0,
 *  "setup": ["fill ... air", "give @p diamond_pickaxe", ...],      // run on the server, in order
 *  "script": ["timeout(200)", "disable()", "select(...)", "clear_area()"],
 *  "checks": [{"type": "blocks", "from": [x,y,z], "to": [x,y,z], "is": ["minecraft:air"]}, ...]}
 * </pre>
 *
 * The test bridge runs the setup, starts the script, and when the script ends (or {@code limit_s} passes) checks the
 * world against {@code checks} on the server thread. One line goes to the log:
 * {@code SUITE_RESULT id=<id> pass=<bool> script_pass=<bool> checks=<ok|failed> elapsed_ms=... reason=<text>}.
 */
final class SuiteCase {
    static final String PATH = System.getProperty("manhattan.suite.case");

    private static JsonObject spec;
    private static volatile boolean setupDone;
    private static volatile boolean setupFailed;
    private static String setupError = "";

    private SuiteCase() {}

    static boolean active() {
        return PATH != null && !PATH.isBlank();
    }

    private static JsonObject spec() {
        if (spec == null) {
            try {
                spec = JsonParser.parseString(Files.readString(Path.of(PATH), StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (Exception e) {
                System.out.println("[suite] cannot read case " + PATH + ": " + e);
                spec = new JsonObject();
            }
        }
        return spec;
    }

    static String id() {
        JsonElement e = spec().get("id");
        return e == null ? "unknown" : e.getAsString();
    }

    static int limitTicks() {
        JsonElement e = spec().get("limit_s");
        return 20 * (e == null ? 360 : e.getAsInt());
    }

    static List<String> script() {
        List<String> out = new ArrayList<>();
        JsonElement e = spec().get("script");
        if (e != null) for (JsonElement l : e.getAsJsonArray()) out.add(l.getAsString());
        return out.isEmpty() ? null : out;
    }

    /** Runs the setup commands on the server thread as the server (use @p for the player). */
    static void setup(Minecraft mc) {
        setupDone = false;
        setupFailed = false;
        MinecraftServer s = mc.getSingleplayerServer();
        if (s == null) {
            setupFailed = true;
            setupError = "no integrated server";
            setupDone = true;
            return;
        }
        JsonElement e = spec().get("setup");
        List<String> cmds = new ArrayList<>();
        if (e != null) for (JsonElement l : e.getAsJsonArray()) cmds.add(l.getAsString());
        s.execute(() -> {
            try {
                var src = s.createCommandSourceStack();
                for (String c : cmds) {
                    if (c.startsWith("suite:surface ")) {
                        surface(s, c.substring(14).trim().split("\s+"));
                        continue;
                    }
                    s.getCommands().performPrefixedCommand(src, c.startsWith("/") ? c.substring(1) : c);
                }
            } catch (Throwable t) {
                setupFailed = true;
                setupError = String.valueOf(t);
            }
            setupDone = true;
        });
    }

    /**
     * "suite:surface X Z [R [D]]": puts the player on dry ground (with no fluid within D blocks, when given) at the surface near X Z (searching outwards up to 256
     * blocks) and force-loads R blocks around that spot. Replaces spreadplayers, which gives up over oceans and
     * rivers and leaves the player where it was. Checks with "rel": true are relative to where the player ends up.
     */
    private static void surface(MinecraftServer s, String[] a) {
        int x0 = Integer.parseInt(a[0]), z0 = Integer.parseInt(a[1]);
        ServerLevel level = s.overworld();
        ServerPlayer p = s.getPlayerList().getPlayers().isEmpty() ? null : s.getPlayerList().getPlayers().get(0);
        if (p == null) throw new IllegalStateException("suite:surface: no player");
        int dry = a.length > 3 ? Integer.parseInt(a[3]) : 0;
        BlockPos best = null;
        for (int r = 0; r <= 256 && best == null; r += 8) {
            for (int dx = -r; dx <= r && best == null; dx += 8) {
                for (int dz = -r; dz <= r && best == null; dz += 8) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = x0 + dx, z = z0 + dz;
                    // generate the chunk first: on a chunk that is not there yet the heightmap answers the world's bottom
                    level.getChunk(x >> 4, z >> 4);
                    int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    if (y <= level.getMinY() + 1) continue;
                    BlockPos ground = new BlockPos(x, y - 1, z);
                    if (level.getFluidState(ground).isEmpty() && level.getFluidState(ground.above()).isEmpty()
                            && (dry <= 0 || dryAround(level, ground, dry))) best = ground.above();
                }
            }
        }
        if (best == null) throw new IllegalStateException("suite:surface: no dry ground within 256 blocks of " + x0 + " " + z0);
        p.teleportTo(level, best.getX() + 0.5, best.getY(), best.getZ() + 0.5, java.util.Set.of(), p.getYRot(), p.getXRot(), true);
        if (a.length > 2) {
            int r = Integer.parseInt(a[2]);
            s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), String.format("forceload add %d %d %d %d",
                    best.getX() - r, best.getZ() - r, best.getX() + r, best.getZ() + r));
        }
    }

    /** No water or lava within d blocks sideways of the spot, from 6 below to 2 above it. */
    private static boolean dryAround(ServerLevel level, BlockPos at, int d) {
        for (int dx = -d; dx <= d; dx++)
            for (int dz = -d; dz <= d; dz++)
                for (int dy = -6; dy <= 2; dy++)
                    if (!level.getFluidState(at.offset(dx, dy, dz)).isEmpty()) return false;
        return true;
    }

    static boolean synced() {
        return setupDone;
    }

    static void failSetup(String why) {
        setupFailed = true;
        setupError = why;
    }

    static boolean setupFailed() {
        return setupFailed;
    }

    /** Checks the world on the server thread and logs SUITE_RESULT. */
    static void finish(Minecraft mc, String why) {
        String runnerLine = dev.midtown.api.BenchmarkLogger.lastLine;
        boolean scriptPass = runnerLine != null && runnerLine.contains(" pass=true");
        String reason;
        MinecraftServer s = mc.getSingleplayerServer();
        JsonElement msy = spec().get("min_start_y");
        if (!setupFailed && msy != null && !msy.isJsonNull() && start.getY() < msy.getAsInt()) {
            // e.g. spreadplayers found no spot and left the player at the bootstrap position underground
            setupFailed = true;
            setupError = "the player started at " + start.toShortString() + ", below y=" + msy.getAsInt();
        }
        if (setupFailed) {
            reason = "setup failed: " + setupError;
        } else if (s == null) {
            reason = "no integrated server";
        } else {
            try {
                // let the last block-place / break packets reach the server before looking at its world
                Thread.sleep(1500);
                reason = s.submit(() -> check(s)).get(20, TimeUnit.SECONDS);
            } catch (Exception e) {
                reason = "check failed: " + e;
            }
        }
        int deaths = intField(runnerLine, "deaths");
        JsonElement md = spec().get("max_deaths");
        int maxDeaths = md == null ? 0 : md.getAsInt();
        if (reason == null && deaths > maxDeaths) reason = "died " + deaths + " time(s)";
        if (why != null) reason = why + (reason == null ? "" : "; " + reason);
        if (reason == null && !scriptPass) reason = runnerLine == null ? "script never finished" : "script did not pass";
        boolean pass = reason == null && scriptPass;
        System.out.println("SUITE_RESULT id=" + id() + " pass=" + pass + " script_pass=" + scriptPass
                + " checks=" + (reason == null ? "ok" : "failed")
                + " elapsed_ms=" + intField(runnerLine, "elapsed_ms")
                + " blocks_moved=" + intField(runnerLine, "blocks_moved")
                + " stuck_count=" + intField(runnerLine, "stuck_count")
                + " interrupts_handled=" + intField(runnerLine, "interrupts_handled")
                + " deaths=" + deaths
                + " reason=" + (reason == null ? "-" : reason.replace('\n', ' ')));
    }

    private static int intField(String line, String key) {
        if (line == null) return -1;
        int i = line.indexOf(" " + key + "=");
        if (i < 0) return -1;
        int j = i + key.length() + 2, k = j;
        while (k < line.length() && Character.isDigit(line.charAt(k))) k++;
        try {
            return Integer.parseInt(line.substring(j, k));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ---- checks (server thread) ----

    /** null when every check holds, else why the first one failed. */
    private static String check(MinecraftServer s) {
        ServerLevel level = s.overworld();
        ServerPlayer p = s.getPlayerList().getPlayers().isEmpty() ? null : s.getPlayerList().getPlayers().get(0);
        JsonElement ce = spec().get("checks");
        if (ce == null) return null;
        for (JsonElement el : ce.getAsJsonArray()) {
            JsonObject c = el.getAsJsonObject();
            String type = c.get("type").getAsString();
            String r = switch (type) {
                case "blocks" -> blocks(level, c);
                case "dry" -> dry(level, c);
                case "block_list" -> blockList(level, c);
                case "player_near" -> playerNear(p, c);
                case "alive" -> p == null || !p.isAlive() ? "player is dead" : null;
                case "item_count" -> itemCount(p, c);
                case "container" -> container(level, c);
                default -> "unknown check " + type;
            };
            if (r != null) {
                String label = c.has("label") ? c.get("label").getAsString() + ": " : "";
                return label + r;
            }
        }
        return null;
    }

    private static BlockPos pos(JsonArray a) {
        return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
    }

    /** Where the player stood when the script started; checks with "rel": true are relative to it. */
    private static volatile BlockPos start = BlockPos.ZERO;

    static void markStart(Minecraft mc) {
        if (mc.player != null) {
            start = mc.player.blockPosition();
            System.out.println("[Suite] start at " + start.toShortString() + ", health " + mc.player.getHealth()
                    + ", food " + mc.player.getFoodData().getFoodLevel());
        }
    }

    private static BlockPos pos(JsonObject c, String key) {
        BlockPos p = pos(c.getAsJsonArray(key));
        return c.has("rel") && c.get("rel").getAsBoolean() ? p.offset(start) : p;
    }

    private static Set<String> ids(JsonObject c, String key) {
        Set<String> out = new HashSet<>();
        JsonElement e = c.get(key);
        if (e == null) return out;
        if (e.isJsonArray()) for (JsonElement x : e.getAsJsonArray()) out.add(norm(x.getAsString()));
        else out.add(norm(e.getAsString()));
        return out;
    }

    private static String norm(String id) {
        return id.contains(":") ? id : "minecraft:" + id;
    }

    private static String id(BlockState st) {
        return BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
    }

    /** Every cell of the box is one of "is" (or none of "not"); "allow_wet_adjacent" skips cells touching water. */
    private static String blocks(ServerLevel level, JsonObject c) {
        BlockPos a = pos(c, "from"), b = pos(c, "to");
        Set<String> is = ids(c, "is"), not = ids(c, "not");
        boolean skipWet = c.has("allow_wet_adjacent") && c.get("allow_wet_adjacent").getAsBoolean();
        int bad = 0, total = 0;
        String first = null;
        for (BlockPos q : BlockPos.betweenClosed(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()))) {
            total++;
            BlockState st = level.getBlockState(q);
            String id = id(st);
            boolean ok = (is.isEmpty() || is.contains(id)) && !not.contains(id);
            // the fluid itself and the cells around it are the seal Manhattan leaves on purpose
            if (!ok && skipWet && (touchesFluid(level, q) || !level.getFluidState(q).isEmpty())) ok = true;
            if (!ok) {
                bad++;
                if (first == null) first = q.getX() + "," + q.getY() + "," + q.getZ() + " " + id + clientSees(q);
            }
        }
        JsonElement tol = c.get("tolerance");
        int allowed = tol == null ? 0 : tol.getAsInt();
        return bad > allowed ? bad + "/" + total + " cells wrong (first " + first + ")" : null;
    }

    /** What the client's world holds at q, when it differs from the server's (a ghost block the bot believes in). */
    private static String clientSees(BlockPos q) {
        var cl = Minecraft.getInstance().level;
        if (cl == null) return "";
        String c = id(cl.getBlockState(q));
        return ", client sees " + c;
    }

    private static boolean touchesFluid(ServerLevel level, BlockPos q) {
        for (var d : net.minecraft.core.Direction.values()) {
            if (!level.getFluidState(q.relative(d)).isEmpty()) return true;
        }
        return false;
    }

    /** No fluid anywhere in the box. */
    private static String dry(ServerLevel level, JsonObject c) {
        BlockPos a = pos(c, "from"), b = pos(c, "to");
        int wet = 0;
        String first = null;
        for (BlockPos q : BlockPos.betweenClosed(a, b)) {
            if (!level.getFluidState(q).isEmpty()) {
                wet++;
                if (first == null) first = q.getX() + "," + q.getY() + "," + q.getZ();
            }
        }
        return wet > 0 ? wet + " wet cells (first " + first + ")" : null;
    }

    /** "cells": [[x,y,z,"id"], ...] must all match. */
    private static String blockList(ServerLevel level, JsonObject c) {
        int bad = 0, total = 0;
        String first = null;
        for (JsonElement e : c.getAsJsonArray("cells")) {
            JsonArray a = e.getAsJsonArray();
            total++;
            String want = norm(a.get(3).getAsString());
            String got = id(level.getBlockState(new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt())));
            if (!want.equals(got)) {
                bad++;
                if (first == null) first = a.get(0) + "," + a.get(1) + "," + a.get(2) + " want " + want + " got " + got
                        + clientSees(new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()));
            }
        }
        JsonElement tol = c.get("tolerance");
        int allowed = tol == null ? 0 : tol.getAsInt();
        return bad > allowed ? bad + "/" + total + " cells wrong (first " + first + ")" : null;
    }

    private static String playerNear(ServerPlayer p, JsonObject c) {
        if (p == null) return "no player";
        BlockPos a = pos(c, "pos");
        double r = c.has("radius") ? c.get("radius").getAsDouble() : 1.5;
        double dx = p.getX() - (a.getX() + 0.5), dz = p.getZ() - (a.getZ() + 0.5);
        double dy = p.getY() - a.getY();
        boolean ignoreY = c.has("ignore_y") && c.get("ignore_y").getAsBoolean();
        double d = Math.sqrt(dx * dx + dz * dz + (ignoreY ? 0 : dy * dy));
        return d > r ? String.format("player %.1f blocks from target (at %.1f %.1f %.1f)", d, p.getX(), p.getY(), p.getZ()) : null;
    }

    private static String itemCount(ServerPlayer p, JsonObject c) {
        if (p == null) return "no player";
        String item = norm(c.get("item").getAsString());
        int n = 0;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (!st.isEmpty() && BuiltInRegistries.ITEM.getKey(st.getItem()).toString().equals(item)) n += st.getCount();
        }
        return countCheck(item + " in inventory", n, c);
    }

    private static String container(ServerLevel level, JsonObject c) {
        BlockEntity be = level.getBlockEntity(pos(c, "pos"));
        if (!(be instanceof Container box)) return "no container at " + c.get("pos");
        String item = norm(c.get("item").getAsString());
        int n = 0;
        for (int i = 0; i < box.getContainerSize(); i++) {
            ItemStack st = box.getItem(i);
            if (!st.isEmpty() && BuiltInRegistries.ITEM.getKey(st.getItem()).toString().equals(item)) n += st.getCount();
        }
        return countCheck(item + " in container", n, c);
    }

    private static String countCheck(String what, int n, JsonObject c) {
        if (c.has("min") && n < c.get("min").getAsInt()) return what + " " + n + " < " + c.get("min").getAsInt();
        if (c.has("max") && n > c.get("max").getAsInt()) return what + " " + n + " > " + c.get("max").getAsInt();
        return null;
    }
}
