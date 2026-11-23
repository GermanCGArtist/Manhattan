package dev.manhattan;

import dev.manhattan.project.GameProjectRuntime;
import dev.manhattan.project.PriorityScheduler;
import dev.manhattan.project.Project;
import dev.manhattan.project.ProjectLayerScheduler;
import dev.manhattan.project.ProjectQueue;
import dev.manhattan.project.ProjectStore;
import dev.manhattan.project.ProjectTask;
import dev.manhattan.project.TaskQueue;
import imgui.ImGui;
import imgui.flag.ImGuiTabItemFlags;
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

import static dev.manhattan.UiTheme.*;

/**
 * Projects &amp; Task Queue drawer panel rendered inside {@link Shell} when {@link Shell.Drawer#PROJECTS} is open.
 */
final class ProjectPanel {
    private ProjectPanel() {}

    private static final Pattern TASK_ID_NUM = Pattern.compile("^t_(\\d+)$");

    private static final String LAYER_DRAG_TYPE = "MANHATTAN_PROJ_LAYER_IDX";
    private static final ImString newProjectInput = new ImString("", 64);
    private static String addLayerSelection = "";
    private static boolean focusTasksTab = false;
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

    // Fleet submission form state
    private static dev.manhattan.fleet.FleetProtocol.AssignmentMode fleetAssignMode =
            dev.manhattan.fleet.FleetProtocol.AssignmentMode.AUTO_POOL;
    private static final java.util.Set<String> fleetSelectedBots = new java.util.LinkedHashSet<>();
    private static final ImString fleetPoolTag = new ImString("", 64);
    private static final ImInt fleetMaxBots = new ImInt(2);
    private static final ImInt fleetMaxDist = new ImInt(500);
    private static final ImInt fleetPriority = new ImInt(0);

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
            ProjectQueue pq = rt.projectQueue();
            int layerCount = pq != null ? pq.snapshot().size() : 0;
            String layersTabTitle = (layerCount > 0 ? "Layers (" + layerCount + ")" : "Layers") + "###ProjTabLayers";
            if (ImGui.beginTabItem(layersTabTitle)) {
                renderLayersTab(rt, active, s);
                ImGui.endTabItem();
            }
            int taskTabFlags = focusTasksTab ? ImGuiTabItemFlags.SetSelected : 0;
            focusTasksTab = false;
            if (ImGui.beginTabItem("Tasks###ProjTabQueue", taskTabFlags)) {
                renderQueueTab(rt, active, s);
                ImGui.endTabItem();
            }
            if (ImGui.beginTabItem("Base & Survival###ProjTabBase")) {
                renderBaseAndSurvivalTab(rt, active, s);
                ImGui.endTabItem();
            }
            if (ImGui.beginTabItem("Fleet Submit###ProjTabFleet")) {
                renderFleetSubmitTab(active, s);
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
        ProjectQueue pq = rt.projectQueue();
        boolean hasLayers = pq != null && pq.enabledCount() > 0;
        if (!running) {
            if (hasLayers) {
                if (Widgets.button("Run Layers##ProjRunLayers", 1)) {
                    String err = rt.startLayerQueue();
                    if (err != null && !err.isBlank()) {
                        setNotice(err, true);
                        Util.msg(err);
                    } else {
                        setNotice("Project layer queue started.", false);
                    }
                }
                if (ImGui.isItemHovered()) {
                    ImGui.setTooltip("Runs all enabled project layers in priority order (topmost layer gets bot capacity first).");
                }
                ImGui.sameLine(0f, 4f * s);
                if (Widgets.button("Run Active##ProjRunQueue", 0)) {
                    String err = rt.startQueue();
                    if (err != null && !err.isBlank()) {
                        setNotice(err, true);
                        Util.msg(err);
                    } else {
                        setNotice("Active project queue started.", false);
                    }
                }
            } else {
                if (Widgets.button("Run Queue##ProjRunQueue", 1)) {
                    String err = rt.startQueue();
                    if (err != null && !err.isBlank()) {
                        setNotice(err, true);
                        Util.msg(err);
                    } else {
                        setNotice("Project queue started.", false);
                    }
                }
            }
        } else {
            if (Widgets.button("Stop##ProjStopQueue", 2)) {
                rt.stopQueue("Stopped from Projects panel.");
                setNotice("Project queue stopped.", false);
            }
            ImGui.sameLine(0f, 4f * s);
            if (rt.scheduler().isPaused()) {
                if (Widgets.button("Resume##ProjResume", 1)) {
                    rt.scheduler().resume();
                    setNotice("Resumed project queue.", false);
                }
            } else {
                if (Widgets.button("Pause##ProjPause", 0)) {
                    rt.scheduler().pause();
                    setNotice("Paused project queue.", false);
                }
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
                String layerPrefix = "";
                if (rt.layerQueueRunning()) {
                    ProjectLayerScheduler.LayerTelemetry lt = rt.layerTelemetry();
                    int layerNum = lt.activeLayerIndex() >= 0 ? lt.activeLayerIndex() + 1 : 1;
                    String pname = lt.activeProjectName() != null ? lt.activeProjectName() : String.valueOf(lt.activeProjectId());
                    layerPrefix = "[Layer #" + layerNum + "/" + Math.max(1, lt.totalLayers()) + ": " + pname + "]  ";
                }
                String lane = formatLane(t);
                String detail = (t.detail() != null && !t.detail().isBlank()) ? t.detail() : "";
                String elapsed = Estimate.duration(t.elapsedMillis() / 50L);
                UiTheme.textColored(ACCENT, layerPrefix + lane + (detail.isEmpty() ? "" : "  " + detail)
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

    // ------------------------------------------------------------------ Layers Tab

    private static void renderLayersTab(GameProjectRuntime rt, Project active, float s) {
        ProjectQueue pq = rt.projectQueue();
        if (pq == null) return;

        UiTheme.push(UiTheme.small);
        UiTheme.textColored(DIM, "Priority stack: topmost enabled layer gets bot capacity first. Drag rows or use Top / ^ / v to reprioritize live.");
        ImGui.popFont();
        ImGui.spacing();

        // Add project to layer queue controls
        List<String> allIds = rt.projectIds();
        List<String> available = new ArrayList<>();
        for (String id : allIds) {
            if (!pq.contains(id)) available.add(id);
        }
        if (addLayerSelection.isEmpty() || !available.contains(addLayerSelection)) {
            addLayerSelection = available.isEmpty() ? "" : available.get(0);
        }

        ImGui.setNextItemWidth(175f * s);
        String comboPreview = addLayerSelection.isEmpty() ? "(all projects queued)" : addLayerSelection;
        if (ImGui.beginCombo("##AddLayerProj", comboPreview)) {
            if (available.isEmpty()) {
                ImGui.textDisabled("All saved projects are already in the layer queue.");
            } else {
                for (String id : available) {
                    if (ImGui.selectable(id + "##addlayer_" + id, id.equals(addLayerSelection))) {
                        addLayerSelection = id;
                    }
                }
            }
            ImGui.endCombo();
        }
        ImGui.sameLine(0f, 6f * s);
        boolean canAdd = !addLayerSelection.isEmpty();
        if (!canAdd) ImGui.beginDisabled();
        if (Widgets.button("+ Add Layer##ProjAddLayer", 0)) {
            if (rt.addProjectLayer(addLayerSelection)) {
                setNotice("Queued project '" + addLayerSelection + "' as layer #" + pq.snapshot().size() + ".", false);
                addLayerSelection = "";
            }
        }
        if (!canAdd) ImGui.endDisabled();

        if (active != null && active.id != null && !pq.contains(active.id)) {
            ImGui.sameLine(0f, 6f * s);
            if (Widgets.button("+ Add Active##ProjAddActiveLayer", 0)) {
                if (rt.addProjectLayer(active.id)) {
                    setNotice("Queued active project '" + active.id + "'.", false);
                }
            }
        }

        // Queue-level policies
        ImBoolean stopOnErr = new ImBoolean(pq.stopOnProjectError);
        if (ImGui.checkbox("Stop on project error##LayerStopOnErr", stopOnErr)) {
            rt.setStopOnProjectError(stopOnErr.get());
        }
        ImGui.sameLine(0f, 12f * s);
        ImBoolean logoffAll = new ImBoolean(pq.logoffWhenAllDone);
        if (ImGui.checkbox("Log off when all done##LayerLogoffAll", logoffAll)) {
            rt.setLogoffWhenAllDone(logoffAll.get());
        }

        ImGui.separator();

        List<ProjectQueue.Layer> layers = pq.snapshot();
        if (layers.isEmpty()) {
            UiTheme.textColored(DIM, "No projects queued. Add one or more projects above to run them in priority order.");
            return;
        }

        int dragFrom = -1;
        int dragTo = -1;

        for (int i = 0; i < layers.size(); i++) {
            ProjectQueue.Layer layer = layers.get(i);
            if (layer == null || layer.projectId == null) continue;
            Project proj = rt.loadProjectCached(layer.projectId);
            String title = (proj != null && proj.name != null && !proj.name.isBlank()) ? proj.name : layer.projectId;
            ProjectLayerScheduler.LayerStatus status = rt.layerStatus(layer);
            int[] progress = rt.projectProgress(layer.projectId);
            int done = progress[0];
            int total = progress[1];
            boolean isRunningLayer = status == ProjectLayerScheduler.LayerStatus.RUNNING;
            boolean isTopLayer = (i == 0);

            ImGui.pushID("layerRow_" + i + "_" + layer.projectId);

            // Drag handle + priority index
            int rankColor = isRunningLayer ? ACCENT : (isTopLayer ? OK : GUTTER);
            ImGui.beginGroup();
            UiTheme.textColored(rankColor, String.format("L%d", i + 1));
            ImGui.sameLine(0f, 4f * s);
            UiTheme.textColored(DIM, "::");
            ImGui.endGroup();
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Drag to reorder priority layer (L1 = highest priority)");
            }
            if (ImGui.beginDragDropSource()) {
                ImGui.setDragDropPayload(LAYER_DRAG_TYPE, Integer.valueOf(i));
                ImGui.text("Move L" + (i + 1) + ": " + title);
                ImGui.endDragDropSource();
            }
            if (ImGui.beginDragDropTarget()) {
                Integer payload = ImGui.acceptDragDropPayload(LAYER_DRAG_TYPE);
                if (payload != null && payload != i) {
                    dragFrom = payload;
                    dragTo = i;
                }
                ImGui.endDragDropTarget();
            }

            ImGui.sameLine(0f, 6f * s);
            ImBoolean en = new ImBoolean(layer.enabled);
            if (ImGui.checkbox("##en", en)) {
                rt.setProjectLayerEnabled(layer.projectId, en.get());
            }
            if (ImGui.isItemHovered()) ImGui.setTooltip("Enable/disable this project layer");

            ImGui.sameLine(0f, 6f * s);
            drawLayerStatusBadge(status);

            ImGui.sameLine(0f, 6f * s);
            int nameColor = !layer.enabled ? DIM : (isRunningLayer ? ACCENT : FG);
            ImGui.beginGroup();
            UiTheme.textColored(nameColor, title);
            if (!title.equals(layer.projectId)) {
                ImGui.sameLine(0f, 4f * s);
                UiTheme.textColored(DIM, "(" + layer.projectId + ")");
            }
            ImGui.sameLine(0f, 8f * s);
            UiTheme.textColored(DIM, done + "/" + total + " tasks");
            ImGui.endGroup();

            // Allow dropping anywhere on the title row too
            if (ImGui.beginDragDropSource()) {
                ImGui.setDragDropPayload(LAYER_DRAG_TYPE, Integer.valueOf(i));
                ImGui.text("Move L" + (i + 1) + ": " + title);
                ImGui.endDragDropSource();
            }
            if (ImGui.beginDragDropTarget()) {
                Integer payload = ImGui.acceptDragDropPayload(LAYER_DRAG_TYPE);
                if (payload != null && payload != i) {
                    dragFrom = payload;
                    dragTo = i;
                }
                ImGui.endDragDropTarget();
            }

            // Compact inline controls row
            ImGui.indent(28f * s);
            boolean first = (i == 0);
            boolean last = (i == layers.size() - 1);

            if (first) ImGui.beginDisabled();
            if (ImGui.smallButton("Top##top")) {
                rt.moveProjectLayerToTop(layer.projectId);
                setNotice("Promoted '" + title + "' to Layer 1.", false);
            }
            ImGui.sameLine(0f, 3f * s);
            if (ImGui.smallButton("^##up")) rt.moveProjectLayer(i, i - 1);
            if (first) ImGui.endDisabled();
            ImGui.sameLine(0f, 3f * s);

            if (last) ImGui.beginDisabled();
            if (ImGui.smallButton("v##dn")) rt.moveProjectLayer(i, i + 1);
            if (last) ImGui.endDisabled();
            ImGui.sameLine(0f, 8f * s);

            if (ImGui.smallButton("Edit##edit")) {
                rt.selectProject(layer.projectId);
                focusTasksTab = true;
            }
            if (ImGui.isItemHovered()) ImGui.setTooltip("Open this project in the Tasks tab");
            ImGui.sameLine(0f, 3f * s);

            if (ImGui.smallButton("Retry##retry")) {
                rt.retryProjectLedger(layer.projectId);
                setNotice("Retried failed/deferred tasks in '" + title + "'.", false);
            }
            ImGui.sameLine(0f, 3f * s);

            if (ImGui.smallButton("Reset##reset")) {
                rt.resetProjectLedger(layer.projectId);
                setNotice("Reset progress for '" + title + "'.", false);
            }
            ImGui.sameLine(0f, 8f * s);

            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, r(ERR), g(ERR), b(ERR), 1f);
            boolean removed = ImGui.smallButton("x##rem");
            ImGui.popStyleColor();
            if (ImGui.isItemHovered()) ImGui.setTooltip("Remove from layer queue (keeps project saved)");
            ImGui.unindent(28f * s);

            ImGui.popID();
            if (removed) {
                rt.removeProjectLayer(layer.projectId);
                setNotice("Removed '" + title + "' from layer queue.", false);
                break;
            }
        }

        if (dragFrom >= 0 && dragTo >= 0 && dragFrom != dragTo) {
            rt.moveProjectLayer(dragFrom, dragTo);
            setNotice("Moved layer L" + (dragFrom + 1) + " to L" + (dragTo + 1) + ".", false);
        }
    }

    private static void drawLayerStatusBadge(ProjectLayerScheduler.LayerStatus st) {
        int col = switch (st) {
            case RUNNING -> ACCENT;
            case DONE -> OK;
            case FAILED, MISSING -> ERR;
            case QUEUED -> FG;
            case DISABLED, EMPTY -> DIM;
        };
        UiTheme.push(UiTheme.small);
        UiTheme.textColored(col, "[" + st.name() + "]");
        ImGui.popFont();
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

                String depLabel = t.waitForPrevious == null ? "Auto"
                        : (Boolean.TRUE.equals(t.waitForPrevious) ? "Barrier" : "Parallel");
                int depCol = t.waitForPrevious == null ? DIM
                        : (Boolean.TRUE.equals(t.waitForPrevious) ? WARN : OK);
                ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, r(depCol), g(depCol), b(depCol), 1f);
                if (ImGui.smallButton(depLabel + "##dep")) {
                    if (t.waitForPrevious == null) t.waitForPrevious = Boolean.TRUE;
                    else if (Boolean.TRUE.equals(t.waitForPrevious)) t.waitForPrevious = Boolean.FALSE;
                    else t.waitForPrevious = null;
                    rt.saveActive();
                }
                ImGui.popStyleColor();
                if (ImGui.isItemHovered()) {
                    ImGui.setTooltip("Fleet order: " + depLabel
                            + "\nAuto: wait on phase change, parallel across same-kind tasks"
                            + "\nBarrier: never start until all previous tasks finish"
                            + "\nParallel: idle fleet bots may spill over into this task early"
                            + "\nClick to cycle.");
                }
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
        ProjectQueue pq = rt.projectQueue();
        if (pq != null && active.id != null && !pq.contains(active.id)) {
            ImGui.sameLine(0f, 6f * s);
            if (Widgets.button("+ Add to Layers##ProjTasksAddLayer", 0)) {
                if (rt.addProjectLayer(active.id)) {
                    setNotice("Added '" + active.id + "' to priority layers.", false);
                }
            }
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
                + "\nPartial policy: " + t.partialPolicy + "   Error policy: " + t.errorPolicy
                + "\nFleet order: " + (t.waitForPrevious == null ? "Auto (phase)"
                        : (Boolean.TRUE.equals(t.waitForPrevious) ? "Barrier" : "Parallel"));
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
                if (ImGui.selectable("all (all enabled placements / assemblies)##addschem_all",
                        "all".equalsIgnoreCase(addPlacement.get()))) {
                    addPlacement.set("all");
                }
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
            ImGui.setNextItemWidth(160f * s);
            ImGui.inputTextWithHint("Placement##AddTaskSchemText", "name, prefix*, a,b, or all", addPlacement);
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
        renderPlaceRow(rt, "Bed", "bed", places.bed, p -> places.bed = p, WorldOverlay.Category.BED, s);
        renderPosListEditor(rt, "Backup Beds", "extraBeds", places.extraBeds, WorldOverlay.Category.BED, s);
        renderBedPoolEditor(rt, places, s);
        renderPlaceRow(rt, "Tool Chest", "toolChest", places.toolChest, p -> places.toolChest = p, WorldOverlay.Category.CHEST, s);
        renderPlaceRow(rt, "Return Chest", "returnChest", places.returnChest, p -> places.returnChest = p, WorldOverlay.Category.CHEST, s);
        renderPosListEditor(rt, "Extra Return Chests", "extraReturnChests", places.extraReturnChests, WorldOverlay.Category.CHEST, s);
        renderPlaceRow(rt, "Dump Chest", "dumpChest", places.dumpChest, p -> places.dumpChest = p, WorldOverlay.Category.CHEST, s);
        renderPlaceRow(rt, "Scaffold Chest", "scaffoldChest", places.scaffoldChest, p -> places.scaffoldChest = p, WorldOverlay.Category.CHEST, s);

        ImGui.spacing();
        Widgets.label("Storage Areas (auto-scan chests/barrels inside)");
        renderStorageAreaEditor(rt, "Supply Chest Areas", "supplyAreas", places.supplyAreas, s);
        renderStorageAreaEditor(rt, "Return Chest Areas", "returnAreas", places.returnAreas, s);
        renderStorageAreaEditor(rt, "Build Supply Areas", "materialAreas", places.materialAreas, s);
        renderStorageAreaEditor(rt, "Scaffolding Chest Areas", "scaffoldAreas", places.scaffoldAreas, s);

        UiTheme.textColored(DIM, "Material chests: " + places.materialChests.size()
                + "   |   Protected areas: " + places.protectedAreas.size());

        if (Widgets.button("Save##ProjSavePlaces", 0)) {
            rt.saveActive();
            setNotice("Saved base places.", false);
        }
        ImGui.sameLine(0f, 8f * s);
        if (Widgets.button("Check All##ProjCheckPlaces", 0)) {
            WorldOverlay.checkReport().forEach(Util::msg);
            setNotice("Checked base places (see chat).", false);
        }

        ImGui.spacing();
        Widgets.label("3D World Overlay");
        ImBoolean ov = new ImBoolean();
        ov.set(Config.cfg.showWorldOverlay);
        if (ImGui.checkbox("Enabled##ovMaster", ov)) { Config.cfg.showWorldOverlay = ov.get(); Config.save(); }
        ImGui.sameLine(0f, 10f * s);
        ov.set(Config.cfg.showOverlayProblemsOnly);
        if (ImGui.checkbox("Problems Only##ovProbOnly", ov)) { Config.cfg.showOverlayProblemsOnly = ov.get(); Config.save(); }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Hide all OK markers & areas; only show MISSING/WRONG/BLOCKED beds and chests.");
        }
        ImGui.sameLine(0f, 10f * s);
        ov.set(Config.cfg.showOverlayAreaFill);
        if (ImGui.checkbox("Area Fill##ovFill", ov)) { Config.cfg.showOverlayAreaFill = ov.get(); Config.save(); }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Off = clean wireframes only (recommended). On = light translucent fill.");
        }

        ov.set(Config.cfg.showOverlayBeds);
        if (ImGui.checkbox("Beds##ovBeds", ov)) { Config.cfg.showOverlayBeds = ov.get(); Config.save(); }
        ImGui.sameLine(0f, 10f * s);
        ov.set(Config.cfg.showOverlayChests);
        if (ImGui.checkbox("Chests##ovChests", ov)) { Config.cfg.showOverlayChests = ov.get(); Config.save(); }
        ImGui.sameLine(0f, 10f * s);
        ov.set(Config.cfg.showOverlaySafeAreas);
        if (ImGui.checkbox("Safe Areas##ovSafe", ov)) { Config.cfg.showOverlaySafeAreas = ov.get(); Config.save(); }

        ov.set(Config.cfg.showOverlayTaskAreas);
        if (ImGui.checkbox("Task Areas##ovTask", ov)) { Config.cfg.showOverlayTaskAreas = ov.get(); Config.save(); }
        ImGui.sameLine(0f, 10f * s);
        ov.set(Config.cfg.showOverlayLabels);
        if (ImGui.checkbox("Labels##ovLabels", ov)) { Config.cfg.showOverlayLabels = ov.get(); Config.save(); }
        ImGui.sameLine(0f, 10f * s);
        ov.set(Config.cfg.showOverlayThroughWalls);
        if (ImGui.checkbox("Through Walls##ovXray", ov)) { Config.cfg.showOverlayThroughWalls = ov.get(); Config.save(); }

        renderOverlayElementsSection("proj", s);

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
                                       int[] current, Consumer<int[]> setter, WorldOverlay.Category category, float s) {
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
        if (current != null && current.length >= 3) {
            ImGui.sameLine(0f, 6f * s);
            ImBoolean vis = new ImBoolean(WorldOverlay.isPointVisible(category, current));
            if (ImGui.checkbox("##vis", vis)) {
                WorldOverlay.setPointVisible(category, current[0], current[1], current[2], vis.get());
            }
            if (ImGui.isItemHovered()) ImGui.setTooltip("Show/hide in 3D World Overlay");
            renderPlaceStatusBadge(current, category, s);
        }
        ImGui.popID();
    }

    /** Shared collapsible list of individual areas, beds, and chests with per-element show/hide checkboxes. */
    static void renderOverlayElementsSection(String idPrefix, float s) {
        int hidden = WorldOverlay.hiddenCount();
        String title = "Individual Overlay Elements" + (hidden > 0 ? " (" + hidden + " hidden)" : "") + "###ovElems_" + idPrefix;
        if (!ImGui.collapsingHeader(title)) return;

        ImGui.indent(6f * s);
        if (Widgets.button("Show All##ovShowAll_" + idPrefix, 0)) {
            WorldOverlay.resetHidden();
        }
        ImGui.sameLine(0f, 6f * s);
        if (Widgets.button("Hide All Areas##ovHideAreas_" + idPrefix, 0)) {
            for (WorldOverlay.AreaMarker a : WorldOverlay.currentAreas()) {
                WorldOverlay.setAreaVisible(a.label(), false);
            }
        }
        ImGui.sameLine(0f, 6f * s);
        if (Widgets.button("Hide All Chests##ovHideChests_" + idPrefix, 0)) {
            for (WorldOverlay.PointMarker p : WorldOverlay.currentPoints()) {
                if (p.category() == WorldOverlay.Category.CHEST) {
                    WorldOverlay.setPointVisible(p.category(), p.x(), p.y(), p.z(), false);
                }
            }
        }

        List<WorldOverlay.AreaMarker> areas = WorldOverlay.currentAreas();
        if (!areas.isEmpty()) {
            UiTheme.textColored(DIM, "Areas:");
            java.util.Set<String> shownNames = new java.util.LinkedHashSet<>();
            int idx = 0;
            for (WorldOverlay.AreaMarker a : areas) {
                if (!shownNames.add(a.key())) continue;
                ImBoolean vis = new ImBoolean(WorldOverlay.isAreaVisible(a.label()));
                String tag = a.safe() ? " [SAFE]" : (a.role() == null || a.role().isBlank() || "custom".equalsIgnoreCase(a.role()) ? "" : " [" + a.role() + "]");
                if (ImGui.checkbox(a.label() + tag + "##ovArea_" + idPrefix + "_" + (idx++), vis)) {
                    WorldOverlay.setAreaVisible(a.label(), vis.get());
                }
            }
        }

        List<WorldOverlay.PointMarker> points = WorldOverlay.currentPoints();
        if (!points.isEmpty()) {
            UiTheme.textColored(DIM, "Beds & Chests:");
            int idx = 0;
            for (WorldOverlay.PointMarker p : points) {
                ImBoolean vis = new ImBoolean(WorldOverlay.isPointVisible(p));
                String lbl = p.label() + " (" + p.x() + " " + p.y() + " " + p.z() + ")";
                if (ImGui.checkbox(lbl + "##ovPt_" + idPrefix + "_" + (idx++), vis)) {
                    WorldOverlay.setPointVisible(p.category(), p.x(), p.y(), p.z(), vis.get());
                }
                renderPlaceStatusBadge(new int[]{p.x(), p.y(), p.z()}, p.category(), s);
            }
        }
        ImGui.unindent(6f * s);
    }

    private static void renderPlaceStatusBadge(int[] coords, WorldOverlay.Category category, float s) {
        WorldOverlay.CheckResult res = WorldOverlay.checkPlace(coords, category);
        if (res == null) return;
        ImGui.sameLine(0f, 6f * s);
        int col = switch (res.status()) {
            case OK -> OK;
            case MISSING, WRONG -> ERR;
            case BLOCKED -> WARN;
            case UNLOADED, INFO -> DIM;
        };
        String txt = switch (res.status()) {
            case OK -> "[OK]";
            case MISSING -> "[MISSING!]";
            case WRONG -> "[WRONG: " + res.detail() + "]";
            case BLOCKED -> "[BLOCKED]";
            case UNLOADED -> "[unloaded]";
            case INFO -> "";
        };
        if (!txt.isEmpty()) {
            UiTheme.textColored(col, txt);
        }
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

    private static void renderPosListEditor(GameProjectRuntime rt, String label, String id,
                                            List<int[]> list, WorldOverlay.Category category, float s) {
        if (list == null) return;
        ImGui.pushID("poslist_" + id);
        UiTheme.textColored(DIM, label + " (" + list.size() + "):");
        ImGui.sameLine(0f, 6f * s);
        if (Widgets.button("+ Aimed##addAim", 0)) {
            Places.Target t = Places.target();
            if (t == null) {
                setNotice("Look at a block first.", true);
            } else {
                int[] pos = new int[]{t.pos().x(), t.pos().y(), t.pos().z()};
                boolean dup = list.stream().anyMatch(e -> e != null && e.length >= 3 && e[0] == pos[0] && e[1] == pos[1] && e[2] == pos[2]);
                if (!dup) {
                    list.add(pos);
                    rt.saveActive();
                    setNotice("Added " + label + " spot at " + t.pos() + ".", false);
                } else {
                    setNotice("That block is already in " + label + ".", false);
                }
            }
        }
        int removeIdx = -1;
        for (int i = 0; i < list.size(); i++) {
            int[] p = list.get(i);
            if (p == null || p.length < 3) continue;
            ImGui.indent(10f * s);
            ImBoolean vis = new ImBoolean(WorldOverlay.isPointVisible(category, p));
            if (ImGui.checkbox("##vis_" + i, vis)) {
                WorldOverlay.setPointVisible(category, p[0], p[1], p[2], vis.get());
            }
            if (ImGui.isItemHovered()) ImGui.setTooltip("Show/hide in 3D World Overlay");
            ImGui.sameLine(0f, 4f * s);
            UiTheme.textColored(FG, "#" + (i + 1) + ": " + p[0] + ", " + p[1] + ", " + p[2]);
            renderPlaceStatusBadge(p, category, s);
            ImGui.sameLine(0f, 6f * s);
            if (Widgets.button("x##rm_" + i, 2)) {
                removeIdx = i;
            }
            ImGui.unindent(10f * s);
        }
        if (removeIdx >= 0) {
            list.remove(removeIdx);
            rt.saveActive();
        }
        ImGui.popID();
    }

    private static void renderStorageAreaEditor(GameProjectRuntime rt, String label, String id,
                                                List<int[]> areas, float s) {
        if (areas == null) return;
        ImGui.pushID("sarea_" + id);
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        int detectedChests = (mc != null && mc.level != null && !areas.isEmpty())
                ? dev.survivalkit.Api.scanStorageAreas(mc.level, List.of(), areas).size()
                : 0;
        UiTheme.textColored(DIM, label + " (" + areas.size() + " box" + (areas.size() == 1 ? "" : "es")
                + (areas.isEmpty() ? "" : ", " + detectedChests + " chest" + (detectedChests == 1 ? "" : "s")) + "):");
        ImGui.sameLine(0f, 6f * s);
        if (Widgets.button("+ Selection##addSel", 0)) {
            List<int[]> sel = Areas.read();
            if (sel == null || sel.isEmpty()) {
                setNotice("No current area selection. Select a box with Litematica or /queue area first.", true);
            } else {
                int added = 0;
                for (int[] b : sel) {
                    if (b == null || b.length < 6) continue;
                    int[] norm = new int[]{
                            Math.min(b[0], b[3]), Math.min(b[1], b[4]), Math.min(b[2], b[5]),
                            Math.max(b[0], b[3]), Math.max(b[1], b[4]), Math.max(b[2], b[5])
                    };
                    boolean dup = areas.stream().anyMatch(e -> java.util.Arrays.equals(e, norm));
                    if (!dup) {
                        areas.add(norm);
                        added++;
                    }
                }
                if (added > 0) {
                    rt.saveActive();
                    setNotice("Added " + added + " box(es) to " + label + ".", false);
                } else {
                    setNotice("Selection box already in " + label + ".", false);
                }
            }
        }
        ImGui.sameLine(0f, 4f * s);
        ImGui.setNextItemWidth(115f * s);
        if (ImGui.beginCombo("##pickArea", "+ Saved Area...")) {
            List<String> names = (Config.cfg != null && Config.cfg.areas != null)
                    ? new ArrayList<>(Config.cfg.areas.keySet())
                    : List.of();
            if (names.isEmpty()) {
                ImGui.textDisabled("No saved areas.");
            } else {
                for (String areaName : names) {
                    if (ImGui.selectable(areaName + "##sa_" + areaName, false)) {
                        List<int[]> boxes = Areas.getBoxes(areaName);
                        int added = 0;
                        if (boxes != null) {
                            for (int[] b : boxes) {
                                if (b == null || b.length < 6) continue;
                                int[] norm = new int[]{
                                        Math.min(b[0], b[3]), Math.min(b[1], b[4]), Math.min(b[2], b[5]),
                                        Math.max(b[0], b[3]), Math.max(b[1], b[4]), Math.max(b[2], b[5])
                                };
                                boolean dup = areas.stream().anyMatch(e -> java.util.Arrays.equals(e, norm));
                                if (!dup) {
                                    areas.add(norm);
                                    added++;
                                }
                            }
                        }
                        if (added > 0) {
                            rt.saveActive();
                            setNotice("Added '" + areaName + "' (" + added + " box(es)) to " + label + ".", false);
                        }
                    }
                }
            }
            ImGui.endCombo();
        }
        int removeIdx = -1;
        for (int i = 0; i < areas.size(); i++) {
            int[] b = areas.get(i);
            if (b == null || b.length < 6) continue;
            ImGui.indent(10f * s);
            UiTheme.textColored(FG, "#" + (i + 1) + ": [" + b[0] + "," + b[1] + "," + b[2] + "] -> [" + b[3] + "," + b[4] + "," + b[5] + "]");
            ImGui.sameLine(0f, 6f * s);
            if (Widgets.button("x##rmBox_" + i, 2)) {
                removeIdx = i;
            }
            ImGui.unindent(10f * s);
        }
        if (removeIdx >= 0) {
            areas.remove(removeIdx);
            rt.saveActive();
        }
        ImGui.popID();
    }

    private static void renderBedPoolEditor(GameProjectRuntime rt, Project.BasePlaces places, float s) {
        if (places.bedPool == null) places.bedPool = new ArrayList<>();
        UiTheme.textColored(DIM, "Bed Pool (" + places.bedPool.size() + " camp beds for multi-bot fleet):");
        ImGui.sameLine();
        if (Widgets.button("+ Add Here##bedPoolAdd", 0)) {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.player != null) {
                net.minecraft.core.BlockPos pos = mc.player.blockPosition();
                places.bedPool.add(new int[]{pos.getX(), pos.getY(), pos.getZ()});
                rt.saveActive();
                setNotice("Added bed pool spot at " + pos.toShortString() + ".", false);
            }
        }
        int removeIdx = -1;
        for (int i = 0; i < places.bedPool.size(); i++) {
            int[] b = places.bedPool.get(i);
            if (b == null || b.length != 3) continue;
            ImBoolean bvis = new ImBoolean(WorldOverlay.isPointVisible(WorldOverlay.Category.BED, b));
            if (ImGui.checkbox("##visBedPool_" + i, bvis)) {
                WorldOverlay.setPointVisible(WorldOverlay.Category.BED, b[0], b[1], b[2], bvis.get());
            }
            if (ImGui.isItemHovered()) ImGui.setTooltip("Show/hide in 3D World Overlay");
            ImGui.sameLine(0f, 4f * s);
            UiTheme.textColored(FG, "#" + (i + 1) + ": " + b[0] + ", " + b[1] + ", " + b[2]);
            renderPlaceStatusBadge(b, WorldOverlay.Category.BED, s);
            ImGui.sameLine();
            if (Widgets.button("x##rmBed_" + i, 2)) {
                removeIdx = i;
            }
        }
        if (removeIdx >= 0) {
            places.bedPool.remove(removeIdx);
            rt.saveActive();
        }
    }

    private static void renderFleetSubmitTab(Project active, float s) {
        dev.manhattan.fleet.FleetClient fc = dev.manhattan.fleet.FleetClient.instance();
        if (!fc.isConnected()) {
            UiTheme.textColored(DIM, "Not connected to a Fleet Server. Open the Fleet drawer to configure your mode.");
            return;
        }
        if (!fc.isEditorAllowed()) {
            UiTheme.textColored(WARN, "Switch to Editor or Editor+Worker mode in the Fleet drawer (and ensure server authorization).");
            return;
        }

        Widgets.label("Bot Assignment Policy");
        if (ImGui.radioButton("Auto / Pool##fleetModeAuto",
                fleetAssignMode == dev.manhattan.fleet.FleetProtocol.AssignmentMode.AUTO_POOL)) {
            fleetAssignMode = dev.manhattan.fleet.FleetProtocol.AssignmentMode.AUTO_POOL;
        }
        ImGui.sameLine();
        if (ImGui.radioButton("Specific Bots##fleetModeExplicit",
                fleetAssignMode == dev.manhattan.fleet.FleetProtocol.AssignmentMode.EXPLICIT)) {
            fleetAssignMode = dev.manhattan.fleet.FleetProtocol.AssignmentMode.EXPLICIT;
        }

        if (fleetAssignMode == dev.manhattan.fleet.FleetProtocol.AssignmentMode.EXPLICIT) {
            dev.manhattan.fleet.FleetProtocol.FleetSnapshot snap = fc.snapshot();
            if (snap == null || snap.bots.isEmpty()) {
                UiTheme.textColored(DIM, "No fleet bots seen yet.");
            } else {
                ImBoolean cb = new ImBoolean();
                for (dev.manhattan.fleet.FleetProtocol.BotInfo bot : snap.bots) {
                    if ("EDITOR".equals(bot.mode) || "LOCAL".equals(bot.mode)) continue;
                    cb.set(fleetSelectedBots.contains(bot.name));
                    String label = bot.name + (bot.online ? " [ON]" : " [OFF]") + "##pickBot_" + bot.name;
                    if (ImGui.checkbox(label, cb)) {
                        if (cb.get()) fleetSelectedBots.add(bot.name);
                        else fleetSelectedBots.remove(bot.name);
                    }
                }
            }
        } else {
            ImGui.setNextItemWidth(160f * s);
            ImGui.inputText("Pool Tag (optional)##fleetPoolTag", fleetPoolTag);
            ImGui.setNextItemWidth(90f * s);
            ImGui.inputInt("Max Bots##fleetMaxBots", fleetMaxBots);
            if (fleetMaxBots.get() < 1) fleetMaxBots.set(1);
            ImGui.setNextItemWidth(110f * s);
            ImGui.inputInt("Max Distance (0=any)##fleetMaxDist", fleetMaxDist);
            if (fleetMaxDist.get() < 0) fleetMaxDist.set(0);
        }

        ImGui.setNextItemWidth(90f * s);
        ImGui.inputInt("Priority##fleetPrio", fleetPriority);
        ImGui.sameLine(0f, 12f * s);
        ImInt chunkSizeBox = new ImInt(active.chunkSize > 0 ? active.chunkSize : Config.cfg.chunkSize);
        ImGui.setNextItemWidth(90f * s);
        if (ImGui.inputInt("Chunk Size##fleetChunkSize", chunkSizeBox)) {
            active.chunkSize = Math.max(8, Math.min(128, chunkSizeBox.get()));
            GameProjectRuntime.instance().saveActive();
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Horizontal chunk size (default 16 = 1 Minecraft chunk). Embedded on submit so all workers match.");
        }

        ImGui.spacing();
        List<String> chunkDiags = new ArrayList<>();
        List<dev.manhattan.fleet.FleetProtocol.ChunkSpec> chunks = QueueMod.collectProjectChunkSpecs(active, chunkDiags);
        UiTheme.textColored(chunks.isEmpty() ? WARN : DIM,
                "Project '" + active.name + "' -> " + chunks.size() + " chunk(s) ready to distribute.");
        for (String diag : chunkDiags) {
            UiTheme.textColored(ERR, "  ! " + diag);
        }

        if (Widgets.button("Submit Project to Fleet##fleetSubmitBtn", 1)) {
            if (chunks.isEmpty()) {
                String reason = chunkDiags.isEmpty()
                        ? "Project produced 0 chunks (enable a task with a valid area or schematic placement first)."
                        : String.join(" | ", chunkDiags);
                setNotice("Cannot submit 0 chunks: " + reason, true);
                return;
            }
            dev.manhattan.fleet.FleetProtocol.AssignmentPolicy pol = new dev.manhattan.fleet.FleetProtocol.AssignmentPolicy();
            pol.mode = fleetAssignMode;
            pol.explicitBots = new ArrayList<>(fleetSelectedBots);
            pol.poolTag = fleetPoolTag.get().trim();
            pol.maxBots = Math.max(1, fleetMaxBots.get());
            pol.maxDistance = Math.max(0, fleetMaxDist.get());
            pol.priority = fleetPriority.get();

            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            String dim = (mc != null && mc.level != null)
                    ? mc.level.dimension().identifier().toString()
                    : "minecraft:overworld";
            Project prepared = QueueMod.prepareProjectForFleetSubmit(active);
            if (fc.submitProject(prepared, dim, pol, chunks)) {
                int schemCount = prepared != null && prepared.embeddedSchematics != null ? prepared.embeddedSchematics.size() : 0;
                String schemNote = schemCount > 0 ? ", " + schemCount + " embedded schematic(s)" : "";
                setNotice("Submitted '" + active.name + "' to Fleet Server (" + chunks.size() + " chunks" + schemNote + ").", false);
            } else {
                setNotice(fc.lastNotice(), true);
            }
        }
    }

    private static void setNotice(String msg, boolean error) {
        notice = msg == null ? "" : msg;
        noticeError = error;
    }
}
