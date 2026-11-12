package dev.baritonequeue;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.ImGuiStyle;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiConfigFlags;
import imgui.flag.ImGuiFocusedFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;
import imgui.internal.ImGuiContext;
import imgui.type.ImBoolean;
import imgui.type.ImString;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWMonitorCallback;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.baritonequeue.Config.cfg;

/**
 * Dear ImGui lifecycle, input routing, Stage 0 spike window (key F9), and Stage 1 Game Viewport (top-right quarter
 * with mouse remapping, keyboard focus gating, and F8 fullscreen game toggle).
 *
 * <p>Natives are extracted into {@code <gameDir>/config/manhattan-natives} before {@link ImGui} is loaded so multiple
 * game instances (or another ImGui mod such as Flashback) never share a temp DLL or {@code imgui.ini}.
 * Input is forwarded from Minecraft's {@code KeyboardHandler} and {@code MouseHandler} mixins (with
 * {@code installCallbacks = false}) so GLFW callbacks are never overwritten.
 */
public final class ImGuiManager {
    public static final float TOP_BAR_H = 30f;

    private static boolean nativesPrepared;
    private static boolean initialized;
    private static boolean initFailed;
    private static boolean spikeOpen;
    private static boolean fullscreenGame;
    private static boolean gameFocused = true;
    private static boolean focused;
    private static boolean focusRequested;
    private static boolean unfocusRequested;
    private static boolean stopRequested;
    private static long framesRendered;
    private static int lastDisplayW, lastDisplayH;
    private static float winX = 24, winY = 24, winW = 340, winH = 220;
    private static float stopBtnX, stopBtnY, stopBtnW, stopBtnH;
    private static float fsBtnX, fsBtnY, fsBtnW, fsBtnH;
    private static float vpX, vpY, vpW, vpH;
    private static double lastMouseX, lastMouseY;

    private ImGuiManager() {}

    /** Whether any ImGui UI should be drawn this frame. */
    public static boolean isActive() {
        return spikeOpen || isDockedViewportActive();
    }

    /** Whether the game is currently rendered into the docked top-right quarter (ui.mode = docked and F8 fullscreen off). */
    public static boolean isDockedViewportActive() {
        return "docked".equals(cfg.uiMode) && !fullscreenGame;
    }

    public static boolean isFullscreenGame() {
        return fullscreenGame;
    }

    public static void toggleFullscreenGame() {
        setFullscreenGame(!fullscreenGame);
    }

    public static void setFullscreenGame(boolean fullscreen) {
        if (fullscreenGame == fullscreen) return;
        fullscreenGame = fullscreen;
        Minecraft mc = Minecraft.getInstance();
        if (fullscreen) {
            gameFocused = true;
            focused = false;
            unfocusRequested = true;
        }
        if (mc != null && mc.getWindow() != null) {
            updateViewportMetrics(mc.getWindow());
        }
    }

    /** Whether keyboard input currently goes to the Minecraft game view rather than ImGui panels. */
    public static boolean isGameFocused() {
        if (isDockedViewportActive()) {
            return gameFocused && !focused && (unfocusRequested || !(initialized && Backend.wantsTextInput()));
        }
        return !isFocused();
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

    public static boolean isInitialized() {
        return initialized;
    }

    public static boolean isSpikeOpen() {
        return spikeOpen;
    }

    public static boolean isFocused() {
        if (isDockedViewportActive()) {
            return !gameFocused || focused || (!unfocusRequested && initialized && Backend.wantsTextInput());
        }
        return spikeOpen && !unfocusRequested && (focused || (initialized && Backend.wantsKeyboard()));
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

    public static float[] windowRect() {
        return new float[]{winX, winY, winW, winH};
    }

    public static float[] stopButtonRect() {
        return new float[]{stopBtnX, stopBtnY, stopBtnW, stopBtnH};
    }

    public static float[] fullscreenButtonRect() {
        return new float[]{fsBtnX, fsBtnY, fsBtnW, fsBtnH};
    }

    public static float[] viewportRect() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.getWindow() != null) updateViewportMetrics(mc.getWindow());
        return new float[]{vpX, vpY, vpW, vpH};
    }

    public static String noteText() {
        return initialized ? Backend.note.get() : "";
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
        if (isDockedViewportActive()) {
            float contentY = TOP_BAR_H;
            float contentH = Math.max(2f, sh - contentY);
            float halfW = (float) Math.floor(sw * 0.5f);
            float halfH = (float) Math.floor(contentH * 0.5f);
            vpX = halfW;
            vpY = contentY;
            vpW = Math.max(1f, sw - halfW);
            vpH = Math.max(1f, halfH);
        } else {
            vpX = 0f;
            vpY = 0f;
            vpW = sw;
            vpH = sh;
        }
    }

    public static boolean insideViewport(double x, double y) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.getWindow() != null) updateViewportMetrics(mc.getWindow());
        return x >= vpX && x <= vpX + vpW && y >= vpY && y <= vpY + vpH;
    }

    public static double remapMouseX(double rawX) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) return rawX;
        Window win = mc.getWindow();
        updateViewportMetrics(win);
        if (!isDockedViewportActive() || vpW <= 0f) return rawX;
        return (rawX - vpX) * (double) win.getScreenWidth() / (double) vpW;
    }

    public static double remapMouseY(double rawY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) return rawY;
        Window win = mc.getWindow();
        updateViewportMetrics(win);
        if (!isDockedViewportActive() || vpH <= 0f) return rawY;
        return (rawY - vpY) * (double) win.getScreenHeight() / (double) vpH;
    }

    public static double guiToWindowX(double guiX) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) return guiX;
        Window win = mc.getWindow();
        updateViewportMetrics(win);
        double normX = guiX / Math.max(1, win.getGuiScaledWidth());
        return isDockedViewportActive() ? vpX + normX * vpW : normX * win.getScreenWidth();
    }

    public static double guiToWindowY(double guiY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) return guiY;
        Window win = mc.getWindow();
        updateViewportMetrics(win);
        double normY = guiY / Math.max(1, win.getGuiScaledHeight());
        return isDockedViewportActive() ? vpY + normY * vpH : normY * win.getScreenHeight();
    }

    public static double viewportCenterX() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.getWindow() != null) updateViewportMetrics(mc.getWindow());
        return vpX + vpW * 0.5;
    }

    public static double viewportCenterY() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.getWindow() != null) updateViewportMetrics(mc.getWindow());
        return vpY + vpH * 0.5;
    }

    public static void toggleSpike() {
        setSpikeOpen(!spikeOpen);
    }

    public static void setSpikeOpen(boolean open) {
        if (spikeOpen == open) return;
        spikeOpen = open;
        Minecraft mc = Minecraft.getInstance();
        if (open) {
            focused = true;
            focusRequested = true;
            unfocusRequested = false;
            KeyMapping.releaseAll();
            if (mc != null && mc.mouseHandler != null && mc.mouseHandler.isMouseGrabbed()) {
                mc.mouseHandler.releaseMouse();
            }
        } else {
            focused = false;
            focusRequested = false;
            unfocusRequested = true;
            if (mc != null && mc.screen == null && mc.level != null && !"true".equals(System.getProperty("manhattan.tests"))) {
                mc.mouseHandler.grabMouse();
            }
        }
    }

    /** Triggers the Stop button action (used by the UI button and testable via TestBridge). */
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
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("manhattan-natives");
        Files.createDirectories(dir);
        Path dll = dir.resolve("imgui-java64.dll");
        try (InputStream in = ImGuiManager.class.getClassLoader().getResourceAsStream("io/imgui/java/native-bin/imgui-java64.dll")) {
            if (in != null) {
                byte[] bytes = in.readAllBytes();
                if (!Files.exists(dll) || Files.size(dll) != bytes.length) {
                    Files.write(dll, bytes);
                }
                System.setProperty("imgui.library.path", dir.toAbsolutePath().toString());
            }
        }
        nativesPrepared = true;
    }

    // ------------------------------------------------------------------ input hooks

    private static boolean insideSpikeWindow(double x, double y) {
        return spikeOpen && x >= winX && x <= winX + winW && y >= winY && y <= winY + winH;
    }

    public static boolean onKey(long window, int key, int scancode, int action, int mods) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null || window != mc.getWindow().handle()) return false;

        KeyEvent ev = new KeyEvent(key, scancode, mods);
        boolean textActive = initialized && !unfocusRequested && Backend.wantsTextInput();
        if (action == GLFW.GLFW_PRESS && !textActive) {
            if ((QueueMod.spike != null && QueueMod.spike.matches(ev)) || key == GLFW.GLFW_KEY_F9) {
                toggleSpike();
                return true;
            }
            if ((QueueMod.fullscreen != null && QueueMod.fullscreen.matches(ev)) || key == GLFW.GLFW_KEY_F8) {
                toggleFullscreenGame();
                return true;
            }
        }
        if (!isActive() || !initialized) return false;

        if (isDockedViewportActive()) {
            if (!gameFocused || focused || textActive) {
                if (action == GLFW.GLFW_PRESS && key == GLFW.GLFW_KEY_ESCAPE && !textActive) {
                    gameFocused = true;
                    focused = false;
                    unfocusRequested = true;
                    Backend.onKey(window, key, scancode, action, mods);
                    if (mc.screen == null && mc.level != null && !"true".equals(System.getProperty("manhattan.tests"))) {
                        mc.mouseHandler.grabMouse();
                    }
                    return true;
                }
                Backend.onKey(window, key, scancode, action, mods);
                return true;
            }
            return false;
        }

        if (action == GLFW.GLFW_PRESS && key == GLFW.GLFW_KEY_ESCAPE && (focused || Backend.wantsKeyboard())) {
            if (!textActive) {
                focused = false;
                unfocusRequested = true;
                Backend.onKey(window, key, scancode, action, mods);
                if (mc.screen == null && mc.level != null && !"true".equals(System.getProperty("manhattan.tests"))) {
                    mc.mouseHandler.grabMouse();
                }
                return true;
            }
        }

        Backend.onKey(window, key, scancode, action, mods);
        return focused || Backend.wantsKeyboard();
    }

    public static boolean onChar(long window, int codepoint) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null || window != mc.getWindow().handle()) return false;
        if (!isActive() || !initialized) return false;
        if (isDockedViewportActive()) {
            if (!gameFocused || focused || (!unfocusRequested && Backend.wantsTextInput())) {
                Backend.onChar(window, codepoint);
                return true;
            }
            return false;
        }
        Backend.onChar(window, codepoint);
        return focused || Backend.wantsKeyboard();
    }

    public static boolean onMouseMove(long window, double xpos, double ypos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null || window != mc.getWindow().handle()) return false;
        lastMouseX = xpos;
        lastMouseY = ypos;
        if (!isActive() || !initialized) return false;
        if (mc.mouseHandler.isMouseGrabbed()) return false;
        Backend.onMouseMove(window, xpos, ypos);
        return false;
    }

    public static boolean onMouseButton(long window, int button, int action, int mods) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null || window != mc.getWindow().handle()) return false;
        if (!isActive() || !initialized) return false;
        if (mc.mouseHandler.isMouseGrabbed()) return false;

        if (isDockedViewportActive()) {
            boolean inSpike = insideSpikeWindow(lastMouseX, lastMouseY);
            boolean inGame = insideViewport(lastMouseX, lastMouseY) && !inSpike;
            if (inGame) {
                if (action == GLFW.GLFW_PRESS) {
                    gameFocused = true;
                    focused = false;
                    unfocusRequested = true;
                }
                Backend.onMouseButton(window, button, action, mods);
                return false;
            }
            if (action == GLFW.GLFW_RELEASE && gameFocused && !inSpike) {
                Backend.onMouseButton(window, button, action, mods);
                return false;
            }
            Backend.onMouseButton(window, button, action, mods);
            if (action == GLFW.GLFW_PRESS) {
                gameFocused = false;
                focused = inSpike;
                unfocusRequested = false;
                KeyMapping.releaseAll();
            }
            return true;
        }

        Backend.onMouseButton(window, button, action, mods);
        boolean overImGui = Backend.wantsMouse() || insideSpikeWindow(lastMouseX, lastMouseY);
        if (overImGui) {
            if (action == GLFW.GLFW_PRESS) {
                focused = true;
                KeyMapping.releaseAll();
            }
            return true;
        }
        if (action == GLFW.GLFW_PRESS && focused) {
            focused = false;
            unfocusRequested = true;
        }
        return false;
    }

    public static boolean onMouseScroll(long window, double xoffset, double yoffset) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null || window != mc.getWindow().handle()) return false;
        if (!isActive() || !initialized) return false;
        if (mc.mouseHandler.isMouseGrabbed()) return false;
        Backend.onMouseScroll(window, xoffset, yoffset);
        if (isDockedViewportActive()) {
            return !insideViewport(lastMouseX, lastMouseY) || insideSpikeWindow(lastMouseX, lastMouseY);
        }
        return Backend.wantsMouse() || insideSpikeWindow(lastMouseX, lastMouseY);
    }

    // ------------------------------------------------------------------ Dear ImGui backend (loaded after prepareNatives)

    private static final class Backend {
        private static ImGuiContext ctx;
        private static ImGuiImplGlfw glfw;
        private static ImGuiImplGl3 gl3;
        private static int blitReadFbo;
        private static final ImBoolean openFlag = new ImBoolean(true);
        private static final ImString note = new ImString(128);

        static void init(long windowHandle) {
            ctx = ImGui.createContext();
            ImGui.setCurrentContext(ctx);

            ImGuiIO io = ImGui.getIO();
            Path ini = FabricLoader.getInstance().getConfigDir().resolve("manhattan-imgui.ini");
            io.setIniFilename(ini.toAbsolutePath().toString());
            io.addConfigFlags(ImGuiConfigFlags.NavEnableKeyboard | ImGuiConfigFlags.DockingEnable);

            applyStyle(ImGui.getStyle());

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

        /** Dark palette matching ProgressView (#23262e bg, #2c303a panels, #343944 borders, #4a9ee0 accent). */
        private static void applyStyle(ImGuiStyle s) {
            ImGui.styleColorsDark(s);
            s.setWindowRounding(4f);
            s.setFrameRounding(3f);
            s.setGrabRounding(3f);
            s.setWindowBorderSize(1f);
            s.setFrameBorderSize(1f);

            s.setColor(ImGuiCol.WindowBg, 0.137f, 0.149f, 0.180f, 0.96f);         // #23262e
            s.setColor(ImGuiCol.ChildBg, 0.173f, 0.188f, 0.227f, 1.00f);          // #2c303a
            s.setColor(ImGuiCol.PopupBg, 0.137f, 0.149f, 0.180f, 0.98f);
            s.setColor(ImGuiCol.Border, 0.204f, 0.224f, 0.267f, 1.00f);           // #343944
            s.setColor(ImGuiCol.FrameBg, 0.173f, 0.188f, 0.227f, 1.00f);          // #2c303a
            s.setColor(ImGuiCol.FrameBgHovered, 0.220f, 0.245f, 0.298f, 1.00f);
            s.setColor(ImGuiCol.FrameBgActive, 0.290f, 0.620f, 0.878f, 0.45f);
            s.setColor(ImGuiCol.TitleBg, 0.137f, 0.149f, 0.180f, 1.00f);
            s.setColor(ImGuiCol.TitleBgActive, 0.173f, 0.188f, 0.227f, 1.00f);
            s.setColor(ImGuiCol.Button, 0.204f, 0.224f, 0.267f, 1.00f);
            s.setColor(ImGuiCol.ButtonHovered, 0.290f, 0.620f, 0.878f, 0.85f);    // #4a9ee0
            s.setColor(ImGuiCol.ButtonActive, 0.290f, 0.620f, 0.878f, 1.00f);
            s.setColor(ImGuiCol.Header, 0.290f, 0.620f, 0.878f, 0.35f);
            s.setColor(ImGuiCol.HeaderHovered, 0.290f, 0.620f, 0.878f, 0.65f);
            s.setColor(ImGuiCol.HeaderActive, 0.290f, 0.620f, 0.878f, 0.85f);
            s.setColor(ImGuiCol.Tab, 0.173f, 0.188f, 0.227f, 1.00f);
            s.setColor(ImGuiCol.TabHovered, 0.290f, 0.620f, 0.878f, 0.80f);
            s.setColor(ImGuiCol.TabActive, 0.290f, 0.620f, 0.878f, 1.00f);
        }

        static void render(Minecraft mc, Window win) {
            ImGui.setCurrentContext(ctx);
            ImGuiIO io = ImGui.getIO();
            if (mc.mouseHandler.isMouseGrabbed()) {
                io.addConfigFlags(ImGuiConfigFlags.NoMouse);
                io.addMousePosEvent(-Float.MAX_VALUE, -Float.MAX_VALUE);
            } else {
                io.removeConfigFlags(ImGuiConfigFlags.NoMouse);
            }

            glfw.newFrame();
            gl3.newFrame();
            ImGui.newFrame();

            if (unfocusRequested && !spikeOpen) {
                ImGui.setWindowFocus(null);
                unfocusRequested = false;
                focused = false;
            }

            boolean dockedActive = isDockedViewportActive();
            if (dockedActive) {
                renderTopBar();
                renderDockedShell(mc);
            }
            if (spikeOpen) {
                renderSpikeWindow();
            }

            ImGui.render();

            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            if (dockedActive) {
                blitGameViewport(mc, win);
            }
            gl3.renderDrawData(ImGui.getDrawData());

            framesRendered++;
            lastDisplayW = (int) io.getDisplaySizeX();
            lastDisplayH = (int) io.getDisplaySizeY();
        }

        private static void blitGameViewport(Minecraft mc, Window win) {
            GlStateManager._disableScissorTest();
            GlStateManager._viewport(0, 0, win.getWidth(), win.getHeight());
            GL11.glClearColor(0.137f, 0.149f, 0.180f, 1.0f);
            GlStateManager._clear(GL11.GL_COLOR_BUFFER_BIT);

            RenderTarget mainTarget = mc.getMainRenderTarget();
            if (mainTarget != null && mainTarget.getColorTexture() instanceof GlTexture glTex) {
                if (blitReadFbo == 0) {
                    blitReadFbo = GlStateManager.glGenFramebuffers();
                }
                GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, blitReadFbo);
                GlStateManager._glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, glTex.glId(), 0);
                GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);

                double sx = (double) win.getWidth() / Math.max(1, win.getScreenWidth());
                double sy = (double) win.getHeight() / Math.max(1, win.getScreenHeight());
                int dstX0 = (int) Math.round(vpX * sx);
                int dstX1 = (int) Math.round((vpX + vpW) * sx);
                int dstY0 = (int) Math.round((win.getScreenHeight() - (vpY + vpH)) * sy);
                int dstY1 = (int) Math.round((win.getScreenHeight() - vpY) * sy);

                GlStateManager._glBlitFrameBuffer(
                        0, 0, mainTarget.width, mainTarget.height,
                        dstX0, dstY0, dstX1, dstY1,
                        GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR
                );
                GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
                GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            }
        }

        private static void renderTopBar() {
            int flags = ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoMove
                    | ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse
                    | ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.NoSavedSettings;
            ImGui.setNextWindowPos(0f, 0f);
            ImGui.setNextWindowSize(lastDisplayW, TOP_BAR_H);
            ImGui.pushStyleVar(imgui.flag.ImGuiStyleVar.WindowRounding, 0f);
            ImGui.pushStyleVar(imgui.flag.ImGuiStyleVar.WindowPadding, 8f, 4f);
            if (ImGui.begin("##ManhattanTopBar", flags)) {
                boolean running = RunnerView.running();
                ImGui.text("Manhattan");
                ImGui.sameLine(0f, 12f);
                if (running) {
                    ImGui.textColored(0.247f, 0.698f, 0.498f, 1.0f, "Running");
                    ImGui.sameLine(0f, 8f);
                    ImGui.textDisabled("Line " + RunnerView.line() + " (" + RunnerView.elapsedText() + ")");
                } else {
                    ImGui.textDisabled("Idle");
                }

                float rightButtonsW = 280f;
                float startX = Math.max(ImGui.getCursorPosX() + 12f, lastDisplayW - rightButtonsW);
                ImGui.sameLine(startX);
                if (ImGui.button("Skip", 54f, 22f)) {
                    RunnerView.skip();
                }
                ImGui.sameLine(0f, 6f);
                if (!spikeOpen) {
                    stopBtnX = ImGui.getCursorScreenPosX();
                    stopBtnY = ImGui.getCursorScreenPosY();
                    stopBtnW = 54f;
                    stopBtnH = 22f;
                }
                if (ImGui.button("Stop", 54f, 22f)) {
                    RunnerView.stop();
                }
                ImGui.sameLine(0f, 8f);
                fsBtnX = ImGui.getCursorScreenPosX();
                fsBtnY = ImGui.getCursorScreenPosY();
                fsBtnW = 148f;
                fsBtnH = 22f;
                if (ImGui.button("Fullscreen game (F8)", fsBtnW, fsBtnH)) {
                    toggleFullscreenGame();
                }
            }
            ImGui.end();
            ImGui.popStyleVar(2);
        }

        private static void renderDockedShell(Minecraft mc) {
            int panelFlags = ImGuiWindowFlags.NoMove | ImGuiWindowFlags.NoResize
                    | ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.NoSavedSettings;
            float leftW = vpX;
            float topH = vpH;
            float bottomY = vpY + vpH;
            float bottomH = Math.max(1f, lastDisplayH - bottomY);

            ImGui.pushStyleVar(imgui.flag.ImGuiStyleVar.WindowRounding, 0f);

            // Top-left: Script Editor shell
            ImGui.setNextWindowPos(0f, TOP_BAR_H);
            ImGui.setNextWindowSize(leftW, topH);
            if (ImGui.begin("Script Editor##DockedTopLeft", panelFlags)) {
                ImGui.textDisabled("Script lines: " + cfg.script.size());
                String cur = RunnerView.current();
                ImGui.textWrapped("Current step: " + (cur.isEmpty() ? "(none)" : cur));
            }
            ImGui.end();

            // Bottom-left: Chests / Areas / Capture shell
            ImGui.setNextWindowPos(0f, bottomY);
            ImGui.setNextWindowSize(leftW, bottomH);
            if (ImGui.begin("Chests / Areas / Capture##DockedBottomLeft", panelFlags)) {
                ImGui.textDisabled("Saved chests: " + cfg.chests.size() + " | Saved areas: " + cfg.areas.size());
            }
            ImGui.end();

            // Bottom-right: Log / Assistant shell
            ImGui.setNextWindowPos(vpX, bottomY);
            ImGui.setNextWindowSize(vpW, bottomH);
            if (ImGui.begin("Log / Assistant##DockedBottomRight", panelFlags)) {
                String ctrl = RunnerView.controller();
                ImGui.textDisabled("Control: " + (ctrl.isEmpty() ? "idle" : ctrl) + " | Actions done: " + RunnerView.actionsDone());
            }
            ImGui.end();

            ImGui.popStyleVar();

            // Draw 1px focus border around the Top-Right Game Viewport
            int borderCol = isGameFocused()
                    ? ImGui.colorConvertFloat4ToU32(0.290f, 0.620f, 0.878f, 1.0f)   // #4a9ee0 active focus
                    : ImGui.colorConvertFloat4ToU32(0.204f, 0.224f, 0.267f, 1.0f);  // #343944 unfocused
            ImGui.getForegroundDrawList().addRect(vpX, vpY, vpX + vpW, vpY + vpH, borderCol, 0f, 0, 1.5f);
        }

        private static void renderSpikeWindow() {
            ImGui.setNextWindowPos(24f, 24f, ImGuiCond.FirstUseEver);
            ImGui.setNextWindowSize(340f, 220f, ImGuiCond.FirstUseEver);
            if (focusRequested) {
                ImGui.setNextWindowFocus();
                focusRequested = false;
            }
            if (unfocusRequested) {
                ImGui.setWindowFocus(null);
            }

            openFlag.set(true);
            if (ImGui.begin("Manhattan - Runner (F9)", openFlag)) {
                winX = ImGui.getWindowPosX();
                winY = ImGui.getWindowPosY();
                winW = ImGui.getWindowSizeX();
                winH = ImGui.getWindowSizeY();

                boolean running = RunnerView.running();
                ImGui.text("Status:");
                ImGui.sameLine();
                if (running) {
                    ImGui.textColored(0.247f, 0.698f, 0.498f, 1.0f, "Running");   // #3fb27f
                } else {
                    ImGui.textDisabled("Not running");
                }

                ImGui.separator();
                ImGui.text("Line: " + RunnerView.line());
                ImGui.sameLine(140f);
                ImGui.text("Actions done: " + RunnerView.actionsDone());
                ImGui.text("Elapsed: " + (running ? RunnerView.elapsedText() : "-"));
                ImGui.sameLine(140f);
                String ctrl = RunnerView.controller();
                ImGui.text("Control: " + (ctrl.isEmpty() ? "idle" : ctrl));
                String cur = RunnerView.current();
                ImGui.textWrapped("Step: " + (cur.isEmpty() ? "(none)" : cur));

                ImGui.spacing();
                stopBtnX = ImGui.getCursorScreenPosX();
                stopBtnY = ImGui.getCursorScreenPosY();
                stopBtnW = 90f;
                stopBtnH = 24f;
                if (ImGui.button("Stop", stopBtnW, stopBtnH)) {
                    RunnerView.stop();
                }
                ImGui.sameLine();
                if (ImGui.button("Skip", 90f, 24f)) {
                    RunnerView.skip();
                }
                ImGui.sameLine();
                ImGui.textDisabled("ui.mode=" + cfg.uiMode);

                ImGui.spacing();
                ImGui.inputText("Note", note);

                if (unfocusRequested) {
                    unfocusRequested = false;
                    focused = false;
                } else {
                    focused = ImGui.isWindowFocused(ImGuiFocusedFlags.RootAndChildWindows) || ImGui.getIO().getWantCaptureKeyboard();
                }
            } else if (unfocusRequested) {
                unfocusRequested = false;
                focused = false;
            }
            ImGui.end();

            if (!openFlag.get()) {
                setSpikeOpen(false);
            }
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
