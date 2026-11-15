package dev.baritonequeue;

import imgui.ImFont;
import imgui.ImFontConfig;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.ImGuiStyle;
import imgui.flag.ImGuiCol;

import java.io.InputStream;

/**
 * The workspace look: colour tokens (Hybrid design), the ImGui style built from them, and the bundled fonts
 * (Inter for UI text, JetBrains Mono for code, Barlow Condensed for large numerals; all SIL OFL, licences next to
 * the font files in {@code assets/baritonequeue/fonts}). Fonts are rasterised at the UI scale, so text stays sharp.
 */
final class UiTheme {
    private UiTheme() {}

    // colour tokens (0xRRGGBB)
    static final int BG = 0x131518, PANEL = 0x191c20, PANEL2 = 0x1e2126, LINE = 0x2a2e35, LINE2 = 0x22262b;
    static final int FG = 0xe2e5e9, DIM = 0x858c96, GUTTER = 0x4f565f;
    static final int ACCENT = 0x5ec4b6, ON_ACCENT = 0x06221e;
    static final int OK = 0x8ad27e, WARN = 0xf2b35e, ERR = 0xf07470;
    static final int BTN = 0x23272d, CHIP = 0x262a31, TRACK = 0x2a2f36, SLOT = 0x1c1f24, SEL = 0x21403b, RUNLINE = 0x18302d;

    static ImFont ui, small, mono, numM, numL;
    private static float builtScale = -1f;
    private static byte[] interBytes, monoBytes, barlowBytes;

    /** ImGui packed colour (ABGR) from 0xRRGGBB and alpha. */
    static int u32(int rgb, float a) {
        int al = Math.max(0, Math.min(255, Math.round(a * 255f)));
        return (al << 24) | ((rgb & 0xFF) << 16) | (rgb & 0xFF00) | ((rgb >> 16) & 0xFF);
    }

    static int u32(int rgb) {
        return u32(rgb, 1f);
    }

    static float r(int rgb) { return ((rgb >> 16) & 0xFF) / 255f; }
    static float g(int rgb) { return ((rgb >> 8) & 0xFF) / 255f; }
    static float b(int rgb) { return (rgb & 0xFF) / 255f; }

    private static void col(ImGuiStyle s, int idx, int rgb, float a) {
        s.setColor(idx, r(rgb), g(rgb), b(rgb), a);
    }

    /** Coloured text, never treated as a format string (a "%" in a message stays a "%"). */
    static void textColored(int rgb, String text) {
        pushText(rgb);
        ImGui.textUnformatted(text);
        ImGui.popStyleColor();
    }

    static void pushText(int rgb) {
        ImGui.pushStyleColor(ImGuiCol.Text, r(rgb), g(rgb), b(rgb), 1f);
    }

    static void apply(ImGuiStyle s, float scale) {
        ImGui.styleColorsDark(s);
        s.setWindowRounding(0f);
        s.setChildRounding(6f * scale);
        s.setFrameRounding(5f * scale);
        s.setPopupRounding(6f * scale);
        s.setGrabRounding(4f * scale);
        s.setTabRounding(4f * scale);
        s.setScrollbarRounding(6f * scale);
        s.setWindowBorderSize(0f);
        s.setChildBorderSize(1f);
        s.setFrameBorderSize(1f);
        s.setPopupBorderSize(1f);
        s.setWindowPadding(14f * scale, 12f * scale);
        s.setFramePadding(9f * scale, 5f * scale);
        s.setItemSpacing(8f * scale, 7f * scale);
        s.setItemInnerSpacing(6f * scale, 5f * scale);
        s.setCellPadding(8f * scale, 4f * scale);
        s.setIndentSpacing(16f * scale);
        s.setScrollbarSize(10f * scale);
        s.setGrabMinSize(10f * scale);

        col(s, ImGuiCol.Text, FG, 1f);
        col(s, ImGuiCol.TextDisabled, DIM, 1f);
        col(s, ImGuiCol.WindowBg, PANEL, 1f);
        col(s, ImGuiCol.ChildBg, PANEL, 1f);
        col(s, ImGuiCol.PopupBg, PANEL2, 0.98f);
        col(s, ImGuiCol.Border, LINE, 1f);
        col(s, ImGuiCol.BorderShadow, 0, 0f);
        col(s, ImGuiCol.FrameBg, BTN, 1f);
        col(s, ImGuiCol.FrameBgHovered, CHIP, 1f);
        col(s, ImGuiCol.FrameBgActive, SEL, 1f);
        col(s, ImGuiCol.TitleBg, PANEL2, 1f);
        col(s, ImGuiCol.TitleBgActive, PANEL2, 1f);
        col(s, ImGuiCol.TitleBgCollapsed, PANEL2, 1f);
        col(s, ImGuiCol.MenuBarBg, PANEL2, 1f);
        col(s, ImGuiCol.ScrollbarBg, PANEL, 0f);
        col(s, ImGuiCol.ScrollbarGrab, TRACK, 1f);
        col(s, ImGuiCol.ScrollbarGrabHovered, GUTTER, 1f);
        col(s, ImGuiCol.ScrollbarGrabActive, DIM, 1f);
        col(s, ImGuiCol.CheckMark, ACCENT, 1f);
        col(s, ImGuiCol.SliderGrab, ACCENT, 1f);
        col(s, ImGuiCol.SliderGrabActive, ACCENT, 1f);
        col(s, ImGuiCol.Button, BTN, 1f);
        col(s, ImGuiCol.ButtonHovered, CHIP, 1f);
        col(s, ImGuiCol.ButtonActive, SEL, 1f);
        col(s, ImGuiCol.Header, SEL, 1f);
        col(s, ImGuiCol.HeaderHovered, CHIP, 1f);
        col(s, ImGuiCol.HeaderActive, SEL, 1f);
        col(s, ImGuiCol.Separator, LINE, 1f);
        col(s, ImGuiCol.SeparatorHovered, ACCENT, 0.6f);
        col(s, ImGuiCol.SeparatorActive, ACCENT, 1f);
        col(s, ImGuiCol.ResizeGrip, LINE, 0f);
        col(s, ImGuiCol.Tab, PANEL2, 1f);
        col(s, ImGuiCol.TabHovered, CHIP, 1f);
        col(s, ImGuiCol.TabActive, PANEL, 1f);
        col(s, ImGuiCol.TabUnfocused, PANEL2, 1f);
        col(s, ImGuiCol.TabUnfocusedActive, PANEL, 1f);
        col(s, ImGuiCol.PlotHistogram, ACCENT, 1f);
        col(s, ImGuiCol.TableHeaderBg, PANEL2, 1f);
        col(s, ImGuiCol.TableBorderStrong, LINE, 1f);
        col(s, ImGuiCol.TableBorderLight, LINE2, 1f);
        col(s, ImGuiCol.TableRowBg, PANEL, 0f);
        col(s, ImGuiCol.TableRowBgAlt, PANEL2, 0.5f);
        col(s, ImGuiCol.TextSelectedBg, ACCENT, 0.30f);
        col(s, ImGuiCol.NavHighlight, ACCENT, 1f);
        col(s, ImGuiCol.ModalWindowDimBg, 0, 0.45f);
    }

    /** True when the fonts must be (re)built for this scale. */
    static boolean needsFonts(float scale) {
        return Math.abs(builtScale - scale) > 0.001f;
    }

    /** Adds the fonts to the atlas at {@code scale}. Call with an empty atlas; returns false if the bundled fonts are missing. */
    static boolean loadFonts(ImGuiIO io, float scale) {
        builtScale = scale;
        if (interBytes == null) interBytes = resource("Inter.ttf");
        if (monoBytes == null) monoBytes = resource("JetBrainsMono.ttf");
        if (barlowBytes == null) barlowBytes = resource("BarlowCondensed-SemiBold.ttf");
        var atlas = io.getFonts();
        short[] ranges = new short[]{0x0020, 0x00FF, 0x2010, 0x2027, 0x2190, 0x2193, 0x2026, 0x2026, 0};
        ui = small = mono = numM = numL = null;
        if (interBytes != null) {
            ui = atlas.addFontFromMemoryTTF(interBytes, 15f * scale, cfg(), ranges);
            small = atlas.addFontFromMemoryTTF(interBytes, 12.5f * scale, cfg(), ranges);
        }
        if (monoBytes != null) mono = atlas.addFontFromMemoryTTF(monoBytes, 14f * scale, cfg(), ranges);
        if (barlowBytes != null) {
            numM = atlas.addFontFromMemoryTTF(barlowBytes, 28f * scale, cfg(), ranges);
            numL = atlas.addFontFromMemoryTTF(barlowBytes, 44f * scale, cfg(), ranges);
        }
        if (ui == null) {
            ui = atlas.addFontDefault();
            return false;
        }
        io.setFontDefault(ui);
        return true;
    }

    private static ImFontConfig cfg() {
        ImFontConfig c = new ImFontConfig();
        c.setOversampleH(2);
        c.setOversampleV(1);
        c.setPixelSnapH(true);
        return c;
    }

    private static byte[] resource(String name) {
        try (InputStream in = UiTheme.class.getClassLoader().getResourceAsStream("assets/baritonequeue/fonts/" + name)) {
            return in == null ? null : in.readAllBytes();
        } catch (Exception e) {
            return null;
        }
    }

    static void push(ImFont f) {
        ImGui.pushFont(f != null ? f : ui);
    }
}
