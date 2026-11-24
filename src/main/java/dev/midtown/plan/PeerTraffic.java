package dev.midtown.plan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where the other bots (and players) are, and where they are about to walk.
 *
 * <p>Two sources feed it: players the client sees in its own world ({@link #observe}, refreshed every few
 * ticks by {@code MidtownEngine}), and path intents relayed by the fleet server ({@link #intent}: the next
 * steps of another bot's current path). Planners read an immutable {@link PeerField} taken when a plan starts,
 * so the planner thread never touches this store.
 *
 * <p>What it is used for:
 * <ul>
 *   <li>cells a peer's body is in cost a lot to path through, cells on its intended path cost a little more,
 *       so bots route around each other instead of walking into each other in 1-wide tunnels;</li>
 *   <li>the block a peer stands on and the cells its body fills may not be broken or built into;</li>
 *   <li>the executor asks {@link #peerAt} for the next step and yields when another bot stands there.</li>
 * </ul>
 */
public final class PeerTraffic {
    /** A peer seen in the world is forgotten this long after it was last seen. */
    public static final long SIGHTING_TTL_MS = 1_500L;
    /** A relayed path intent is used for this long. */
    public static final long INTENT_TTL_MS = 4_000L;
    /** Extra cost (ticks) to path through a cell another bot's body is in. */
    public static final double OCCUPIED_PENALTY = 120.0;
    /** Extra cost (ticks) to path through a cell on another bot's intended path. */
    public static final double INTENT_PENALTY = 12.0;
    public static final int MAX_INTENT_POINTS = 16;

    private static final PeerTraffic INSTANCE = new PeerTraffic();

    public static PeerTraffic global() {
        return INSTANCE;
    }

    private static final class Peer {
        final String uuid;
        String name = "";
        String dimension = "";
        double x;
        double y;
        double z;
        long seenMs;
        int[] intent = new int[0]; // x,y,z triplets
        long intentMs;

        Peer(String uuid) {
            this.uuid = uuid;
        }
    }

    private final Map<String, Peer> peers = new LinkedHashMap<>();

    /** A peer at a position (from the client world, or from a relayed path intent). */
    public synchronized void observe(String uuid, String name, String dimension, double x, double y, double z, long nowMs) {
        if (uuid == null) return;
        Peer p = peers.computeIfAbsent(uuid, Peer::new);
        p.name = name == null ? "" : name;
        p.dimension = dimension == null ? "" : dimension;
        p.x = x;
        p.y = y;
        p.z = z;
        p.seenMs = nowMs;
    }

    /** The next steps of a peer's path ({@code points} = {x, y, z} block positions, nearest first). */
    public synchronized void intent(String uuid, String name, String dimension, double x, double y, double z,
                                    List<int[]> points, long nowMs) {
        if (uuid == null) return;
        observe(uuid, name, dimension, x, y, z, nowMs);
        Peer p = peers.get(uuid);
        int n = points == null ? 0 : Math.min(points.size(), MAX_INTENT_POINTS);
        int[] packed = new int[n * 3];
        int k = 0;
        for (int i = 0; i < n; i++) {
            int[] pt = points.get(i);
            if (pt == null || pt.length < 3) continue;
            packed[k++] = pt[0];
            packed[k++] = pt[1];
            packed[k++] = pt[2];
        }
        p.intent = k == packed.length ? packed : java.util.Arrays.copyOf(packed, k);
        p.intentMs = nowMs;
    }

    public synchronized void forget(String uuid) {
        peers.remove(uuid);
    }

    public synchronized void clear() {
        peers.clear();
    }

    /** Peers seen recently in {@code dimension}, except {@code selfUuid} (for tests and the monitor). */
    public synchronized int activePeers(String selfUuid, String dimension, long nowMs) {
        prune(nowMs);
        int n = 0;
        for (Peer p : peers.values()) {
            if (!p.uuid.equals(selfUuid) && p.dimension.equals(dimension) && nowMs - p.seenMs <= SIGHTING_TTL_MS) n++;
        }
        return n;
    }

    /** The uuid of a peer whose body is in block cell {@code (x, y, z)}, or null. */
    public synchronized String peerAt(String selfUuid, String dimension, int x, int y, int z, long nowMs) {
        for (Peer p : peers.values()) {
            if (p.uuid.equals(selfUuid) || !p.dimension.equals(dimension)) continue;
            if (nowMs - p.seenMs > SIGHTING_TTL_MS) continue;
            if (bodyCovers(p.x, p.y, p.z, x, y, z)) return p.uuid;
        }
        return null;
    }

    /**
     * True when breaking block {@code (x, y, z)} now would drop a peer seen in the world (it stands on it)
     * or dig into its body. Checked right before a block is broken, because peers move after a plan is made.
     */
    public synchronized boolean protectsBreak(String selfUuid, String dimension, int x, int y, int z, long nowMs) {
        for (Peer p : peers.values()) {
            if (p.uuid.equals(selfUuid) || !p.dimension.equals(dimension)) continue;
            if (nowMs - p.seenMs > SIGHTING_TTL_MS) continue;
            if (bodyCovers(p.x, p.y, p.z, x, y, z)) return true;
            if (bodyCovers(p.x, p.y, p.z, x, y + 1, z) && y + 1 == (int) Math.floor(p.y)) return true; // under its feet
        }
        return false;
    }

    /** Convenience for game code: uses the local player and level. */
    public static boolean protectsBreakHere(net.minecraft.client.Minecraft mc, net.minecraft.core.BlockPos pos) {
        if (mc == null || mc.player == null || mc.level == null || pos == null) return false;
        return INSTANCE.protectsBreak(mc.player.getUUID().toString(), mc.level.dimension().identifier().toString(),
                pos.getX(), pos.getY(), pos.getZ(), System.currentTimeMillis());
    }

    /** An immutable picture for one plan. */
    public synchronized PeerField field(String selfUuid, String dimension, long nowMs) {
        prune(nowMs);
        Set<Long> occupied = new HashSet<>();
        Set<Long> support = new HashSet<>();
        Set<Long> intents = new HashSet<>();
        for (Peer p : peers.values()) {
            if (p.uuid.equals(selfUuid) || !p.dimension.equals(dimension)) continue;
            if (nowMs - p.seenMs <= SIGHTING_TTL_MS) {
                addBody(p.x, p.y, p.z, occupied, support);
            }
            if (nowMs - p.intentMs <= INTENT_TTL_MS) {
                for (int i = 0; i + 2 < p.intent.length; i += 3) {
                    intents.add(pack(p.intent[i], p.intent[i + 1], p.intent[i + 2]));
                    intents.add(pack(p.intent[i], p.intent[i + 1] + 1, p.intent[i + 2]));
                }
            }
        }
        if (occupied.isEmpty() && intents.isEmpty()) return PeerField.EMPTY;
        return new PeerField(occupied, support, intents);
    }

    private void prune(long nowMs) {
        peers.values().removeIf(p -> nowMs - p.seenMs > Math.max(SIGHTING_TTL_MS, INTENT_TTL_MS) * 4);
    }

    /** Player hitbox: 0.6 wide, 1.8 tall. */
    static boolean bodyCovers(double px, double py, double pz, int x, int y, int z) {
        return x >= (int) Math.floor(px - 0.3) && x <= (int) Math.floor(px + 0.3)
                && z >= (int) Math.floor(pz - 0.3) && z <= (int) Math.floor(pz + 0.3)
                && y >= (int) Math.floor(py) && y <= (int) Math.floor(py + 1.79);
    }

    private static void addBody(double px, double py, double pz, Set<Long> occupied, Set<Long> support) {
        int x0 = (int) Math.floor(px - 0.3), x1 = (int) Math.floor(px + 0.3);
        int z0 = (int) Math.floor(pz - 0.3), z1 = (int) Math.floor(pz + 0.3);
        int y0 = (int) Math.floor(py), y1 = (int) Math.floor(py + 1.79);
        int below = (int) Math.floor(py - 0.001);
        if (below == y0) below = y0 - 1;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) occupied.add(pack(x, y, z));
                support.add(pack(x, below, z));
                support.add(pack(x, y0 - 1, z));
            }
        }
    }

    static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }

    /** What one plan knows about the other bots. */
    public static final class PeerField {
        public static final PeerField EMPTY = new PeerField(Set.of(), Set.of(), Set.of());

        private final Set<Long> occupied;
        private final Set<Long> support;
        private final Set<Long> intents;

        PeerField(Set<Long> occupied, Set<Long> support, Set<Long> intents) {
            this.occupied = Collections.unmodifiableSet(occupied);
            this.support = Collections.unmodifiableSet(support);
            this.intents = Collections.unmodifiableSet(intents);
        }

        /** For tests: a field with peers standing at the given feet cells. */
        public static PeerField ofFeet(List<int[]> feet, List<int[]> intentCells) {
            Set<Long> occ = new HashSet<>();
            Set<Long> sup = new HashSet<>();
            Set<Long> in = new HashSet<>();
            for (int[] f : feet) addBody(f[0] + 0.5, f[1], f[2] + 0.5, occ, sup);
            if (intentCells != null) {
                for (int[] c : intentCells) {
                    in.add(pack(c[0], c[1], c[2]));
                    in.add(pack(c[0], c[1] + 1, c[2]));
                }
            }
            return new PeerField(occ, sup, in);
        }

        public boolean isEmpty() {
            return occupied.isEmpty() && intents.isEmpty();
        }

        /** Extra path cost for standing with feet in {@code (x, y, z)} (head in y + 1). */
        public double penaltyAt(int x, int y, int z) {
            if (isEmpty()) return 0.0;
            if (occupied.contains(pack(x, y, z)) || occupied.contains(pack(x, y + 1, z))) return OCCUPIED_PENALTY;
            if (intents.contains(pack(x, y, z))) return INTENT_PENALTY;
            return 0.0;
        }

        /** True when breaking this block would drop a peer or dig into its body. */
        public boolean protectsBreak(int x, int y, int z) {
            long k = pack(x, y, z);
            return support.contains(k) || occupied.contains(k);
        }

        /** True when a block placed here would go into a peer's body. */
        public boolean protectsPlace(int x, int y, int z) {
            return occupied.contains(pack(x, y, z));
        }

        public int occupiedCells() {
            return occupied.size();
        }

        public List<Long> occupiedForDebug() {
            return new ArrayList<>(occupied);
        }
    }
}
