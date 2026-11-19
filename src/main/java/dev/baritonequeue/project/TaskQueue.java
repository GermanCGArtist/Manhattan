package dev.baritonequeue.project;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Live-mutable task queue backed by {@link Project#tasks} and a pure-Java {@link TaskLedger} keyed by
 * stable {@link ProjectTask#id}. Reordering, inserting, or removing tasks never invalidates ledger state
 * because progress is keyed by task id rather than list index.
 */
public final class TaskQueue {
    private static final Pattern ID_PATTERN = Pattern.compile("^t_(\\d+)$");

    /** Pure-Java persistence seam for task execution state (implemented in-memory for tests and by Ledger in-game). */
    public interface TaskLedger {
        enum State {
            PENDING,
            RUNNING,
            DONE,
            DEFERRED,
            FAILED
        }

        record EntryView(String id, State state, int tries, int left, String reason, long time) {
        }

        EntryView get(String id);

        EntryView set(String id, State state, int tries, int left, String reason);

        /** Clears ledger entries matching {@code idOrNull} (or all entries when {@code null}); returns count removed. */
        int reset(String idOrNull);

        /**
         * Resets DEFERRED, FAILED, and RUNNING entries matching {@code idOrNull} (or all when {@code null})
         * back to PENDING with tries = 0 and reason = null; returns count updated.
         */
        int retry(String idOrNull);

        /** Returns all ledger ids currently in {@code stateOrNull} (or all ids when {@code null}). */
        List<String> ids(State stateOrNull);
    }

    /** In-memory {@link TaskLedger} implementation for headless tests and ephemeral runs. */
    public static final class InMemoryTaskLedger implements TaskLedger {
        private final Map<String, EntryView> entries = new LinkedHashMap<>();

        private static boolean matchesPrefix(String id, String prefix) {
            if (prefix == null) return true;
            return id.equals(prefix) || id.startsWith(prefix + "/");
        }

        @Override
        public synchronized EntryView get(String id) {
            return id == null ? null : entries.get(id);
        }

        @Override
        public synchronized EntryView set(String id, State state, int tries, int left, String reason) {
            Objects.requireNonNull(id, "id");
            State resolved = state != null ? state : State.PENDING;
            EntryView view = new EntryView(id, resolved, Math.max(0, tries), Math.max(0, left), reason, System.currentTimeMillis());
            entries.put(id, view);
            return view;
        }

        @Override
        public synchronized int reset(String idOrNull) {
            int count = 0;
            for (var it = entries.keySet().iterator(); it.hasNext(); ) {
                String key = it.next();
                if (matchesPrefix(key, idOrNull)) {
                    it.remove();
                    count++;
                }
            }
            return count;
        }

        @Override
        public synchronized int retry(String idOrNull) {
            int count = 0;
            long now = System.currentTimeMillis();
            for (var entry : entries.entrySet()) {
                if (!matchesPrefix(entry.getKey(), idOrNull)) continue;
                EntryView v = entry.getValue();
                if (v.state() == State.DEFERRED || v.state() == State.FAILED || v.state() == State.RUNNING) {
                    entry.setValue(new EntryView(v.id(), State.PENDING, 0, v.left(), null, now));
                    count++;
                }
            }
            return count;
        }

        @Override
        public synchronized List<String> ids(State stateOrNull) {
            List<String> out = new ArrayList<>();
            for (var entry : entries.entrySet()) {
                if (stateOrNull == null || entry.getValue().state() == stateOrNull) {
                    out.add(entry.getKey());
                }
            }
            return out;
        }
    }

    private final Project project;
    private final TaskLedger ledger;

    public TaskQueue(Project project, TaskLedger ledger) {
        this.project = Objects.requireNonNull(project, "project");
        this.project.ensureInitialized();
        this.ledger = ledger != null ? ledger : new InMemoryTaskLedger();
    }

    public Project project() {
        return project;
    }

    public TaskLedger ledger() {
        return ledger;
    }

    /** Appends a task, assigning a unique stable id if missing or duplicate. */
    public ProjectTask add(ProjectTask task) {
        Objects.requireNonNull(task, "task");
        prepareTask(task);
        project.tasks.add(task);
        return task;
    }

    /** Inserts a task at {@code index} (clamped to {@code [0, size]}), assigning a unique stable id if needed. */
    public ProjectTask insertAt(int index, ProjectTask task) {
        Objects.requireNonNull(task, "task");
        prepareTask(task);
        int clamped = Math.max(0, Math.min(index, project.tasks.size()));
        project.tasks.add(clamped, task);
        return task;
    }

    /** Removes the task with {@code taskId}; returns true if a task was removed. */
    public boolean remove(String taskId) {
        if (taskId == null) return false;
        for (int i = 0; i < project.tasks.size(); i++) {
            if (taskId.equals(project.tasks.get(i).id)) {
                project.tasks.remove(i);
                return true;
            }
        }
        return false;
    }

    /** Moves the task at {@code fromIdx} to {@code toIdx}; returns false if either index is out of bounds. */
    public boolean move(int fromIdx, int toIdx) {
        int size = project.tasks.size();
        if (fromIdx < 0 || fromIdx >= size || toIdx < 0 || toIdx >= size) {
            return false;
        }
        if (fromIdx == toIdx) return true;
        ProjectTask moved = project.tasks.remove(fromIdx);
        project.tasks.add(toIdx, moved);
        return true;
    }

    /** Marks the task with {@code taskId} as {@link TaskLedger.State#DEFERRED} so it is skipped. */
    public boolean skip(String taskId) {
        if (findById(taskId) == null) return false;
        TaskLedger.EntryView prev = ledger.get(taskId);
        int tries = prev != null ? prev.tries() : 0;
        int left = prev != null ? prev.left() : 0;
        ledger.set(taskId, TaskLedger.State.DEFERRED, tries, left, "skipped");
        return true;
    }

    /**
     * Retries a DEFERRED, FAILED, or RUNNING task (or all matching tasks when {@code taskId} is null),
     * resetting its state to PENDING and tries to 0.
     */
    public boolean retry(String taskId) {
        return ledger.retry(taskId) > 0;
    }

    /** Resets ledger progress for {@code taskIdOrNull} (or the whole queue when {@code null}). */
    public int reset(String taskIdOrNull) {
        return ledger.reset(taskIdOrNull);
    }

    /**
     * Returns the first enabled task whose ledger state is runnable ({@link TaskLedger.State#PENDING}
     * or {@link TaskLedger.State#RUNNING} for crash-resume), or {@code null} if no runnable tasks remain.
     */
    public ProjectTask nextRunnableTask() {
        for (ProjectTask task : project.tasks) {
            if (task == null || !task.enabled) continue;
            TaskLedger.State st = stateOf(task.id);
            if (st == TaskLedger.State.PENDING || st == TaskLedger.State.RUNNING) {
                return task;
            }
        }
        return null;
    }

    /** Returns the current ledger state of {@code taskId}, defaulting to {@link TaskLedger.State#PENDING}. */
    public TaskLedger.State stateOf(String taskId) {
        if (taskId == null) return TaskLedger.State.PENDING;
        TaskLedger.EntryView entry = ledger.get(taskId);
        return entry != null && entry.state() != null ? entry.state() : TaskLedger.State.PENDING;
    }

    /** Count of enabled tasks whose ledger state is {@link TaskLedger.State#DONE}. */
    public int doneCount() {
        int count = 0;
        for (ProjectTask task : project.tasks) {
            if (task != null && task.enabled && stateOf(task.id) == TaskLedger.State.DONE) {
                count++;
            }
        }
        return count;
    }

    /** Count of enabled tasks in the project. */
    public int totalEnabledCount() {
        int count = 0;
        for (ProjectTask task : project.tasks) {
            if (task != null && task.enabled) count++;
        }
        return count;
    }

    /** Generates the next unused sequential task id (e.g. {@code "t_001"}, {@code "t_002"}). */
    public String generateNextId() {
        int max = 0;
        for (ProjectTask task : project.tasks) {
            if (task == null || task.id == null) continue;
            Matcher m = ID_PATTERN.matcher(task.id);
            if (m.matches()) {
                try {
                    int num = Integer.parseInt(m.group(1));
                    if (num > max) max = num;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        int next = max + 1;
        String candidate = String.format(Locale.ROOT, "t_%03d", next);
        while (findById(candidate) != null) {
            next++;
            candidate = String.format(Locale.ROOT, "t_%03d", next);
        }
        return candidate;
    }

    /** Returns an unmodifiable snapshot of the tasks in current queue order. */
    public List<ProjectTask> tasks() {
        return Collections.unmodifiableList(new ArrayList<>(project.tasks));
    }

    /** Finds a task by its stable id, or returns {@code null}. */
    public ProjectTask findById(String taskId) {
        if (taskId == null) return null;
        for (ProjectTask task : project.tasks) {
            if (task != null && taskId.equals(task.id)) return task;
        }
        return null;
    }

    /** Returns the current 0-based index of {@code taskId}, or {@code -1} if absent. */
    public int indexOf(String taskId) {
        if (taskId == null) return -1;
        for (int i = 0; i < project.tasks.size(); i++) {
            ProjectTask task = project.tasks.get(i);
            if (task != null && taskId.equals(task.id)) return i;
        }
        return -1;
    }

    private void prepareTask(ProjectTask task) {
        task.ensureInitialized();
        if (task.id == null || task.id.isBlank() || findById(task.id) != null) {
            task.id = generateNextId();
        }
    }
}
