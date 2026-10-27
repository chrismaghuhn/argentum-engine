package com.wingedsheep.gameserver.config

import com.wingedsheep.ai.engine.SealedDeckGenerator
import com.wingedsheep.ai.engine.deck.CommanderDeckGenerator
import com.wingedsheep.ai.engine.deck.ConstructedDeckGenerator
import com.wingedsheep.ai.engine.deck.RandomDeckGenerator
import com.wingedsheep.engine.limited.BoosterGenerator
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.registry.PrintingRegistry
import com.wingedsheep.engine.registry.TokenArtRegistry
import com.wingedsheep.gameserver.coverage.SetCoverageService
import com.wingedsheep.gameserver.policy.PolicySeatRuntimeManager
import com.wingedsheep.mtg.sets.MtgSetCatalog
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.MtgSet
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class GameBeansConfig(
    private val gameProperties: GameProperties,
) {

    private fun activeSets(): List<MtgSet> =
        MtgSetCatalog.all.filter { gameProperties.sets.isEnabled(it.code) }

    @Bean
    fun activeMtgSets(): List<MtgSet> = activeSets()

    @Bean
    fun cardRegistry(): CardRegistry = ServerRegistries.cardRegistry()

    /** See [ServerRegistries.printingRegistry]. */
    @Bean
    fun printingRegistry(cardRegistry: CardRegistry): PrintingRegistry = ServerRegistries.printingRegistry(cardRegistry)

    /** See [ServerRegistries.tokenArtRegistry]. */
    @Bean
    fun tokenArtRegistry(): TokenArtRegistry = ServerRegistries.tokenArtRegistry()

    @Bean
    fun boosterGenerator(
        cardRegistry: CardRegistry,
        setCoverageService: SetCoverageService,
    ): BoosterGenerator = BoosterGenerator(
        // Every set with a non-empty card pool is selectable, not just the sealed-curated few.
        // Sets that aren't `sealedSupported` (or are flagged incomplete) ride along as "partial":
        // clients hide them behind a default-off toggle, but a host can still pick them. An empty
        // pool can't produce a booster, so those are the only sets excluded here — and an
        // all-reprint set's pool is resolved from its printings (see [boosterCardPool]) so it
        // isn't wrongly treated as empty.
        activeSets()
            .mapNotNull { set ->
                val limitedNames = setCoverageService.limitedCardNames(set.code)
                val pool = set.boosterCardPool(cardRegistry, limitedNames)
                val allExtras = if (limitedNames == null) emptyList() else {
                    set.boosterCardPool(cardRegistry, limitedCardNames = null)
                        .filterNot { it.name in limitedNames }
                        .map { it.copy(metadata = it.metadata.copy(inBooster = true)) }
                }
                val extrasByName = allExtras.associateBy { it.name }
                val extrasByProduct = setCoverageService.limitedProducts(set.code)
                    .mapValues { (_, names) -> names.mapNotNull(extrasByName::get) }
                    .filterValues { it.isNotEmpty() }
                if (pool.isEmpty()) null else set.code to set.toBoosterSetConfig(pool, extrasByProduct)
            }
            .toMap()
    )

    @Bean
    fun sealedDeckGenerator(boosterGenerator: BoosterGenerator): SealedDeckGenerator =
        SealedDeckGenerator(boosterGenerator)

    /**
     * Builds format-legal 60-card decks for the AI seat. Needs the registry as well as the booster
     * generator: a set's reprints are name-only [com.wingedsheep.sdk.model.Printing] rows whose
     * canonical definition lives in an earlier set.
     */
    @Bean
    fun constructedDeckGenerator(
        boosterGenerator: BoosterGenerator,
        cardRegistry: CardRegistry,
    ): ConstructedDeckGenerator = ConstructedDeckGenerator(boosterGenerator, cardRegistry)

    /**
     * Builds legal Commander / Brawl decks for the AI seat — the singleton shape
     * [constructedDeckGenerator] refuses, with a designated commander picked first.
     */
    @Bean
    fun commanderDeckGenerator(
        boosterGenerator: BoosterGenerator,
        cardRegistry: CardRegistry,
    ): CommanderDeckGenerator = CommanderDeckGenerator(boosterGenerator, cardRegistry)

    @Bean
    fun randomDeckGenerator(): RandomDeckGenerator {
        val active = activeSets()
        return RandomDeckGenerator(
            cardPool = active.flatMap { it.cards },
            basicLandVariants = PortalSet.basicLands,
            setCodes = active.map { it.code },
        )
    }

    @Bean
    fun policySeatRuntimeManager(): PolicySeatRuntimeManager =
        PolicySeatRuntimeManager(gameProperties)
}

/**
 * The card pool a set contributes to booster / sealed / draft generation.
 *
 * A set contributes both its own [MtgSet.cards] and every distinct reprinted card in
 * [MtgSet.printings]. Each reprint is resolved to the canonical [CardDefinition] via [registry],
 * then overlaid with its set-specific presentation and rarity. Reprints whose canonical isn't
 * implemented anywhere are skipped.
 *
 * A card with both an own definition and one or more additional printings in the same set remains
 * one booster-pool entry. Likewise, multiple treatments of the same reprint do not weight that
 * oracle card more heavily; alternate frames are handled separately by the variant slot.
 *
 * This union is needed for both all-reprint sets (such as Eighth Edition) and mixed sets (such as
 * Foundations). Returning early when [MtgSet.cards] was non-empty silently omitted every reprint
 * from mixed-set boosters.
 */
private fun MtgSet.boosterCardPool(
    registry: CardRegistry,
    limitedCardNames: Set<String>?,
): List<CardDefinition> {
    val eligibleOwnCards = cards.stamp(this)
        .asSequence()
        .filter { limitedCardNames == null || it.name in limitedCardNames }
        .map { card ->
            if (limitedCardNames == null) card
            else card.copy(metadata = card.metadata.copy(inBooster = true))
        }
        .toList()
    val ownNames = eligibleOwnCards.asSequence().map { it.name }.toHashSet()
    val resolvedReprints = printings
        .asSequence()
        .filter { limitedCardNames == null || it.name in limitedCardNames }
        .filter { it.name !in ownNames }
        .groupBy { it.name }
        .mapNotNull { (_, treatments) ->
            val printing = treatments.firstOrNull { !it.isAlternateFrame && !it.isPromo }
                ?: treatments.first()
            registry.getCardsByName(printing.name).firstOrNull()?.let { canonical ->
                val withArt = canonical.withPrinting(printing)
                withArt.copy(
                    metadata = withArt.metadata.copy(
                        rarity = printing.rarity,
                        inBooster = limitedCardNames != null || withArt.metadata.inBooster,
                    ),
                )
            }
        }
    return (eligibleOwnCards + resolvedReprints).sortedBy { it.name }
}

private fun MtgSet.toBoosterSetConfig(
    cards: List<CardDefinition>,
    extraCardsByProduct: Map<String, List<CardDefinition>>,
): BoosterGenerator.SetConfig {
    val defaultPoolNames = cards.mapTo(hashSetOf()) { it.name }
    return BoosterGenerator.SetConfig(
        setCode = code,
        setName = displayName,
        cards = cards,
        extraCardsByProduct = extraCardsByProduct,
        basicLands = (basicLandsFallback ?: this).basicLands,
        incomplete = incomplete,
        sealedSupported = sealedSupported,
        extensionSet = extensionSet,
        block = block,
        releaseDate = releaseDate,
        boosterStrategy = boosterStrategy,
        // Printing rows also drive the set-picker count. Keep only treatments for cards in the
        // default booster pool; optional product printings are counted in their product rows.
        printings = printings.filter { it.name in defaultPoolNames },
        variantChance = boosterVariantChance,
    )
}
