package dev.survivalkit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

import static dev.survivalkit.Config.cfg;

/**
 * The server dropped the connection (timeout, restart, kick): reconnect after a growing pause, wait until the world
 * is there again, then bring the task back (the queue's current step, or the last Baritone command).
 * Only the "Disconnected" screen starts this - leaving through the menu yourself does nothing. Gives up after 8 tries.
 */
final class Reconnect {
    private static final int[] DELAYS = {10, 20, 40, 60, 90, 120, 180, 300};   // seconds
    private static final int CONNECT_TIMEOUT = 20 * 40;                         // ticks to wait for a join
    private static final int SETTLE = 20 * 6;                                   // ticks in the world before resuming

    private ServerData last;
    private int attempts, countdown, connecting, settle;
    private boolean pending;   // a reconnect cycle is in progress

    /** Every client tick, also while no world is loaded. */
    void tick(Minecraft mc) {
        if (mc.player != null && mc.level != null) {
            if (mc.getCurrentServer() != null) last = mc.getCurrentServer();
            if (pending) {
                if (++settle >= SETTLE) {
                    pending = false;
                    attempts = 0;
                    connecting = 0;
                    Util.msg("Reconnected - carrying on.");
                    SurvivalKitMod.recovery.afterReconnect();
                }
            }
            return;
        }
        settle = 0;
        if (!cfg.reconnect || last == null) return;

        if (mc.screen instanceof DisconnectedScreen) {
            if (!pending) {
                pending = true;
                attempts = 0;
                SurvivalKitMod.recovery.onDisconnect();
                Session.count("disconnects");
                Session.archive();
                Session.onDisconnect();
                countdown = DELAYS[0] * 20;
                System.out.println("[SurvivalKit] Disconnected from " + last.ip + ", reconnecting in " + DELAYS[0] + " s.");
            } else if (connecting > 0) {   // the last try ended on the Disconnected screen again
                connecting = 0;
                attempts++;
                if (attempts >= DELAYS.length) { giveUp(); return; }
                countdown = DELAYS[attempts] * 20;
            }
            if (countdown > 0 && --countdown == 0) {
                connecting = 1;
                ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(last.ip), last, false, null);
            }
            return;
        }
        if (mc.screen instanceof ConnectScreen) {
            if (pending && ++connecting > CONNECT_TIMEOUT) connecting = 1;
            return;
        }
        if (pending) {   // the player went to the menu by hand: leave it to them
            giveUp();
        }
    }

    private void giveUp() {
        pending = false;
        attempts = 0;
        connecting = 0;
        SurvivalKitMod.recovery.cancel();
        System.out.println("[SurvivalKit] Reconnecting stopped.");
    }

    /** Seconds until the next try, or -1. */
    int secondsLeft() {
        return pending && countdown > 0 ? countdown / 20 : -1;
    }
}
