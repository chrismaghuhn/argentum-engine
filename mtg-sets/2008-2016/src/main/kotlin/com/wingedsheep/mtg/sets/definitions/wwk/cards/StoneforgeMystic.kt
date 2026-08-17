package com.wingedsheep.mtg.sets.definitions.wwk.cards

import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Patterns
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.effects.SearchDestination

/**
 * Stoneforge Mystic
 * {1}{W}
 * Creature — Kor Artificer
 * 1/2
 * When this creature enters, you may search your library for an Equipment card, reveal it, put
 * it into your hand, then shuffle.
 * {1}{W}, {T}: You may put an Equipment card from your hand onto the battlefield.
 */
val StoneforgeMystic = card("Stoneforge Mystic") {
    manaCost = "{1}{W}"
    colorIdentity = "W"
    typeLine = "Creature — Kor Artificer"
    oracleText =
        "When this creature enters, you may search your library for an Equipment card, reveal it, " +
            "put it into your hand, then shuffle.\n" +
            "{1}{W}, {T}: You may put an Equipment card from your hand onto the battlefield."
    power = 1
    toughness = 2

    val equipment = GameObjectFilter.Artifact.withSubtype("Equipment")

    triggeredAbility {
        trigger = Triggers.EntersBattlefield
        optional = true
        effect = Patterns.Library.searchLibrary(
            filter = equipment,
            destination = SearchDestination.HAND,
            reveal = true,
            shuffleAfter = true,
        )
    }

    activatedAbility {
        cost = Costs.Composite(Costs.Mana("{1}{W}"), Costs.Tap)
        effect = Patterns.Hand.putFromHand(filter = equipment)
    }

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "20"
        artist = "Mike Bierek"
        imageUri = "https://cards.scryfall.io/normal/front/1/9/19557351-b65f-4b04-b971-66abdc07000a.jpg?1783942065"
    }
}
