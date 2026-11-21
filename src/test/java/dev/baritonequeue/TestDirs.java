package dev.baritonequeue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/** Temp folders for the offline tests that are deleted again when the test JVM exits (pass, fail or crash). */
public final class TestDirs {
    private static final List<Path> DIRS = new CopyOnWriteArrayList<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(TestDirs::deleteAll, "test-dirs-cleanup"));
    }

    private TestDirs() {}

    /** Like {@link Files#createTempDirectory(String, java.nio.file.attribute.FileAttribute[])}, but removed on exit. */
    public static Path create(String prefix) throws IOException {
        Path dir = Files.createTempDirectory(prefix);
        DIRS.add(dir);
        return dir;
    }

    private static void deleteAll() {
        for (Path dir : DIRS) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException | RuntimeException ignored) {
                // best effort: a locked file only leaves this one folder behind
            }
        }
    }
}
