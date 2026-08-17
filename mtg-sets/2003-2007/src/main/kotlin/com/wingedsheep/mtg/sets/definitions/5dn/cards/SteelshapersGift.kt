package com.wingedsheep.mtg.sets.definitions.`5dn`.cards

import com.wingedsheep.sdk.dsl.Patterns
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.effects.SearchDestination

/**
 * Steelshaper's Gift — Fifth Dawn #19
 * {W}
 * Sorcery
 * Search your library for an Equipment card, reveal that card, put it into your hand, then shuffle.
 */
val SteelshapersGift = card("Steelshaper's Gift") {
    manaCost = "{W}"
    colorIdentity = "W"
    typeLine = "Sorcery"
    oracleText = "Search your library for an Equipment card, reveal that card, put it into your hand, then shuffle."
    spell {
        effect = Patterns.Library.searchLibrary(
            filter = GameObjectFilter.Artifact.withSubtype("Equipment"),
            destination = SearchDestination.HAND,
            reveal = true,
        )
    }
    metadata {
        rarity = Rarity.UNCOMMON
        collectorNumber = "19"
        artist = "Greg Hildebrandt"
        flavorText = "Some blades seek their own wielders."
        imageUri = "https://cards.scryfall.io/normal/front/d/b/db75b77f-a230-4bad-b697-a40687671842.jpg?1783944407"
    }
}
