package dev.loadchest;

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
                    default -> {}
                }
            }
        });
    }
}
