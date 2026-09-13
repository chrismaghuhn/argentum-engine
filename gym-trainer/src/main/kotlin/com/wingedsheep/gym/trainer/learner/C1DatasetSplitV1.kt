package com.wingedsheep.gym.trainer.learner

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** The only partition vocabulary used by the C1 derived learner artifact. */
enum class C1DatasetPartition {
    TRAIN,
    VALIDATION,
    TEST,
}

/** Exact C0-02 episode-level split implementation. */
object C1DatasetSplitV1 {
    private val semanticEpisodeIdPattern = Regex("[0-9a-f]{64}")
    private val bucketModulus = BigInteger.valueOf(100L)

    fun bucket(semanticEpisodeId: String): Int {
        require(semanticEpisodeIdPattern.matches(semanticEpisodeId)) {
            "semanticEpisodeId must be lowercase SHA-256 hex"
        }
        val preimage = (
            "${C1_SPLIT_CONTRACT_IDENTITY}\n$semanticEpisodeId"
            ).toByteArray(StandardCharsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(preimage)
        val unsignedWord = BigInteger(1, digest.copyOfRange(0, 8))
        return unsignedWord.mod(bucketModulus).intValueExact()
    }

    fun assign(semanticEpisodeId: String): C1DatasetPartition = when (val value = bucket(semanticEpisodeId)) {
        in 0..79 -> C1DatasetPartition.TRAIN
        in 80..89 -> C1DatasetPartition.VALIDATION
        in 90..99 -> C1DatasetPartition.TEST
        else -> error("Unexpected C0-02 split bucket: $value")
    }
}
