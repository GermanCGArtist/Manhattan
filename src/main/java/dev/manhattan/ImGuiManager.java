package dev.manhattan;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import dev.liskov.ui.host.InputMap;
import dev.liskov.ui.host.LiskovUiHost;
import dev.liskov.ui.host.UiLayer;
import dev.liskov.ui.theme.MubTheme;
import imgui.ImGui;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;

import java.nio.file.Path;

/**
 * Facade bridging Manhattan's workspace ({@link Shell}) and HUD ({@link Overlay}) onto
 * Liskov's shared {@link LiskovUiHost} (single Dear ImGui context, input router, and RenderPearl quad).
 */
public final class ImGuiManager {
    private static boolean layersRegistered;
    private static boolean gameFocused = true;
    private static boolean focused;
    private static boolean unfocusRequested;
    private static boolean stopRequested;
    private static long framesRendered;
    private static int lastDisplayW, lastDisplayH;
    private static float stopBtnX, stopBtnY, stopBtnW, stopBtnH;
    private static float vpX, vpY, vpW, vpH;
    private static double lastMouseX, lastMouseY;
    private static volatile String shotDone = "";

    private ImGuiManager() {}

    /** Registers Manhattan's {@link Shell} and {@link Overlay} layers with {@link LiskovUiHost}. */
    public static synchronized void registerLayers() {
        if (layersRegistered) return;
        layersRegistered = true;

        LiskovUiHost.register(new UiLayer() {
            @Override
            public String id() {
                return "manhattan.shell";
            }

            @Override
            public int order() {
                return 30;
            }

            @Override
            public boolean visible() {
                return Shell.isOpen();
            }

            @Override
            public boolean blocksMouseGrab() {
                return Shell.isOpen();
            }

            @Override
            public boolean wantsKeyboard() {
                return isFocused();
            }

            @Override
            public boolean wantsMouse() {
                return Shell.isOpen();
            }

            @Override
            public boolean wantsKey(int key, int scancode, int action, int mods) {
                if (!Shell.isOpen()) return false;
                if (isFocused()) return true;
                if (action == InputMap.GLFW_PRESS) {
                    int sdlMods = toSdlMods(mods);
                    if (QueueMod.open != null && QueueMod.open.matches(new KeyEvent(scancode, 0, sdlMods))) return true;
                    return scancode == InputConstants.KEY_ESCAPE;
                }
                return false;
            }

            @Override
            public void onKey(int key, int scancode, int action, int mods) {
                if (!Shell.isOpen()) return;
                int sdlMods = toSdlMods(mods);
                boolean textActive = !unfocusRequested && LiskovUiHost.imguiWantsTextInput();
                boolean uiHasKeys = !gameFocused || focused || textActive;
                if (action == InputMap.GLFW_PRESS) {
                    boolean ctrl = (mods & InputMap.GLFW_MOD_CONTROL) != 0
                            || (Platform.isMac() && (mods & InputMap.GLFW_MOD_SUPER) != 0);
                    if (ctrl && scancode == InputConstants.KEY_P) {
                        CommandPalette.toggle();
                        setGameFocused(false);
                        return;
                    }
                    KeyEvent ev = new KeyEvent(scancode, 0, sdlMods);
                    if (uiHasKeys && !textActive && QueueMod.open != null && QueueMod.open.matches(ev)) {
                        closeShell();
                        return;
                    }
                    if (uiHasKeys && !textActive && QueueMod.capture != null && QueueMod.capture.matches(ev)) {
                        Shell.toggleDrawer(Shell.Drawer.CAPTURE);
                        return;
                    }
                }
                if (uiHasKeys) {
                    if ((action == InputMap.GLFW_PRESS || action == InputMap.GLFW_REPEAT)
                            && DockedPanels.handleEditorKey(scancode, sdlMods)) {
                        return;
                    }
                    if (action == InputMap.GLFW_PRESS && scancode == InputConstants.KEY_ESCAPE && !textActive) {
                        if (CommandPalette.isOpen()) {
                            CommandPalette.close();
                        } else if (Shell.drawer != Shell.Drawer.NONE) {
                            Shell.drawer = Shell.Drawer.NONE;
                        } else {
                            gameFocused = true;
                            focused = false;
                            unfocusRequested = true;
                        }
                    }
                }
            }

            @Override
            public void onMouseMove(double x, double y) {
                lastMouseX = x;
                lastMouseY = y;
            }

            @Override
            public void onMouseButton(int button, int action, int mods) {
                if (!Shell.isOpen()) return;
                if (action == InputMap.GLFW_PRESS && button == 0
                        && DockedPanels.handlePopupClick(lastMouseX, lastMouseY)) {
                    return;
                }
                boolean overPopup = CommandPalette.isOpen()
                        || (Shell.drawer != Shell.Drawer.NONE && LiskovUiHost.imguiWantsMouse());
                boolean inGame = insideViewport(lastMouseX, lastMouseY) && !overPopup;
                if (inGame) {
                    if (action == InputMap.GLFW_PRESS) {
                        gameFocused = true;
                        focused = false;
                        unfocusRequested = true;
                    }
                    return;
                }
                if (action == InputMap.GLFW_PRESS) {
                    gameFocused = false;
                    focused = true;
                    unfocusRequested = false;
                    KeyMapping.releaseAll();
                }
            }

            @Override
            public void onResourcesReload() {
                ItemIcons.clear();
            }

            @Override
            public float[] dockedGameViewport() {
                return Shell.isOpen() ? fittedViewportRect() : null;
            }

            @Override
            public void draw() {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null && mc.getWindow() != null) {
                    updateViewportMetrics(mc.getWindow());
                    if (mc.mouseHandler != null && mc.mouseHandler.isMouseGrabbed()) {
                        mc.mouseHandler.releaseMouse();
                    }
                }
                UiTheme.syncFromMubTheme();
                if (unfocusRequested) {
                    ImGui.setWindowFocus(null);
                    unfocusRequested = false;
                    focused = false;
                }
                if (stopRequested) {
                    stopRequested = false;
                    RunnerView.stop();
                }
                Shell.render(ProgressModel.snapshot(), Math.max(1f, lastDisplayW), Math.max(1f, lastDisplayH));
                framesRendered++;
            }
        });

        LiskovUiHost.register(new UiLayer() {
            @Override
            public String id() {
                return "manhattan.overlay";
            }

            @Override
            public int order() {
                return 15;
            }

            @Override
            public boolean visible() {
                return !Shell.isOpen() && Overlay.visible();
            }

            @Override
            public boolean wantsKeyboard() {
                return false;
            }

            @Override
            public boolean wantsMouse() {
                return false;
            }

            @Override
            public void draw() {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null && mc.getWindow() != null) {
                    updateViewportMetrics(mc.getWindow());
                }
                UiTheme.syncFromMubTheme();
                Overlay.draw(ProgressModel.snapshot(), Math.max(1f, lastDisplayW), Math.max(1f, lastDisplayH));
                framesRendered++;
            }
        });
    }

    private static int toSdlMods(int glfwMods) {
        int sdl = 0;
        if ((glfwMods & InputMap.GLFW_MOD_SHIFT) != 0) sdl |= InputConstants.MOD_SHIFT;
        if ((glfwMods & InputMap.GLFW_MOD_CONTROL) != 0) sdl |= InputConstants.MOD_CONTROL;
        if ((glfwMods & InputMap.GLFW_MOD_ALT) != 0) sdl |= InputConstants.MOD_ALT;
        if ((glfwMods & InputMap.GLFW_MOD_SUPER) != 0) sdl |= InputConstants.MOD_SUPER;
        return sdl;
    }

    /** Screenshot hook kept for TestBridge compatibility. */
    public static void requestScreenshot(Path p) {
        shotDone = "unsupported on RenderPearl";
    }

    public static String lastShot() {
        return shotDone;
    }

    public static boolean isActive() {
        return Shell.isOpen() || Overlay.visible();
    }

    public static boolean isDockedViewportActive() {
        return Shell.isOpen();
    }

    public static boolean capturesInput() {
        return Shell.isOpen();
    }

    // ------------------------------------------------------------------ workspace open / close

    public static void toggleShell() {
        if (Shell.isOpen()) closeShell();
        else openShell(Shell.lastMode());
    }

    public static void openShell(Shell.Mode mode) {
        registerLayers();
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

    public static boolean isGameFocused() {
        if (Shell.isOpen()) {
            return gameFocused && !focused && (unfocusRequested || !(isInitialized() && LiskovUiHost.imguiWantsTextInput()));
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
            return !gameFocused || focused || (!unfocusRequested && isInitialized() && LiskovUiHost.imguiWantsTextInput());
        }
        return false;
    }

    public static void pushMonoFont() {
        if (isInitialized()) MubTheme.push(MubTheme.mono());
    }

    public static void popMonoFont() {
        if (isInitialized()) ImGui.popFont();
    }

    public static float getUiScale() {
        float s = LiskovUiHost.scale();
        return s > 0.5f ? s : 1.0f;
    }

    public static void setUiScale(float scale) {
        float clean = scale > 0.5f ? scale : 1.0f;
        LiskovUiHost.setScale(clean);
        Config.cfg.uiScale = clean;
        Config.save();
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.getWindow() != null) updateViewportMetrics(mc.getWindow());
    }

    public static boolean isInitialized() {
        return LiskovUiHost.isInitialized();
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
        return LiskovUiHost.remapMouseX(rawX);
    }

    public static double remapMouseY(double rawY) {
        return LiskovUiHost.remapMouseY(rawY);
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
}
