package dev.manhattan.fleet;

/**
 * Optional lease provider seam for {@link dev.manhattan.ChunkRunner}.
 * When {@code null}, {@code ChunkRunner} uses its default local {@link dev.manhattan.Ledger} queues.
 * When present (Fleet Worker mode), {@code ChunkRunner} claims one chunk at a time from the server
 * and reports completion/deferral/failure back to the coordinator.
 */
public interface ChunkLeaseProvider {
    enum ClaimStatus {
        /** A specific chunk key has been leased to this worker. */
        GRANTED,
        /** Waiting for an asynchronous network reply from the fleet server. */
        WAITING,
        /** No eligible chunks remain for this worker on this job right now. */
        NO_WORK
    }

    record ClaimResult(ClaimStatus status, String chunkKey) {
        public static ClaimResult granted(String chunkKey) {
            return new ClaimResult(ClaimStatus.GRANTED, chunkKey);
        }

        public static ClaimResult waiting() {
            return new ClaimResult(ClaimStatus.WAITING, null);
        }

        public static ClaimResult noWork() {
            return new ClaimResult(ClaimStatus.NO_WORK, null);
        }
    }

    /** Polls or requests the next chunk lease for the active fleet job. */
    ClaimResult pollNextChunk();

    /** Reports outcome of a previously leased chunk ({@code "DONE"}, {@code "DEFERRED"}, or {@code "FAILED"}). */
    void reportChunk(String chunkKey, String outcome, String reason);
}
