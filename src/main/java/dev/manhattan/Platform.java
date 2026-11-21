package dev.manhattan;

import com.mojang.blaze3d.platform.InputConstants;

import java.util.Locale;

/** The few places where the UI behaves differently per OS: the shortcut modifier and its label. */
public final class Platform {
    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");
    public static final int MOD_SHIFT = InputConstants.MOD_SHIFT;
    public static final int MOD_CONTROL = InputConstants.MOD_CONTROL;
    public static final int MOD_ALT = InputConstants.MOD_ALT;
    public static final int MOD_SUPER = InputConstants.MOD_SUPER;

    private Platform() {}

    public static boolean isMac() {
        return MAC;
    }

    /** Whether the shortcut modifier is held in MC 26.3 (SDL3) {@code mods}: Ctrl everywhere, and Cmd as well on macOS. */
    public static boolean shortcutDown(int mods) {
        int mask = MAC ? MOD_CONTROL | MOD_SUPER : MOD_CONTROL;
        return (mods & mask) != 0;
    }

    /** The shortcut modifier as shown in labels ("Cmd+P" on macOS, "Ctrl+P" elsewhere). */
    public static String shortcutName() {
        return MAC ? "Cmd" : "Ctrl";
    }
}
