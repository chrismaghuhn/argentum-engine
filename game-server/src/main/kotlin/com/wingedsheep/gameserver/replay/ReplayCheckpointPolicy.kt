package com.wingedsheep.gameserver.replay

/** Persistence-only checkpoint shaping. It never mutates a live GameSession recording list. */
internal object ReplayCheckpointPolicy {

    /** v3-v6 have a persistence-only tail checkpoint; older formats retain their historical shape. */
    fun requiresTailCheckpoint(replayVersion: Int): Boolean =
        replayVersion == 3 || replayVersion == 4 || replayVersion == 5 || replayVersion == 6

    /**
     * Return a de-duplicated, ordered checkpoint list with the current v3 tail materialized.
     * Callers must use the returned list only for the CompactReplay being persisted.
     */
    fun withV3Tail(
        checkpoints: List<ReplayCheckpoint>,
        actionCount: Int,
        fingerprint: String,
    ): List<ReplayCheckpoint> {
        val byCount = LinkedHashMap<Int, ReplayCheckpoint>()
        checkpoints.forEach { checkpoint ->
            byCount[checkpoint.afterActionCount] = checkpoint
        }
        byCount[actionCount] = ReplayCheckpoint(actionCount, fingerprint)
        return byCount.values.sortedBy { it.afterActionCount }
    }
}
