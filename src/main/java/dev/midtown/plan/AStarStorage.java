package dev.midtown.plan;

import net.minecraft.core.BlockPos;

import java.util.Arrays;

/**
 * Primitive open-addressed node/cost/parent/edge hash storage and binary min-heap for 3D A*.
 * <p>
 * Avoids per-node object allocations during search by storing parallel primitive arrays indexed by
 * integer node ID, backed by an open-addressed {@code long -> int} position lookup table
 * ({@link PosToNodeMap}) and an intrusive indexed binary min-heap ({@link NodeMinHeap}).
 */
public final class AStarStorage {

    public static final double HEURISTIC_WEIGHT = 1.1;

    long[] packedPos;
    double[] gScore;
    double[] hScore;
    double[] fScore;
    int[] parentId;
    byte[] edgeType;
    byte[] edgeFlag;
    long[] aux1;
    long[] aux2;
    long[] aux3;
    double[] edgeCost;
    int[] placedCount;
    boolean[] closed;
    int size;

    final PosToNodeMap posMap;
    final NodeMinHeap heap;

    public AStarStorage(int initialCap) {
        int cap = Math.max(16, initialCap);
        this.packedPos = new long[cap];
        this.gScore = new double[cap];
        this.hScore = new double[cap];
        this.fScore = new double[cap];
        this.parentId = new int[cap];
        this.edgeType = new byte[cap];
        this.edgeFlag = new byte[cap];
        this.aux1 = new long[cap];
        this.aux2 = new long[cap];
        this.aux3 = new long[cap];
        this.edgeCost = new double[cap];
        this.placedCount = new int[cap];
        this.closed = new boolean[cap];
        this.posMap = new PosToNodeMap(cap * 2);
        this.heap = new NodeMinHeap(cap, this);
    }

    /** Appends a new search node, registers it in {@link #posMap}, and returns its node ID. */
    public int addNode(
            int x, int y, int z,
            double g, double h,
            int parent,
            byte type, byte flag,
            long a1, long a2, long a3,
            double eCost,
            int placed) {
        int id = size++;
        if (id >= packedPos.length) {
            grow();
        }
        long packed = BlockPos.asLong(x, y, z);
        packedPos[id] = packed;
        gScore[id] = g;
        hScore[id] = h;
        fScore[id] = g + HEURISTIC_WEIGHT * h;
        parentId[id] = parent;
        edgeType[id] = type;
        edgeFlag[id] = flag;
        aux1[id] = a1;
        aux2[id] = a2;
        aux3[id] = a3;
        edgeCost[id] = eCost;
        placedCount[id] = placed;
        closed[id] = false;
        posMap.put(packed, id);
        return id;
    }

    /** Relaxes an existing open node with a better path from {@code parent} and updates the heap. */
    public void updateNode(
            int id,
            double g,
            int parent,
            byte type, byte flag,
            long a1, long a2, long a3,
            double eCost,
            int placed) {
        gScore[id] = g;
        fScore[id] = g + HEURISTIC_WEIGHT * hScore[id];
        parentId[id] = parent;
        edgeType[id] = type;
        edgeFlag[id] = flag;
        aux1[id] = a1;
        aux2[id] = a2;
        aux3[id] = a3;
        edgeCost[id] = eCost;
        placedCount[id] = placed;
        heap.decreaseKey(id);
    }

    public int size() {
        return size;
    }

    public boolean isHeapEmpty() {
        return heap.isEmpty();
    }

    public void push(int nodeId) {
        heap.push(nodeId);
    }

    public int popMin() {
        return heap.popMin();
    }

    public int getNodeId(long packed) {
        return posMap.get(packed);
    }

    public long packedPos(int id) {
        return packedPos[id];
    }

    public double gScore(int id) {
        return gScore[id];
    }

    public double hScore(int id) {
        return hScore[id];
    }

    public double fScore(int id) {
        return fScore[id];
    }

    public int parentId(int id) {
        return parentId[id];
    }

    public byte edgeType(int id) {
        return edgeType[id];
    }

    public byte edgeFlag(int id) {
        return edgeFlag[id];
    }

    public long aux1(int id) {
        return aux1[id];
    }

    public long aux2(int id) {
        return aux2[id];
    }

    public long aux3(int id) {
        return aux3[id];
    }

    public double edgeCost(int id) {
        return edgeCost[id];
    }

    public int placedCount(int id) {
        return placedCount[id];
    }

    public boolean isClosed(int id) {
        return closed[id];
    }

    public void markClosed(int id) {
        closed[id] = true;
    }

    private void grow() {
        int n = packedPos.length << 1;
        packedPos = Arrays.copyOf(packedPos, n);
        gScore = Arrays.copyOf(gScore, n);
        hScore = Arrays.copyOf(hScore, n);
        fScore = Arrays.copyOf(fScore, n);
        parentId = Arrays.copyOf(parentId, n);
        edgeType = Arrays.copyOf(edgeType, n);
        edgeFlag = Arrays.copyOf(edgeFlag, n);
        aux1 = Arrays.copyOf(aux1, n);
        aux2 = Arrays.copyOf(aux2, n);
        aux3 = Arrays.copyOf(aux3, n);
        edgeCost = Arrays.copyOf(edgeCost, n);
        placedCount = Arrays.copyOf(placedCount, n);
        closed = Arrays.copyOf(closed, n);
        heap.ensureNodeCapacity(n);
    }

    /** Primitive open-addressed {@code long -> int} hash table mapping packed {@link BlockPos} keys to node IDs. */
    public static final class PosToNodeMap {
        private long[] keys;
        private int[] values;
        private boolean[] used;
        private int mask;
        private int size;
        private int threshold;

        public PosToNodeMap(int initialCap) {
            int cap = Integer.highestOneBit(Math.max(16, initialCap) - 1) << 1;
            keys = new long[cap];
            values = new int[cap];
            used = new boolean[cap];
            mask = cap - 1;
            threshold = (int) (cap * 0.7f);
        }

        public int get(long key) {
            int idx = mix(key) & mask;
            while (used[idx]) {
                if (keys[idx] == key) return values[idx];
                idx = (idx + 1) & mask;
            }
            return -1;
        }

        public void put(long key, int val) {
            if (size >= threshold) rehash();
            int idx = mix(key) & mask;
            while (used[idx]) {
                if (keys[idx] == key) {
                    values[idx] = val;
                    return;
                }
                idx = (idx + 1) & mask;
            }
            keys[idx] = key;
            values[idx] = val;
            used[idx] = true;
            size++;
        }

        public int size() {
            return size;
        }

        private void rehash() {
            long[] ok = keys;
            int[] ov = values;
            boolean[] ou = used;
            int cap = ok.length << 1;
            keys = new long[cap];
            values = new int[cap];
            used = new boolean[cap];
            mask = cap - 1;
            threshold = (int) (cap * 0.7f);
            for (int i = 0; i < ok.length; i++) {
                if (!ou[i]) continue;
                long k = ok[i];
                int idx = mix(k) & mask;
                while (used[idx]) {
                    idx = (idx + 1) & mask;
                }
                keys[idx] = k;
                values[idx] = ov[i];
                used[idx] = true;
            }
        }

        private static int mix(long x) {
            x ^= (x >>> 33);
            x *= 0xff51afd7ed558ccdL;
            x ^= (x >>> 33);
            return (int) x;
        }
    }

    /** Indexed binary min-heap ordered by {@code fScore} with {@code hScore} tie-breaking. */
    public static final class NodeMinHeap {
        private final AStarStorage state;
        private int[] heap;
        private int[] heapPos;
        private int size;

        NodeMinHeap(int cap, AStarStorage state) {
            this.state = state;
            this.heap = new int[cap];
            this.heapPos = new int[cap];
            Arrays.fill(this.heapPos, -1);
        }

        void ensureNodeCapacity(int n) {
            if (n > heapPos.length) {
                int old = heapPos.length;
                heapPos = Arrays.copyOf(heapPos, n);
                Arrays.fill(heapPos, old, n, -1);
            }
        }

        public boolean isEmpty() {
            return size == 0;
        }

        public int size() {
            return size;
        }

        public void push(int nodeId) {
            if (size >= heap.length) {
                heap = Arrays.copyOf(heap, heap.length << 1);
            }
            int idx = size++;
            heap[idx] = nodeId;
            heapPos[nodeId] = idx;
            siftUp(idx);
        }

        public int popMin() {
            int min = heap[0];
            heapPos[min] = -1;
            size--;
            if (size > 0) {
                int last = heap[size];
                heap[0] = last;
                heapPos[last] = 0;
                siftDown(0);
            }
            return min;
        }

        public void decreaseKey(int nodeId) {
            int idx = heapPos[nodeId];
            if (idx >= 0) {
                siftUp(idx);
            } else {
                push(nodeId);
            }
        }

        private void siftUp(int idx) {
            int node = heap[idx];
            while (idx > 0) {
                int parentIdx = (idx - 1) >>> 1;
                int parentNode = heap[parentIdx];
                if (!less(node, parentNode)) break;
                heap[idx] = parentNode;
                heapPos[parentNode] = idx;
                idx = parentIdx;
            }
            heap[idx] = node;
            heapPos[node] = idx;
        }

        private void siftDown(int idx) {
            int node = heap[idx];
            int half = size >>> 1;
            while (idx < half) {
                int childIdx = (idx << 1) + 1;
                int childNode = heap[childIdx];
                int rightIdx = childIdx + 1;
                if (rightIdx < size && less(heap[rightIdx], childNode)) {
                    childIdx = rightIdx;
                    childNode = heap[rightIdx];
                }
                if (!less(childNode, node)) break;
                heap[idx] = childNode;
                heapPos[childNode] = idx;
                idx = childIdx;
            }
            heap[idx] = node;
            heapPos[node] = idx;
        }

        private boolean less(int a, int b) {
            double fa = state.fScore[a];
            double fb = state.fScore[b];
            if (fa != fb) return fa < fb;
            return state.hScore[a] < state.hScore[b];
        }
    }
}
