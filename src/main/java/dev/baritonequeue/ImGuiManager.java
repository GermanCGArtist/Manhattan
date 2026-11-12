package dev.baritonequeue;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.ImGuiStyle;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiConfigFlags;
import imgui.flag.ImGuiDir;
import imgui.flag.ImGuiDockNodeFlags;
import imgui.flag.ImGuiFocusedFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;
import imgui.internal.ImGuiContext;
import imgui.internal.ImGuiDockNode;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
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
import java.util.List;

import static dev.baritonequeue.Config.cfg;

/**
 * Dear ImGui lifecycle, input routing, Stage 0 spike window (key F9), Stage 1 Game Viewport (top-right quarter
 * with mouse remapping, keyboard focus gating, and F8 fullscreen game toggle), and Stage 2 Docked Shell & Dashboard
 * (ImGui DockSpace with default 2x2 DockBuilder layout, Reset layout, Top Bar with status/Skip/Stop/F8, and
 * ProgressModel segmented step bar + player telemetry).
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
    private static boolean resetLayoutRequested;
    private static int layoutResetCount;
    private static long framesRendered;
    private static int lastDisplayW, lastDisplayH;
    private static float winX = 24, winY = 24, winW = 340, winH = 220;
    private static float stopBtnX, stopBtnY, stopBtnW, stopBtnH;
    private static float fsBtnX, fsBtnY, fsBtnW, fsBtnH;
    private static float resetBtnX, resetBtnY, resetBtnW, resetBtnH;
    private static float vpX, vpY, vpW, vpH;
    private static boolean customDockedViewport;
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

    /** Requests a reset of the ImGui DockSpace back to the default 2x2 layout and saves it to config/manhattan-imgui.ini. */
    public static void resetLayout() {
        resetLayoutRequested = true;
        customDockedViewport = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.getWindow() != null) {
            updateViewportMetrics(mc.getWindow());
        }
    }

    public static int layoutResetCount() {
        return layoutResetCount;
    }

    public static String layoutLabel() {
        return "2x2 Docked";
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

    public static float[] resetButtonRect() {
        return new float[]{resetBtnX, resetBtnY, resetBtnW, resetBtnH};
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
        boolean sizeChanged = sw != lastDisplayW || sh != lastDisplayH;
        lastDisplayW = sw;
        lastDisplayH = sh;
        if (isDockedViewportActive()) {
            if (!customDockedViewport || sizeChanged || vpW <= 0f || vpH <= 0f) {
                float contentY = TOP_BAR_H;
                float contentH = Math.max(2f, sh - contentY);
                float halfW = (float) Math.floor(sw * 0.5f);
                float halfH = (float) Math.floor(contentH * 0.5f);
                vpX = halfW;
                vpY = contentY;
                vpW = Math.max(1f, sw - halfW);
                vpH = Math.max(1f, halfH);
                customDockedViewport = false;
            }
        } else {
            vpX = 0f;
            vpY = 0f;
            vpW = sw;
            vpH = sh;
            customDockedViewport = false;
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
        private static boolean dockLayoutInitialized;
        private static int dockBuiltW = -1, dockBuiltH = -1;
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
            s.setColor(ImGuiCol.TabUnfocused, 0.137f, 0.149f, 0.180f, 1.00f);
            s.setColor(ImGuiCol.TabUnfocusedActive, 0.173f, 0.188f, 0.227f, 1.00f);
            s.setColor(ImGuiCol.DockingEmptyBg, 0.0f, 0.0f, 0.0f, 0.0f);
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

            ProgressModel.Snapshot snap = ProgressModel.snapshot();
            boolean dockedActive = isDockedViewportActive();
            if (dockedActive) {
                renderTopBar(snap);
                renderDockedShell(mc, snap);
            }
            if (spikeOpen) {
                renderSpikeWindow(snap);
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

        private static void renderTopBar(ProgressModel.Snapshot snap) {
            int flags = ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoMove
                    | ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse
                    | ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.NoSavedSettings;
            ImGui.setNextWindowPos(0f, 0f);
            ImGui.setNextWindowSize(lastDisplayW, TOP_BAR_H);
            ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 0f);
            ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 8f, 4f);
            if (ImGui.begin("##ManhattanTopBar", flags)) {
                ImGui.text("Manhattan");
                ImGui.sameLine(0f, 10f);
                if (snap.running()) {
                    ImGui.textColored(0.247f, 0.698f, 0.498f, 1.0f, "Running");
                    ImGui.sameLine(0f, 8f);
                    ImGui.text("Step " + snap.currentStep() + "/" + snap.totalSteps() + " (" + snap.overallPercent() + "%)");
                    ImGui.sameLine(0f, 8f);
                    ImGui.textDisabled(snap.elapsedText());
                } else {
                    ImGui.textDisabled("Idle (" + snap.totalSteps() + " steps)");
                }

                float rightButtonsW = 440f;
                float startX = Math.max(ImGui.getCursorPosX() + 10f, lastDisplayW - rightButtonsW);
                ImGui.sameLine(startX);
                ImGui.textDisabled("Layout: " + layoutLabel());
                ImGui.sameLine(0f, 6f);
                resetBtnX = ImGui.getCursorScreenPosX();
                resetBtnY = ImGui.getCursorScreenPosY();
                resetBtnW = 86f;
                resetBtnH = 22f;
                if (ImGui.button("Reset layout", resetBtnW, resetBtnH)) {
                    resetLayout();
                }
                ImGui.sameLine(0f, 6f);
                if (ImGui.button("Skip", 46f, 22f)) {
                    RunnerView.skip();
                }
                ImGui.sameLine(0f, 6f);
                if (!spikeOpen) {
                    stopBtnX = ImGui.getCursorScreenPosX();
                    stopBtnY = ImGui.getCursorScreenPosY();
                    stopBtnW = 46f;
                    stopBtnH = 22f;
                }
                if (ImGui.button("Stop", 46f, 22f)) {
                    RunnerView.stop();
                }
                ImGui.sameLine(0f, 6f);
                fsBtnX = ImGui.getCursorScreenPosX();
                fsBtnY = ImGui.getCursorScreenPosY();
                fsBtnW = 136f;
                fsBtnH = 22f;
                if (ImGui.button("Fullscreen game (F8)", fsBtnW, fsBtnH)) {
                    toggleFullscreenGame();
                }
            }
            ImGui.end();
            ImGui.popStyleVar(2);
        }

        private static void buildDefault2x2Layout(int dockspaceId) {
            float contentH = Math.max(2f, lastDisplayH - TOP_BAR_H);
            imgui.internal.ImGui.dockBuilderRemoveNode(dockspaceId);
            imgui.internal.ImGui.dockBuilderAddNode(dockspaceId, imgui.internal.flag.ImGuiDockNodeFlags.DockSpace);
            imgui.internal.ImGui.dockBuilderSetNodePos(dockspaceId, 0f, TOP_BAR_H);
            imgui.internal.ImGui.dockBuilderSetNodeSize(dockspaceId, lastDisplayW, contentH);

            ImInt leftId = new ImInt();
            ImInt rightId = new ImInt();
            imgui.internal.ImGui.dockBuilderSplitNode(dockspaceId, ImGuiDir.Left, 0.5f, leftId, rightId);

            ImInt topLeftId = new ImInt();
            ImInt bottomLeftId = new ImInt();
            imgui.internal.ImGui.dockBuilderSplitNode(leftId.get(), ImGuiDir.Up, 0.5f, topLeftId, bottomLeftId);

            ImInt topRightId = new ImInt();
            ImInt bottomRightId = new ImInt();
            imgui.internal.ImGui.dockBuilderSplitNode(rightId.get(), ImGuiDir.Up, 0.5f, topRightId, bottomRightId);

            ImGuiDockNode trNode = imgui.internal.ImGui.dockBuilderGetNode(topRightId.get());
            if (trNode != null && trNode.ptr != 0) {
                trNode.setLocalFlags(trNode.getLocalFlags()
                        | imgui.internal.flag.ImGuiDockNodeFlags.NoTabBar
                        | imgui.internal.flag.ImGuiDockNodeFlags.NoWindowMenuButton);
            }

            imgui.internal.ImGui.dockBuilderDockWindow("Script Editor", topLeftId.get());
            imgui.internal.ImGui.dockBuilderDockWindow("Presets", topLeftId.get());
            imgui.internal.ImGui.dockBuilderDockWindow("Game Viewport", topRightId.get());
            imgui.internal.ImGui.dockBuilderDockWindow("Chests", bottomLeftId.get());
            imgui.internal.ImGui.dockBuilderDockWindow("Areas", bottomLeftId.get());
            imgui.internal.ImGui.dockBuilderDockWindow("Capture", bottomLeftId.get());
            imgui.internal.ImGui.dockBuilderDockWindow("Log", bottomRightId.get());
            imgui.internal.ImGui.dockBuilderDockWindow("Assistant", bottomRightId.get());
            imgui.internal.ImGui.dockBuilderFinish(dockspaceId);

            String ini = ImGui.getIO().getIniFilename();
            if (ini != null && !ini.isEmpty()) {
                ImGui.saveIniSettingsToDisk(ini);
            }
        }

        private static void renderDockedShell(Minecraft mc, ProgressModel.Snapshot snap) {
            float contentH = Math.max(2f, lastDisplayH - TOP_BAR_H);
            int hostFlags = ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoCollapse
                    | ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoMove
                    | ImGuiWindowFlags.NoBringToFrontOnFocus | ImGuiWindowFlags.NoNavFocus
                    | ImGuiWindowFlags.NoBackground | ImGuiWindowFlags.NoSavedSettings;

            ImGui.setNextWindowPos(0f, TOP_BAR_H);
            ImGui.setNextWindowSize(lastDisplayW, contentH);
            ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 0f);
            ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 0f);
            ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f);
            ImGui.pushStyleColor(ImGuiCol.WindowBg, 0f, 0f, 0f, 0f);

            ImGui.begin("##ManhattanDockSpaceHost", hostFlags);
            ImGui.popStyleColor();
            ImGui.popStyleVar(3);

            int dockspaceId = ImGui.getID("ManhattanDockSpace");
            ImGuiDockNode rootNode = imgui.internal.ImGui.dockBuilderGetNode(dockspaceId);
            boolean resizedDefault = !customDockedViewport && (dockBuiltW != lastDisplayW || dockBuiltH != lastDisplayH);
            boolean needBuild = !dockLayoutInitialized || resetLayoutRequested || resizedDefault
                    || rootNode == null || rootNode.ptr == 0 || !rootNode.isSplitNode();
            if (needBuild) {
                buildDefault2x2Layout(dockspaceId);
                dockLayoutInitialized = true;
                dockBuiltW = lastDisplayW;
                dockBuiltH = lastDisplayH;
                customDockedViewport = false;
                if (resetLayoutRequested) {
                    resetLayoutRequested = false;
                    layoutResetCount++;
                }
            }

            ImGui.dockSpace(dockspaceId, 0f, 0f, ImGuiDockNodeFlags.PassthruCentralNode | imgui.internal.flag.ImGuiDockNodeFlags.NoWindowMenuButton);
            ImGui.end();

            // Top-Right: Game Viewport dock pane (transparent so FBO 0 blit shows through cleanly)
            ImGui.pushStyleColor(ImGuiCol.WindowBg, 0f, 0f, 0f, 0f);
            int vpFlags = ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoBackground
                    | ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse | ImGuiWindowFlags.NoCollapse;
            if (ImGui.begin("Game Viewport", vpFlags)) {
                float gx = ImGui.getWindowPosX();
                float gy = ImGui.getWindowPosY();
                float gw = ImGui.getWindowSizeX();
                float gh = ImGui.getWindowSizeY();
                float defX = (float) Math.floor(lastDisplayW * 0.5f);
                float defY = TOP_BAR_H;
                float defW = Math.max(1f, lastDisplayW - defX);
                float defH = Math.max(1f, (float) Math.floor(contentH * 0.5f));
                if (needBuild) {
                    vpX = defX;
                    vpY = defY;
                    vpW = defW;
                    vpH = defH;
                    customDockedViewport = false;
                } else if (gw > 10f && gh > 10f) {
                    if (Math.abs(gx - defX) <= 6f && Math.abs(gy - defY) <= 6f
                            && Math.abs(gw - defW) <= 6f && Math.abs(gh - defH) <= 6f) {
                        vpX = defX;
                        vpY = defY;
                        vpW = defW;
                        vpH = defH;
                        customDockedViewport = false;
                    } else {
                        vpX = gx;
                        vpY = gy;
                        vpW = gw;
                        vpH = gh;
                        customDockedViewport = true;
                    }
                }
            }
            ImGui.end();
            ImGui.popStyleColor();

            // Top-Left: Script Editor + Dashboard telemetry & segmented step bar
            if (ImGui.begin("Script Editor")) {
                renderDashboardSection(snap);
                ImGui.separator();
                DockedPanels.renderScriptEditorPanel(snap);
            }
            ImGui.end();

            if (ImGui.begin("Presets")) {
                DockedPanels.renderPresetsPanel();
            }
            ImGui.end();

            // Bottom-Left: Chests, Areas, Capture tabs
            if (ImGui.begin("Chests")) {
                DockedPanels.renderChestsPanel();
            }
            ImGui.end();

            if (ImGui.begin("Areas")) {
                DockedPanels.renderAreasPanel();
            }
            ImGui.end();

            if (ImGui.begin("Capture")) {
                DockedPanels.renderCapturePanel();
            }
            ImGui.end();

            // Bottom-Right: Log, Assistant tabs
            if (ImGui.begin("Log")) {
                DockedPanels.renderLogPanel();
            }
            ImGui.end();

            if (ImGui.begin("Assistant")) {
                DockedPanels.renderAssistantPanel();
            }
            ImGui.end();

            // Draw 1px focus border around the Top-Right Game Viewport
            int borderCol = isGameFocused()
                    ? ImGui.colorConvertFloat4ToU32(0.290f, 0.620f, 0.878f, 1.0f)   // #4a9ee0 active focus
                    : ImGui.colorConvertFloat4ToU32(0.204f, 0.224f, 0.267f, 1.0f);  // #343944 unfocused
            ImGui.getForegroundDrawList().addRect(vpX, vpY, vpX + vpW, vpY + vpH, borderCol, 0f, 0, 1.5f);
        }

        private static void renderDashboardSection(ProgressModel.Snapshot snap) {
            // Segmented step bar: green (#3fb27f) done, blue (#4a9ee0) running with partial fill, grey (#2c303a) pending
            float availW = Math.max(80f, ImGui.getContentRegionAvailX());
            float barX = ImGui.getCursorScreenPosX();
            float barY = ImGui.getCursorScreenPosY();
            float barH = 10f;
            ImDrawList dl = ImGui.getWindowDrawList();

            int colPending = ImGui.colorConvertFloat4ToU32(0.173f, 0.188f, 0.227f, 1.0f);  // #2c303a
            int colBorder = ImGui.colorConvertFloat4ToU32(0.204f, 0.224f, 0.267f, 1.0f);   // #343944
            int colDone = ImGui.colorConvertFloat4ToU32(0.247f, 0.698f, 0.498f, 1.0f);     // #3fb27f
            int colRunning = ImGui.colorConvertFloat4ToU32(0.290f, 0.620f, 0.878f, 1.0f);  // #4a9ee0

            List<ProgressModel.Segment> segs = snap.segments();
            if (segs.isEmpty()) {
                dl.addRectFilled(barX, barY, barX + availW, barY + barH, colPending, 2f);
                dl.addRect(barX, barY, barX + availW, barY + barH, colBorder, 2f);
            } else {
                int n = segs.size();
                float gap = n > 1 ? 2f : 0f;
                float segW = Math.max(2f, (availW - gap * (n - 1)) / n);
                for (int i = 0; i < n; i++) {
                    float sx0 = barX + i * (segW + gap);
                    float sx1 = (i == n - 1) ? (barX + availW) : (sx0 + segW);
                    dl.addRectFilled(sx0, barY, sx1, barY + barH, colPending, 1f);
                    ProgressModel.Segment seg = segs.get(i);
                    if (seg.state() == ProgressModel.State.DONE) {
                        dl.addRectFilled(sx0, barY, sx1, barY + barH, colDone, 1f);
                    } else if (seg.state() == ProgressModel.State.RUNNING) {
                        float fillW = Math.max(2f, (float) ((sx1 - sx0) * seg.fraction()));
                        dl.addRectFilled(sx0, barY, Math.min(sx1, sx0 + fillW), barY + barH, colRunning, 1f);
                    }
                    dl.addRect(sx0, barY, sx1, barY + barH, colBorder, 1f);
                }
            }
            ImGui.dummy(availW, barH + 2f);

            String ctrl = snap.controller().isEmpty() ? "idle" : snap.controller();
            ImGui.text("Control: " + ctrl + "  |  Elapsed: " + (snap.running() ? snap.elapsedText() : "-")
                    + "  |  " + snap.stepTailText());
            ImGui.text("HP: " + String.format(java.util.Locale.ROOT, "%.0f/%.0f", snap.health(), snap.maxHealth())
                    + "  |  Food: " + snap.food() + "/20"
                    + "  |  Pos: " + snap.posText());
            if (!snap.stallWarning().isEmpty()) {
                ImGui.textColored(1.0f, 0.627f, 0.251f, 1.0f, snap.stallWarning());
            }
        }

        private static void renderSpikeWindow(ProgressModel.Snapshot snap) {
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

                boolean running = snap.running();
                ImGui.text("Status:");
                ImGui.sameLine();
                if (running) {
                    ImGui.textColored(0.247f, 0.698f, 0.498f, 1.0f, "Running");   // #3fb27f
                } else {
                    ImGui.textDisabled("Not running");
                }

                ImGui.separator();
                ImGui.text("Line: " + snap.line());
                ImGui.sameLine(140f);
                ImGui.text("Actions done: " + snap.actionsDone());
                ImGui.text("Elapsed: " + (running ? snap.elapsedText() : "-"));
                ImGui.sameLine(140f);
                String ctrl = snap.controller();
                ImGui.text("Control: " + (ctrl.isEmpty() ? "idle" : ctrl));
                String cur = snap.currentCommand();
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
