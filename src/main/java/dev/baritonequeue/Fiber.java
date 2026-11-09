package dev.baritonequeue;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * One running piece of script (the main program, or an "on" block that fired). It has its own thread, but only ever
 * runs while the game thread waits for it (resume() hands over, yieldToGame() hands back), so the script and the game
 * never touch the world at the same time.
 */
final class Fiber {
    /** Thrown inside the script thread when the script is stopped; try/catch in the script cannot catch it. */
    static final class Killed extends Error {
        Killed() {
            super(null, null, false, false);
        }
    }

    /** stop() in the script: ends the whole script normally. */
    static final class Exit extends Error {
        Exit() {
            super(null, null, false, false);
        }
    }

    private final Semaphore toScript = new Semaphore(0), toGame = new Semaphore(0);
    private final Thread thread;
    volatile boolean done, killed, exited;
    volatile Throwable failure;

    // an action the script waits for: the factory is set by the script, the game thread makes and runs the action
    volatile Supplier<Actions.Action> wanted;
    volatile Actions.Action action;
    volatile String actionText;
    volatile int actionLine;

    Fiber(String name, Runnable body) {
        thread = new Thread(() -> {
            toScript.acquireUninterruptibly();
            try {
                if (!killed) body.run();
            } catch (Exit e) {
                exited = true;
            } catch (Killed k) {
                // stopped
            } catch (Throwable t) {
                failure = t;
            } finally {
                done = true;
                toGame.release();
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
    }

    /** Game thread: lets the script run until it waits for something or ends. False if it did not hand back in time. */
    boolean resume() {
        if (done) return true;
        toScript.release();
        try {
            if (toGame.tryAcquire(15, TimeUnit.SECONDS)) return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // a builtin that never returns: give up on this fiber rather than freeze the game
        killed = true;
        thread.interrupt();
        return false;
    }

    /** Script thread: hands control back to the game until the next resume(). */
    void yieldToGame() {
        toGame.release();
        toScript.acquireUninterruptibly();
        if (killed) throw new Killed();
    }

    /** Ends the fiber: it unwinds at its next step. */
    void kill() {
        if (done) return;
        killed = true;
        resume();
    }
}
