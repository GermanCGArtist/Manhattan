package dev.baritonequeue;

import dev.baritonequeue.project.GameProjectRuntime;
import dev.baritonequeue.project.PriorityScheduler;
import dev.baritonequeue.project.Project;
import dev.baritonequeue.project.ProjectStore;
import dev.baritonequeue.project.ProjectTask;
import dev.baritonequeue.project.TaskQueue;
import imgui.ImGui;
import imgui.type.ImBoolean;
import imgui.type.ImDouble;
import imgui.type.ImInt;
import imgui.type.ImString;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.baritonequeue.UiTheme.*;

/**
 * Projects &amp; Task Queue drawer panel rendered inside {@link Shell} when {@link Shell.Drawer#PROJECTS} is open.
 */
final class ProjectPanel {
    private ProjectPanel() {}

    private static final Pattern TASK_ID_NUM = Pattern.compile("^t_(\\d+)$");

    private static final ImString newProjectInput = new ImString("", 64);
    private static String notice = "";
    private static boolean noticeError = false;

    // "+ Add Task" form state
    private static ProjectTask.Kind addKind = ProjectTask.Kind.CLEAR_AREA;
    private static final ImString addLabel = new ImString("", 64);
    private static final ImString addArea = new ImString("", 64);
    private static final ImString addPlacement = new ImString("", 64);
    private static final ImString addScript = new ImString("", 64);
    private static int addExpand = 0;
    private static final ImInt addLayers = new ImInt(3);
    private static final ImString addBlock = new ImString("dirt", 64);
    private static final ImInt addTreeZoneDiameter = new ImInt(0);
    private static final ImBoolean addDumpAfter = new ImBoolean(false);
    private static ProjectTask.PartialPolicy addPartial = ProjectTask.PartialPolicy.WARN_CONTINUE;
    private static ProjectTask.ErrorPolicy addError = ProjectTask.ErrorPolicy.STOP;

    // Base & Survival map entry inputs
    private static final ImString wantItemInput = new ImString("", 64);
    private static final ImInt wantCountInput = new ImInt(1);
    private static final ImString keepItemInput = new ImString("", 64);
    private static final ImInt keepCountInput = new ImInt(1);

    // Reusable buffers for numeric survival inputs
    private static final ImDouble numDoubleBuf = new ImDouble();
    private static final ImInt numIntBuf = new ImInt();

    static void render() {
        float s = ImGuiManager.getUiScale();
        GameProjectRuntime rt;
        try {
            rt = GameProjectRuntime.instance();
        } catch (Throwable t) {
            UiTheme.textColored(ERR, "Project runtime unavailable: " + t.getMessage());
            return;
        }
        if (rt == null) {
            UiTheme.textColored(DIM, "Project runtime not initialized.");
            return;
        }

        Project active = rt.activeProject();
        renderTopBar(rt, active, s);
        renderStatusBanner(rt, s);

        if (active == null) {
            UiTheme.textColored(DIM, "No active project. Create one above.");
            return;
        }
        active.ensureInitialized();

        ImGui.separator();
        if (ImGui.beginTabBar("##ProjectPanelTabs")) {
            if (ImGui.beginTabItem("Queue##ProjTabQueue")) {
                renderQueueTab(rt, active, s);
                ImGui.endTabItem();
            }
            if (ImGui.beginTabItem("Base & Survival##ProjTabBase")) {
                renderBaseAndSurvivalTab(rt, active, s);
                ImGui.endTabItem();
            }
            ImGui.endTabBar();
        }
    }

    // ------------------------------------------------------------------ Top Bar

    private static void renderTopBar(GameProjectRuntime rt, Project active, float s) {
        List<String> ids = rt.projectIds();
        String preview = active == null
                ? "(none)"
                : (active.name != null && !active.name.isBlank() && !active.name.equals(active.id)
                    ? active.name + " (" + active.id + ")"
                    : active.id);

        ImGui.setNextItemWidth(170f * s);
        if (ImGui.beginCombo("##ProjSelect", preview)) {
            if (ids.isEmpty()) {
                ImGui.textDisabled("No saved projects.");
            } else {
                for (String id : ids) {
                    boolean selected = active != null && id.equals(active.id);
                    if (ImGui.selectable(id + "##projsel_" + id, selected)) {
                        rt.selectProject(id);
                        setNotice("Selected project '" + id + "'.", false);
                    }
                }
            }
            ImGui.endCombo();
        }

        ImGui.sameLine(0f, 6f * s);
        ImGui.setNextItemWidth(130f * s);
        ImGui.inputTextWithHint("##ProjNewName", "New project...", newProjectInput);
        ImGui.sameLine(0f, 4f * s);
        if (Widgets.button("Create##ProjCreate", 0)) {
            String raw = newProjectInput.get().trim();
            if (raw.isEmpty()) {
                setNotice("Enter a project name first.", true);
            } else {
                String id = ProjectStore.sanitizeId(raw);
                Project created = rt.createProject(id, raw);
                newProjectInput.set("");
                setNotice("Created project '" + (created != null ? created.id : id) + "'.", false);
            }
        }

        ImGui.sameLine(0f, 6f * s);
        if (Widgets.button("Import Capture##ProjImportCapture", 0)) {
            String res = rt.importCaptureIntoActive();
            String msg = (res == null || res.isBlank()) ? "Imported capture sheet." : res;
            setNotice(msg, false);
            Util.msg(msg);
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Imports bed, tool/return/dump/scaffold/material chests and protected areas from Capture (K).");
        }

        ImGui.sameLine(0f, 8f * s);
        boolean running = rt.running();
        if (!running) {
            if (Widgets.button("Run Queue##ProjRunQueue", 1)) {
                String err = rt.startQueue();
                if (err != null && !err.isBlank()) {
                    setNotice(err, true);
                    Util.msg(err);
                } else {
                    setNotice("Project queue started.", false);
                }
            }
        } else {
            if (Widgets.button("Stop Queue##ProjStopQueue", 2)) {
                rt.stopQueue("Stopped from Projects panel.");
                setNotice("Project queue stopped.", false);
            }
            ImGui.sameLine(0f, 4f * s);
            if (Widgets.button("Skip##ProjSkipCurrent", 0)) {
                rt.skipCurrent();
                setNotice("Skipped current task.", false);
            }
        }
    }

    private static void renderStatusBanner(GameProjectRuntime rt, float s) {
        if (rt.running()) {
            PriorityScheduler.Telemetry t = rt.telemetry();
            if (t != null) {
                String lane = formatLane(t);
                String detail = (t.detail() != null && !t.detail().isBlank()) ? t.detail() : "";
                String elapsed = Estimate.duration(t.elapsedMillis() / 50L);
                UiTheme.textColored(ACCENT, lane + (detail.isEmpty() ? "" : "  " + detail)
                        + "   (" + t.doneTasks() + "/" + t.totalTasks() + " done, " + elapsed + ")");
                if (t.lastError() != null && !t.lastError().isBlank()) {
                    UiTheme.textColored(ERR, "Last error: " + Alerts.shorten(t.lastError(), 120));
                }
            }
        } else {
            PriorityScheduler.Telemetry t = rt.telemetry();
            if (t != null && t.lastError() != null && !t.lastError().isBlank()) {
                UiTheme.textColored(ERR, "Last error: " + Alerts.shorten(t.lastError(), 120));
            }
        }
        if (!notice.isEmpty()) {
            UiTheme.textColored(noticeError ? ERR : OK, Alerts.shorten(notice, 140));
        }
    }

    private static String formatLane(PriorityScheduler.Telemetry t) {
        if (t.lane() == null) return "[Project]";
        return switch (t.lane()) {
            case P0_EMERGENCY -> "[P0 Emergency]";
            case P1_COMBAT -> "[P1 Combat]";
            case P2_VITALS -> "[P2 Vitals]";
            case P3_LOGISTICS -> "[P3 Base Trip]";
            case P4_WORK -> {
                int step = t.taskIndex() >= 0 ? (t.taskIndex() + 1) : Math.min(Math.max(1, t.totalTasks()), t.doneTasks() + 1);
                yield "[P4 Task " + step + "/" + Math.max(1, t.totalTasks()) + "]";
            }
            case IDLE -> t.paused() ? "[Paused]" : "[Idle]";
        };
    }

    // ------------------------------------------------------------------ Queue Tab

    private static void renderQueueTab(GameProjectRuntime rt, Project active, float s) {
        List<ProjectTask> tasks = active.tasks;
        if (tasks.isEmpty()) {
            UiTheme.textColored(DIM, "No tasks queued. Use \"+ Add Task\" below or \"+ Queue\" from the Areas panel.");
        } else {
            for (int i = 0; i < tasks.size(); i++) {
                ProjectTask t = tasks.get(i);
                if (t == null) continue;
                t.ensureInitialized();
                String tid = t.id != null ? t.id : ("idx_" + i);
                TaskQueue.TaskLedger.State st = t.id != null ? rt.taskState(t.id) : TaskQueue.TaskLedger.State.PENDING;
                if (st == null) st = TaskQueue.TaskLedger.State.PENDING;

                ImGui.pushID("taskRow_" + i + "_" + tid);
                UiTheme.textColored(GUTTER, String.format("%2d.", i + 1));
                ImGui.sameLine(0f, 4f * s);

                ImBoolean en = new ImBoolean(t.enabled);
                if (ImGui.checkbox("##en", en)) {
                    t.enabled = en.get();
                    rt.saveActive();
                }
                if (ImGui.isItemHovered()) ImGui.setTooltip("Enable/disable task");

                ImGui.sameLine(0f, 6f * s);
                drawStateBadge(st);

                ImGui.sameLine(0f, 6f * s);
                String summary = taskSummary(t);
                int labelColor = !t.enabled ? DIM : (st == TaskQueue.TaskLedger.State.RUNNING ? ACCENT : FG);
                ImGui.beginGroup();
                UiTheme.textColored(labelColor, t.displayLabel());
                if (!summary.isEmpty()) {
                    ImGui.sameLine(0f, 6f * s);
                    UiTheme.textColored(DIM, summary);
                }
                ImGui.endGroup();
                if (ImGui.isItemHovered()) {
                    ImGui.setTooltip(taskTooltip(t, st));
                }

                // Action buttons on next line or compactly aligned? Compact inline row keeps queue scannable
                ImGui.indent(24f * s);
                boolean first = (i == 0);
                boolean last = (i == tasks.size() - 1);
                if (first) ImGui.beginDisabled();
                if (ImGui.smallButton("^##up")) rt.moveTask(i, i - 1);
                if (first) ImGui.endDisabled();
                ImGui.sameLine(0f, 3f * s);

                if (last) ImGui.beginDisabled();
                if (ImGui.smallButton("v##dn")) rt.moveTask(i, i + 1);
                if (last) ImGui.endDisabled();
                ImGui.sameLine(0f, 6f * s);

                if (ImGui.smallButton("Skip##skip")) rt.skipTask(t.id);
                ImGui.sameLine(0f, 3f * s);
                if (ImGui.smallButton("Retry##retry")) rt.retryTask(t.id);
                ImGui.sameLine(0f, 3f * s);
                if (ImGui.smallButton("Reset##reset")) rt.resetQueue(t.id);
                ImGui.sameLine(0f, 6f * s);

                ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, r(ERR), g(ERR), b(ERR), 1f);
                boolean removed = ImGui.smallButton("x##del");
                ImGui.popStyleColor();
                ImGui.unindent(24f * s);

                ImGui.popID();
                if (removed) {
                    rt.removeTask(t.id);
                    setNotice("Removed task " + t.displayLabel() + ".", false);
                    break;
                }
            }
        }

        ImGui.spacing();
        if (ImGui.collapsingHeader("+ Add Task##ProjAddTaskHeader")) {
            renderAddTaskForm(rt, active, s);
        }

        ImGui.separator();
        if (Widgets.button("Reset All##ProjResetAll", 0)) {
            rt.resetQueue(null);
            setNotice("Reset progress for all tasks.", false);
        }
        ImGui.sameLine(0f, 6f * s);
        if (Widgets.button("Retry Failed/Deferred##ProjRetryFailed", 0)) {
            int count = 0;
            for (ProjectTask t : active.tasks) {
                if (t == null || t.id == null) continue;
                TaskQueue.TaskLedger.State st = rt.taskState(t.id);
                if (st == TaskQueue.TaskLedger.State.FAILED || st == TaskQueue.TaskLedger.State.DEFERRED) {
                    rt.retryTask(t.id);
                    count++;
                }
            }
            setNotice("Retried " + count + " failed/deferred task" + (count == 1 ? "" : "s") + ".", false);
        }
    }

    private static void drawStateBadge(TaskQueue.TaskLedger.State st) {
        int col = switch (st) {
            case DONE -> OK;
            case RUNNING -> ACCENT;
            case FAILED -> ERR;
            case DEFERRED -> WARN;
            case PENDING -> DIM;
        };
        UiTheme.push(UiTheme.small);
        UiTheme.textColored(col, "[" + st.name() + "]");
        ImGui.popFont();
    }

    private static String taskSummary(ProjectTask t) {
        List<String> parts = new ArrayList<>();
        parts.add(t.kind.name());
        if (t.areaName != null && !t.areaName.isBlank()) parts.add("area=" + t.areaName);
        if (t.placement != null && !t.placement.isBlank()) parts.add("schem=" + t.placement);
        if (t.scriptPath != null && !t.scriptPath.isBlank()) parts.add("script=" + t.scriptPath);
        if (t.block != null && !t.block.isBlank()) parts.add("block=" + t.block);
        if (t.expand != 0) parts.add("expand=" + (t.expand > 0 ? "+" : "") + t.expand);
        if (t.kind == ProjectTask.Kind.CLEAR_AREA || t.kind == ProjectTask.Kind.MINE || t.kind == ProjectTask.Kind.DRAIN) {
            parts.add("layers=" + t.layers);
        }
        if (t.kind == ProjectTask.Kind.CLEAR_TREES) {
            if (t.treeZoneDiameter != null) parts.add("zone=" + t.treeZoneDiameter);
            if (t.dumpAfter) parts.add("dumpAfter");
        }
        return "(" + String.join(", ", parts) + ")";
    }

    private static String taskTooltip(ProjectTask t, TaskQueue.TaskLedger.State st) {
        return "ID: " + t.id
                + "\nKind: " + t.kind
                + "\nState: " + st
                + (t.areaName != null && !t.areaName.isBlank() ? "\nArea: " + t.areaName : "")
                + (t.placement != null && !t.placement.isBlank() ? "\nPlacement: " + t.placement : "")
                + (t.scriptPath != null && !t.scriptPath.isBlank() ? "\nScript: " + t.scriptPath : "")
                + (t.block != null && !t.block.isBlank() ? "\nBlock: " + t.block : "")
                + "\nExpand: " + t.expand + "   Layers: " + t.layers
                + (t.treeZoneDiameter != null ? "\nZone diameter: " + t.treeZoneDiameter : "")
                + (t.dumpAfter ? "\nDump after: true" : "")
                + "\nPartial policy: " + t.partialPolicy + "   Error policy: " + t.errorPolicy;
    }

    private static void renderAddTaskForm(GameProjectRuntime rt, Project active, float s) {
        ImGui.indent(8f * s);

        ImGui.setNextItemWidth(160f * s);
        if (ImGui.beginCombo("Kind##AddTaskKind", addKind.name())) {
            for (ProjectTask.Kind k : ProjectTask.Kind.values()) {
                if (ImGui.selectable(k.name() + "##kind_" + k.name(), k == addKind)) {
                    addKind = k;
                }
            }
            ImGui.endCombo();
        }
        ImGui.sameLine(0f, 8f * s);
        ImGui.setNextItemWidth(160f * s);
        ImGui.inputTextWithHint("##AddTaskLabel", "Label (optional)", addLabel);

        // Area picker + manual input
        if (needsArea(addKind)) {
            List<AreasView.AreaEntry> areas = AreasView.list();
            ImGui.setNextItemWidth(160f * s);
            String areaPreview = addArea.get().isBlank() ? "Pick saved area..." : addArea.get();
            if (ImGui.beginCombo("##AddTaskAreaPick", areaPreview)) {
                if (areas.isEmpty()) {
                    ImGui.textDisabled("No saved areas.");
                }
                for (int i = 0; i < areas.size(); i++) {
                    AreasView.AreaEntry a = areas.get(i);
                    String lbl = a.name() + ("custom".equalsIgnoreCase(a.role()) ? "" : " [" + a.role() + "]");
                    if (ImGui.selectable(lbl + "##addarea_" + i, a.name().equals(addArea.get()))) {
                        addArea.set(a.name());
                    }
                }
                ImGui.endCombo();
            }
            ImGui.sameLine(0f, 6f * s);
            ImGui.setNextItemWidth(140f * s);
            ImGui.inputTextWithHint("Area##AddTaskAreaText", "area name", addArea);
        }

        // Placement picker for SCHEM_BUILD
        if (addKind == ProjectTask.Kind.SCHEM_BUILD) {
            List<PlacesView.SchematicEntry> schems = PlacesView.schematics();
            ImGui.setNextItemWidth(160f * s);
            String schemPreview = addPlacement.get().isBlank() ? "Pick schematic..." : addPlacement.get();
            if (ImGui.beginCombo("##AddTaskSchemPick", schemPreview)) {
                if (schems.isEmpty()) {
                    ImGui.textDisabled("No Litematica placements.");
                }
                for (int i = 0; i < schems.size(); i++) {
                    PlacesView.SchematicEntry sc = schems.get(i);
                    if (ImGui.selectable(sc.name() + "##addschem_" + i, sc.name().equals(addPlacement.get()))) {
                        addPlacement.set(sc.name());
                    }
                }
                ImGui.endCombo();
            }
            ImGui.sameLine(0f, 6f * s);
            ImGui.setNextItemWidth(140f * s);
            ImGui.inputTextWithHint("Placement##AddTaskSchemText", "placement", addPlacement);
        }

        // Script tab picker for SCRIPT
        if (addKind == ProjectTask.Kind.SCRIPT) {
            int tabCount = DockedPanels.tabCount();
            ImGui.setNextItemWidth(160f * s);
            String tabPreview = addScript.get().isBlank() ? "Pick script tab..." : addScript.get();
            if (ImGui.beginCombo("##AddTaskScriptPick", tabPreview)) {
                for (int i = 0; i < tabCount; i++) {
                    String tn = DockedPanels.tabName(i);
                    if (ImGui.selectable(tn + "##addtab_" + i, tn.equals(addScript.get()))) {
                        addScript.set(tn);
                    }
                }
                ImGui.endCombo();
            }
            ImGui.sameLine(0f, 6f * s);
            ImGui.setNextItemWidth(140f * s);
            ImGui.inputTextWithHint("Script##AddTaskScriptText", "script tab/path", addScript);
        }

        // Expand (-1, 0, +1), layers, block
        UiTheme.textColored(DIM, "Expand:");
        ImGui.sameLine(0f, 4f * s);
        for (int exp : new int[]{-1, 0, 1}) {
            String lbl = (exp > 0 ? "+" : "") + exp + "##addExp_" + exp;
            if (Widgets.button(lbl, addExpand == exp ? 1 : 0)) {
                addExpand = exp;
            }
            ImGui.sameLine(0f, 4f * s);
        }
        ImGui.setNextItemWidth(85f * s);
        ImGui.inputInt("Layers##AddTaskLayers", addLayers);
        if (addLayers.get() < 1) addLayers.set(1);
        ImGui.sameLine(0f, 8f * s);
        ImGui.setNextItemWidth(110f * s);
        ImGui.inputTextWithHint("Block##AddTaskBlock", "dirt", addBlock);

        if (addKind == ProjectTask.Kind.CLEAR_TREES) {
            ImGui.setNextItemWidth(90f * s);
            if (ImGui.inputInt("Zone Diameter (0 = project default)##AddTaskTreeZoneDiam", addTreeZoneDiameter)) {
                int v = addTreeZoneDiameter.get();
                if (v < 0) addTreeZoneDiameter.set(0);
                else if (v > 32) addTreeZoneDiameter.set(32);
            }
            if (ImGui.isItemDeactivatedAfterEdit()) {
                int v = addTreeZoneDiameter.get();
                if (v > 0 && v < 3) addTreeZoneDiameter.set(3);
            }
            ImGui.sameLine(0f, 8f * s);
            ImGui.checkbox("Dump After##AddTaskDumpAfter", addDumpAfter);
        }

        // Policies
        ImGui.setNextItemWidth(140f * s);
        if (ImGui.beginCombo("Partial##AddTaskPartial", addPartial.name())) {
            for (ProjectTask.PartialPolicy p : ProjectTask.PartialPolicy.values()) {
                if (ImGui.selectable(p.name() + "##partial_" + p.name(), p == addPartial)) {
                    addPartial = p;
                }
            }
            ImGui.endCombo();
        }
        ImGui.sameLine(0f, 8f * s);
        ImGui.setNextItemWidth(110f * s);
        if (ImGui.beginCombo("On Error##AddTaskError", addError.name())) {
            for (ProjectTask.ErrorPolicy ep : ProjectTask.ErrorPolicy.values()) {
                if (ImGui.selectable(ep.name() + "##errpol_" + ep.name(), ep == addError)) {
                    addError = ep;
                }
            }
            ImGui.endCombo();
        }

        if (Widgets.button("Enqueue##ProjEnqueueTask", 1)) {
            String lbl = addLabel.get().trim();
            ProjectTask task = new ProjectTask(nextTaskId(active), lbl.isEmpty() ? null : lbl, addKind);
            if (!addArea.get().isBlank()) task.areaName = addArea.get().trim();
            if (!addPlacement.get().isBlank()) task.placement = addPlacement.get().trim();
            if (!addScript.get().isBlank()) task.scriptPath = addScript.get().trim();
            task.expand = addExpand;
            task.layers = Math.max(1, addLayers.get());
            if (!addBlock.get().isBlank()) task.block = addBlock.get().trim();
            if (addKind == ProjectTask.Kind.CLEAR_TREES) {
                int zd = addTreeZoneDiameter.get();
                task.treeZoneDiameter = zd <= 0 ? null : Math.max(3, Math.min(32, zd));
                task.dumpAfter = addDumpAfter.get();
            }
            task.partialPolicy = addPartial;
            task.errorPolicy = addError;
            rt.enqueueTask(task);
            addLabel.set("");
            setNotice("Enqueued " + task.displayLabel() + ".", false);
        }

        ImGui.unindent(8f * s);
    }

    private static boolean needsArea(ProjectTask.Kind kind) {
        return kind != ProjectTask.Kind.SCHEM_BUILD
                && kind != ProjectTask.Kind.DUMP
                && kind != ProjectTask.Kind.RESTOCK
                && kind != ProjectTask.Kind.SCRIPT;
    }

    private static String nextTaskId(Project active) {
        int max = 0;
        if (active != null && active.tasks != null) {
            for (ProjectTask t : active.tasks) {
                if (t == null || t.id == null) continue;
                Matcher m = TASK_ID_NUM.matcher(t.id);
                if (m.matches()) {
                    try {
                        max = Math.max(max, Integer.parseInt(m.group(1)));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return String.format(Locale.ROOT, "t_%03d", max + 1);
    }

    // ------------------------------------------------------------------ Base & Survival Tab

    private static void renderBaseAndSurvivalTab(GameProjectRuntime rt, Project active, float s) {
        Project.BasePlaces places = active.places;
        Project.SurvivalPolicy surv = active.survival;

        Widgets.label("Base Places");
        renderPlaceRow(rt, "Bed", "bed", places.bed, p -> places.bed = p, s);
        renderPlaceRow(rt, "Tool Chest", "toolChest", places.toolChest, p -> places.toolChest = p, s);
        renderPlaceRow(rt, "Return Chest", "returnChest", places.returnChest, p -> places.returnChest = p, s);
        renderPlaceRow(rt, "Dump Chest", "dumpChest", places.dumpChest, p -> places.dumpChest = p, s);
        renderPlaceRow(rt, "Scaffold Chest", "scaffoldChest", places.scaffoldChest, p -> places.scaffoldChest = p, s);

        UiTheme.textColored(DIM, "Material chests: " + places.materialChests.size()
                + "   |   Protected areas: " + places.protectedAreas.size());

        if (Widgets.button("Save##ProjSavePlaces", 0)) {
            rt.saveActive();
            setNotice("Saved base places.", false);
        }

        ImGui.separator();
        Widgets.label("Survival Policy");

        ImBoolean b = new ImBoolean();
        if (checkbox("Defend enabled", "defendEnabled", surv.defendEnabled, b)) surv.defendEnabled = b.get();
        ImGui.sameLine(190f * s);
        if (checkbox("Eat enabled", "eatEnabled", surv.eatEnabled, b)) surv.eatEnabled = b.get();

        if (checkbox("Sleep enabled", "sleepEnabled", surv.sleepEnabled, b)) surv.sleepEnabled = b.get();
        ImGui.sameLine(190f * s);
        if (checkbox("Pre-dusk gate", "preDuskGate", surv.preDuskGate, b)) surv.preDuskGate = b.get();

        if (checkbox("Restock enabled", "restockEnabled", surv.restockEnabled, b)) surv.restockEnabled = b.get();
        ImGui.sameLine(190f * s);
        if (checkbox("Dump enabled", "dumpEnabled", surv.dumpEnabled, b)) surv.dumpEnabled = b.get();

        if (checkbox("Batch base trips", "batchBaseTrips", surv.batchBaseTrips, b)) surv.batchBaseTrips = b.get();
        ImGui.sameLine(190f * s);
        if (checkbox("Item saver", "itemSaver", surv.itemSaver, b)) surv.itemSaver = b.get();

        if (checkbox("Respawn", "respawn", surv.respawn, b)) surv.respawn = b.get();
        ImGui.sameLine(190f * s);
        if (checkbox("Resume after death", "resumeAfterDeath", surv.resumeAfterDeath, b)) surv.resumeAfterDeath = b.get();

        if (checkbox("Reconnect", "reconnect", surv.reconnect, b)) surv.reconnect = b.get();
        ImGui.sameLine(190f * s);
        if (checkbox("Logoff on done", "logoffOnDone", surv.logoffOnDone, b)) surv.logoffOnDone = b.get();

        if (checkbox("Logoff on error", "logoffOnError", surv.logoffOnError, b)) surv.logoffOnError = b.get();
        ImGui.sameLine(190f * s);
        if (checkbox("Show Tree Zones", "showTreeZones", surv.showTreeZones, b)) surv.showTreeZones = b.get();

        ImGui.spacing();
        ImGui.setNextItemWidth(90f * s);
        numIntBuf.set(surv.itemSaverThreshold);
        if (ImGui.inputInt("Item saver threshold##itemSaverThresh", numIntBuf)) {
            surv.itemSaverThreshold = Math.max(0, numIntBuf.get());
        }
        ImGui.setNextItemWidth(90f * s);
        numDoubleBuf.set(surv.defendMinHealth);
        if (ImGui.inputDouble("Defend min health##defendMinHealth", numDoubleBuf, 1.0, 2.0, "%.1f")) {
            surv.defendMinHealth = Math.max(0.0, numDoubleBuf.get());
        }
        ImGui.setNextItemWidth(90f * s);
        numDoubleBuf.set(surv.defendRange);
        if (ImGui.inputDouble("Defend range##defendRange", numDoubleBuf, 1.0, 2.0, "%.1f")) {
            surv.defendRange = Math.max(1.0, numDoubleBuf.get());
        }
        ImGui.setNextItemWidth(90f * s);
        numIntBuf.set(surv.eatFoodBelow);
        if (ImGui.inputInt("Eat food below##eatFoodBelow", numIntBuf)) {
            surv.eatFoodBelow = Math.max(1, Math.min(20, numIntBuf.get()));
        }
        ImGui.setNextItemWidth(90f * s);
        numIntBuf.set(surv.preDuskMarginTicks);
        if (ImGui.inputInt("Pre-dusk margin (ticks)##preDuskTicks", numIntBuf)) {
            surv.preDuskMarginTicks = Math.max(0, numIntBuf.get());
        }
        ImGui.setNextItemWidth(90f * s);
        numIntBuf.set(surv.treeZoneDiameter);
        if (ImGui.inputInt("Tree Zone Diameter##treeZoneDiameter", numIntBuf)) {
            surv.treeZoneDiameter = Math.max(3, Math.min(32, numIntBuf.get()));
        }

        ImGui.separator();
        renderItemCountMap(rt, "want", "Want (Restock Targets)", surv.want, wantItemInput, wantCountInput, s);

        ImGui.separator();
        renderItemCountMap(rt, "keep", "Keep (Do Not Dump)", surv.keep, keepItemInput, keepCountInput, s);

        ImGui.separator();
        if (Widgets.button("Save Project##ProjSaveAll", 1)) {
            rt.saveActive();
            setNotice("Saved project '" + active.id + "'.", false);
        }
    }

    private static boolean checkbox(String label, String id, boolean current, ImBoolean buf) {
        buf.set(current);
        return ImGui.checkbox(label + "##surv_" + id, buf);
    }

    private static void renderPlaceRow(GameProjectRuntime rt, String label, String key,
                                       int[] current, Consumer<int[]> setter, float s) {
        ImGui.pushID("place_" + key);
        ImGui.alignTextToFramePadding();
        ImGui.textUnformatted(label);
        ImGui.sameLine(115f * s);
        if (current != null && current.length >= 3) {
            ImGui.setNextItemWidth(175f * s);
            ImGui.inputInt3("##coords", current);
            ImGui.sameLine(0f, 4f * s);
            if (ImGui.smallButton("Clear##clr")) {
                setter.accept(null);
                rt.saveActive();
            }
        } else {
            UiTheme.textColored(DIM, "(unset)");
            ImGui.sameLine(0f, 6f * s);
            if (ImGui.smallButton("Set##set")) {
                setter.accept(new int[]{0, 64, 0});
            }
        }
        ImGui.sameLine(0f, 6f * s);
        if (ImGui.smallButton("Looked-at##look")) {
            Places.Target t = Places.target();
            if (t == null) {
                setNotice("Look at a block in-game first.", true);
            } else {
                setter.accept(new int[]{t.pos().x(), t.pos().y(), t.pos().z()});
                rt.saveActive();
                setNotice("Set " + label + " to " + t.pos() + ".", false);
            }
        }
        ImGui.popID();
    }

    private static void renderItemCountMap(GameProjectRuntime rt, String idPrefix, String title,
                                           Map<String, Integer> map, ImString itemBuf, ImInt countBuf, float s) {
        Widgets.label(title);
        if (map.isEmpty()) {
            UiTheme.textColored(DIM, "No entries.");
        } else {
            String toRemove = null;
            int idx = 0;
            for (Map.Entry<String, Integer> e : map.entrySet()) {
                ImGui.pushID(idPrefix + "_" + idx++);
                if (ImGui.smallButton("x##rm")) {
                    toRemove = e.getKey();
                }
                ImGui.sameLine(0f, 6f * s);
                ImGui.textUnformatted(e.getKey() + " : " + e.getValue());
                ImGui.popID();
            }
            if (toRemove != null) {
                map.remove(toRemove);
                rt.saveActive();
            }
        }
        ImGui.setNextItemWidth(160f * s);
        ImGui.inputTextWithHint("##item_" + idPrefix, "item (e.g. bread)", itemBuf);
        ImGui.sameLine(0f, 4f * s);
        ImGui.setNextItemWidth(80f * s);
        ImGui.inputInt("##cnt_" + idPrefix, countBuf);
        if (countBuf.get() < 1) countBuf.set(1);
        ImGui.sameLine(0f, 4f * s);
        if (Widgets.button("Add##add_" + idPrefix, 0)) {
            String item = itemBuf.get().trim();
            if (!item.isEmpty()) {
                map.put(item, Math.max(1, countBuf.get()));
                itemBuf.set("");
                rt.saveActive();
                setNotice("Added " + item + " to " + idPrefix + ".", false);
            }
        }
    }

    private static void setNotice(String msg, boolean error) {
        notice = msg == null ? "" : msg;
        noticeError = error;
    }
}
