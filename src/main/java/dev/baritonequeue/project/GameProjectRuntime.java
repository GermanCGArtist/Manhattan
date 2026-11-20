package dev.baritonequeue.project;

import dev.baritonequeue.Areas;
import dev.baritonequeue.QueueMod;
import dev.midtown.api.EngineSelector;
import dev.midtown.api.RuntimeBus;
import dev.midtown.body.BodyController;
import dev.midtown.body.HotbarController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * In-game runtime bridge connecting {@link PriorityScheduler}, {@link ProjectStore}, and {@link TaskQueue}
 * to Minecraft client systems, survival helpers, build helpers, and Midtown.
 */
public final class GameProjectRuntime implements PriorityScheduler.Env {

    private static final GameProjectRuntime INSTANCE = new GameProjectRuntime();

    public static GameProjectRuntime instance() {
        return INSTANCE;
    }

    private final PriorityScheduler scheduler;
    private String loadedWorld;
    private Project activeProject;
    private TaskQueue.TaskLedger activeLedger;
    private ProjectActivationSnapshot activationSnapshot;
    private OverrideSnapshot overrideSnapshot;
    private int eatCooldownTicks;
    private int sleepCooldownTicks;
    private int baseTripCooldownTicks;
    private int emergencyCooldownTicks;

    private GameProjectRuntime() {
        this.scheduler = new PriorityScheduler(this);
    }

    // ------------------------------------------------------------------ public API contract

    public void tick(Minecraft mc) {
        if (eatCooldownTicks > 0) eatCooldownTicks--;
        if (sleepCooldownTicks > 0) sleepCooldownTicks--;
        if (baseTripCooldownTicks > 0) baseTripCooldownTicks--;
        if (emergencyCooldownTicks > 0) emergencyCooldownTicks--;

        if (mc == null || mc.player == null || mc.level == null) {
            return;
        }
        if (!scheduler.isRunning()) {
            return;
        }

        RuntimeBus.setScriptActive(true);
        String halt = RuntimeBus.consumeHaltReason();
        if (halt != null) {
            stopQueue(halt);
            return;
        }

        scheduler.tick();

        if (scheduler.isRunning()) {
            PriorityScheduler.Telemetry tel = scheduler.telemetry();
            RuntimeBus.setCurrentStep(tel.detail());
        }
    }

    public Project activeProject() {
        ensureLoaded();
        return activeProject;
    }

    public List<String> projectIds() {
        String world = currentWorld();
        return ProjectStore.list(projectsDir(), world);
    }

    public void selectProject(String id) {
        if (id == null || id.isBlank()) return;
        String world = currentWorld();
        String cleanId = ProjectStore.sanitizeId(id);
        Project loaded = ProjectStore.load(projectsDir(), world, cleanId);
        if (loaded == null) {
            return;
        }
        if (scheduler.isRunning()) {
            stopQueue("Switched active project to '" + cleanId + "'.");
        }
        this.loadedWorld = world;
        this.activeProject = loaded;
        this.activeLedger = QueueMod.projectOpenLedger(world, loaded.id);
        ProjectStore.setActiveId(projectsDir(), world, loaded.id);
    }

    public Project createProject(String id, String name) {
        String world = currentWorld();
        Project created = ProjectStore.createDefault(world, id, name);
        ProjectStore.save(projectsDir(), created);
        if (scheduler.isRunning()) {
            stopQueue("Created and switched to project '" + created.id + "'.");
        }
        this.loadedWorld = world;
        this.activeProject = created;
        this.activeLedger = QueueMod.projectOpenLedger(world, created.id);
        ProjectStore.setActiveId(projectsDir(), world, created.id);
        return created;
    }

    public boolean deleteProject(String id) {
        if (id == null || id.isBlank()) return false;
        String world = currentWorld();
        String cleanId = ProjectStore.sanitizeId(id);
        if (activeProject != null && cleanId.equals(activeProject.id) && scheduler.isRunning()) {
            stopQueue("Deleted active project '" + cleanId + "'.");
        }
        boolean deleted = ProjectStore.delete(projectsDir(), world, cleanId);
        if (deleted && activeProject != null && cleanId.equals(activeProject.id)) {
            activeProject = null;
            activeLedger = null;
            ensureLoaded();
        }
        return deleted;
    }

    public void saveActive() {
        if (activeProject == null) return;
        activeProject.ensureInitialized();
        if (activeProject.world == null || activeProject.world.isBlank()) {
            activeProject.world = currentWorld();
        }
        if (activeProject.id == null || activeProject.id.isBlank()) {
            activeProject.id = "default";
        }
        ProjectStore.save(projectsDir(), activeProject);
        ProjectStore.setActiveId(projectsDir(), activeProject.world, activeProject.id);
    }

    public String startQueue() {
        ensureLoaded();
        if (activeProject == null) {
            return "No active project loaded.";
        }
        return startQueue(activeProject);
    }

    public String startQueue(Project project) {
        if (project == null) {
            return "Project is null.";
        }
        if (scheduler.isRunning()) {
            return "Project queue is already running.";
        }
        if (QueueMod.projectRunnerRunning()) {
            return "A standalone script is already running. Stop it first.";
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null) {
            return "Not in a world.";
        }

        project.ensureInitialized();
        String world = currentWorld();
        if (project.world == null || project.world.isBlank()) {
            project.world = world;
        }
        if (project.id == null || project.id.isBlank()) {
            project.id = "default";
        }
        project.id = ProjectStore.sanitizeId(project.id);

        this.loadedWorld = world;
        this.activeProject = project;
        this.activeLedger = QueueMod.projectOpenLedger(project.world, project.id);

        eatCooldownTicks = 0;
        sleepCooldownTicks = 0;
        baseTripCooldownTicks = 0;
        emergencyCooldownTicks = 0;

        activateProject(project);
        RuntimeBus.setPaused(false);
        RuntimeBus.setScriptActive(true);
        RuntimeBus.setRecoveryGaveUpReason(null);
        dev.survivalkit.Api.onBeforeDisconnect = () -> {
            if (running()) stopQueue("Disconnected.");
        };

        scheduler.start(project, activeLedger);
        return null;
    }

    public void stopQueue(String reason) {
        scheduler.stop(reason);
        cleanupAfterQueueEnd();
    }

    public void stopQueue() {
        stopQueue("Stopped by user.");
    }

    public void skipCurrent() {
        scheduler.skipCurrent();
    }

    public boolean running() {
        return scheduler.isRunning();
    }

    public PriorityScheduler.Telemetry telemetry() {
        return scheduler.telemetry();
    }

    public PriorityScheduler scheduler() {
        return scheduler;
    }

    public TaskQueue.TaskLedger.State taskState(String taskId) {
        if (taskId == null) return TaskQueue.TaskLedger.State.PENDING;
        TaskQueue q = scheduler.queue();
        if (q != null && q.project() == activeProject) {
            return q.stateOf(taskId);
        }
        ensureLoaded();
        if (activeLedger == null) return TaskQueue.TaskLedger.State.PENDING;
        TaskQueue.TaskLedger.EntryView ev = activeLedger.get(taskId);
        return ev != null && ev.state() != null ? ev.state() : TaskQueue.TaskLedger.State.PENDING;
    }

    public void enqueueTask(ProjectTask task) {
        if (task == null) return;
        ensureLoaded();
        if (scheduler.queue() != null && scheduler.queue().project() == activeProject) {
            scheduler.enqueue(task);
        } else {
            new TaskQueue(activeProject, activeLedger).add(task);
        }
        saveActive();
    }

    public void removeTask(String taskId) {
        if (taskId == null) return;
        ensureLoaded();
        if (scheduler.queue() != null && scheduler.queue().project() == activeProject) {
            scheduler.remove(taskId);
        } else {
            new TaskQueue(activeProject, activeLedger).remove(taskId);
        }
        saveActive();
    }

    public void moveTask(int fromIdx, int toIdx) {
        ensureLoaded();
        if (scheduler.queue() != null && scheduler.queue().project() == activeProject) {
            scheduler.move(fromIdx, toIdx);
        } else {
            new TaskQueue(activeProject, activeLedger).move(fromIdx, toIdx);
        }
        saveActive();
    }

    public void skipTask(String taskId) {
        if (taskId == null) return;
        ensureLoaded();
        if (scheduler.queue() != null && scheduler.queue().project() == activeProject) {
            scheduler.skipTask(taskId);
        } else {
            new TaskQueue(activeProject, activeLedger).skip(taskId);
        }
    }

    public void retryTask(String taskId) {
        ensureLoaded();
        if (scheduler.queue() != null && scheduler.queue().project() == activeProject) {
            scheduler.retryTask(taskId);
        } else {
            new TaskQueue(activeProject, activeLedger).retry(taskId);
        }
    }

    public void resetQueue(String taskIdOrNull) {
        ensureLoaded();
        if (scheduler.queue() != null && scheduler.queue().project() == activeProject) {
            scheduler.resetQueue(taskIdOrNull);
        } else {
            new TaskQueue(activeProject, activeLedger).reset(taskIdOrNull);
        }
    }

    public String importCaptureIntoActive() {
        ensureLoaded();
        String text = QueueMod.projectCaptureText();
        if (text == null || text.isBlank()) {
            return "Capture sheet is empty.";
        }
        ProjectStore.ImportResult res = ProjectStore.importFromCaptureText(activeProject, text);
        if (res.totalImported() == 0) {
            return "No recognized places, material chests, or protected areas found in the capture sheet.";
        }
        saveActive();
        return "Imported " + res.placesImported() + " base place(s), "
                + res.materialChestsImported() + " material chest(s), and "
                + res.protectedAreasImported() + " protected area(s) into '" + activeProject.id + "'.";
    }

    // ------------------------------------------------------------------ PriorityScheduler.Env

    @Override
    public boolean isRecovering() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null && mc.player.isDeadOrDying()) {
            return true;
        }
        return RuntimeBus.isRecovering();
    }

    @Override
    public boolean isEmergencyNeeded() {
        if (emergencyCooldownTicks > 0) return false;
        return dev.buildtools.Api.isEmergencyNeeded();
    }

    @Override
    public int threats(double range) {
        return dev.survivalkit.Api.threats(range);
    }

    @Override
    public double health() {
        Minecraft mc = Minecraft.getInstance();
        return (mc == null || mc.player == null) ? 20.0 : mc.player.getHealth();
    }

    @Override
    public int food() {
        Minecraft mc = Minecraft.getInstance();
        return (mc == null || mc.player == null) ? 20 : mc.player.getFoodData().getFoodLevel();
    }

    @Override
    public boolean isBedTime() {
        if (sleepCooldownTicks > 0) return false;
        return dev.survivalkit.Api.bedTime();
    }

    @Override
    public int ticksUntilDusk() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) return -1;
        if (mc.level.dimension() != Level.OVERWORLD) return -1;
        if (mc.level.isDarkOutside()) return 0;
        if (activeProject != null && activeProject.survival.sleepWeather && mc.level.isThundering()) return 0;
        long time = mc.level.getOverworldClockTime() % 24000L;
        if (time >= 12542L && time < 23460L) return 0;
        if (time < 12542L) return (int) (12542L - time);
        return (int) (24000L - time + 12542L);
    }

    @Override
    public LogisticsPlanner.InventoryView inventoryView() {
        return new LogisticsPlanner.InventoryView() {
            @Override
            public int freeSlots() {
                Minecraft mc = Minecraft.getInstance();
                LocalPlayer p = mc == null ? null : mc.player;
                if (p == null) return 36;
                int free = 0;
                for (int i = 0; i < 36; i++) {
                    if (p.getInventory().getItem(i).isEmpty()) free++;
                }
                return free;
            }

            @Override
            public int count(String item) {
                Minecraft mc = Minecraft.getInstance();
                LocalPlayer p = mc == null ? null : mc.player;
                if (p == null || item == null || item.isBlank()) return 0;
                int total = 0;
                String normalized = item.trim().toLowerCase(Locale.ROOT);
                String rule = normalized.equals("food") ? "@food" : normalized;
                for (int i = 0; i < 36; i++) {
                    ItemStack st = p.getInventory().getItem(i);
                    if (st.isEmpty()) continue;
                    if (st.isDamageableItem() && dev.survivalkit.Api.worn(st)) continue;
                    if (dev.survivalkit.Api.wantMatches(st, rule)) {
                        total += st.getCount();
                    }
                }
                return total;
            }

            @Override
            public boolean restockNeeded() {
                return dev.survivalkit.Api.restockNeeded();
            }

            @Override
            public boolean hasWornTools() {
                return dev.survivalkit.Api.hasWornTools();
            }
        };
    }

    @Override
    public PriorityScheduler.ActionHandle startEmergency() {
        if (emergencyCooldownTicks > 0) return null;
        if (!dev.buildtools.Api.startEmergencyNow()) {
            emergencyCooldownTicks = 40;
            return null;
        }
        return QueueMod.projectCreateFeatureHandle(
                () -> dev.buildtools.Api.emergencyBusy() ? null : "",
                dev.buildtools.Api::emergencyBusy,
                dev.buildtools.Api::stopEmergency);
    }

    @Override
    public PriorityScheduler.ActionHandle startDefend() {
        String err = dev.survivalkit.Api.defendNow();
        if (err != null) return null;
        return new PriorityScheduler.ActionHandle() {
            private boolean done;
            private int ticks;

            @Override public boolean isDone() { return done; }
            @Override public String error() { return null; }
            @Override public int remainingWork() { return 0; }
            @Override public void cancel() { dev.survivalkit.Api.stopDefending(); done = true; }
            @Override public void suspend() { dev.survivalkit.Api.stopDefending(); }
            @Override public void resume() { if (!done) dev.survivalkit.Api.defendNow(); }

            @Override
            public void tick() {
                if (done) return;
                ticks++;
                if (ticks >= 2 && !dev.survivalkit.Api.fighting()) {
                    done = true;
                }
            }
        };
    }

    @Override
    public PriorityScheduler.ActionHandle startEat() {
        if (eatCooldownTicks > 0) return null;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc == null ? null : mc.player;
        if (p == null) return null;
        if (!p.getFoodData().needsFood() || !hasEdibleFood(p)) {
            eatCooldownTicks = 60;
            return null;
        }
        String err = dev.survivalkit.Api.eatNow();
        if (err != null) {
            eatCooldownTicks = 60;
            return null;
        }
        int foodBefore = p.getFoodData().getFoodLevel();
        return new PriorityScheduler.ActionHandle() {
            private boolean done;
            private int ticks;

            @Override public boolean isDone() { return done; }
            @Override public String error() { return null; }
            @Override public int remainingWork() { return 0; }
            @Override public void cancel() { dev.survivalkit.Api.stopEating(); done = true; }
            @Override public void suspend() { dev.survivalkit.Api.stopEating(); }
            @Override public void resume() { if (!done) dev.survivalkit.Api.eatNow(); }

            @Override
            public void tick() {
                if (done) return;
                ticks++;
                if ((ticks >= 3 && !dev.survivalkit.Api.eating()) || ticks > 160) {
                    if (ticks > 160) dev.survivalkit.Api.stopEating();
                    LocalPlayer cur = Minecraft.getInstance().player;
                    if (cur != null && cur.getFoodData().getFoodLevel() <= foodBefore) {
                        eatCooldownTicks = 80;
                    }
                    done = true;
                }
            }
        };
    }

    @Override
    public PriorityScheduler.ActionHandle startSleep() {
        if (sleepCooldownTicks > 0) return null;
        String err = dev.survivalkit.Api.sleepNow(true);
        if (err != null) {
            sleepCooldownTicks = 80;
            return null;
        }
        return new PriorityScheduler.ActionHandle() {
            private boolean done;
            private int ticks;

            @Override public boolean isDone() { return done; }
            @Override public String error() { return null; }
            @Override public int remainingWork() { return 0; }
            @Override public void cancel() { dev.survivalkit.Api.stopSleeping(); done = true; }
            @Override public void suspend() { dev.survivalkit.Api.stopSleeping(); }
            @Override public void resume() { if (!done) dev.survivalkit.Api.sleepNow(true); }

            @Override
            public void tick() {
                if (done) return;
                ticks++;
                if (ticks >= 2 && !dev.survivalkit.Api.sleeping()) {
                    if (isBedTime()) {
                        sleepCooldownTicks = 120;
                    }
                    done = true;
                }
            }
        };
    }

    @Override
    public PriorityScheduler.ActionHandle startBaseTrip(LogisticsPlanner.BaseTripPlan plan) {
        if (plan == null || plan.isEmpty() || baseTripCooldownTicks > 0) return null;
        Project proj = activeProject;
        if (proj == null) return null;
        proj.ensureInitialized();

        List<Supplier<PriorityScheduler.ActionHandle>> stepFactories = new ArrayList<>();
        boolean hasDump = plan.steps().contains(LogisticsPlanner.StepKind.DUMP_EXCESS) && proj.places.dumpChest != null;
        boolean hasRestock = plan.steps().contains(LogisticsPlanner.StepKind.RESTOCK_TOOLS_FOOD) && proj.places.toolChest != null;
        boolean hasReturn = plan.steps().contains(LogisticsPlanner.StepKind.RETURN_WORN)
                && (proj.places.returnChest != null || proj.places.toolChest != null);

        // Order: DUMP_EXCESS frees slots before RESTOCK; RETURN_WORN is performed via RestockProcess
        // (which visits returnChest first, then toolChest) so fresh tools are never accidentally deposited.
        if (hasReturn && !hasRestock) {
            stepFactories.add(() -> QueueMod.projectCreateFeatureHandle(
                    dev.survivalkit.Api::restockNow,
                    dev.survivalkit.Api::restocking,
                    dev.survivalkit.Api::stopRestocking));
        }
        if (hasDump) {
            BlockPos dumpPos = toBlockPos(proj.places.dumpChest);
            stepFactories.add(() -> QueueMod.projectCreateDumpHandle(dumpPos));
        }
        if (hasRestock) {
            stepFactories.add(() -> QueueMod.projectCreateFeatureHandle(
                    dev.survivalkit.Api::restockNow,
                    dev.survivalkit.Api::restocking,
                    dev.survivalkit.Api::stopRestocking));
        }
        if (plan.steps().contains(LogisticsPlanner.StepKind.FETCH_MATERIALS)
                && !plan.materialsToFetch().isEmpty()
                && !proj.places.materialChests.isEmpty()) {
            for (var entry : plan.materialsToFetch().entrySet()) {
                String itemName = entry.getKey();
                int qty = entry.getValue() == null ? 0 : entry.getValue();
                if (itemName == null || itemName.isBlank() || qty <= 0) continue;
                Item item = QueueMod.projectResolveItem(itemName);
                if (item == null) continue;
                List<BlockPos> chests = orderMaterialChests(proj.places.materialChests, item);
                for (BlockPos chestPos : chests) {
                    stepFactories.add(() -> {
                        int needNow = Math.max(0, qty - countExactItem(item));
                        if (needNow <= 0) return null;
                        return QueueMod.projectCreateChestGetHandle(chestPos, item, needNow, false, true);
                    });
                }
            }
        }

        if (stepFactories.isEmpty()) {
            baseTripCooldownTicks = 60;
            return null;
        }

        int freeBefore = inventoryView().freeSlots();
        return new SequentialHandle(stepFactories, null, (err) -> {
            int freeAfter = inventoryView().freeSlots();
            boolean progressed = err == null && (freeAfter > freeBefore || !inventoryView().restockNeeded());
            baseTripCooldownTicks = progressed ? 40 : 200;
        });
    }

    @Override
    public PriorityScheduler.ActionHandle startTask(ProjectTask task, ScopedOverrides overrides) {
        if (task == null) return null;
        task.ensureInitialized();
        Project proj = activeProject;
        if (proj == null) return null;

        return switch (task.kind) {
            case FILL_WATER -> startFillWaterTask(proj, task);
            case CLEAR_AREA -> startClearAreaTask(proj, task);
            case CLEAR_TREES -> startClearTreesTask(proj, task);
            case SCHEM_BUILD -> QueueMod.projectCreateSchemBuildHandle(
                    task.placement, task.schemMode, task.schemMissing, task.id);
            case FLOOD -> startFloodTask(task);
            case DRAIN -> startDrainTask(task);
            case MINE -> startMineTask(task);
            case DUMP -> QueueMod.projectCreateDumpHandle(toBlockPos(proj.places.dumpChest));
            case RESTOCK -> startRestockTask(proj);
            case SCRIPT -> startScriptTask(proj, task);
        };
    }

    @Override
    public void applyOverrides(ScopedOverrides overrides) {
        if (overrides == null || overrides.isEmpty()) return;
        if (overrideSnapshot != null) {
            restoreOverrides();
        }
        OverrideSnapshot snap = new OverrideSnapshot();
        if (overrides.allowBreak != null) {
            snap.hadBreak = true;
            snap.prevBreak = dev.buildtools.Api.setOwnBreak(overrides.allowBreak);
        }
        if (overrides.allowPlace != null) {
            snap.hadPlace = true;
            snap.prevPlace = dev.buildtools.Api.setOwnPlace(overrides.allowPlace);
        }
        if (overrides.keep != null && !overrides.keep.isEmpty()) {
            snap.prevKeep = new LinkedHashMap<>(dev.loadchest.LoadChestMod.keepRules());
            for (var e : overrides.keep.entrySet()) {
                String k = e.getKey().toLowerCase(Locale.ROOT);
                if (k.equals("food")) k = "@food";
                int n = e.getValue() == null ? 0 : e.getValue();
                if (n == 0) dev.loadchest.LoadChestMod.keepRules().remove(k);
                else dev.loadchest.LoadChestMod.keepRules().put(k, n);
            }
            dev.loadchest.LoadChestMod.saveSettings();
        }
        if (overrides.want != null && !overrides.want.isEmpty()) {
            snap.prevWant = new LinkedHashMap<>(dev.survivalkit.Api.wantList());
            for (var e : overrides.want.entrySet()) {
                int n = e.getValue() == null ? 0 : e.getValue();
                dev.survivalkit.Api.want(e.getKey(), n);
            }
        }
        overrideSnapshot = snap;
    }

    @Override
    public void restoreOverrides() {
        OverrideSnapshot snap = overrideSnapshot;
        overrideSnapshot = null;
        if (snap == null) return;
        if (snap.hadBreak) {
            dev.buildtools.Api.setOwnBreak(snap.prevBreak);
        }
        if (snap.hadPlace) {
            dev.buildtools.Api.setOwnPlace(snap.prevPlace);
        }
        if (snap.prevKeep != null) {
            var rules = dev.loadchest.LoadChestMod.keepRules();
            rules.clear();
            rules.putAll(snap.prevKeep);
            dev.loadchest.LoadChestMod.saveSettings();
        }
        if (snap.prevWant != null) {
            var want = dev.survivalkit.Api.wantList();
            want.clear();
            want.putAll(snap.prevWant);
            dev.survivalkit.Api.save();
        }
    }

    @Override
    public void onLog(String message) {
        if (message != null && !message.isBlank()) {
            QueueMod.projectLog(message);
        }
    }

    @Override
    public void onQueueComplete(boolean success, String summary, boolean logoff) {
        cleanupAfterQueueEnd();
        String stamped = summary + "|" + System.currentTimeMillis();
        RuntimeBus.setFinishedSummary(stamped);
        System.setProperty("baritonequeue.finished", stamped);
        if (logoff) {
            dev.survivalkit.Api.disconnect(summary);
        }
    }

    // ------------------------------------------------------------------ task factories

    private PriorityScheduler.ActionHandle startFillWaterTask(Project proj, ProjectTask task) {
        List<int[]> boxes = resolveTaskBoxes(task);
        if (boxes == null || boxes.isEmpty()) {
            return failedHandle("No area/selection available for FILL_WATER task '" + task.displayLabel() + "'.");
        }
        BlockPos chestPos = null;
        if (!proj.places.materialChests.isEmpty()) {
            chestPos = toBlockPos(proj.places.materialChests.get(0));
        } else if (proj.places.scaffoldChest != null) {
            chestPos = toBlockPos(proj.places.scaffoldChest);
        }
        return QueueMod.projectCreateFillWaterHandle(task.block, boxes, chestPos, task.id);
    }

    private PriorityScheduler.ActionHandle startClearAreaTask(Project proj, ProjectTask task) {
        List<int[]> boxes = resolveTaskBoxes(task);
        if (boxes == null || boxes.isEmpty()) {
            return failedHandle("No area/selection available for CLEAR_AREA task '" + task.displayLabel() + "'.");
        }
        PriorityScheduler.ActionHandle mainHandle = QueueMod.projectCreateClearAreaHandle(task.layers, boxes, task.id);
        if (!task.collectScaffoldAfter && !task.pickupAfter && !task.dumpAfter) {
            return mainHandle;
        }
        List<Supplier<PriorityScheduler.ActionHandle>> steps = new ArrayList<>();
        steps.add(() -> mainHandle);
        if (task.collectScaffoldAfter) {
            steps.add(() -> dev.buildtools.Api.scaffoldCount() > 0 ? QueueMod.projectCreateCollectScaffoldHandle() : null);
        }
        if (task.pickupAfter) {
            steps.add(QueueMod::projectCreatePickupHandle);
        }
        if (task.dumpAfter && proj.places.dumpChest != null) {
            BlockPos dumpPos = toBlockPos(proj.places.dumpChest);
            steps.add(() -> QueueMod.projectCreateDumpHandle(dumpPos));
        }
        return new SequentialHandle(steps, mainHandle::remainingWork, null);
    }

    private PriorityScheduler.ActionHandle startClearTreesTask(Project proj, ProjectTask task) {
        List<int[]> boxes = resolveTaskBoxes(task);
        if (boxes == null || boxes.isEmpty()) {
            return failedHandle("No area/selection available for CLEAR_TREES task '" + task.displayLabel() + "'.");
        }
        return QueueMod.projectStartClearTrees(
                boxes,
                task.id,
                proj.places.dumpChest,
                !proj.survival.batchBaseTrips,
                task.treeZoneDiameter);
    }

    private PriorityScheduler.ActionHandle startFloodTask(ProjectTask task) {
        List<int[]> boxes = resolveTaskBoxes(task);
        if (boxes == null || boxes.isEmpty()) {
            return failedHandle("No area/selection available for FLOOD task '" + task.displayLabel() + "'.");
        }
        return QueueMod.projectCreateFloodHandle(boxes);
    }

    private PriorityScheduler.ActionHandle startDrainTask(ProjectTask task) {
        List<int[]> boxes = resolveTaskBoxes(task);
        if (boxes == null || boxes.isEmpty()) {
            return failedHandle("No area/selection available for DRAIN task '" + task.displayLabel() + "'.");
        }
        return QueueMod.projectCreateDrainHandle(boxes, task.id);
    }

    private PriorityScheduler.ActionHandle startMineTask(ProjectTask task) {
        List<int[]> boxes = (task.areaName != null && !task.areaName.isBlank()) ? resolveTaskBoxes(task) : null;
        return QueueMod.projectCreateMineHandle(task.mineBlocks, boxes);
    }

    private PriorityScheduler.ActionHandle startRestockTask(Project proj) {
        if (proj.places.toolChest == null) {
            return failedHandle("No toolChest configured on project for RESTOCK task.");
        }
        return QueueMod.projectCreateFeatureHandle(
                dev.survivalkit.Api::restockNow,
                dev.survivalkit.Api::restocking,
                dev.survivalkit.Api::stopRestocking);
    }

    private PriorityScheduler.ActionHandle startScriptTask(Project proj, ProjectTask task) {
        List<String> lines;
        if (task.inlineScript != null && !task.inlineScript.isBlank()) {
            lines = Arrays.asList(task.inlineScript.split("\\r?\\n"));
        } else if (task.scriptPath != null && !task.scriptPath.isBlank()) {
            lines = QueueMod.projectReadScript(task.scriptPath);
            if (lines == null) {
                return failedHandle("Script file not found: " + task.scriptPath);
            }
        } else {
            return failedHandle("SCRIPT task '" + task.displayLabel() + "' has neither inlineScript nor scriptPath.");
        }
        Map<String, Object> places = buildProjectPlacesMap(proj);
        String err = QueueMod.projectRunnerStart(lines, task.id, task.params, places);
        if (err != null) {
            return failedHandle(err);
        }
        return new RunnerScriptHandle(null);
    }

    private static Map<String, Object> buildProjectPlacesMap(Project proj) {
        Map<String, Object> places = new LinkedHashMap<>();
        places.put("bed", proj.places.bed);
        places.put("tool_chest", proj.places.toolChest);
        places.put("return_chest", proj.places.returnChest);
        places.put("dump_chest", proj.places.dumpChest);
        places.put("scaffold_chest", proj.places.scaffoldChest);
        places.put("material_chests", new ArrayList<>(proj.places.materialChests));
        return places;
    }

    private static List<int[]> resolveTaskBoxes(ProjectTask task) {
        List<int[]> raw;
        if (task.areaName != null && !task.areaName.isBlank()) {
            raw = Areas.getBoxes(task.areaName);
            if (raw == null || raw.isEmpty()) return null;
        } else {
            raw = Areas.read();
            if (raw == null || raw.isEmpty()) return null;
        }
        if (task.expand == 0) {
            return raw;
        }
        return expandBoxesHorizontal(raw, task.expand);
    }

    private static List<int[]> expandBoxesHorizontal(List<int[]> boxes, int delta) {
        List<int[]> out = new ArrayList<>(boxes.size());
        for (int[] b : boxes) {
            if (b == null || b.length < 6) continue;
            int x1 = Math.min(b[0], b[3]) - delta;
            int y1 = Math.min(b[1], b[4]);
            int z1 = Math.min(b[2], b[5]) - delta;
            int x2 = Math.max(b[0], b[3]) + delta;
            int y2 = Math.max(b[1], b[4]);
            int z2 = Math.max(b[2], b[5]) + delta;
            if (x1 <= x2 && y1 <= y2 && z1 <= z2) {
                out.add(new int[]{x1, y1, z1, x2, y2, z2});
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ activation & teardown

    private void activateProject(Project p) {
        if (activationSnapshot != null) {
            deactivateProject();
        }
        ProjectActivationSnapshot snap = new ProjectActivationSnapshot();
        String[] optKeys = {
                "survival.bed", "survival.supply", "survival.returnChest",
                "survival.eat", "survival.eatBelow", "survival.defence",
                "survival.sleep", "survival.bedLead", "survival.weather", "survival.logoffOnMissingBed",
                "survival.restock", "survival.respawn", "survival.resume", "survival.reconnect", "survival.maxDeaths",
                "buildtools.escape", "buildtools.scaffoldAuto", "buildtools.treeZoneDiameter", "buildtools.showTreeZones",
                "loadchest.chest", "loadchest.auto", "loadchest.pickup"
        };
        for (String key : optKeys) {
            snap.prevOptions.put(key, QueueMod.projectGetOption(key));
        }
        snap.prevWant = new LinkedHashMap<>(dev.survivalkit.Api.wantList());
        snap.prevKeep = new LinkedHashMap<>(dev.loadchest.LoadChestMod.keepRules());
        snap.prevHotbarMask = HotbarController.reservedSlotMask();
        snap.prevHotbarPins = QueueMod.projectSnapshotHotbarPins();
        snap.prevItemSaver = EngineSelector.current().getSetting("itemSaver");
        snap.prevItemSaverThreshold = EngineSelector.current().getSetting("itemSaverThreshold");

        // Places
        if (p.places.bed != null) QueueMod.projectSetPosOption("survival.bed", p.places.bed);
        if (p.places.toolChest != null) QueueMod.projectSetPosOption("survival.supply", p.places.toolChest);
        if (p.places.returnChest != null) QueueMod.projectSetPosOption("survival.returnChest", p.places.returnChest);
        if (p.places.dumpChest != null) QueueMod.projectSetPosOption("loadchest.chest", p.places.dumpChest);

        // Protected areas
        for (var entry : p.places.protectedAreas.entrySet()) {
            String groupName = "project_" + p.id + "_" + entry.getKey();
            dev.buildtools.Api.protectGroup(groupName, entry.getValue());
            snap.addedProtectGroups.add(groupName);
        }

        // PriorityScheduler owns P0..P3 triggering; disable background helper auto-triggers so they do not compete
        QueueMod.projectSetOption("survival.eat", false);
        QueueMod.projectSetOption("survival.defence", false);
        QueueMod.projectSetOption("survival.sleep", false);
        QueueMod.projectSetOption("survival.restock", false);
        QueueMod.projectSetOption("buildtools.escape", false);
        QueueMod.projectSetOption("buildtools.scaffoldAuto", false);
        QueueMod.projectSetOption("buildtools.treeZoneDiameter", (double) Math.max(3, p.survival.treeZoneDiameter));
        QueueMod.projectSetOption("buildtools.showTreeZones", p.survival.showTreeZones);
        QueueMod.projectSetOption("loadchest.auto", false);
        QueueMod.projectSetOption("loadchest.pickup", false);

        // Sync survival thresholds & recovery options
        QueueMod.projectSetOption("survival.eatBelow", (double) p.survival.eatFoodBelow);
        QueueMod.projectSetOption("survival.bedLead", (double) p.survival.bedLeadSeconds);
        QueueMod.projectSetOption("survival.weather", p.survival.sleepWeather);
        QueueMod.projectSetOption("survival.logoffOnMissingBed", p.survival.logoffOnMissingBed);
        QueueMod.projectSetOption("survival.respawn", p.survival.respawn);
        QueueMod.projectSetOption("survival.resume", p.survival.resumeAfterDeath);
        QueueMod.projectSetOption("survival.reconnect", p.survival.reconnect);
        QueueMod.projectSetOption("survival.maxDeaths", (double) p.survival.maxDeaths);

        // Sync want & keep lists
        var want = dev.survivalkit.Api.wantList();
        want.clear();
        for (var e : p.survival.want.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue() <= 0) continue;
            String k = e.getKey().equalsIgnoreCase("food") ? "@food" : e.getKey().toLowerCase(Locale.ROOT);
            want.put(k, e.getValue());
        }
        dev.survivalkit.Api.save();

        var keep = dev.loadchest.LoadChestMod.keepRules();
        keep.clear();
        for (var e : p.survival.keep.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue() == 0) continue;
            String k = e.getKey().equalsIgnoreCase("food") ? "@food" : e.getKey().toLowerCase(Locale.ROOT);
            keep.put(k, e.getValue());
        }
        dev.loadchest.LoadChestMod.saveSettings();

        // Sync hotbar slot reservations (1-based or 0-based keys in Project.SurvivalPolicy.hotbar)
        HotbarController.clearReservedSlots();
        QueueMod.projectRestoreHotbarPins(Map.of());
        for (var e : p.survival.hotbar.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue().isBlank()) continue;
            int raw = e.getKey();
            int zeroBased = (raw >= 1 && raw <= 9) ? raw - 1 : raw;
            if (zeroBased >= 0 && zeroBased < 9) {
                HotbarController.reserveSlot(zeroBased);
                QueueMod.projectPinHotbar(zeroBased, e.getValue());
            }
        }

        // Sync engine itemSaver settings
        EngineSelector.current().setSetting("itemSaver", String.valueOf(p.survival.itemSaver));
        EngineSelector.current().setSetting("itemSaverThreshold", String.valueOf(p.survival.itemSaverThreshold));

        activationSnapshot = snap;
    }

    private void deactivateProject() {
        restoreOverrides();
        ProjectActivationSnapshot snap = activationSnapshot;
        activationSnapshot = null;
        if (snap == null) return;

        for (var entry : snap.prevOptions.entrySet()) {
            QueueMod.projectSetOption(entry.getKey(), entry.getValue());
        }
        var want = dev.survivalkit.Api.wantList();
        want.clear();
        want.putAll(snap.prevWant);
        dev.survivalkit.Api.save();

        var keep = dev.loadchest.LoadChestMod.keepRules();
        keep.clear();
        keep.putAll(snap.prevKeep);
        dev.loadchest.LoadChestMod.saveSettings();

        HotbarController.setReservedSlotMask(snap.prevHotbarMask);
        QueueMod.projectRestoreHotbarPins(snap.prevHotbarPins);

        if (snap.prevItemSaver != null) {
            EngineSelector.current().setSetting("itemSaver", snap.prevItemSaver);
        }
        if (snap.prevItemSaverThreshold != null) {
            EngineSelector.current().setSetting("itemSaverThreshold", snap.prevItemSaverThreshold);
        }
        for (String group : snap.addedProtectGroups) {
            dev.buildtools.Api.unprotect(group);
        }
    }

    private void cleanupAfterQueueEnd() {
        deactivateProject();
        Minecraft mc = null;
        try {
            mc = Minecraft.getInstance();
        } catch (Throwable ignored) {
        }
        EngineSelector.current().cancel();
        BodyController.INSTANCE.releaseAll(mc);
        RuntimeBus.setScriptActive(QueueMod.projectRunnerRunning());
        RuntimeBus.setCurrentStep(null);
    }

    // ------------------------------------------------------------------ internals & helpers

    private void ensureLoaded() {
        String world = currentWorld();
        if (activeProject != null && world.equals(loadedWorld)) {
            return;
        }
        loadedWorld = world;
        Path dir = projectsDir();
        String activeId = ProjectStore.activeId(dir, world);
        Project p = activeId != null ? ProjectStore.load(dir, world, activeId) : null;
        if (p == null) {
            List<String> ids = ProjectStore.list(dir, world);
            if (!ids.isEmpty()) {
                p = ProjectStore.load(dir, world, ids.get(0));
            }
        }
        if (p == null) {
            p = ProjectStore.createDefault(world, "default", "Default");
            ProjectStore.save(dir, p);
        }
        ProjectStore.setActiveId(dir, world, p.id);
        this.activeProject = p;
        this.activeLedger = QueueMod.projectOpenLedger(world, p.id);
    }

    private static Path projectsDir() {
        return QueueMod.projectConfigDir().resolve("manhattan-projects");
    }

    private static String currentWorld() {
        return QueueMod.projectWorld();
    }

    private static BlockPos toBlockPos(int[] coords) {
        if (coords == null || coords.length < 3) return null;
        return new BlockPos(coords[0], coords[1], coords[2]);
    }

    private static boolean hasEdibleFood(LocalPlayer p) {
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (isSafeFood(st)) return true;
        }
        return isSafeFood(p.getOffhandItem());
    }

    private static boolean isSafeFood(ItemStack st) {
        if (st.isEmpty() || !st.has(DataComponents.FOOD)) return false;
        String path = BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
        return !(path.contains("rotten_flesh") || path.contains("spider_eye") || path.contains("pufferfish")
                || path.contains("poisonous_potato") || path.contains("chorus_fruit")
                || path.contains("raw_chicken") || path.contains("suspicious_stew")
                || path.contains("ominous_bottle"));
    }

    private static int countExactItem(Item item) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc == null ? null : mc.player;
        if (p == null || item == null) return 0;
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (!st.isEmpty() && st.is(item)) n += st.getCount();
        }
        return n;
    }

    private static List<BlockPos> orderMaterialChests(List<int[]> rawChests, Item item) {
        String itemId = BuiltInRegistries.ITEM.getKey(item).toString();
        Minecraft mc = Minecraft.getInstance();
        BlockPos me = (mc != null && mc.player != null) ? mc.player.blockPosition() : BlockPos.ZERO;
        record Cand(BlockPos pos, int rank, double dist) {}
        List<Cand> cands = new ArrayList<>();
        for (int[] c : rawChests) {
            BlockPos pos = toBlockPos(c);
            if (pos == null) continue;
            var info = dev.survivalkit.Api.chestInfo(pos, itemId);
            if (info.known() && info.fresh() && info.count() == 0) continue;
            int rank = (info.known() && info.count() > 0) ? 0 : 1;
            cands.add(new Cand(pos, rank, pos.distSqr(me)));
        }
        cands.sort(Comparator.comparingInt(Cand::rank).thenComparingDouble(Cand::dist));
        List<BlockPos> out = new ArrayList<>(cands.size());
        for (Cand c : cands) out.add(c.pos);
        return out;
    }

    private static PriorityScheduler.ActionHandle failedHandle(String error) {
        return new PriorityScheduler.ActionHandle() {
            @Override public boolean isDone() { return true; }
            @Override public String error() { return error; }
            @Override public int remainingWork() { return 0; }
            @Override public void cancel() {}
            @Override public void suspend() {}
            @Override public void resume() {}
            @Override public void tick() {}
        };
    }

    private static final class RunnerScriptHandle implements PriorityScheduler.ActionHandle {
        private final Runnable onFinish;
        private boolean done;
        private String error;
        private boolean cleaned;

        RunnerScriptHandle(Runnable onFinish) {
            this.onFinish = onFinish;
        }

        private void cleanupOnce() {
            if (!cleaned) {
                cleaned = true;
                if (onFinish != null) onFinish.run();
            }
        }

        @Override
        public boolean isDone() {
            if (!done && !QueueMod.projectRunnerRunning()) {
                done = true;
                error = QueueMod.projectRunnerLastFailure();
                cleanupOnce();
            }
            return done;
        }

        @Override public String error() { return error; }
        @Override public int remainingWork() { return 0; }

        @Override
        public void cancel() {
            QueueMod.projectRunnerCancel();
            done = true;
            cleanupOnce();
        }

        @Override
        public void suspend() {
            QueueMod.projectRunnerSuspend();
        }

        @Override
        public void resume() {
            QueueMod.projectRunnerResume();
        }

        @Override
        public void tick() {
            isDone();
        }
    }

    private static final class SequentialHandle implements PriorityScheduler.ActionHandle {
        private final Deque<Supplier<PriorityScheduler.ActionHandle>> remaining;
        private final java.util.function.IntSupplier remainingWorkOverride;
        private final java.util.function.Consumer<String> onComplete;
        private PriorityScheduler.ActionHandle current;
        private boolean done;
        private String error;
        private boolean notified;

        SequentialHandle(
                List<Supplier<PriorityScheduler.ActionHandle>> steps,
                java.util.function.IntSupplier remainingWorkOverride,
                java.util.function.Consumer<String> onComplete) {
            this.remaining = new ArrayDeque<>(steps);
            this.remainingWorkOverride = remainingWorkOverride;
            this.onComplete = onComplete;
        }

        private void advance() {
            while (current == null && !remaining.isEmpty()) {
                PriorityScheduler.ActionHandle next = remaining.poll().get();
                if (next != null) {
                    current = next;
                    if (current.isDone()) {
                        if (current.error() != null) {
                            error = current.error();
                            current = null;
                            finishNow();
                            return;
                        }
                        current = null;
                    }
                }
            }
            if (current == null && remaining.isEmpty()) {
                finishNow();
            }
        }

        private void finishNow() {
            done = true;
            if (!notified) {
                notified = true;
                if (onComplete != null) onComplete.accept(error);
            }
        }

        @Override
        public boolean isDone() {
            if (!done && current == null) advance();
            return done;
        }

        @Override public String error() { return error; }

        @Override
        public int remainingWork() {
            if (remainingWorkOverride != null) return Math.max(0, remainingWorkOverride.getAsInt());
            return current != null ? current.remainingWork() : 0;
        }

        @Override
        public void cancel() {
            if (current != null) {
                current.cancel();
                current = null;
            }
            remaining.clear();
            finishNow();
        }

        @Override
        public void suspend() {
            if (current != null) current.suspend();
        }

        @Override
        public void resume() {
            if (current != null) current.resume();
        }

        @Override
        public void tick() {
            if (done) return;
            if (current == null) {
                advance();
                if (done || current == null) return;
            }
            if (!current.isDone()) {
                current.tick();
            }
            if (current.isDone()) {
                if (current.error() != null) {
                    error = current.error();
                    current = null;
                    finishNow();
                    return;
                }
                current = null;
                advance();
            }
        }

        @Override
        public Map<String, Integer> materialDeficit() {
            return current != null ? current.materialDeficit() : Map.of();
        }
    }

    private static final class ProjectActivationSnapshot {
        final Map<String, Object> prevOptions = new LinkedHashMap<>();
        Map<String, Integer> prevWant = new LinkedHashMap<>();
        Map<String, Integer> prevKeep = new LinkedHashMap<>();
        int prevHotbarMask;
        Map<Integer, String> prevHotbarPins = new LinkedHashMap<>();
        String prevItemSaver;
        String prevItemSaverThreshold;
        final List<String> addedProtectGroups = new ArrayList<>();
    }

    private static final class OverrideSnapshot {
        boolean hadBreak;
        boolean prevBreak;
        boolean hadPlace;
        boolean prevPlace;
        Map<String, Integer> prevKeep;
        Map<String, Integer> prevWant;
    }
}
