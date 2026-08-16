package com.wingedsheep.gameserver.replay

/**
 * State-local aliases for the runtime routing identities used by pending decisions and
 * continuations. This deliberately accepts only already-typed decision-reference slots; it is
 * not a generic string/ID normalizer.
 */
internal class DecisionNonceAliasTable {
    private val aliases = LinkedHashMap<String, String>()

    fun alias(rawNonce: String): String =
        aliases.getOrPut(rawNonce) { "D${aliases.size}" }
}
