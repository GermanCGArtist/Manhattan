package dev.baritonequeue;

import java.util.function.Supplier;

import static dev.baritonequeue.Values.error;

/**
 * The state of one fiber while it runs the script: the line it is on, how deep in calls it is, and the bridge to the
 * game (await an action, wait a tick). One per fiber; all of them share the script's variables.
 */
final class Interp {
    private static final int SLICE = 4000;   // steps before the script lets the game run a tick

    private final Runner runner;
    final Fiber fiber;        // null: evaluating an "on" condition on the game thread (no waiting allowed)
    int depth, line;
    private int budget;
    volatile String activity;   // what a waiting statement is doing, for the progress display

    Interp(Runner runner, Fiber fiber) {
        this.runner = runner;
        this.fiber = fiber;
    }

    Runner runner() {
        return runner;
    }

    String lineText(int line) {
        return runner.lineText(line);
    }

    /** Called before every statement: keeps the line, and lets the game breathe in long loops. */
    void step(int line) {
        this.line = line;
        if (++budget >= SLICE) {
            budget = 0;
            if (fiber == null) throw error("This 'on' condition takes too long to work out.", line);
            yieldTick();
        }
    }

    /** A fresh budget (an "on" condition is worked out anew every half second). */
    void resetBudget() {
        budget = 0;
    }

    /** Lets one game tick pass. */
    void yieldTick() {
        if (fiber == null) throw error("An 'on' condition cannot wait.", line);
        budget = 0;
        fiber.yieldToGame();
    }

    /** Runs something that takes time in the world (a Baritone task, a chest trip ...) and waits for it. */
    Object await(Supplier<Actions.Action> factory, String text, int line) {
        Actions.Action a = awaitRaw(factory, text, line);
        if (a == null) return null;
        if (a.error != null) throw error(a.error, line);
        return a.result;
    }

    /** Like await, but hands back the finished action (null if it was skipped) so the caller can read a failure. */
    Actions.Action awaitRaw(Supplier<Actions.Action> factory, String text, int line) {
        if (fiber == null) throw error("An 'on' condition cannot start '" + text + "'. Put it inside the block.", line);
        fiber.actionText = text;
        fiber.actionLine = line;
        fiber.action = null;
        fiber.wanted = factory;
        fiber.yieldToGame();   // the game thread makes the action, runs it and wakes us when it is finished
        Actions.Action a = fiber.action;
        fiber.action = null;
        fiber.wanted = null;
        budget = 0;
        return a;
    }
}
