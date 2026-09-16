package com.wingedsheep.gym.trainer.actor

import kotlinx.serialization.Serializable

/** Deterministic nearest-rank summary for bounded, operational measurements. */
@Serializable
data class ActorMeasurementSummaryV1(
    val count: Int,
    val total: Long,
    val min: Long,
    val p50: Long,
    val p95: Long,
    val max: Long,
) {
    init {
        require(count > 0) { "Measurement summary requires at least one sample" }
        require(total >= 0) { "Measurement total must not be negative" }
        require(min >= 0 && p50 >= min && p95 >= p50 && max >= p95) {
            "Measurement summary values must be ordered and nonnegative"
        }
    }

    companion object {
        fun from(samples: List<Long>): ActorMeasurementSummaryV1 {
            require(samples.isNotEmpty()) { "Measurement summary requires at least one sample" }
            require(samples.all { it >= 0 }) { "Measurement samples must not be negative" }
            val sorted = samples.sorted()
            val total = samples.fold(0L, Math::addExact)
            return ActorMeasurementSummaryV1(
                count = sorted.size,
                total = total,
                min = sorted.first(),
                p50 = nearestRank(sorted, 50, 100),
                p95 = nearestRank(sorted, 95, 100),
                max = sorted.last(),
            )
        }

        private fun nearestRank(sorted: List<Long>, numerator: Long, denominator: Long): Long {
            val rank = (numerator * sorted.size.toLong() + denominator - 1L) / denominator
            return sorted[(rank - 1L).toInt().coerceIn(0, sorted.lastIndex)]
        }
    }
}
