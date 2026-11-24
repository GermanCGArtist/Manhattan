package dev.manhattan;

import dev.manhattan.fleet.FleetClient;
import dev.manhattan.fleet.FleetMode;
import dev.manhattan.fleet.FleetProtocol;
import dev.manhattan.project.Project;
import dev.manhattan.project.ProjectTask;
import dev.midtown.api.BenchmarkLogger;
import dev.midtown.api.EngineSelector;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client half of the Deming fleet scenarios ({@code -Dmanhattan.benchmark=fleet_*}). The server half is
 * {@code FleetBenchBridge} in manhattan-fleet-server; Deming connects every game to the dedicated server.
 *
 * <ul>
 *   <li>Every game becomes a fleet worker ({@code -Dmanhattan.fleetRole=editor} also edits: mode BOTH).</li>
 *   <li>The editor waits for the server's {@code [FleetBench] READY ... box=...}, saves the box as an area, builds a
 *       one-task CLEAR_AREA project and submits it exactly like the Fleet panel's Submit button.</li>
 *   <li>All games wait for {@code [FleetBench] RESULT scenario=... pass=...} and print their
 *       {@code BENCHMARK_RESULT} line from it, then quit.</li>
 * </ul>
 */
final class FleetBench {
    private static final Pattern READY = Pattern.compile("\\[FleetBench] READY scenario=(\\S+) bots=(\\d+) box=(-?\\d+),(-?\\d+),(-?\\d+),(-?\\d+),(-?\\d+),(-?\\d+)");
    private static final Pattern RESULT = Pattern.compile("\\[FleetBench] RESULT scenario=(\\S+) pass=(true|false)(.*)");

    /** Server messages, from Fabric's message event (the chat-component capture sees nothing on a server in 26.3). */
    private static final java.util.concurrent.ConcurrentLinkedQueue<String> incoming = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static boolean listening;

    private final String scenario;
    private final boolean editor;
    private final long startMs = System.currentTimeMillis();
    private int ticks;
    private int chatSeen;
    private boolean modeSet;
    private boolean submitted;
    private boolean emitted;
    private int quitTicks = -1;

    FleetBench(String scenario) {
        this.scenario = scenario;
        this.editor = "editor".equalsIgnoreCase(System.getProperty("manhattan.fleetRole", "worker"));
        System.out.println("[fleetbench] scenario=" + scenario + " role=" + (editor ? "editor" : "worker"));
        if (!listening) {
            listening = true;
            net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
                if (!overlay && message != null) incoming.add(message.getString());
            });
        }
    }

    void tick(Minecraft mc, List<String> chat) {
        ticks++;
        if (quitTicks >= 0) {
            if (--quitTicks <= 0) {
                if (mc.level != null) mc.disconnectWithSavingScreen();
                mc.stop();
            }
            return;
        }
        if (System.currentTimeMillis() - startMs > 40L * 60_000L) {
            emit(false, "client timeout");
            return;
        }
        if (mc.level == null || mc.player == null) return; // connecting (or reconnecting after a kick)
        if (mc.gui.screen() != null && ticks % 20 == 0) mc.gui.setScreen(null);

        FleetClient fc = FleetClient.instance();
        if (!modeSet || (ticks % 100 == 0 && fc.mode() != wantedMode())) {
            fc.setMode(wantedMode());
            modeSet = true;
        }

        List<String> lines = new ArrayList<>();
        for (String l; (l = incoming.poll()) != null; ) lines.add(l);
        synchronized (chat) { // also whatever the test bridge's own capture found (singleplayer)
            lines.addAll(chat.subList(Math.min(chatSeen, chat.size()), chat.size()));
            chatSeen = chat.size();
        }
        for (String line : lines) {
            Matcher r = RESULT.matcher(line);
            if (r.find() && r.group(1).equals(scenario)) {
                emit(Boolean.parseBoolean(r.group(2)), r.group(3).trim());
                return;
            }
            Matcher m = READY.matcher(line);
            if (editor && !submitted && m.find() && m.group(1).equals(scenario)) {
                int[] box = new int[6];
                for (int i = 0; i < 6; i++) box[i] = Integer.parseInt(m.group(3 + i));
                submit(mc, fc, Integer.parseInt(m.group(2)), box);
            }
        }
        if (ticks % 600 == 0) {
            System.out.println("[fleetbench] " + (editor ? "editor" : "worker") + " connected=" + fc.isConnected()
                    + " mode=" + fc.mode() + " grant=" + (fc.activeGrant() == null ? "-" : fc.activeGrant().chunkKey)
                    + " worker=" + dev.manhattan.fleet.FleetWorker.instance().describe()
                    + " pendingReports=" + fc.pendingReports() + " refused=" + fc.refusedReports());
        }
    }

    private FleetMode wantedMode() {
        return editor ? FleetMode.BOTH : FleetMode.WORKER;
    }

    private void submit(Minecraft mc, FleetClient fc, int bots, int[] b) {
        if (!fc.isEditorAllowed()) {
            System.out.println("[fleetbench] READY seen but not an allowed editor yet; asking again");
            fc.setMode(FleetMode.BOTH); // a fresh hello makes the server re-check the operator status
            return;
        }
        Areas.setSingleBox(new BlockPos(b[0], b[1], b[2]), new BlockPos(b[3], b[4], b[5]));
        Areas.save("fleet_box");
        Areas.clearSelection();
        Project p = new Project("fleet_bench_" + scenario, "Fleet bench " + scenario, "manhattan-benchmark");
        p.ensureInitialized();
        p.chunkSize = 16;
        ProjectTask t = new ProjectTask("t_clear", "Clear the box", ProjectTask.Kind.CLEAR_AREA);
        t.areaName = "fleet_box";
        p.tasks.add(t);

        List<String> diags = new ArrayList<>();
        List<FleetProtocol.ChunkSpec> chunks = QueueMod.collectProjectChunkSpecs(p, diags);
        if (chunks.isEmpty()) {
            System.out.println("[fleetbench] project produced no chunks: " + diags);
            emit(false, "no chunks: " + diags);
            return;
        }
        FleetProtocol.AssignmentPolicy pol = new FleetProtocol.AssignmentPolicy();
        pol.mode = FleetProtocol.AssignmentMode.AUTO_POOL;
        pol.maxBots = bots;
        pol.maxDistance = 0;
        String dim = mc.level.dimension().identifier().toString();
        Project prepared = QueueMod.prepareProjectForFleetSubmit(p);
        if (fc.submitProject(prepared, dim, pol, chunks)) {
            submitted = true;
            System.out.println("[fleetbench] submitted " + chunks.size() + " chunk(s) for " + bots + " bot(s)");
        } else {
            System.out.println("[fleetbench] submit refused: " + fc.lastNotice());
        }
    }

    private void emit(boolean pass, String detail) {
        if (emitted) return;
        emitted = true;
        System.out.println("[fleetbench] result pass=" + pass + " " + detail);
        BenchmarkLogger.emit(EngineSelector.selectedName(), scenario, pass,
                Math.max(0L, System.currentTimeMillis() - startMs), 0, 0, 0, 0);
        quitTicks = 100;
    }
}
