package dev.manhattan;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiConfigFlags;
import imgui.flag.ImGuiKey;
import imgui.internal.ImGuiContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;

import java.nio.file.Path;

import static dev.manhattan.Config.cfg;

/**
 * Dear ImGui lifecycle and input routing for the workspace ({@link Shell}, key J) and the closed-state
 * {@link Overlay}. Powered by {@link ImGuiRenderPearlRenderer} and direct SDL3/{@link InputConstants}
 * event feeding (MC 26.3+, zero OpenGL/GLFW dependencies).
 */
public final class ImGuiManager {
    private static boolean nativesPrepared;
    private static boolean initialized;
    private static boolean initFailed;
    private static boolean gameFocused = true;
    private static boolean focused;
    private static boolean unfocusRequested;
    private static boolean stopRequested;
    private static long framesRendered;
    private static int lastDisplayW, lastDisplayH;
    private static float stopBtnX, stopBtnY, stopBtnW, stopBtnH;
    private static float vpX, vpY, vpW, vpH;
    private static double lastMouseX, lastMouseY;
    private static volatile Path shotPath;
    private static volatile String shotDone = "";

    private ImGuiManager() {}

    /** Saves the next frame ImGui draws as a PNG at {@code p} (tests); {@link #lastShot()} says when it is written. */
    public static void requestScreenshot(Path p) {
        shotDone = "";
        shotPath = p;
    }

    public static String lastShot() {
        return shotDone;
    }

    /** Whether ImGui draws this frame (workspace open, or the overlay has something to show). */
    public static boolean isActive() {
        return Shell.isOpen() || Overlay.visible();
    }

    /** Whether the workspace is open and the game is drawn into its game area. */
    public static boolean isDockedViewportActive() {
        return Shell.isOpen();
    }

    /** Whether ImGui takes mouse and keyboard input (only while the workspace is open). */
    public static boolean capturesInput() {
        return Shell.isOpen();
    }

    // ------------------------------------------------------------------ workspace open / close

    public static void toggleShell() {
        if (Shell.isOpen()) closeShell();
        else openShell(Shell.lastMode());
    }

    public static void openShell(Shell.Mode mode) {
        boolean wasOpen = Shell.isOpen();
        Shell.setMode(mode == Shell.Mode.CLOSED ? Shell.lastMode() : mode);
        Minecraft mc = Minecraft.getInstance();
        if (!wasOpen) {
            gameFocused = false;
            focused = true;
            unfocusRequested = false;
            KeyMapping.releaseAll();
            if (mc != null && mc.mouseHandler != null && mc.mouseHandler.isMouseGrabbed()) {
                mc.mouseHandler.releaseMouse();
            }
        }
        if (mc != null && mc.getWindow() != null) updateViewportMetrics(mc.getWindow());
    }

    public static void closeShell() {
        if (!Shell.isOpen()) return;
        Shell.setMode(Shell.Mode.CLOSED);
        CommandPalette.close();
        DockedPanels.flushConfig(true);
        gameFocused = true;
        focused = false;
        unfocusRequested = true;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.gui.screen() == null && mc.level != null && !"true".equals(System.getProperty("manhattan.tests"))) {
            mc.mouseHandler.grabMouse();
        }
        if (mc != null && mc.getWindow() != null) updateViewportMetrics(mc.getWindow());
    }

    public static String layoutLabel() {
        return Shell.mode.name().toLowerCase();
    }

    /** Whether keyboard input currently goes to the game rather than the panels. */
    public static boolean isGameFocused() {
        if (Shell.isOpen()) {
            return gameFocused && !focused && (unfocusRequested || !(initialized && Backend.wantsTextInput()));
        }
        return true;
    }

    public static void setGameFocused(boolean focusGame) {
        gameFocused = focusGame;
        if (focusGame) {
            focused = false;
            unfocusRequested = true;
        } else {
            focused = true;
            unfocusRequested = false;
            KeyMapping.releaseAll();
        }
    }

    public static boolean isFocused() {
        if (Shell.isOpen()) {
            return !gameFocused || focused || (!unfocusRequested && initialized && Backend.wantsTextInput());
        }
        return false;
    }

    public static void pushMonoFont() {
        if (initialized) UiTheme.push(UiTheme.mono);
    }

    public static void popMonoFont() {
        if (initialized) ImGui.popFont();
    }

    public static float getUiScale() {
        return cfg.uiScale > 0.5f ? cfg.uiScale : 1.0f;
    }

    public static void setUiScale(float scale) {
        cfg.uiScale = scale > 0.5f ? scale : 1.0f;
        Config.save();
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.getWindow() != null) updateViewportMetrics(mc.getWindow());
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static long framesRendered() {
        return framesRendered;
    }

    public static int displayWidth() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.getWindow() != null) updateViewportMetrics(mc.getWindow());
        return lastDisplayW;
    }

    public static int displayHeight() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.getWindow() != null) updateViewportMetrics(mc.getWindow());
        return lastDisplayH;
    }

    static void recordStopRect(float x, float y, float w, float h) {
        stopBtnX = x;
        stopBtnY = y;
        stopBtnW = w;
        stopBtnH = h;
    }

    public static float[] stopButtonRect() {
        return new float[]{stopBtnX, stopBtnY, stopBtnW, stopBtnH};
    }

    public static float[] viewportRect() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.getWindow() != null) updateViewportMetrics(mc.getWindow());
        return new float[]{vpX, vpY, vpW, vpH};
    }

    /** The game area shrunk to the window's aspect ratio and centred (where the game image actually is). */
    public static float[] fittedViewportRect() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.getWindow() != null) updateViewportMetrics(mc.getWindow());
        if (!Shell.isOpen() || vpW <= 0f || vpH <= 0f) {
            return new float[]{vpX, vpY, vpW, vpH};
        }
        float aspect = Math.max(1f, lastDisplayW) / Math.max(1f, lastDisplayH);
        float fw = vpW;
        float fh = fw / aspect;
        if (fh > vpH) {
            fh = vpH;
            fw = fh * aspect;
        }
        float fx = (float) Math.floor(vpX + (vpW - fw) * 0.5f);
        float fy = (float) Math.floor(vpY + (vpH - fh) * 0.5f);
        return new float[]{fx, fy, Math.max(1f, (float) Math.floor(fw)), Math.max(1f, (float) Math.floor(fh))};
    }

    public static double lastMouseX() {
        return lastMouseX;
    }

    public static double lastMouseY() {
        return lastMouseY;
    }

    public static void updateViewportMetrics(Window win) {
        if (win == null) return;
        int sw = Math.max(1, win.getScreenWidth());
        int sh = Math.max(1, win.getScreenHeight());
        lastDisplayW = sw;
        lastDisplayH = sh;
        Shell.layout(sw, sh, getUiScale());
        vpX = Shell.gameX;
        vpY = Shell.gameY;
        vpW = Math.max(1f, Shell.gameW);
        vpH = Math.max(1f, Shell.gameH);
    }

    public static boolean insideViewport(double x, double y) {
        float[] f = fittedViewportRect();
        return x >= f[0] && x <= f[0] + f[2] && y >= f[1] && y <= f[1] + f[3];
    }

    public static double remapMouseX(double rawX) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null || !Shell.isOpen()) return rawX;
        float[] fit = fittedViewportRect();
        if (fit[2] <= 0f) return rawX;
        return (rawX - fit[0]) * (double) mc.getWindow().getScreenWidth() / (double) fit[2];
    }

    public static double remapMouseY(double rawY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null || !Shell.isOpen()) return rawY;
        float[] fit = fittedViewportRect();
        if (fit[3] <= 0f) return rawY;
        return (rawY - fit[1]) * (double) mc.getWindow().getScreenHeight() / (double) fit[3];
    }

    public static double guiToWindowX(double guiX) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) return guiX;
        Window win = mc.getWindow();
        double normX = guiX / Math.max(1, win.getGuiScaledWidth());
        if (!Shell.isOpen()) return normX * win.getScreenWidth();
        float[] fit = fittedViewportRect();
        return fit[0] + normX * fit[2];
    }

    public static double guiToWindowY(double guiY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) return guiY;
        Window win = mc.getWindow();
        double normY = guiY / Math.max(1, win.getGuiScaledHeight());
        if (!Shell.isOpen()) return normY * win.getScreenHeight();
        float[] fit = fittedViewportRect();
        return fit[1] + normY * fit[3];
    }

    /** Stop button action (also used by the in-game tests). */
    public static void triggerStop() {
        stopRequested = true;
        RunnerView.stop();
    }

    // ------------------------------------------------------------------ render thread

    public static void renderFrame() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        Window win = mc.getWindow();
        if (win == null || win.isIconified() || win.getWidth() <= 0 || win.getHeight() <= 0) return;
        updateViewportMetrics(win);
        if (!isActive() || initFailed) return;
        if (Shell.isOpen() && mc.mouseHandler != null && mc.mouseHandler.isMouseGrabbed()) {
            mc.mouseHandler.releaseMouse();
        }
        if (!initialized) {
            if (!init()) return;
        }
        if (stopRequested) {
            stopRequested = false;
            RunnerView.stop();
        }
        Backend.render(mc, win);
    }

    private static boolean init() {
        try {
            prepareNatives();
            Backend.init();
            initialized = true;
            return true;
        } catch (Throwable t) {
            initFailed = true;
            t.printStackTrace();
            return false;
        }
    }

    private static synchronized void prepareNatives() throws Exception {
        if (nativesPrepared) return;
        try {
            ImGuiNatives.load(FabricLoader.getInstance().getConfigDir().resolve("manhattan-natives"), ImGuiManager.class.getClassLoader());
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(ImGuiManager.class).warn("[Manhattan] ImGui workspace disabled: {}", e.getMessage());
            throw e;
        }
        nativesPrepared = true;
    }

    // ------------------------------------------------------------------ input hooks

    public static boolean onKey(long window, int key, int scancode, int action, int mods) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null || window != mc.getWindow().handle()) return false;
        if (!capturesInput() || !initialized) return false;

        KeyEvent ev = new KeyEvent(key, scancode, mods);
        boolean textActive = !unfocusRequested && Backend.wantsTextInput();
        boolean uiHasKeys = !gameFocused || focused || textActive;
        if (action == InputConstants.PRESS) {
            boolean ctrl = Platform.shortcutDown(mods);   // Cmd on macOS
            if (ctrl && key == InputConstants.KEY_P) {
                CommandPalette.toggle();
                setGameFocused(false);
                Backend.onKey(key, InputConstants.RELEASE, mods);
                return true;
            }
            if (uiHasKeys && !textActive && QueueMod.open != null && QueueMod.open.matches(ev)) {
                closeShell();
                return true;
            }
            if (uiHasKeys && !textActive && QueueMod.capture != null && QueueMod.capture.matches(ev)) {
                Shell.toggleDrawer(Shell.Drawer.CAPTURE);
                return true;
            }
        }
        if (uiHasKeys) {
            if ((action == InputConstants.PRESS || action == InputConstants.REPEAT) && DockedPanels.handleEditorKey(key, mods)) {
                return true;
            }
            if (action == InputConstants.PRESS && key == InputConstants.KEY_ESCAPE && !textActive) {
                if (CommandPalette.isOpen()) {
                    CommandPalette.close();
                } else if (Shell.drawer != Shell.Drawer.NONE) {
                    Shell.drawer = Shell.Drawer.NONE;
                } else {
                    gameFocused = true;
                    focused = false;
                    unfocusRequested = true;
                }
                Backend.onKey(key, action, mods);
                return true;
            }
            Backend.onKey(key, action, mods);
            return true;
        }
        return false;
    }

    public static boolean onChar(long window, int codepoint) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null || window != mc.getWindow().handle()) return false;
        if (!capturesInput() || !initialized) return false;
        if (!gameFocused || focused || (!unfocusRequested && Backend.wantsTextInput())) {
            Backend.onChar(codepoint);
            return true;
        }
        return false;
    }

    public static boolean onMouseMove(long window, double xpos, double ypos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null || window != mc.getWindow().handle()) return false;
        lastMouseX = xpos;
        lastMouseY = ypos;
        if (!capturesInput() || !initialized) return false;
        if (mc.mouseHandler.isMouseGrabbed()) return false;
        Backend.onMouseMove(xpos, ypos);
        return false;
    }

    public static boolean onMouseButton(long window, int button, int action, int mods) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null || window != mc.getWindow().handle()) return false;
        if (!capturesInput() || !initialized) return false;
        if (mc.mouseHandler.isMouseGrabbed()) return false;
        if (action == InputConstants.PRESS && button == InputConstants.MOUSE_BUTTON_LEFT && DockedPanels.handlePopupClick(lastMouseX, lastMouseY)) {
            return true;
        }

        boolean overPopup = CommandPalette.isOpen() || Shell.drawer != Shell.Drawer.NONE && Backend.wantsMouse();
        boolean inGame = insideViewport(lastMouseX, lastMouseY) && !overPopup;
        if (inGame) {
            if (action == InputConstants.PRESS) {
                gameFocused = true;
                focused = false;
                unfocusRequested = true;
            }
            Backend.onMouseButton(button, action);
            return false;
        }
        if (action == InputConstants.RELEASE && gameFocused) {
            Backend.onMouseButton(button, action);
            return false;
        }
        Backend.onMouseButton(button, action);
        if (action == InputConstants.PRESS) {
            gameFocused = false;
            focused = true;
            unfocusRequested = false;
            KeyMapping.releaseAll();
        }
        return true;
    }

    public static boolean onMouseScroll(long window, double xoffset, double yoffset) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null || window != mc.getWindow().handle()) return false;
        if (!capturesInput() || !initialized) return false;
        if (mc.mouseHandler.isMouseGrabbed()) return false;
        Backend.onMouseScroll(xoffset, yoffset);
        return !insideViewport(lastMouseX, lastMouseY);
    }

    // ------------------------------------------------------------------ Dear ImGui backend (loaded after prepareNatives)

    private static final class Backend {
        private static ImGuiContext ctx;
        private static long lastFrameNs;

        static void init() {
            ctx = ImGui.createContext();
            ImGui.setCurrentContext(ctx);

            ImGuiIO io = ImGui.getIO();
            io.setIniFilename(null);   // fixed layouts: nothing to save
            io.addConfigFlags(ImGuiConfigFlags.NavEnableKeyboard);

            UiTheme.apply(ImGui.getStyle(), getUiScale());
            try {
                UiTheme.loadFonts(io, getUiScale());
            } catch (Throwable t) {
                t.printStackTrace();
                io.getFonts().addFontDefault();
            }

            ImGuiRenderPearlRenderer.init();
            lastFrameNs = System.nanoTime();
        }

        /** Re-rasterises the fonts and rescales the style when the UI scale changed. */
        private static void refreshScale() {
            float scale = getUiScale();
            if (!UiTheme.needsFonts(scale)) return;
            ImGuiIO io = ImGui.getIO();
            io.getFonts().clear();
            UiTheme.loadFonts(io, scale);
            ImGuiRenderPearlRenderer.rebuildFontsTexture();
            UiTheme.apply(ImGui.getStyle(), scale);
        }

        static void render(Minecraft mc, Window win) {
            ImGui.setCurrentContext(ctx);
            refreshScale();
            ImGuiIO io = ImGui.getIO();

            int sw = Math.max(1, win.getScreenWidth());
            int sh = Math.max(1, win.getScreenHeight());
            int fbW = Math.max(1, win.getWidth());
            int fbH = Math.max(1, win.getHeight());
            io.setDisplaySize((float) sw, (float) sh);
            io.setDisplayFramebufferScale((float) fbW / sw, (float) fbH / sh);

            long nowNs = System.nanoTime();
            float dt = (float) ((nowNs - lastFrameNs) / 1_000_000_000.0);
            lastFrameNs = nowNs;
            io.setDeltaTime(dt > 0f && dt < 1f ? dt : 1f / 60f);

            boolean shell = Shell.isOpen();
            if (!shell || mc.mouseHandler.isMouseGrabbed()) {
                io.addConfigFlags(ImGuiConfigFlags.NoMouse);
                io.addMousePosEvent(-Float.MAX_VALUE, -Float.MAX_VALUE);
            } else {
                io.removeConfigFlags(ImGuiConfigFlags.NoMouse);
            }
            if (shell) io.removeConfigFlags(ImGuiConfigFlags.NoMouseCursorChange);
            else io.addConfigFlags(ImGuiConfigFlags.NoMouseCursorChange);

            io.setWantSetMousePos(false);
            ImGui.newFrame();

            if (unfocusRequested) {
                ImGui.setWindowFocus(null);
                unfocusRequested = false;
                focused = false;
            }

            ProgressModel.Snapshot snap = ProgressModel.snapshot();
            float w = io.getDisplaySizeX(), h = io.getDisplaySizeY();
            if (shell) Shell.render(snap, w, h);
            else Overlay.draw(snap, w, h);

            ImGui.render();

            ImGuiRenderPearlRenderer.renderDrawData(mc, ImGui.getDrawData(), shell, shell ? fittedViewportRect() : null);

            if (shotPath != null) {
                Path p = shotPath;
                shotPath = null;
                shotDone = "unsupported on RenderPearl";
            }

            framesRendered++;
            lastDisplayW = (int) w;
            lastDisplayH = (int) h;
        }

        static boolean wantsKeyboard() {
            ImGui.setCurrentContext(ctx);
            return ImGui.getIO().getWantCaptureKeyboard();
        }

        static boolean wantsTextInput() {
            ImGui.setCurrentContext(ctx);
            return ImGui.getIO().getWantTextInput();
        }

        static boolean wantsMouse() {
            ImGui.setCurrentContext(ctx);
            return ImGui.getIO().getWantCaptureMouse();
        }

        static void onKey(int key, int action, int mods) {
            ImGui.setCurrentContext(ctx);
            ImGuiIO io = ImGui.getIO();
            io.addKeyEvent(ImGuiKey.ModCtrl, (mods & Platform.MOD_CONTROL) != 0);
            io.addKeyEvent(ImGuiKey.ModShift, (mods & Platform.MOD_SHIFT) != 0);
            io.addKeyEvent(ImGuiKey.ModAlt, (mods & Platform.MOD_ALT) != 0);
            io.addKeyEvent(ImGuiKey.ModSuper, (mods & Platform.MOD_SUPER) != 0);
            int imguiKey = toImGuiKey(key);
            if (imguiKey != ImGuiKey.None) {
                io.addKeyEvent(imguiKey, action != InputConstants.RELEASE);
            }
        }

        static void onChar(int codepoint) {
            ImGui.setCurrentContext(ctx);
            ImGui.getIO().addInputCharacter(codepoint);
        }

        static void onMouseMove(double x, double y) {
            ImGui.setCurrentContext(ctx);
            ImGui.getIO().addMousePosEvent((float) x, (float) y);
        }

        static void onMouseButton(int button, int action) {
            ImGui.setCurrentContext(ctx);
            if (button >= 0 && button < 5) {
                ImGui.getIO().addMouseButtonEvent(button, action != InputConstants.RELEASE);
            }
        }

        static void onMouseScroll(double dx, double dy) {
            ImGui.setCurrentContext(ctx);
            ImGui.getIO().addMouseWheelEvent((float) dx, (float) dy);
        }

        private static int toImGuiKey(int key) {
            if (key >= InputConstants.KEY_A && key <= InputConstants.KEY_Z) {
                return ImGuiKey.A + (key - InputConstants.KEY_A);
            }
            if (key >= InputConstants.KEY_0 && key <= InputConstants.KEY_9) {
                return ImGuiKey._0 + (key - InputConstants.KEY_0);
            }
            return switch (key) {
                case InputConstants.KEY_TAB -> ImGuiKey.Tab;
                case InputConstants.KEY_LEFT -> ImGuiKey.LeftArrow;
                case InputConstants.KEY_RIGHT -> ImGuiKey.RightArrow;
                case InputConstants.KEY_UP -> ImGuiKey.UpArrow;
                case InputConstants.KEY_DOWN -> ImGuiKey.DownArrow;
                case InputConstants.KEY_PAGEUP -> ImGuiKey.PageUp;
                case InputConstants.KEY_PAGEDOWN -> ImGuiKey.PageDown;
                case InputConstants.KEY_HOME -> ImGuiKey.Home;
                case InputConstants.KEY_END -> ImGuiKey.End;
                case InputConstants.KEY_INSERT -> ImGuiKey.Insert;
                case InputConstants.KEY_DELETE -> ImGuiKey.Delete;
                case InputConstants.KEY_BACKSPACE -> ImGuiKey.Backspace;
                case InputConstants.KEY_SPACE -> ImGuiKey.Space;
                case InputConstants.KEY_RETURN -> ImGuiKey.Enter;
                case InputConstants.KEY_NUMPADENTER -> ImGuiKey.KeypadEnter;
                case InputConstants.KEY_ESCAPE -> ImGuiKey.Escape;
                case InputConstants.KEY_SLASH -> ImGuiKey.Slash;
                case InputConstants.KEY_MINUS -> ImGuiKey.Minus;
                case InputConstants.KEY_EQUALS -> ImGuiKey.Equal;
                case InputConstants.KEY_LBRACKET -> ImGuiKey.LeftBracket;
                case InputConstants.KEY_RBRACKET -> ImGuiKey.RightBracket;
                case InputConstants.KEY_BACKSLASH -> ImGuiKey.Backslash;
                case InputConstants.KEY_SEMICOLON -> ImGuiKey.Semicolon;
                case InputConstants.KEY_APOSTROPHE -> ImGuiKey.Apostrophe;
                case InputConstants.KEY_COMMA -> ImGuiKey.Comma;
                case InputConstants.KEY_PERIOD -> ImGuiKey.Period;
                case InputConstants.KEY_GRAVE -> ImGuiKey.GraveAccent;
                case InputConstants.KEY_LCONTROL -> ImGuiKey.LeftCtrl;
                case InputConstants.KEY_RCONTROL -> ImGuiKey.RightCtrl;
                case InputConstants.KEY_LSHIFT -> ImGuiKey.LeftShift;
                case InputConstants.KEY_RSHIFT -> ImGuiKey.RightShift;
                case InputConstants.KEY_LALT -> ImGuiKey.LeftAlt;
                case InputConstants.KEY_RALT -> ImGuiKey.RightAlt;
                case InputConstants.KEY_LGUI -> ImGuiKey.LeftSuper;
                case InputConstants.KEY_RGUI -> ImGuiKey.RightSuper;
                default -> ImGuiKey.None;
            };
        }
    }
}
