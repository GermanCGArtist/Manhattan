package dev.baritonequeue.project;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Per-world priority-layered queue of {@link Project} references, modeled after a media encoder layer stack:
 * index {@code 0} is the topmost layer and receives bot capacity first.
 * Pure Java + Gson (zero Minecraft imports).
 */
public final class ProjectQueue {
    public int version = 1;
    /** When true, a project failure halts the entire layer queue; when false, the queue advances to the next layer. */
    public boolean stopOnProjectError = true;
    /** When true, disconnects once all enabled project layers finish. */
    public boolean logoffWhenAllDone = false;
    /** Ordered priority layers: index 0 = highest priority (topmost). */
    public List<Layer> layers = new ArrayList<>();

    public ProjectQueue() {
    }

    public static final class Layer {
        public String projectId;
        public boolean enabled = true;

        public Layer() {
        }

        public Layer(String projectId, boolean enabled) {
            this.projectId = projectId;
            this.enabled = enabled;
        }
    }

    /** Ensures collections are non-null, sanitizes project ids, and removes invalid or duplicate layers. */
    public void ensureInitialized() {
        if (layers == null) {
            layers = new ArrayList<>();
            return;
        }
        Set<String> seen = new LinkedHashSet<>();
        List<Layer> cleaned = new ArrayList<>(layers.size());
        for (Layer layer : layers) {
            if (layer == null || layer.projectId == null || layer.projectId.isBlank()) continue;
            String id = ProjectStore.sanitizeId(layer.projectId);
            if (seen.add(id)) {
                layer.projectId = id;
                cleaned.add(layer);
            }
        }
        layers = cleaned;
    }

    /** Appends {@code projectId} as the bottommost layer. Returns {@code false} if invalid or already queued. */
    public boolean add(String projectId) {
        return insertAt(layers != null ? layers.size() : 0, projectId);
    }

    /**
     * Inserts {@code projectId} at {@code index} (clamped to {@code [0, size]}).
     * Returns {@code false} if {@code projectId} is blank or already present in the queue.
     */
    public boolean insertAt(int index, String projectId) {
        if (projectId == null || projectId.isBlank()) return false;
        ensureInitialized();
        String id = ProjectStore.sanitizeId(projectId);
        if (contains(id)) return false;
        int clamped = Math.max(0, Math.min(index, layers.size()));
        layers.add(clamped, new Layer(id, true));
        return true;
    }

    /** Removes {@code projectId} from the layer queue; returns {@code true} if removed. */
    public boolean remove(String projectId) {
        if (projectId == null || projectId.isBlank()) return false;
        ensureInitialized();
        String id = ProjectStore.sanitizeId(projectId);
        for (int i = 0; i < layers.size(); i++) {
            if (id.equals(layers.get(i).projectId)) {
                layers.remove(i);
                return true;
            }
        }
        return false;
    }

    /** Moves the layer at {@code fromIdx} to {@code toIdx}; returns {@code false} if out of bounds. */
    public boolean move(int fromIdx, int toIdx) {
        ensureInitialized();
        int size = layers.size();
        if (fromIdx < 0 || fromIdx >= size || toIdx < 0 || toIdx >= size) {
            return false;
        }
        if (fromIdx == toIdx) return true;
        Layer moved = layers.remove(fromIdx);
        layers.add(toIdx, moved);
        return true;
    }

    /** Promotes {@code projectId} to index 0 (topmost priority layer). Returns {@code false} if not queued. */
    public boolean moveToTop(String projectId) {
        int idx = indexOf(projectId);
        if (idx < 0) return false;
        return move(idx, 0);
    }

    /** Moves {@code projectId} to the bottom of the layer stack. Returns {@code false} if not queued. */
    public boolean moveToBottom(String projectId) {
        int idx = indexOf(projectId);
        if (idx < 0) return false;
        return move(idx, layers.size() - 1);
    }

    /** Enables or disables a queued layer. Returns {@code false} if {@code projectId} is not queued. */
    public boolean setEnabled(String projectId, boolean enabled) {
        Layer layer = find(projectId);
        if (layer == null) return false;
        layer.enabled = enabled;
        return true;
    }

    /** Returns {@code true} if {@code projectId} is present in the layer queue. */
    public boolean contains(String projectId) {
        return indexOf(projectId) >= 0;
    }

    /** Returns the 0-based priority index of {@code projectId}, or {@code -1} if absent. */
    public int indexOf(String projectId) {
        if (projectId == null || projectId.isBlank()) return -1;
        ensureInitialized();
        String id = ProjectStore.sanitizeId(projectId);
        for (int i = 0; i < layers.size(); i++) {
            if (id.equals(layers.get(i).projectId)) return i;
        }
        return -1;
    }

    /** Returns the {@link Layer} for {@code projectId}, or {@code null} if absent. */
    public Layer find(String projectId) {
        int idx = indexOf(projectId);
        return idx >= 0 ? layers.get(idx) : null;
    }

    /** Count of currently enabled layers. */
    public int enabledCount() {
        ensureInitialized();
        int count = 0;
        for (Layer l : layers) {
            if (l.enabled) count++;
        }
        return count;
    }

    /** Returns an unmodifiable snapshot of the layers in priority order (index 0 = highest priority). */
    public List<Layer> snapshot() {
        ensureInitialized();
        return Collections.unmodifiableList(new ArrayList<>(layers));
    }

    /** Returns a deep copy of this queue. */
    public ProjectQueue copy() {
        ensureInitialized();
        ProjectQueue out = new ProjectQueue();
        out.version = this.version;
        out.stopOnProjectError = this.stopOnProjectError;
        out.logoffWhenAllDone = this.logoffWhenAllDone;
        for (Layer l : this.layers) {
            out.layers.add(new Layer(l.projectId, l.enabled));
        }
        return out;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProjectQueue other)) return false;
        if (version != other.version
                || stopOnProjectError != other.stopOnProjectError
                || logoffWhenAllDone != other.logoffWhenAllDone) {
            return false;
        }
        if (layers.size() != other.layers.size()) return false;
        for (int i = 0; i < layers.size(); i++) {
            Layer a = layers.get(i);
            Layer b = other.layers.get(i);
            if (!Objects.equals(a.projectId, b.projectId) || a.enabled != b.enabled) return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int h = Objects.hash(version, stopOnProjectError, logoffWhenAllDone);
        for (Layer l : layers) {
            h = 31 * h + Objects.hash(l.projectId, l.enabled);
        }
        return h;
    }
}
