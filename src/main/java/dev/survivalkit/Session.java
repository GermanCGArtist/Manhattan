package dev.survivalkit;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What happened since the session began: counters, a report in chat, and a line in config/survivalkit-sessions.log. */
final class Session {
    private Session() {}

    private static final Map<String, Long> counts = new LinkedHashMap<>();
    private static long start = System.currentTimeMillis();
    private static double walked;
    private static double px, pz;
    private static boolean havePos;
    private static String lastFinished = "";

    static void count(String what) {
        counts.merge(what, 1L, Long::sum);
    }

    static void add(String what, long n) {
        counts.merge(what, n, Long::sum);
    }

    /** Every tick: distance walked, and a report when the queue says it finished. */
    static void tick(Minecraft mc, LocalPlayer p) {
        if (havePos) {
            double d = Math.hypot(p.getX() - px, p.getZ() - pz);
            if (d < 8) walked += d;   // larger jumps are teleports / respawns
        }
        px = p.getX();
        pz = p.getZ();
        havePos = true;

        String fin = System.getProperty("baritonequeue.finished");
        if (fin != null && !fin.equals(lastFinished)) {
            lastFinished = fin;
            Util.msg("The queue finished (" + fin.substring(0, fin.indexOf('|')) + "). Session so far:");
            print();
        }
    }

    static void onDisconnect() {
        havePos = false;
    }

    private static String duration(long ms) {
        long s = ms / 1000;
        return (s / 3600) + " h " + ((s / 60) % 60) + " min";
    }

    static List<String> lines() {
        List<String> out = new ArrayList<>();
        out.add("time " + duration(System.currentTimeMillis() - start) + ", walked " + Math.round(walked) + " blocks");
        if (counts.isEmpty()) {
            out.add("nothing counted yet");
        } else {
            StringBuilder sb = new StringBuilder();
            counts.forEach((k, v) -> sb.append(sb.length() == 0 ? "" : ", ").append(v).append(" ").append(k));
            out.add(sb.toString());
        }
        return out;
    }

    static void print() {
        Util.msg("Session report:");
        for (String l : lines()) Util.msg("  " + l);
    }

    /** Appends the report to the log file and starts counting afresh. */
    static void archive() {
        if (counts.isEmpty() && walked < 1) return;
        try {
            var path = FabricLoader.getInstance().getConfigDir().resolve("survivalkit-sessions.log");
            String text = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + "  " + String.join("; ", lines()) + "\n";
            Files.writeString(path, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            e.printStackTrace();
        }
        reset();
    }

    static void reset() {
        counts.clear();
        walked = 0;
        start = System.currentTimeMillis();
    }
}
