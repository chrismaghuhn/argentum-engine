package com.wingedsheep.gameserver.config

import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.registry.PrintingRegistry
import com.wingedsheep.engine.registry.TokenArtRegistry
import com.wingedsheep.mtg.sets.MtgSetCatalog
import com.wingedsheep.mtg.sets.definitions.custom.JustOneGlassToken
import com.wingedsheep.mtg.sets.definitions.custom.SekshaasEarlySleeper
import com.wingedsheep.mtg.sets.legality.LegalityData
import com.wingedsheep.mtg.sets.tokens.PredefinedTokens
import com.wingedsheep.mtg.sets.tokens.TokenArtData
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.MtgSet

/**
 * The server's card, printing and token-art registries, built without Spring.
 *
 * [GameBeansConfig] exposes these as beans; headless tools (Gym tournaments that record replays)
 * build the same registries directly, because printing and token art are part of the state a
 * replay fingerprints — a game played with different registries drifts when the server replays it.
 */
object ServerRegistries {

    fun cardRegistry(): CardRegistry = CardRegistry().apply {
        register(PredefinedTokens.allTokens)
        // Register every catalogued set so the standalone deckbuilder can browse and validate
        // cards from all sets, even those gated out of sealed/draft via game.sets.disabled-by-default.
        // Booster/sealed/draft generation still respects the active-set filter via boosterGenerator.
        for (set in MtgSetCatalog.all) {
            // A set that contributes nothing has almost always shipped hollow because its
            // CARDS_PACKAGE constant has a typo — discovery scans the wrong package and silently
            // returns empty. Turn that worst-case silent failure into a named, immediate error.
            // (All-reprint sets like Eighth Edition legitimately have no own `cards`, so reprints
            // and basic lands count too.)
            check(
                set.cards.isNotEmpty() || set.printings.isNotEmpty() || set.basicLands.isNotEmpty(),
            ) {
                "Set ${set.code} (${set.displayName}) loaded no cards, printings, or basic lands — " +
                    "typo in its CARDS_PACKAGE?"
            }
            register(set.cards.stamp(set).withLegalities())
            register(set.basicLands.withLegalities())
            set.basicLandsFallback?.let { register(it.basicLands.withLegalities()) }
        }
        // Easter egg card — injected into Rick's deck at game start
        register(LegalityData.stamp(SekshaasEarlySleeper))
        register(LegalityData.stamp(JustOneGlassToken))
        // Momir Basic Vanguard avatar — placed in the command zone when the format is active.
        register(LegalityData.stamp(com.wingedsheep.mtg.sets.definitions.custom.MomirVigSimicVisionary))
    }

    /**
     * Per-printing index. Populated in two passes:
     *
     * 1. Synthesised defaults from every registered card — one printing row per
     *    `CardDefinition` derived from its `setCode` + `metadata.collectorNumber`.
     *    This covers the canonical printing of every card.
     * 2. Explicit reprint rows contributed by each [MtgSet] via `MtgSet.printings`.
     *    Reprints overwrite synthesised entries with the same `(setCode, collectorNumber)`
     *    so a hand-curated reprint always wins over auto-derivation.
     *
     * Real Scryfall printing data lands in a later phase via a classpath-loaded jsonl;
     * until then these two passes are enough for the deckbuilder picker and the
     * game-init art override to function.
     */
    fun printingRegistry(cardRegistry: CardRegistry): PrintingRegistry = PrintingRegistry().apply {
        for (name in cardRegistry.allCardNames()) {
            cardRegistry.getCardsByName(name).forEach(::registerSynthesizedDefault)
        }
        for (set in MtgSetCatalog.all) {
            register(set.printings)
        }
    }

    /**
     * Per-set token art. A token has no `CardDefinition` and no `Printing` row, so this is the
     * only place its art can be keyed to a set; the token executors consult it so a token shows
     * the art of the set the card that created it was printed in.
     */
    fun tokenArtRegistry(): TokenArtRegistry = TokenArtRegistry().apply {
        for (set in MtgSetCatalog.all) {
            register(set.code, TokenArtData.forSet(set), set.cards.map { it.name })
        }
    }
}

/**
 * Stamp each card with its [set]'s identity that the bare `CardDefinition` doesn't carry:
 * `setCode` (so the deckbuilder can resolve a default printing) and `metadata.releaseDate`
 * (so the synthesised default printing dates from the set, not `null`).
 *
 * The release-date stamp is what keeps the deckbuilder defaulting to the *plain* frame: a set's
 * showcase/borderless variant printings ship explicit release dates, but the canonical printing
 * is synthesised from this metadata. Without the stamp it dates to `null` and sorts *after* the
 * dated variants, so the catalog defaults to a showcase/borderless art. Stamping the set date
 * ties the canonical printing with its variants, letting the `isAlternateFrame` tiebreaker in
 * `PrintingRegistry.defaultPrinting` pick the plain one. Only fills gaps — never overwrites a
 * value a card already declares.
 */
internal fun List<CardDefinition>.stamp(set: MtgSet): List<CardDefinition> =
    map { card ->
        val withSet = if (card.setCode == null) card.copy(setCode = set.code) else card
        if (withSet.metadata.releaseDate == null && set.releaseDate != null) {
            withSet.copy(metadata = withSet.metadata.copy(releaseDate = set.releaseDate))
        } else {
            withSet
        }
    }

private fun List<CardDefinition>.withLegalities(): List<CardDefinition> =
    map { LegalityData.stamp(it) }
