package com.wingedsheep.gameserver.replay

/**
 * State-local aliases for generated [com.wingedsheep.sdk.scripting.AbilityId] handles.
 *
 * AbilityId.generate() is intentionally convenient for runtime construction, but its numeric
 * suffix is allocation-order state rather than game semantics. This table only aliases that
 * exact generated wire form. Stable printed, donor-derived, and other explicit ability IDs remain
 * unchanged, while repeated references to one generated handle share one alias for the whole
 * canonicalized GameState. Raw stable IDs are reserved so an explicit ID such as "A0" cannot
 * collide with a generated alias.
 */
internal class AbilityIdAliasTable(
    initialAliases: Map<String, String> = emptyMap(),
    private val reservedRawIds: Set<String> = emptySet(),
) {
    private val aliases = LinkedHashMap(initialAliases)
    private var nextAliasIndex = 0

    fun aliasIfGenerated(rawAbilityId: String): String =
        if (isGenerated(rawAbilityId)) {
            aliases.getOrPut(rawAbilityId) { allocateAlias() }
        } else {
            rawAbilityId
        }

    private fun allocateAlias(): String {
        while (true) {
            val candidate = "A${nextAliasIndex++}"
            if (candidate !in reservedRawIds && candidate !in aliases.values) return candidate
        }
    }

    companion object {
        private val generatedPattern = Regex("^ability_[0-9]+$")

        fun isGenerated(rawAbilityId: String): Boolean = generatedPattern.matches(rawAbilityId)
    }
}
