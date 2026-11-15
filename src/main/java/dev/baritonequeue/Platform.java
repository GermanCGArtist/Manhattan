package dev.baritonequeue;

import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/** The few places where the UI behaves differently per OS: the shortcut modifier and its label. */
public final class Platform {
    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");

    private Platform() {}

    public static boolean isMac() {
        return MAC;
    }

    /** Whether the shortcut modifier is held in GLFW {@code mods}: Ctrl everywhere, and Cmd as well on macOS. */
    public static boolean shortcutDown(int mods) {
        int mask = MAC ? GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER : GLFW.GLFW_MOD_CONTROL;
        return (mods & mask) != 0;
    }

    /** The shortcut modifier as shown in labels ("Cmd+P" on macOS, "Ctrl+P" elsewhere). */
    public static String shortcutName() {
        return MAC ? "Cmd" : "Ctrl";
    }
}
