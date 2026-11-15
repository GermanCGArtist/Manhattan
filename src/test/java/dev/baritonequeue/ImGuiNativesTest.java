package dev.baritonequeue;

import java.nio.file.Files;
import java.nio.file.Path;

/** Offline test for {@link ImGuiNatives}: platform mapping, bundled resources, and a real extract + load. */
public class ImGuiNativesTest {
    private static int fails = 0;

    private static void eq(String name, Object got, Object expected) {
        if (!java.util.Objects.equals(got, expected)) {
            fails++;
            System.out.println("FAIL " + name + "\n  expected: " + expected + "\n  got:      " + got);
        }
    }

    private static void check(String name, boolean cond) {
        if (!cond) {
            fails++;
            System.out.println("FAIL " + name);
        }
    }

    public static void main(String[] args) throws Exception {
        eq("windows x64", ImGuiNatives.resourceName("Windows 11", "amd64"), "imgui-java64.dll");
        eq("windows arm64", ImGuiNatives.resourceName("Windows 11", "aarch64"), null);
        eq("linux x64", ImGuiNatives.resourceName("Linux", "amd64"), "libimgui-java64.so");
        eq("linux x86_64", ImGuiNatives.resourceName("linux", "x86_64"), "libimgui-java64.so");
        eq("macos x64", ImGuiNatives.resourceName("Mac OS X", "x86_64"), "libimgui-java64.dylib");
        eq("macos arm64", ImGuiNatives.resourceName("Mac OS X", "aarch64"), "libimgui-java64.dylib");
        eq("darwin arm64", ImGuiNatives.resourceName("Darwin", "arm64"), "libimgui-java64.dylib");
        eq("linux aarch64 unsupported", ImGuiNatives.resourceName("Linux", "aarch64"), null);
        eq("unknown os", ImGuiNatives.resourceName("Plan 9", "amd64"), null);

        ClassLoader cl = ImGuiNativesTest.class.getClassLoader();
        for (String f : new String[]{"imgui-java64.dll", "libimgui-java64.so", "libimgui-java64.dylib"}) {
            check("resource on classpath: " + f, cl.getResource(ImGuiNatives.RESOURCE_DIR + f) != null);
        }

        Path dir = Files.createTempDirectory("manhattan-natives-test");
        try {
            ImGuiNatives.load(dir, cl);
            String v = imgui.ImGui.getVersion();
            check("ImGui.getVersion() returned something", v != null && !v.isEmpty());
            System.out.println("loaded ImGui " + v + " from " + dir);
        } finally {
            // the loaded library stays mapped (locked on Windows), so cleanup is best effort
            try (var s = Files.walk(dir)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }

        if (fails > 0) {
            System.out.println(fails + " failure(s)");
            System.exit(1);
        }
        System.out.println("ImGuiNativesTest OK");
    }
}
