package com.wingedsheep.gym.contract

/**
 * Contract identifier. Python clients compare this against the value they were
 * built with; a mismatch warns (or hard-fails in strict mode) that the DTO,
 * privacy, or canonical semantic contract has drifted. It is not a separately
 * computed SHA-256 of the Kotlin schema.
 *
 * Bump [CURRENT] whenever any `@Serializable` data class in this package
 * changes shape — or a field changes what it means — in a way that would
 * break a downstream consumer. The value itself is arbitrary; uniqueness is
 * what matters.
 */
object SchemaHash {
    /**
     * Fork contract v1.26 merged with upstream v1.3–v1.6 (pinned upstream 12317227, sync 05):
     * per-player zone views now follow [TRAINING_OBSERVATION_ZONE_ORDER] (adds SIDEBOARD), stack
     * abilities carry their source name and description, an uncrewed Vehicle or other noncreature
     * permanent reports null power/toughness, and an entity's name is its projected name.
     */
    const val CURRENT: String = "argentum-gym-contract@v1.27-upstream-sync-05"
}
