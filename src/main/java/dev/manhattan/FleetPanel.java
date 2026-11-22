package dev.manhattan;

import dev.manhattan.fleet.FleetClient;
import dev.manhattan.fleet.FleetMode;
import dev.manhattan.fleet.FleetProtocol;
import imgui.ImGui;
import imgui.type.ImString;

import java.util.ArrayList;
import java.util.List;

import static dev.manhattan.Config.cfg;
import static dev.manhattan.UiTheme.*;

/**
 * Multi-Bot Fleet drawer rendered inside {@link Shell} when {@link Shell.Drawer#FLEET} is open.
 * Allows switching client mode ({@code LOCAL}/{@code EDITOR}/{@code WORKER}/{@code BOTH}),
 * configuring worker pool tags, inspecting connected bots, and controlling server-side fleet jobs.
 */
final class FleetPanel {
    private FleetPanel() {}

    private static final ImString tagsInput = new ImString("", 128);
    private static boolean tagsLoaded = false;

    static void render() {
        float s = ImGuiManager.getUiScale();
        FleetClient fc = FleetClient.instance();

        if (!tagsLoaded) {
            tagsLoaded = true;
            tagsInput.set(String.join(", ", cfg.fleetTags));
        }

        // 1. Connection Status
        if (fc.isConnected()) {
            UiTheme.textColored(OK, "Connected to Fleet Server"
                    + (fc.worldId().isBlank() ? "" : " (" + fc.worldId() + ")"));
            if (fc.mode().canEdit() && !fc.isEditorAllowed()) {
                UiTheme.textColored(WARN, "Not authorized as Editor on this server (need op >= 2 or whitelist).");
            }
        } else {
            UiTheme.textColored(DIM, "Not connected to a Fleet Server (solo/local server or mod not installed).");
        }

        if (!fc.lastNotice().isBlank()) {
            UiTheme.textColored(fc.lastNoticeError() ? ERR : ACCENT, fc.lastNotice());
        }

        // World Overlay quick controls (for both Editors and Worker bots)
        imgui.type.ImBoolean overlayOn = new imgui.type.ImBoolean(cfg.showWorldOverlay);
        if (ImGui.checkbox("3D Overlay##fleetOverlay", overlayOn)) {
            cfg.showWorldOverlay = overlayOn.get();
            Config.save();
        }
        ImGui.sameLine(0f, 8f * s);
        imgui.type.ImBoolean probOnly = new imgui.type.ImBoolean(cfg.showOverlayProblemsOnly);
        if (ImGui.checkbox("Problems Only##fleetProbOnly", probOnly)) {
            cfg.showOverlayProblemsOnly = probOnly.get();
            Config.save();
        }
        ImGui.sameLine(0f, 8f * s);
        if (Widgets.button("Check Base##fleetCheckBase", 0)) {
            WorldOverlay.checkReport().forEach(Util::msg);
        }
        ProjectPanel.renderOverlayElementsSection("fleet", s);

        ImGui.separator();

        // 2. Client Mode Selector
        Widgets.label("Client Mode");
        FleetMode current = fc.mode();
        for (FleetMode m : FleetMode.values()) {
            boolean selected = (m == current);
            if (ImGui.radioButton(m.label + "##fleetMode_" + m.name(), selected)) {
                fc.setMode(m);
                cfg.fleetMode = m.name();
                Config.save();
            }
            if (m != FleetMode.BOTH) {
                ImGui.sameLine();
            }
        }
        UiTheme.textColored(DIM, switch (current) {
            case LOCAL -> "Solo mode: runs local scripts/projects only.";
            case EDITOR -> "Editor only: submits/monitors jobs; bots keep working even after you log off.";
            case WORKER -> "Worker: claims chunks from the Fleet Server automatically.";
            case BOTH -> "Editor + Worker: can submit jobs and also claim chunks.";
        });

        // 3. Worker Tags / Pool
        if (current.canWork()) {
            ImGui.spacing();
            Widgets.label("Worker Pool Tags (comma-separated)");
            ImGui.setNextItemWidth(220f * s);
            ImGui.inputText("##fleetTags", tagsInput);
            ImGui.sameLine();
            if (Widgets.button("Apply##fleetTagsApply", 0)) {
                List<String> parsed = new ArrayList<>();
                for (String part : tagsInput.get().split(",")) {
                    String trimmed = part.trim();
                    if (!trimmed.isBlank()) parsed.add(trimmed);
                }
                fc.setTags(parsed);
                cfg.fleetTags = parsed;
                Config.save();
            }
        }

        if (!fc.isConnected()) {
            return;
        }

        FleetProtocol.FleetSnapshot snap = fc.snapshot();
        if (snap == null) return;

        // 4. Connected Bots Roster
        ImGui.separator();
        String sleepInfo = snap.designatedSleeperName != null && !snap.designatedSleeperName.isBlank()
                ? "   |   Sleeper: " + snap.designatedSleeperName
                : "";
        Widgets.label("Fleet Roster (" + snap.bots.size() + ")" + sleepInfo);

        if (snap.bots.isEmpty()) {
            UiTheme.textColored(DIM, "No fleet clients registered yet.");
        } else {
            for (FleetProtocol.BotInfo bot : snap.bots) {
                int col = !bot.online ? DIM : (bot.inDanger ? ERR : OK);
                String tagStr = (bot.tags != null && !bot.tags.isEmpty()) ? " [" + String.join(",", bot.tags) + "]" : "";
                String workStr = bot.activeJobId != null ? " -> " + bot.activeJobId + " (" + bot.activeChunkKey + ")" : " (" + bot.phase + ")";
                UiTheme.textColored(col, (bot.online ? "[ON] " : "[OFF] ") + bot.name + " [" + bot.mode + "]" + tagStr + workStr);
                if (bot.alertSummary != null && !bot.alertSummary.isBlank()) {
                    UiTheme.textColored(WARN, "   Alert: " + bot.alertSummary);
                }
            }
        }

        // 5. Server Job Queue
        ImGui.separator();
        Widgets.label("Server Job Queue (" + snap.jobs.size() + ")");
        if (snap.jobs.isEmpty()) {
            UiTheme.textColored(DIM, "No jobs queued on the server. Open Projects -> Submit to Fleet.");
        } else {
            for (FleetProtocol.JobSummary job : snap.jobs) {
                ImGui.pushID(job.jobId);
                int statusCol = "ACTIVE".equals(job.status) ? OK
                        : "PAUSED".equals(job.status) ? WARN
                        : "DONE".equals(job.status) ? ACCENT : DIM;
                UiTheme.textColored(statusCol, job.jobId + " · " + job.projectName + " [" + job.status + "]");
                float frac = job.totalChunks > 0 ? (float) job.doneChunks / job.totalChunks : 1.0f;
                ImGui.progressBar(frac, -1f, 14f * s,
                        job.doneChunks + "/" + job.totalChunks + " chunks (" + job.claimedChunks + " active)");
                if (job.activeWorkers != null && !job.activeWorkers.isEmpty()) {
                    UiTheme.textColored(DIM, "Workers: " + String.join(", ", job.activeWorkers));
                }
                if (fc.isEditorAllowed()) {
                    if ("ACTIVE".equals(job.status)) {
                        if (Widgets.button("Pause", 0)) fc.controlJob(job.jobId, "pause");
                        ImGui.sameLine();
                    } else if ("PAUSED".equals(job.status)) {
                        if (Widgets.button("Resume", 0)) fc.controlJob(job.jobId, "resume");
                        ImGui.sameLine();
                    }
                    if (!"CANCELLED".equals(job.status) && !"DONE".equals(job.status)) {
                        if (Widgets.button("Cancel", 2)) fc.controlJob(job.jobId, "cancel");
                    }
                }
                ImGui.spacing();
                ImGui.popID();
            }
        }
    }
}
