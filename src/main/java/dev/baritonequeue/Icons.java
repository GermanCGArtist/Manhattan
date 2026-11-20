package dev.baritonequeue;

import imgui.ImDrawList;
import imgui.ImVec2;

/** Line icons for the activity bar, drawn with the draw list (no icon font needed). {@code r} is half the icon size. */
final class Icons {
    private Icons() {}

    static void draw(ImDrawList dl, String name, float cx, float cy, float r, int col) {
        float t = Math.max(1.4f, r / 6f);
        float x0 = cx - r, y0 = cy - r, x1 = cx + r, y1 = cy + r;
        switch (name) {
            case "EDITOR" -> {   // document with folded corner
                poly(dl, col, t, x0 + r * 0.25f, y0, x0 + r * 1.15f, y0, x1 - r * 0.25f, y0 + r * 0.6f, x1 - r * 0.25f, y1, x0 + r * 0.25f, y1, x0 + r * 0.25f, y0);
                dl.addLine(x0 + r * 0.7f, cy, x1 - r * 0.7f, cy, col, t);
                dl.addLine(x0 + r * 0.7f, cy + r * 0.45f, x1 - r * 0.9f, cy + r * 0.45f, col, t);
            }
            case "MONITOR" -> {   // gauge
                arc(dl, cx, cy + r * 0.35f, r, col, t);
                dl.addLine(cx, cy + r * 0.35f, cx + r * 0.5f, cy - r * 0.4f, col, t);
                dl.addCircleFilled(cx, cy + r * 0.35f, t * 1.2f, col);
            }
            case "PROJECTS" -> {  // stacked checklist / task queue
                float[] ys = {y0 + r * 0.32f, cy, y1 - r * 0.32f};
                for (float ry : ys) {
                    dl.addRectFilled(x0, ry - t, x0 + t * 2.2f, ry + t, col, 1f);
                    dl.addLine(x0 + r * 0.55f, ry, x1, ry, col, t);
                }
            }
            case "PRESETS" -> poly(dl, col, t, x0 + r * 0.35f, y0, x1 - r * 0.35f, y0, x1 - r * 0.35f, y1, cx, cy + r * 0.4f, x0 + r * 0.35f, y1, x0 + r * 0.35f, y0);
            case "CHESTS" -> {
                dl.addRect(x0, cy - r * 0.2f, x1, y1, col, 1f, 0, t);
                poly(dl, col, t, x0, cy - r * 0.2f, x0 + r * 0.25f, y0 + r * 0.1f, x1 - r * 0.25f, y0 + r * 0.1f, x1, cy - r * 0.2f);
                dl.addLine(cx - r * 0.25f, cy + r * 0.3f, cx + r * 0.25f, cy + r * 0.3f, col, t);
            }
            case "AREAS" -> {   // dashed square
                float d = r * 0.5f;
                for (float p = x0; p < x1; p += d * 2) {
                    dl.addLine(p, y0, Math.min(x1, p + d), y0, col, t);
                    dl.addLine(p, y1, Math.min(x1, p + d), y1, col, t);
                }
                for (float p = y0; p < y1; p += d * 2) {
                    dl.addLine(x0, p, x0, Math.min(y1, p + d), col, t);
                    dl.addLine(x1, p, x1, Math.min(y1, p + d), col, t);
                }
            }
            case "CAPTURE" -> {   // crosshair
                dl.addCircle(cx, cy, r * 0.65f, col, 0, t);
                dl.addLine(cx, y0 - r * 0.1f, cx, cy - r * 0.35f, col, t);
                dl.addLine(cx, cy + r * 0.35f, cx, y1 + r * 0.1f, col, t);
                dl.addLine(x0 - r * 0.1f, cy, cx - r * 0.35f, cy, col, t);
                dl.addLine(cx + r * 0.35f, cy, x1 + r * 0.1f, cy, col, t);
            }
            case "LOG" -> {
                dl.addLine(x0, y0 + r * 0.3f, x1, y0 + r * 0.3f, col, t);
                dl.addLine(x0, cy, x1, cy, col, t);
                dl.addLine(x0, y1 - r * 0.3f, cx + r * 0.2f, y1 - r * 0.3f, col, t);
            }
            default -> dl.addCircle(cx, cy, r * 0.6f, col, 0, t);
        }
    }

    private static void poly(ImDrawList dl, int col, float t, float... xy) {
        ImVec2[] pts = new ImVec2[xy.length / 2];
        for (int i = 0; i < pts.length; i++) pts[i] = new ImVec2(xy[i * 2], xy[i * 2 + 1]);
        dl.addPolyline(pts, pts.length, col, 0, t);
    }

    private static void arc(ImDrawList dl, float cx, float cy, float r, int col, float t) {
        int n = 14;
        ImVec2[] pts = new ImVec2[n + 1];
        for (int i = 0; i <= n; i++) {
            double a = Math.PI + Math.PI * i / n;
            pts[i] = new ImVec2(cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r);
        }
        dl.addPolyline(pts, pts.length, col, 0, t);
    }
}
