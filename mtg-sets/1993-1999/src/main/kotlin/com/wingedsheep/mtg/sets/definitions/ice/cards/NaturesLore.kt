package com.wingedsheep.mtg.sets.definitions.ice.cards

import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.dsl.Patterns
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.effects.SearchDestination


/**
 * Nature's Lore
 * {1}{G}
 * Sorcery
 * Search your library for a Forest card, put that card onto the battlefield, then shuffle.
 */
val NaturesLore = card("Nature's Lore") {
    manaCost = "{1}{G}"
    colorIdentity = "G"
    typeLine = "Sorcery"
    oracleText = "Search your library for a Forest card, put that card onto the battlefield, then shuffle."
    spell {
        effect = Patterns.Library.searchLibrary(
            filter = GameObjectFilter.Land.withSubtype(Subtype.FOREST),
            destination = SearchDestination.BATTLEFIELD
        )
    }
    metadata {
        rarity = Rarity.UNCOMMON
        collectorNumber = "255"
        artist = "Rick Emond"
        flavorText = "\"Fyndhorn is our home.\"\n—Kolbjörn, Elder Druid of the Juniper Order"
        imageUri = "https://cards.scryfall.io/normal/front/6/6/668d2969-b6b7-4507-bdd4-20bbaa68035a.jpg?1783947474"
        ruling(
            "2022-12-08",
            "You may find a basic Forest or any land card with the land type Forest."
        )
    }
}
