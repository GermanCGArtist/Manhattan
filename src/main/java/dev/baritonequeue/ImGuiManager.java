package dev.baritonequeue;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.platform.Window;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.ImGuiStyle;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiConfigFlags;
import imgui.flag.ImGuiFocusedFlags;
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
import org.lwjgl.opengl.GL30;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static dev.baritonequeue.Config.cfg;

/**
 * Dear ImGui lifecycle, input routing and Stage 0 spike window (key F9).
 *
 * <p>Natives are extracted into {@code <gameDir>/config/manhattan-natives} before {@link ImGui} is loaded so multiple
 * game instances (or another ImGui mod such as Flashback) never share a temp DLL or {@code imgui.ini}.
 * Input is forwarded from Minecraft's {@code KeyboardHandler} and {@code MouseHandler} mixins (with
 * {@code installCallbacks = false}) so GLFW callbacks are never overwritten.
 */
public final class ImGuiManager {
    private static boolean nativesPrepared;
    private static boolean initialized;
    private static boolean initFailed;
    private static boolean spikeOpen;
    private static boolean focused;
    private static boolean focusRequested;
    private static boolean unfocusRequested;
    private static boolean stopRequested;
    private static long framesRendered;
    private static int lastDisplayW, lastDisplayH;
    private static float winX = 24, winY = 24, winW = 340, winH = 220;
    private static float stopBtnX, stopBtnY, stopBtnW, stopBtnH;
    private static double lastMouseX, lastMouseY;

    private ImGuiManager() {}

    /** Whether any ImGui UI should be drawn this frame. */
    public static boolean isActive() {
        return spikeOpen || "docked".equals(cfg.uiMode);
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static boolean isSpikeOpen() {
        return spikeOpen;
    }

    public static boolean isFocused() {
        return isActive() && (focused || (initialized && Backend.wantsKeyboard()));
    }

    public static long framesRendered() {
        return framesRendered;
    }

    public static int displayWidth() {
        return lastDisplayW;
    }

    public static int displayHeight() {
        return lastDisplayH;
    }

    public static float[] windowRect() {
        return new float[]{winX, winY, winW, winH};
    }

    public static float[] stopButtonRect() {
        return new float[]{stopBtnX, stopBtnY, stopBtnW, stopBtnH};
    }

    public static String noteText() {
        return initialized ? Backend.note.get() : "";
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
        if (!isActive() || initFailed) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        Window win = mc.getWindow();
        if (win == null || win.isMinimized() || win.getWidth() <= 0 || win.getHeight() <= 0) return;
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

    private static boolean insideWindow(double x, double y) {
        return spikeOpen && x >= winX && x <= winX + winW && y >= winY && y <= winY + winH;
    }

    public static boolean onKey(long window, int key, int scancode, int action, int mods) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null || window != mc.getWindow().handle()) return false;

        KeyEvent ev = new KeyEvent(key, scancode, mods);
        if (action == GLFW.GLFW_PRESS && ((QueueMod.spike != null && QueueMod.spike.matches(ev)) || key == GLFW.GLFW_KEY_F9)) {
            toggleSpike();
            return true;
        }
        if (!isActive() || !initialized) return false;

        if (action == GLFW.GLFW_PRESS && key == GLFW.GLFW_KEY_ESCAPE && (focused || Backend.wantsKeyboard())) {
            if (!Backend.wantsTextInput()) {
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

        Backend.onMouseButton(window, button, action, mods);
        boolean overImGui = Backend.wantsMouse() || insideWindow(lastMouseX, lastMouseY);
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
        return Backend.wantsMouse() || insideWindow(lastMouseX, lastMouseY);
    }

    // ------------------------------------------------------------------ Dear ImGui backend (loaded after prepareNatives)

    private static final class Backend {
        private static ImGuiContext ctx;
        private static ImGuiImplGlfw glfw;
        private static ImGuiImplGl3 gl3;
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

            if (spikeOpen || "docked".equals(cfg.uiMode)) {
                renderSpikeWindow();
            }

            ImGui.render();
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            gl3.renderDrawData(ImGui.getDrawData());

            framesRendered++;
            lastDisplayW = (int) io.getDisplaySizeX();
            lastDisplayH = (int) io.getDisplaySizeY();
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
