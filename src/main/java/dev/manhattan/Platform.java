package dev.manhattan;

import java.util.Locale;

/** The few places where the UI behaves differently per OS: the shortcut modifier and its label. */
public final class Platform {
    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");
    public static final int MOD_SHIFT = 0x0001;
    public static final int MOD_CONTROL = 0x0002;
    public static final int MOD_ALT = 0x0004;
    public static final int MOD_SUPER = 0x0008;

    private Platform() {}

    public static boolean isMac() {
        return MAC;
    }

    /** Whether the shortcut modifier is held in modifier bitmask: Ctrl everywhere, and Cmd as well on macOS. */
    public static boolean shortcutDown(int mods) {
        int mask = MAC ? MOD_CONTROL | MOD_SUPER : MOD_CONTROL;
        return (mods & mask) != 0;
    }

    /** The shortcut modifier as shown in labels ("Cmd+P" on macOS, "Ctrl+P" elsewhere). */
    public static String shortcutName() {
        return MAC ? "Cmd" : "Ctrl";
    }
}
