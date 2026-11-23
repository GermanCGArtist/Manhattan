package dev.manhattan.project;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Atomic JSON persistence for {@link Project} definitions under {@code <projectsDir>/<world>/<projectId>.json},
 * plus {@code <projectsDir>/<world>/_active.txt} and Capture-sheet text import.
 * Pure Java + Gson (no Minecraft imports).
 */
public final class ProjectStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String ACTIVE_FILE = "_active.txt";
    private static final String QUEUE_FILE = "_queue.json";
    private static final String JSON_EXT = ".json";

    private static final Pattern POS_PATTERN = Pattern.compile(
            "pos\\(\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*\\)");
    private static final Pattern LET_PATTERN = Pattern.compile(
            "^\\s*let\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=");
    private static final Pattern PROTECT_PATTERN = Pattern.compile(
            "^\\s*protect\\s*\\(\\s*\"([^\"]+)\"");

    private ProjectStore() {
    }

    /** Summary returned by {@link #importFromCaptureText(Project, String)}. */
    public record ImportResult(int placesImported, int materialChestsImported, int protectedAreasImported) {
        public int totalImported() {
            return placesImported + materialChestsImported + protectedAreasImported;
        }
    }

    /** Sanitizes a raw project identifier into a safe lowercase filename stem. */
    public static String sanitizeId(String raw) {
        if (raw == null) return "default";
        String s = raw.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^[_-]+|[_-]+$", "");
        if (s.isEmpty() || s.equals(".") || s.equals("..")) {
            return "default";
        }
        return s;
    }

    /** Sanitizes a world identifier into a safe directory name segment. */
    public static String sanitizeWorld(String world) {
        if (world == null || world.isBlank()) return "default";
        String s = world.trim().replaceAll("[^A-Za-z0-9._-]+", "_");
        while (s.startsWith(".")) s = s.substring(1);
        s = s.replaceAll("^_+|_+$", "");
        return s.isEmpty() ? "default" : s;
    }

    /** Resolves {@code <rootDir>/<sanitizedWorld>}. */
    public static Path worldDir(Path rootDir, String world) {
        Objects.requireNonNull(rootDir, "rootDir");
        return rootDir.resolve(sanitizeWorld(world));
    }

    /** Resolves {@code <rootDir>/<sanitizedWorld>/<sanitizedId>.json}. */
    public static Path projectFile(Path rootDir, String world, String id) {
        return worldDir(rootDir, world).resolve(sanitizeId(id) + JSON_EXT);
    }

    /** Creates a new default project with initialized collections and sanitized id. */
    public static Project createDefault(String world, String id, String name) {
        String cleanId = sanitizeId(id);
        String cleanName = (name == null || name.isBlank()) ? cleanId : name.trim();
        String cleanWorld = (world == null || world.isBlank()) ? "default" : world.trim();
        Project p = new Project(cleanId, cleanName, cleanWorld);
        p.ensureInitialized();
        return p;
    }

    /** Lists sorted project IDs saved under {@code <rootDir>/<world>/}. */
    public static List<String> list(Path rootDir, String world) {
        Path dir = worldDir(rootDir, world);
        if (!Files.isDirectory(dir)) return List.of();
        List<String> out = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(JSON_EXT) && !n.startsWith("_"))
                    .map(n -> n.substring(0, n.length() - JSON_EXT.length()))
                    .sorted()
                    .forEach(out::add);
        } catch (IOException e) {
            return List.of();
        }
        return Collections.unmodifiableList(out);
    }

    /** Loads a project from disk, or returns {@code null} if it does not exist or cannot be parsed. */
    public static Project load(Path rootDir, String world, String id) {
        if (id == null || id.isBlank()) return null;
        Path file = projectFile(rootDir, world, id);
        if (!Files.isRegularFile(file)) return null;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Project p = GSON.fromJson(r, Project.class);
            if (p == null) return null;
            if (p.id == null || p.id.isBlank()) p.id = sanitizeId(id);
            if (p.world == null || p.world.isBlank()) p.world = world;
            if (p.name == null || p.name.isBlank()) p.name = p.id;
            p.ensureInitialized();
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    /** Atomically saves {@code project} under {@code <rootDir>/<project.world>/<project.id>.json}. */
    public static Path save(Path rootDir, Project project) {
        Objects.requireNonNull(rootDir, "rootDir");
        Objects.requireNonNull(project, "project");
        project.ensureInitialized();
        project.id = sanitizeId(project.id);
        if (project.world == null || project.world.isBlank()) {
            project.world = "default";
        }
        if (project.name == null || project.name.isBlank()) {
            project.name = project.id;
        }

        Path file = projectFile(rootDir, project.world, project.id);
        writeAtomically(file, GSON.toJson(project));
        return file;
    }

    /** Deletes a saved project file; clears {@code _active.txt} if it pointed to this project. */
    public static boolean delete(Path rootDir, String world, String id) {
        if (id == null || id.isBlank()) return false;
        String cleanId = sanitizeId(id);
        Path file = projectFile(rootDir, world, cleanId);
        try {
            boolean deleted = Files.deleteIfExists(file);
            if (deleted) {
                if (cleanId.equals(activeId(rootDir, world))) {
                    setActiveId(rootDir, world, null);
                }
                ProjectQueue q = loadQueue(rootDir, world);
                if (q.remove(cleanId)) {
                    saveQueue(rootDir, world, q);
                }
            }
            return deleted;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete project " + file, e);
        }
    }

    /** Reads the active project id from {@code <rootDir>/<world>/_active.txt}, or {@code null} if unset. */
    public static String activeId(Path rootDir, String world) {
        Path activeFile = worldDir(rootDir, world).resolve(ACTIVE_FILE);
        if (!Files.isRegularFile(activeFile)) return null;
        try {
            String raw = Files.readString(activeFile, StandardCharsets.UTF_8).trim();
            return raw.isEmpty() ? null : raw;
        } catch (IOException e) {
            return null;
        }
    }

    /** Writes (or clears when {@code id} is null/blank) {@code <rootDir>/<world>/_active.txt} atomically. */
    public static void setActiveId(Path rootDir, String world, String id) {
        Path activeFile = worldDir(rootDir, world).resolve(ACTIVE_FILE);
        if (id == null || id.isBlank()) {
            try {
                Files.deleteIfExists(activeFile);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to clear active project file " + activeFile, e);
            }
            return;
        }
        writeAtomically(activeFile, sanitizeId(id) + "\n");
    }

    /** Resolves {@code <rootDir>/<sanitizedWorld>/_queue.json}. */
    public static Path queueFile(Path rootDir, String world) {
        return worldDir(rootDir, world).resolve(QUEUE_FILE);
    }

    /** Loads the per-world {@link ProjectQueue}, returning an empty queue if missing or unreadable. */
    public static ProjectQueue loadQueue(Path rootDir, String world) {
        Path file = queueFile(rootDir, world);
        if (!Files.isRegularFile(file)) {
            ProjectQueue empty = new ProjectQueue();
            empty.ensureInitialized();
            return empty;
        }
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            ProjectQueue q = GSON.fromJson(r, ProjectQueue.class);
            if (q == null) q = new ProjectQueue();
            q.ensureInitialized();
            return q;
        } catch (Exception e) {
            ProjectQueue empty = new ProjectQueue();
            empty.ensureInitialized();
            return empty;
        }
    }

    /** Atomically saves the per-world {@link ProjectQueue} to {@code <rootDir>/<world>/_queue.json}. */
    public static Path saveQueue(Path rootDir, String world, ProjectQueue queue) {
        Objects.requireNonNull(rootDir, "rootDir");
        Objects.requireNonNull(queue, "queue");
        queue.ensureInitialized();
        Path file = queueFile(rootDir, world);
        writeAtomically(file, GSON.toJson(queue));
        return file;
    }

    /**
     * Parses a Capture sheet text and imports recognized base places, material chests, and protected areas
     * into {@code project.places}. Commented-out lines (starting with {@code //}) are ignored.
     */
    public static ImportResult importFromCaptureText(Project project, String captureText) {
        Objects.requireNonNull(project, "project");
        project.ensureInitialized();
        if (captureText == null || captureText.isBlank()) {
            return new ImportResult(0, 0, 0);
        }

        int placesImported = 0;
        int materialChestsImported = 0;
        int protectedAreasImported = 0;

        String[] lines = captureText.split("\\r?\\n");
        for (String rawLine : lines) {
            String trimmed = rawLine.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("//")) continue;

            int commentIdx = commentStart(trimmed);
            String code = (commentIdx < 0 ? trimmed : trimmed.substring(0, commentIdx)).trim();
            String comment = (commentIdx < 0 ? "" : trimmed.substring(commentIdx + 2)).trim();
            if (code.isEmpty()) continue;

            String role = comment.isEmpty() ? "" : comment.split("[;:,\\s]", 2)[0].toLowerCase(Locale.ROOT);
            List<int[]> positions = extractPositions(code);
            if (positions.isEmpty()) continue;

            // 1. protect("name", pos(x1,y1,z1), pos(x2,y2,z2))
            Matcher protectMatcher = PROTECT_PATTERN.matcher(code);
            if (protectMatcher.find() && positions.size() >= 2) {
                String areaName = protectMatcher.group(1).trim();
                if (!areaName.isEmpty()) {
                    int[] a = positions.get(0);
                    int[] b = positions.get(1);
                    int[] box = new int[]{
                            Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]),
                            Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2])
                    };
                    List<int[]> boxes = project.places.protectedAreas.computeIfAbsent(areaName, k -> new ArrayList<>());
                    if (!containsIntArray(boxes, box)) {
                        boxes.add(box);
                        protectedAreasImported++;
                    }
                }
                continue;
            }

            // 1b. area_box("name", pos(x1,y1,z1), pos(x2,y2,z2)) // supply_area | return_area | material_area | scaffold_area
            if (code.startsWith("area_box") && positions.size() >= 2) {
                int[] a = positions.get(0);
                int[] b = positions.get(1);
                int[] box = new int[]{
                        Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]),
                        Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2])
                };
                String areaRole = resolveStorageAreaRole(code, role);
                if (areaRole != null) {
                    List<int[]> targetList = switch (areaRole) {
                        case "supply_area" -> project.places.supplyAreas;
                        case "return_area" -> project.places.returnAreas;
                        case "material_area" -> project.places.materialAreas;
                        case "scaffold_area" -> project.places.scaffoldAreas;
                        default -> null;
                    };
                    if (targetList != null && !containsIntArray(targetList, box)) {
                        targetList.add(box);
                        if ("material_area".equals(areaRole)) materialChestsImported++;
                        else placesImported++;
                    }
                    continue;
                }
            }

            // 2. let <var> = pos(x, y, z) [// role: ...]
            Matcher letMatcher = LET_PATTERN.matcher(code);
            if (!letMatcher.find()) continue;
            String varName = letMatcher.group(1).toLowerCase(Locale.ROOT);
            int[] pos = positions.get(0);

            String target = resolvePlaceTarget(varName, role);
            if (target == null) continue;

            switch (target) {
                case "bed" -> {
                    if (project.places.bed == null) {
                        project.places.bed = pos;
                        placesImported++;
                    } else if (!sameIntArray(project.places.bed, pos) && !containsIntArray(project.places.extraBeds, pos)) {
                        project.places.extraBeds.add(pos);
                        placesImported++;
                    }
                }
                case "dump" -> {
                    project.places.dumpChest = pos;
                    placesImported++;
                }
                case "tool" -> {
                    if (project.places.toolChest == null) {
                        project.places.toolChest = pos;
                        placesImported++;
                    } else if (!sameIntArray(project.places.toolChest, pos) && !containsIntArray(project.places.extraToolChests, pos)) {
                        project.places.extraToolChests.add(pos);
                        placesImported++;
                    }
                }
                case "return" -> {
                    if (project.places.returnChest == null) {
                        project.places.returnChest = pos;
                        placesImported++;
                    } else if (!sameIntArray(project.places.returnChest, pos) && !containsIntArray(project.places.extraReturnChests, pos)) {
                        project.places.extraReturnChests.add(pos);
                        placesImported++;
                    }
                }
                case "scaffold" -> {
                    if (project.places.scaffoldChest == null) {
                        project.places.scaffoldChest = pos;
                        placesImported++;
                    } else if (!sameIntArray(project.places.scaffoldChest, pos) && !containsIntArray(project.places.extraScaffoldChests, pos)) {
                        project.places.extraScaffoldChests.add(pos);
                        placesImported++;
                    }
                }
                case "material" -> {
                    if (!containsIntArray(project.places.materialChests, pos)) {
                        project.places.materialChests.add(pos);
                        materialChestsImported++;
                    }
                }
                default -> {
                    // ignore unrecognized roles
                }
            }
        }

        return new ImportResult(placesImported, materialChestsImported, protectedAreasImported);
    }

    private static String resolveStorageAreaRole(String code, String role) {
        switch (role) {
            case "supply_area", "tool_area", "tools_area" -> { return "supply_area"; }
            case "return_area", "returns_area" -> { return "return_area"; }
            case "material_area", "materials_area", "build_supply", "build_supply_area" -> { return "material_area"; }
            case "scaffold_area", "scaffolding_area" -> { return "scaffold_area"; }
            default -> { /* inspect quoted area name */ }
        }
        String lower = code.toLowerCase(Locale.ROOT);
        if (lower.contains("\"supply_area")) return "supply_area";
        if (lower.contains("\"return_area")) return "return_area";
        if (lower.contains("\"material_area") || lower.contains("\"build_supply")) return "material_area";
        if (lower.contains("\"scaffold_area") || lower.contains("\"scaffolding_area")) return "scaffold_area";
        return null;
    }

    private static boolean sameIntArray(int[] a, int[] b) {
        return a != null && b != null && java.util.Arrays.equals(a, b);
    }

    private static String resolvePlaceTarget(String varName, String role) {
        // Explicit comment role takes priority when present
        switch (role) {
            case "bed" -> { return "bed"; }
            case "dump" -> { return "dump"; }
            case "supply", "tool", "tools" -> { return "tool"; }
            case "return" -> { return "return"; }
            case "scaffolding", "scaffold" -> { return "scaffold"; }
            case "material", "materials" -> { return "material"; }
            default -> { /* fall back to variable name */ }
        }

        if (varName.equals("bed") || varName.startsWith("bed_")) return "bed";
        if (varName.equals("dump_chest") || varName.startsWith("dump_chest_")) return "dump";
        if (varName.equals("tool_chest") || varName.startsWith("tool_chest_")
                || varName.equals("supply_chest") || varName.startsWith("supply_chest_")) return "tool";
        if (varName.equals("return_chest") || varName.startsWith("return_chest_")) return "return";
        if (varName.equals("scaffold_chest") || varName.startsWith("scaffold_chest_")
                || varName.equals("scaffolding_chest") || varName.startsWith("scaffolding_chest_")) return "scaffold";
        if (varName.equals("material_chest") || varName.startsWith("material_chest_")) return "material";
        return null;
    }

    private static List<int[]> extractPositions(String code) {
        List<int[]> out = new ArrayList<>();
        Matcher m = POS_PATTERN.matcher(code);
        while (m.find()) {
            out.add(new int[]{
                    Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3))
            });
        }
        return out;
    }

    private static boolean containsIntArray(List<int[]> list, int[] target) {
        for (int[] existing : list) {
            if (Arrays.equals(existing, target)) return true;
        }
        return false;
    }

    /** Finds the start index of a // comment outside double-quoted string literals, or -1. */
    private static int commentStart(String line) {
        boolean inQuotes = false;
        for (int i = 0; i < line.length() - 1; i++) {
            char c = line.charAt(i);
            if (c == '"' && (i == 0 || line.charAt(i - 1) != '\\')) {
                inQuotes = !inQuotes;
            } else if (!inQuotes && c == '/' && line.charAt(i + 1) == '/') {
                return i;
            }
        }
        return -1;
    }

    private static void writeAtomically(Path target, String content) {
        try {
            Path parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                w.write(content);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + target, e);
        }
    }
}
