package dev.manhattan;

import dev.liskov.ui.theme.MubTheme;
import imgui.ImFont;
import imgui.ImGui;
import imgui.flag.ImGuiCol;

/**
 * Live colour &amp; font facade synced from Liskov's {@link MubTheme} so Manhattan automatically
 * adopts all 4 ModsUndBots terminal presets ({@code SYNTH_TERMINAL}, {@code GRUVBOX_PIXEL},
 * {@code TOKYO_NIGHT}, {@code DRAFTING_PAPER}) and 8 semantic roles.
 */
final class UiTheme {
    private UiTheme() {}

    static int BG = MubTheme.BG;
    static int PANEL = MubTheme.PANEL;
    static int PANEL2 = MubTheme.PANEL2;
    static int SURFACE = MubTheme.SURFACE;
    static int LINE = MubTheme.LINE;
    static int LINE2 = MubTheme.LINE2;
    static int BORDER_HI = MubTheme.BORDER_HI;
    static int FG = MubTheme.FG;
    static int DIM = MubTheme.DIM;
    static int GUTTER = MubTheme.GUTTER;
    static int ACCENT = MubTheme.ACCENT;
    static int ON_ACCENT = MubTheme.ON_ACCENT;
    static int OK = MubTheme.OK;
    static int WARN = MubTheme.WARN;
    static int ERR = MubTheme.ERR;
    static int BOT = MubTheme.BOT;
    static int PLAYER = MubTheme.PLAYER;
    static int CF = MubTheme.CF;
    static int META = MubTheme.META;
    static int BTN = MubTheme.BTN;
    static int CHIP = MubTheme.CHIP;
    static int TRACK = MubTheme.TRACK;
    static int SLOT = MubTheme.SLOT;
    static int SEL = MubTheme.SEL;
    static int RUNLINE = MubTheme.RUNLINE;

    static ImFont ui, small, mono, numM, numL;

    static void syncFromMubTheme() {
        BG = MubTheme.BG;
        PANEL = MubTheme.PANEL;
        PANEL2 = MubTheme.PANEL2;
        SURFACE = MubTheme.SURFACE;
        LINE = MubTheme.LINE;
        LINE2 = MubTheme.LINE2;
        BORDER_HI = MubTheme.BORDER_HI;
        FG = MubTheme.FG;
        DIM = MubTheme.DIM;
        GUTTER = MubTheme.GUTTER;
        ACCENT = MubTheme.ACCENT;
        ON_ACCENT = MubTheme.ON_ACCENT;
        OK = MubTheme.OK;
        WARN = MubTheme.WARN;
        ERR = MubTheme.ERR;
        BOT = MubTheme.BOT;
        PLAYER = MubTheme.PLAYER;
        CF = MubTheme.CF;
        META = MubTheme.META;
        BTN = MubTheme.BTN;
        CHIP = MubTheme.CHIP;
        TRACK = MubTheme.TRACK;
        SLOT = MubTheme.SLOT;
        SEL = MubTheme.SEL;
        RUNLINE = MubTheme.RUNLINE;

        ui = MubTheme.ui();
        small = MubTheme.small();
        mono = MubTheme.mono();
        numM = MubTheme.numM();
        numL = MubTheme.numL();
    }

    static int u32(int rgb, float a) {
        return MubTheme.u32(rgb, a);
    }

    static int u32(int rgb) {
        return MubTheme.u32(rgb);
    }

    static float r(int rgb) { return MubTheme.r(rgb); }
    static float g(int rgb) { return MubTheme.g(rgb); }
    static float b(int rgb) { return MubTheme.b(rgb); }

    /** Coloured text, never treated as a format string (a "%" in a message stays a "%"). */
    static void textColored(int rgb, String text) {
        MubTheme.textColored(rgb, text);
    }

    static void pushText(int rgb) {
        ImGui.pushStyleColor(ImGuiCol.Text, r(rgb), g(rgb), b(rgb), 1f);
    }

    static void push(ImFont f) {
        MubTheme.push(f != null ? f : ui);
    }
}
