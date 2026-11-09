package dev.survivalkit;

import baritone.api.BaritoneAPI;
import baritone.api.event.events.ChatEvent;
import baritone.api.event.listener.AbstractGameEventListener;

/**
 * Follows Baritone's #pause / #resume: while Baritone is paused nothing here may act either (no eating, restocking,
 * chest trips, ...). Baritone does not expose its pause state, so this watches the commands you type.
 */
final class PauseWatch {
    private PauseWatch() {}

    static volatile boolean paused = false;
    private static boolean registered = false;

    private static final java.util.Set<String> TASKS = java.util.Set.of(
            "mine", "m", "build", "b", "farm", "follow", "explore", "goto", "tunnel", "thisway");
    private static final java.util.Set<String> SEL = java.util.Set.of("sel", "selection", "s");
    private static final java.util.Set<String> SEL_TASKS = java.util.Set.of("set", "fill", "s", "f", "walls", "w", "shell",
            "sphere", "hsphere", "cylinder", "hcylinder", "cleararea", "ca", "replace", "r", "paste");

    /** A command that starts a long-running task (and so is worth resuming after a death). */
    private static boolean isTask(String label, String sub) {
        return TASKS.contains(label) || (SEL.contains(label) && SEL_TASKS.contains(sub));
    }

    static void register(baritone.api.IBaritone baritone) {
        if (registered) return;
        registered = true;
        baritone.getGameEventHandler().registerEventListener(new AbstractGameEventListener() {
            @Override
            public void onSendChatMessage(ChatEvent event) {
                String prefix = BaritoneAPI.getSettings().prefix.value;
                String m = event.getMessage() == null ? "" : event.getMessage().trim();
                if (!m.startsWith(prefix)) return;
                String[] w = m.substring(prefix.length()).trim().toLowerCase().split("\s+");
                switch (w[0]) {
                    case "p", "pause", "paws" -> paused = w.length > 1 && w[1].equals("toggle") ? !paused : true;
                    case "r", "resume", "unpause", "unp" -> paused = false;
                    case "stop", "cancel" -> SurvivalKitMod.recovery.forget();
                    default -> {
                        String sub = w.length > 1 ? w[1] : "";
                        if (isTask(w[0], sub)) {
                            SurvivalKitMod.recovery.cancel();
                            SurvivalKitMod.recovery.remember(String.join(" ", w));
                        }
                    }
                }
            }
        });
    }
}
