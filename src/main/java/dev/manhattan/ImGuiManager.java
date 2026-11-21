package dev.manhattan;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiConfigFlags;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;
import imgui.internal.ImGuiContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWMonitorCallback;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.nio.file.Files;
import java.nio.file.Path;

import static dev.manhattan.Config.cfg;

/**
 * Dear ImGui lifecycle and input routing for the workspace ({@link Shell}, key J) and the closed-state
 * {@link Overlay}. While the workspace is open the game is drawn into {@link Shell}'s game area (letterboxed to the
 * window's aspect ratio): a click there gives the game the keyboard and mouse buttons, Esc gives them back to the
 * panels. While it is closed nothing here takes input; the overlay is only drawn.
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
        if (mc != null && mc.screen == null && mc.level != null && !"true".equals(System.getProperty("manhattan.tests"))) {
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
        if (win == null || win.isMinimized() || win.getWidth() <= 0 || win.getHeight() <= 0) return;
        updateViewportMetrics(win);
        if (!isActive() || initFailed) return;
        if (Shell.isOpen() && mc.mouseHandler != null && mc.mouseHandler.isMouseGrabbed()) {
            mc.mouseHandler.releaseMouse();
        }
        if (!initialized) {
            if (!init(win.handle())) return;
        }
        if (stopRequested) {
            stopRequested = false;
            RunnerView.stop();
        }
        Backend.render(mc, win);
    }

    private static boolean init(long windowHandle) {
        try {
            prepareNatives();
            Backend.init(windowHandle);
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
        if (action == GLFW.GLFW_PRESS) {
            boolean ctrl = Platform.shortcutDown(mods);   // Cmd on macOS
            if (ctrl && key == GLFW.GLFW_KEY_P) {
                CommandPalette.toggle();
                setGameFocused(false);
                Backend.onKey(window, key, scancode, GLFW.GLFW_RELEASE, mods);
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
            if ((action == GLFW.GLFW_PRESS || action == GLFW.GLFW_REPEAT) && DockedPanels.handleEditorKey(key, mods)) {
                return true;
            }
            if (action == GLFW.GLFW_PRESS && key == GLFW.GLFW_KEY_ESCAPE && !textActive) {
                if (CommandPalette.isOpen()) {
                    CommandPalette.close();
                } else if (Shell.drawer != Shell.Drawer.NONE) {
                    Shell.drawer = Shell.Drawer.NONE;
                } else {
                    gameFocused = true;
                    focused = false;
                    unfocusRequested = true;
                }
                Backend.onKey(window, key, scancode, action, mods);
                return true;
            }
            Backend.onKey(window, key, scancode, action, mods);
            return true;
        }
        return false;
    }

    public static boolean onChar(long window, int codepoint) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null || window != mc.getWindow().handle()) return false;
        if (!capturesInput() || !initialized) return false;
        if (!gameFocused || focused || (!unfocusRequested && Backend.wantsTextInput())) {
            Backend.onChar(window, codepoint);
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
        Backend.onMouseMove(window, xpos, ypos);
        return false;
    }

    public static boolean onMouseButton(long window, int button, int action, int mods) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null || window != mc.getWindow().handle()) return false;
        if (!capturesInput() || !initialized) return false;
        if (mc.mouseHandler.isMouseGrabbed()) return false;
        if (action == GLFW.GLFW_PRESS && button == GLFW.GLFW_MOUSE_BUTTON_LEFT && DockedPanels.handlePopupClick(lastMouseX, lastMouseY)) {
            return true;
        }

        boolean overPopup =CommandPalette.isOpen() || Shell.drawer != Shell.Drawer.NONE && Backend.wantsMouse();
        boolean inGame = insideViewport(lastMouseX, lastMouseY) && !overPopup;
        if (inGame) {
            if (action == GLFW.GLFW_PRESS) {
                gameFocused = true;
                focused = false;
                unfocusRequested = true;
            }
            Backend.onMouseButton(window, button, action, mods);
            return false;
        }
        if (action == GLFW.GLFW_RELEASE && gameFocused) {
            Backend.onMouseButton(window, button, action, mods);
            return false;
        }
        Backend.onMouseButton(window, button, action, mods);
        if (action == GLFW.GLFW_PRESS) {
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
        Backend.onMouseScroll(window, xoffset, yoffset);
        return !insideViewport(lastMouseX, lastMouseY);
    }

    // ------------------------------------------------------------------ Dear ImGui backend (loaded after prepareNatives)

    private static final class Backend {
        private static ImGuiContext ctx;
        private static ImGuiImplGlfw glfw;
        private static ImGuiImplGl3 gl3;
        private static int blitReadFbo;

        static void init(long windowHandle) {
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

            glfw = new ImGuiImplGlfw();
            GLFWMonitorCallback prevMonitor = GLFW.glfwSetMonitorCallback(null);
            glfw.init(windowHandle, false);
            GLFWMonitorCallback imguiMonitor = GLFW.glfwSetMonitorCallback(null);
            GLFW.glfwSetMonitorCallback((monitor, event) -> {
                if (prevMonitor != null) prevMonitor.invoke(monitor, event);
                if (imguiMonitor != null) imguiMonitor.invoke(monitor, event);
            });

            gl3 = new ImGuiImplGl3();
            gl3.init("#version 150");
        }

        /** Re-rasterises the fonts and rescales the style when the UI scale changed. */
        private static void refreshScale() {
            float scale = getUiScale();
            if (!UiTheme.needsFonts(scale)) return;
            ImGuiIO io = ImGui.getIO();
            io.getFonts().clear();
            UiTheme.loadFonts(io, scale);
            gl3.destroyFontsTexture();
            gl3.createFontsTexture();
            UiTheme.apply(ImGui.getStyle(), scale);
        }

        static void render(Minecraft mc, Window win) {
            ImGui.setCurrentContext(ctx);
            refreshScale();
            ImGuiIO io = ImGui.getIO();
            boolean shell = Shell.isOpen();
            if (!shell || mc.mouseHandler.isMouseGrabbed()) {
                io.addConfigFlags(ImGuiConfigFlags.NoMouse);
                io.addMousePosEvent(-Float.MAX_VALUE, -Float.MAX_VALUE);
            } else {
                io.removeConfigFlags(ImGuiConfigFlags.NoMouse);
            }
            // Closed (overlay only): ImGui must not touch the cursor mode, or the game's mouse look and aim jump.
            if (shell) io.removeConfigFlags(ImGuiConfigFlags.NoMouseCursorChange);
            else io.addConfigFlags(ImGuiConfigFlags.NoMouseCursorChange);

            io.setWantSetMousePos(false);
            glfw.newFrame();
            gl3.newFrame();
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

            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            if (shell) blitGameViewport(mc, win);
            gl3.renderDrawData(ImGui.getDrawData());
            if (shotPath != null) {
                Path p = shotPath;
                shotPath = null;
                saveShot(win, p);
            }

            framesRendered++;
            lastDisplayW = (int) w;
            lastDisplayH = (int) h;
        }

        /** The finished frame (game and panels) as a PNG; used by the in-game tests to look at the layouts. */
        private static void saveShot(Window win, Path p) {
            try {
                int w = win.getWidth(), h = win.getHeight();
                java.nio.ByteBuffer buf = org.lwjgl.BufferUtils.createByteBuffer(w * h * 4);
                GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
                java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int i = ((h - 1 - y) * w + x) * 4;
                        img.setRGB(x, y, ((buf.get(i) & 0xFF) << 16) | ((buf.get(i + 1) & 0xFF) << 8) | (buf.get(i + 2) & 0xFF));
                    }
                }
                Files.createDirectories(p.getParent());
                javax.imageio.ImageIO.write(img, "png", p.toFile());
                shotDone = p.toString();
            } catch (Throwable t) {
                shotDone = "error: " + t;
            }
        }

        /** Clears the window to the workspace background and copies the game image into the fitted game area. */
        private static void blitGameViewport(Minecraft mc, Window win) {
            RenderTarget mainTarget = mc.getMainRenderTarget();
            if (mainTarget == null || !(mainTarget.getColorTexture() instanceof GlTexture glTex)) return;
            if (blitReadFbo == 0) blitReadFbo = GlStateManager.glGenFramebuffers();

            float[] fit = fittedViewportRect();
            double sx = (double) win.getWidth() / Math.max(1, win.getScreenWidth());
            double sy = (double) win.getHeight() / Math.max(1, win.getScreenHeight());
            int dstX0 = (int) Math.round(fit[0] * sx);
            int dstX1 = (int) Math.round((fit[0] + fit[2]) * sx);
            int dstY0 = (int) Math.round((win.getScreenHeight() - (fit[1] + fit[3])) * sy);
            int dstY1 = (int) Math.round((win.getScreenHeight() - fit[1]) * sy);

            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
            GlStateManager._disableScissorTest();
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glClearColor(UiTheme.r(UiTheme.BG), UiTheme.g(UiTheme.BG), UiTheme.b(UiTheme.BG), 1.0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, blitReadFbo);
            GlStateManager._glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, glTex.glId(), 0);
            GlStateManager._glBlitFrameBuffer(
                    0, 0, mainTarget.width, mainTarget.height,
                    dstX0, dstY0, dstX1, dstY1,
                    GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR
            );
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
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

        static void onKey(long window, int key, int scancode, int action, int mods) {
            ImGui.setCurrentContext(ctx);
            glfw.keyCallback(window, key, scancode, action, mods);
        }

        static void onChar(long window, int codepoint) {
            ImGui.setCurrentContext(ctx);
            glfw.charCallback(window, codepoint);
        }

        static void onMouseMove(long window, double x, double y) {
            ImGui.setCurrentContext(ctx);
            glfw.cursorPosCallback(window, x, y);
        }

        static void onMouseButton(long window, int button, int action, int mods) {
            ImGui.setCurrentContext(ctx);
            glfw.mouseButtonCallback(window, button, action, mods);
        }

        static void onMouseScroll(long window, double dx, double dy) {
            ImGui.setCurrentContext(ctx);
            glfw.scrollCallback(window, dx, dy);
        }
    }
}
