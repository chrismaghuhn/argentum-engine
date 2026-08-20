package com.wingedsheep.mtg.sets.definitions.cmr.cards

import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.DynamicAmounts
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.TimingRule

/**
 * War Room (CMR #361)
 * Land
 *
 * {T}: Add {C}.
 * {3}, {T}, Pay life equal to the number of colors in your commanders' color identity: Draw a card.
 */
val WarRoom = card("War Room") {
    typeLine = "Land"
    colorIdentity = ""
    oracleText = "{T}: Add {C}.\n" +
        "{3}, {T}, Pay life equal to the number of colors in your commanders' color identity: Draw a card."

    activatedAbility {
        cost = Costs.Tap
        effect = Effects.AddColorlessMana(1)
        manaAbility = true
        timing = TimingRule.ManaAbility
    }

    activatedAbility {
        cost = Costs.Composite(
            Costs.Mana("{3}"),
            Costs.Tap,
            Costs.PayLife(DynamicAmounts.commanderColorIdentityCount()),
        )
        effect = Effects.DrawCards(1)
    }

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "361"
        artist = "Milivoj Ćeran"
        flavorText = "A figure is moved on a map, and the tide of war changes."
        imageUri = "https://cards.scryfall.io/normal/front/4/8/48d6ce7c-5dc8-449b-acbd-db259ae687ed.jpg?1783928737"
    }
}
