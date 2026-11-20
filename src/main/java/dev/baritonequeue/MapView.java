package dev.baritonequeue;

import imgui.ImDrawList;
import imgui.ImGui;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.baritonequeue.UiTheme.*;

/**
 * Top-down map (x right, z down = north up) of what the script works with near the player: saved areas (the last
 * used one highlighted), the current #sel selection, chests and captured spots, positions named in the script
 * ({@code let name = pos(x, y, z)}), and the player with its heading. Fits itself around those within 192 blocks.
 */
final class MapView {
    private MapView() {}

    private static final Pattern LET_POS = Pattern.compile("let\\s+(\\w+)\\s*=\\s*pos\\(\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*\\)");
    private static final int RANGE = 192;

    private record Box(String name, int x1, int z1, int x2, int z2, int kind) {}   // kind 0 area, 1 last area, 2 selection
    private record Pt(String name, String kind, int x, int z) {}

    private static String cachedScript = null;
    private static List<Pt> scriptPts = List.of();

    static void draw(float x0, float y0, float w, float h) {
        ImDrawList dl = ImGui.getWindowDrawList();
        dl.addRectFilled(x0, y0, x0 + w, y0 + h, u32(SLOT), 6f);
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) {
            ImGui.dummy(w, h);
            return;
        }
        double px = mc.player.getX(), pz = mc.player.getZ();
        float yaw = mc.player.getYRot();

        List<Box> boxes = new ArrayList<>();
        String lastArea = null;
        for (AreasView.AreaEntry a : AreasView.list()) if (a.isLast()) lastArea = a.name();
        for (String name : Areas.savedNames()) {
            List<int[]> bs = Areas.getBoxes(name);
            if (bs == null) continue;
            for (int[] b : bs) addBox(boxes, name, b, name.equals(lastArea) ? 1 : 0, px, pz);
        }
        for (int[] b : Areas.read()) {   // the selection, unless it is just a saved area loaded again
            boolean same = false;
            for (Box o : boxes) {
                if (o.x1() == Math.min(b[0], b[3]) && o.x2() == Math.max(b[0], b[3]) && o.z1() == Math.min(b[2], b[5]) && o.z2() == Math.max(b[2], b[5])) same = true;
            }
            if (!same) addBox(boxes, "#sel", b, 2, px, pz);
        }

        List<Pt> pts = new ArrayList<>();
        for (PlacesView.SavedSpot s : PlacesView.spots()) {
            if (near(s.x(), s.z(), px, pz)) pts.add(new Pt(s.name(), s.kind(), s.x(), s.z()));
        }
        for (Pt p : scriptPoints()) if (near(p.x(), p.z(), px, pz)) pts.add(p);

        List<MapLayers.Region> layer = MapLayers.regions();

        // bounds: the job's chunks if there are any (that is what is being worked on), else everything shown;
        // always the player, at least 32 blocks, square
        double minX = px - 16, maxX = px + 16, minZ = pz - 16, maxZ = pz + 16;
        if (!layer.isEmpty()) {
            for (MapLayers.Region r : layer) { minX = Math.min(minX, r.x1()); maxX = Math.max(maxX, r.x2() + 1); minZ = Math.min(minZ, r.z1()); maxZ = Math.max(maxZ, r.z2() + 1); }
        } else {
            for (Box b : boxes) { minX = Math.min(minX, b.x1()); maxX = Math.max(maxX, b.x2() + 1); minZ = Math.min(minZ, b.z1()); maxZ = Math.max(maxZ, b.z2() + 1); }
            for (Pt p : pts) { minX = Math.min(minX, p.x()); maxX = Math.max(maxX, p.x() + 1); minZ = Math.min(minZ, p.z()); maxZ = Math.max(maxZ, p.z() + 1); }
        }
        double span = Math.max(maxX - minX, maxZ - minZ) * 1.12 + 4;
        double cx = (minX + maxX) / 2, cz = (minZ + maxZ) / 2;
        float scale = (float) (Math.min(w, h) / span);
        float ox = x0 + w / 2f, oz = y0 + h / 2f;

        dl.pushClipRect(x0, y0, x0 + w, y0 + h, true);
        // grid every 8 or 16 blocks
        int step = span > 160 ? 32 : span > 80 ? 16 : 8;
        int lineCol = u32(LINE, 0.7f);
        for (long gx = (long) Math.floor((cx - span) / step) * step; gx <= cx + span; gx += step) {
            float sx = ox + (float) (gx - cx) * scale;
            dl.addLine(sx, y0, sx, y0 + h, lineCol);
        }
        for (long gz = (long) Math.floor((cz - span) / step) * step; gz <= cz + span; gz += step) {
            float sz = oz + (float) (gz - cz) * scale;
            dl.addLine(x0, sz, x0 + w, sz, lineCol);
        }

        String hover = null;
        float mx = ImGui.getMousePosX(), my = ImGui.getMousePosY();
        for (Box b : boxes) {
            float bx1 = ox + (float) (b.x1() - cx) * scale, bz1 = oz + (float) (b.z1() - cz) * scale;
            float bx2 = ox + (float) (b.x2() + 1 - cx) * scale, bz2 = oz + (float) (b.z2() + 1 - cz) * scale;
            int stroke = b.kind() == 1 ? ACCENT : b.kind() == 2 ? WARN : DIM;
            dl.addRectFilled(bx1, bz1, bx2, bz2, u32(stroke, b.kind() == 0 ? 0.06f : 0.12f));
            dl.addRect(bx1, bz1, bx2, bz2, u32(stroke, 0.9f), 0f, 0, b.kind() == 0 ? 1f : 1.6f);
            if (bx2 - bx1 > 30) {
                UiTheme.push(UiTheme.small);
                dl.addText(bx1 + 3, bz1 + 2, u32(stroke), b.name());
                ImGui.popFont();
            }
            if (mx >= bx1 && mx <= bx2 && my >= bz1 && my <= bz2) {
                hover = "area " + b.name() + "  " + b.x1() + " " + b.z1() + " to " + b.x2() + " " + b.z2();
            }
        }
        // the job's chunks: grey = to do, accent = being worked on (outlined), green = done, red = failed
        for (MapLayers.Region r : layer) {
            float rx1 = ox + (float) (r.x1() - cx) * scale, rz1 = oz + (float) (r.z1() - cz) * scale;
            float rx2 = ox + (float) (r.x2() + 1 - cx) * scale, rz2 = oz + (float) (r.z2() + 1 - cz) * scale;
            int c = switch (r.state()) {
                case DONE -> OK;
                case ACTIVE -> ACCENT;
                case FAILED -> ERR;
                case SKIPPED -> DIM;
                default -> GUTTER;
            };
            float fill = switch (r.state()) {
                case DONE -> 0.30f;
                case ACTIVE -> 0.40f;
                case FAILED -> 0.35f;
                default -> 0.08f;
            };
            dl.addRectFilled(rx1 + 0.5f, rz1 + 0.5f, rx2 - 0.5f, rz2 - 0.5f, u32(c, fill));
            dl.addRect(rx1, rz1, rx2, rz2, u32(c, r.state() == MapLayers.State.ACTIVE ? 1f : 0.55f), 0f, 0,
                    r.state() == MapLayers.State.ACTIVE ? 2f : 1f);
            if (!r.label().isEmpty() && rx2 - rx1 > ImGui.calcTextSizeX(r.label()) + 6) {
                UiTheme.push(UiTheme.small);
                float tw = ImGui.calcTextSizeX(r.label());
                dl.addText((rx1 + rx2 - tw) / 2f, (rz1 + rz2 - ImGui.getFontSize()) / 2f,
                        u32(r.state() == MapLayers.State.PENDING ? DIM : FG), r.label());
                ImGui.popFont();
            }
            if (mx >= rx1 && mx <= rx2 && my >= rz1 && my <= rz2) {
                hover = "chunk " + r.label() + " (" + r.state().name().toLowerCase() + ")  " + r.x1() + " " + r.z1()
                        + " to " + r.x2() + " " + r.z2() + (r.detail().isEmpty() ? "" : "\n" + r.detail());
            }
        }
        for (Pt p : pts) {
            float sx = ox + (float) (p.x() + 0.5 - cx) * scale, sz = oz + (float) (p.z() + 0.5 - cz) * scale;
            int c = p.kind().contains("bed") ? ERR : p.kind().equals("pos") ? FG : 0xc8925a;
            float r = Math.max(3f, Math.min(6f, scale * 0.6f));
            dl.addRectFilled(sx - r, sz - r, sx + r, sz + r, u32(c));
            dl.addRect(sx - r, sz - r, sx + r, sz + r, u32(0x000000, 0.7f));
            if (Math.abs(mx - sx) <= r + 2 && Math.abs(my - sz) <= r + 2) {
                hover = p.name() + " (" + p.kind() + ")  " + p.x() + " " + p.z();
            }
        }
        // telemetry hotspots: stuck / edge-blacklist / death markers from RuntimeBus
        for (var hs : dev.midtown.api.RuntimeBus.telemetrySnapshot().hotspots()) {
            if (!near(hs.x(), hs.z(), px, pz)) continue;
            float hx = ox + (float) (hs.x() + 0.5 - cx) * scale, hz = oz + (float) (hs.z() + 0.5 - cz) * scale;
            boolean isDeath = hs.type() == dev.midtown.api.RuntimeEvent.Type.DEATH;
            int col = isDeath ? ERR : WARN;
            float r = Math.min(10f, 4f + hs.count());
            dl.addCircleFilled(hx, hz, r, u32(col, 0.25f));
            dl.addCircle(hx, hz, r, u32(col, 0.9f), 12, 1.5f);
            if (isDeath) {
                dl.addLine(hx - 4f, hz - 4f, hx + 4f, hz + 4f, u32(ERR), 1.8f);
                dl.addLine(hx + 4f, hz - 4f, hx - 4f, hz + 4f, u32(ERR), 1.8f);
            }
            if (Math.hypot(mx - hx, my - hz) <= r + 3f) {
                hover = hs.type().name().toLowerCase() + " ×" + hs.count()
                        + "  (" + hs.x() + ", " + hs.y() + ", " + hs.z() + ")"
                        + (hs.lastMessage().isEmpty() ? "" : "\n" + hs.lastMessage());
            }
        }
        // player arrow: yaw 0 = south (+z), 90 = west (-x)
        float sx = ox + (float) (px - cx) * scale, sz = oz + (float) (pz - cz) * scale;
        double a = Math.toRadians(yaw);
        float fx = (float) -Math.sin(a), fz = (float) Math.cos(a);
        float s = 8f;
        dl.addCircleFilled(sx, sz, 11f, u32(ACCENT, 0.25f));
        dl.addTriangleFilled(sx + fx * s, sz + fz * s,
                sx - fx * s * 0.6f + fz * s * 0.7f, sz - fz * s * 0.6f - fx * s * 0.7f,
                sx - fx * s * 0.6f - fz * s * 0.7f, sz - fz * s * 0.6f + fx * s * 0.7f, u32(FG));
        UiTheme.push(UiTheme.small);
        dl.addText(x0 + 6, y0 + h - ImGui.getFontSize() - 4, u32(DIM), "N ↑   " + step + " blocks / grid");
        ImGui.popFont();
        dl.popClipRect();

        ImGui.invisibleButton("##map", w, h);
        if (hover != null && ImGui.isItemHovered()) ImGui.setTooltip(hover);
    }

    private static void addBox(List<Box> out, String name, int[] b, int kind, double px, double pz) {
        if (b == null || b.length < 6) return;
        int x1 = Math.min(b[0], b[3]), x2 = Math.max(b[0], b[3]), z1 = Math.min(b[2], b[5]), z2 = Math.max(b[2], b[5]);
        if (x2 < px - RANGE || x1 > px + RANGE || z2 < pz - RANGE || z1 > pz + RANGE) return;
        out.add(new Box(name, x1, z1, x2, z2, kind));
    }

    private static boolean near(int x, int z, double px, double pz) {
        return Math.abs(x - px) <= RANGE && Math.abs(z - pz) <= RANGE;
    }

    private static List<Pt> scriptPoints() {
        String src = String.join("\n", QueueMod.runner.scriptLines());   // the running script, else the editor's
        if (!src.equals(cachedScript)) {
            cachedScript = src;
            List<Pt> out = new ArrayList<>();
            Matcher m = LET_POS.matcher(src);
            while (m.find()) {
                String name = m.group(1);
                String kind = name.contains("bed") ? "bed" : name.contains("chest") ? "chest" : "pos";
                out.add(new Pt(name, kind, Integer.parseInt(m.group(2)), Integer.parseInt(m.group(4))));
            }
            scriptPts = out;
        }
        return scriptPts;
    }
}
