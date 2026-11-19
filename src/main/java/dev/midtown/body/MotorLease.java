package dev.midtown.body;

/**
 * Ownership lease for client body/motor inputs (movement keys, camera rotation, breaking, placing).
 * <p>
 * Ensures only one controller drives the player at a time, and that switching owners invokes a
 * cleanup hook to release held movement keys and active block breaking cleanly.
 * Pure Java with zero Minecraft client dependencies so it can be unit-tested headlessly.
 */
public final class MotorLease {

    private volatile String owner;
    private volatile int priority;
    private volatile long acquiredAtMillis;

    /**
     * Attempts to acquire the lease for {@code newOwner} at default priority {@code 0}.
     *
     * @param newOwner non-blank owner identifier (e.g. {@code "midtown"}, {@code "defence"}, {@code "eat"})
     * @return {@code true} if {@code newOwner} now owns the lease
     */
    public synchronized boolean acquire(String newOwner) {
        return acquire(newOwner, 0, null);
    }

    /**
     * Attempts to acquire the lease for {@code newOwner} at default priority {@code 0}, running
     * {@code onSwitchRelease} if a different previous owner was evicted.
     */
    public synchronized boolean acquire(String newOwner, Runnable onSwitchRelease) {
        return acquire(newOwner, 0, onSwitchRelease);
    }

    /**
     * Attempts to acquire the lease for {@code newOwner} with the given {@code priority}.
     * Higher numeric priority takes precedence over lower priority; equal priority allows
     * handoff between peers.
     */
    public synchronized boolean acquire(String newOwner, int priority) {
        return acquire(newOwner, priority, null);
    }

    /**
     * Attempts to acquire the lease for {@code newOwner} with {@code priority}.
     * If another owner currently holds a strictly higher priority, acquisition is denied and
     * returns {@code false}. Otherwise, if a different owner held the lease, {@code onSwitchRelease}
     * is invoked before transferring ownership.
     */
    public synchronized boolean acquire(String newOwner, int priority, Runnable onSwitchRelease) {
        validateOwner(newOwner);
        if (newOwner.equals(this.owner)) {
            this.priority = priority;
            this.acquiredAtMillis = System.currentTimeMillis();
            return true;
        }
        if (this.owner != null && priority < this.priority) {
            return false;
        }
        if (this.owner != null && onSwitchRelease != null) {
            onSwitchRelease.run();
        }
        this.owner = newOwner;
        this.priority = priority;
        this.acquiredAtMillis = System.currentTimeMillis();
        return true;
    }

    /**
     * Unconditionally takes ownership regardless of the previous owner's priority, invoking
     * {@code onSwitchRelease} if a different owner was previously active.
     */
    public synchronized void forceAcquire(String newOwner, int priority, Runnable onSwitchRelease) {
        validateOwner(newOwner);
        if (this.owner != null && !newOwner.equals(this.owner) && onSwitchRelease != null) {
            onSwitchRelease.run();
        }
        this.owner = newOwner;
        this.priority = priority;
        this.acquiredAtMillis = System.currentTimeMillis();
    }

    /**
     * Releases the lease only if {@code expectedOwner} currently holds it, invoking {@code onRelease}
     * when released.
     *
     * @return {@code true} if {@code expectedOwner} held the lease and was released
     */
    public synchronized boolean releaseIfOwner(String expectedOwner, Runnable onRelease) {
        if (expectedOwner == null || !expectedOwner.equals(this.owner)) {
            return false;
        }
        this.owner = null;
        this.priority = 0;
        this.acquiredAtMillis = 0L;
        if (onRelease != null) {
            onRelease.run();
        }
        return true;
    }

    /**
     * Unconditionally releases the lease regardless of current owner and invokes {@code onRelease}.
     */
    public synchronized void releaseAll(Runnable onRelease) {
        this.owner = null;
        this.priority = 0;
        this.acquiredAtMillis = 0L;
        if (onRelease != null) {
            onRelease.run();
        }
    }

    public String currentOwner() {
        return owner;
    }

    public int currentPriority() {
        return priority;
    }

    public long acquiredAtMillis() {
        return acquiredAtMillis;
    }

    public boolean isOwned() {
        return owner != null;
    }

    public boolean isOwnedBy(String candidate) {
        return candidate != null && candidate.equals(owner);
    }

    private static void validateOwner(String newOwner) {
        if (newOwner == null || newOwner.isBlank()) {
            throw new IllegalArgumentException("owner must be non-blank");
        }
    }
}
