package com.wingedsheep.engine.registry

import com.wingedsheep.sdk.model.CardDefinition

/**
 * Registry for looking up card definitions by name.
 *
 * The CardRegistry is injected into the GameInitializer to resolve card names
 * from decks into CardDefinition objects.
 *
 * ## Usage
 * ```kotlin
 * val registry = CardRegistry()
 * registry.register(PortalSet.cards)
 * val lightningBolt = registry.getCard("Lightning Bolt")
 * ```
 *
 * ## Overlays
 * Pass a [parent] to build a thin registry that resolves its own entries first and defers
 * everything else — used by replay reconstruction to stack a recorded game's archived card
 * definitions over the live corpus without copying it (see
 * `com.wingedsheep.gameserver.replay.ReplayCardPin`). Registering into a child never mutates the
 * parent.
 */
class CardRegistry(private val parent: CardRegistry? = null) {
    private val cardsByName = mutableMapOf<String, CardDefinition>()
    // Secondary index: "CardName#CollectorNumber" -> CardDefinition (for variants like basic lands)
    private val cardsByNameAndNumber = mutableMapOf<String, CardDefinition>()
    // Reverse DFC index: back face name -> front face name
    private val backFaceToFrontFace = mutableMapOf<String, String>()
    /**
     * Lookup-only aliases for the external name printed across a physical DFC.
     *
     * These deliberately do not live in [cardsByName]: a combined name is not a third card
     * definition and must not appear in card-name pools, registry size, or printing indexes.
     */
    private val combinedDfcAliases = mutableMapOf<String, CombinedDfcAlias>()

    private data class CombinedDfcAlias(
        val frontName: String,
        val backName: String,
    ) {
        val externalName: String get() = "$frontName // $backName"
    }

    /**
     * Register a single card definition.
     *
     * Double-faced cards (where `card.isDoubleFaced`) are also registered under the back face's
     * name so the back face is resolvable by name (e.g., after a transform). The back face entry
     * carries no further `backFace` pointer — it stands alone as the back-face identity.
     */
    fun register(card: CardDefinition) {
        validateRegistration(card)

        cardsByName[card.name] = card
        // Also register by name#collectorNumber for variants.
        // When setCode is present, use "Name#SetCode-CollectorNumber" to avoid collisions
        // between sets that share collector numbers (e.g., Khans and Dominaria both use 250-269).
        val collectorNumber = card.metadata.collectorNumber
        if (collectorNumber != null) {
            val key = if (card.setCode != null) {
                "${card.name}#${card.setCode}-$collectorNumber"
            } else {
                "${card.name}#$collectorNumber"
            }
            cardsByNameAndNumber[key] = card
        }

        // Auto-register the back face as a standalone entry so lookups by its name succeed.
        // The TransformEffectExecutor resolves face definitions via this registry.
        card.backFace?.let { backFace ->
            // Don't clobber an existing registration (e.g., if someone already registered the back face).
            if (!cardsByName.containsKey(backFace.name)) {
                cardsByName[backFace.name] = backFace
            }
            // Track the reverse mapping so scenario builders can find the front face.
            backFaceToFrontFace[backFace.name] = card.name

            // A linked backFace is the authoritative discriminator for a physical DFC. This
            // creates only an additional external lookup key; the front definition remains the
            // sole deck/library definition.
            val alias = CombinedDfcAlias(card.name, backFace.name)
            combinedDfcAliases[alias.externalName] = alias
        }
    }

    /**
     * Register multiple card definitions.
     */
    fun register(cards: Iterable<CardDefinition>) {
        cards.forEach { register(it) }
    }

    /**
     * Look up a card by name or by name#collectorNumber.
     *
     * Supports two formats:
     * - "Lightning Bolt" - returns the card by name
     * - "Plains#196" - returns the specific variant by collector number
     *
     * @param name The card name or name#collectorNumber (case-sensitive)
     * @return The card definition, or null if not found
     */
    fun getCard(name: String): CardDefinition? {
        // First try exact match with collector number format
        cardsByNameAndNumber[name]?.let { return it }
        // Fall back to name-only lookup, then to the parent registry for an overlay.
        cardsByName[name]?.let { return it }
        combinedDfcAliases[name]?.let { alias ->
            // Registration validates that the local front definition exists. Resolving through
            // the canonical local name keeps re-registration idempotent and preserves overlay
            // shadowing: a child alias can never accidentally return the parent's live front.
            return cardsByName[alias.frontName]
        }
        return parent?.getCard(name)
    }

    /**
     * Look up a card by name, throwing if not found.
     *
     * @param name The card name or name#collectorNumber (case-sensitive)
     * @return The card definition
     * @throws CardDefinitionMissingException if the card is not found
     */
    fun requireCard(name: String): CardDefinition {
        return getCard(name)
            ?: throw CardDefinitionMissingException(name)
    }

    /**
     * Check if a card is registered.
     */
    fun hasCard(name: String): Boolean = getCard(name) != null

    /**
     * Get all registered card names (unique names only, not variants).
     */
    fun allCardNames(): Set<String> =
        if (parent == null) cardsByName.keys.toSet() else parent.allCardNames() + cardsByName.keys

    /**
     * Get the names of every registered card whose type line includes Land (e.g. for
     * Petrified Hamlet's "choose a land card name"). Unique names only.
     */
    fun landCardNames(): Set<String> {
        val own = cardsByName.values.filter { it.isLand }.map { it.name }.toSet()
        // An overlay only shadows definitions; a name is a land name if it is one in either layer.
        return if (parent == null) own else own + parent.landCardNames().filter { it !in cardsByName }
    }

    /**
     * Get the names of every registered card whose type line does *not* include Land (Skyseer's
     * Chariot's "choose a nonland card name"). Unique names only; the mirror of [landCardNames].
     */
    fun nonlandCardNames(): Set<String> {
        val own = cardsByName.values.filter { !it.isLand }.map { it.name }.toSet()
        return if (parent == null) own else own + parent.nonlandCardNames().filter { it !in cardsByName }
    }

    /**
     * The names offered by an `EntersWithChoice(ChoiceType.CARD_NAME)` drawing from [pool] — the
     * single place the pool-to-registry mapping lives, so every naming path (spell resolution,
     * land drop, leyline opening) offers the same option set.
     */
    fun cardNamesIn(pool: com.wingedsheep.sdk.scripting.CardNamePool): Set<String> = when (pool) {
        com.wingedsheep.sdk.scripting.CardNamePool.ANY -> allCardNames()
        com.wingedsheep.sdk.scripting.CardNamePool.LAND -> landCardNames()
        com.wingedsheep.sdk.scripting.CardNamePool.NONLAND -> nonlandCardNames()
    }

    /**
     * Get all cards with a given name (useful for basic lands with multiple variants).
     *
     * @param name The card name (e.g., "Plains")
     * @return All cards with that name, including variants
     */
    fun getCardsByName(name: String): List<CardDefinition> {
        val own = cardsByNameAndNumber.entries
            .filter { (key, _) -> key.startsWith("$name#") }
            .map { it.value }
            .ifEmpty { listOfNotNull(cardsByName[name]) }
        if (parent == null) return own
        // Overlay entries shadow the parent's variant with the same identity, but the parent may
        // hold printings this overlay never pinned — keep both, overlay first.
        val ownNumbers = own.mapNotNullTo(mutableSetOf()) { it.metadata.collectorNumber }
        return own + parent.getCardsByName(name)
            .filter { it.metadata.collectorNumber !in ownNumbers }
    }

    /**
     * Get the total number of registered unique card names.
     *
     * O(1) on a root registry. An overlay has to discount the parent names it shadows, so it is
     * O(parent names) and allocates — fine for the diagnostics this serves, not for a loop.
     */
    val size: Int get() {
        val parent = this.parent ?: return cardsByName.size
        return cardsByName.size + parent.allCardNames().count { it !in cardsByName }
    }

    /**
     * Look up the front face of a DFC given its back face name.
     * Returns null if the name is not a registered back face.
     */
    fun getFrontFace(backFaceName: String): CardDefinition? {
        val frontName = backFaceToFrontFace[backFaceName] ?: return parent?.getFrontFace(backFaceName)
        return getCard(frontName)
    }

    /**
     * Clear all registered cards.
     */
    fun clear() {
        cardsByName.clear()
        cardsByNameAndNumber.clear()
        backFaceToFrontFace.clear()
        combinedDfcAliases.clear()
    }

    /**
     * Validate all local-name interactions before mutating any registry index. Parent entries are
     * intentionally ignored here: a child registry is allowed to shadow a parent's answer.
     */
    private fun validateRegistration(card: CardDefinition) {
        require(card.name !in combinedDfcAliases) {
            "Cannot register canonical card '${card.name}': it is already a local combined DFC alias"
        }

        val aliasesForFront = combinedDfcAliases.values.filter { it.frontName == card.name }
        val backName = card.backFace?.name
        require(aliasesForFront.all { it.backName == backName }) {
            "Cannot register card '${card.name}': its DFC face linkage conflicts with an existing local alias"
        }

        card.backFace?.let { backFace ->
            require(backFace.name !in combinedDfcAliases) {
                "Cannot register back face '${backFace.name}': it is already a local combined DFC alias"
            }

            val alias = CombinedDfcAlias(card.name, backFace.name)
            require(alias.externalName != card.name && alias.externalName != backFace.name) {
                "Cannot register DFC '${card.name}': combined name '${alias.externalName}' conflicts with a face name"
            }

            cardsByName[alias.externalName]?.let {
                throw IllegalArgumentException(
                    "Cannot register combined DFC alias '${alias.externalName}': " +
                        "a local canonical card with that name is already registered",
                )
            }

            combinedDfcAliases[alias.externalName]?.let { existing ->
                require(existing == alias) {
                    "Cannot register combined DFC alias '${alias.externalName}': " +
                        "its face linkage conflicts with the existing local alias " +
                        "'${existing.frontName} // ${existing.backName}'"
                }
            }
        }
    }
}
