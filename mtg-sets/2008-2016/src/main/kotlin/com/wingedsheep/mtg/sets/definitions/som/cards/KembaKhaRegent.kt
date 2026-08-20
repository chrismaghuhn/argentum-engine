package com.wingedsheep.mtg.sets.definitions.som.cards

import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.dsl.DynamicAmounts
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.effects.CreateTokenEffect

/**
 * Kemba, Kha Regent — Scars of Mirrodin #12
 *
 * At the beginning of your upkeep, create a 2/2 white Cat creature token for each Equipment
 * attached to Kemba.
 */
val KembaKhaRegent = card("Kemba, Kha Regent") {
    manaCost = "{1}{W}{W}"
    colorIdentity = "W"
    typeLine = "Legendary Creature — Cat Cleric"
    power = 2
    toughness = 4
    oracleText = "At the beginning of your upkeep, create a 2/2 white Cat creature token for each Equipment attached to Kemba."

    triggeredAbility {
        trigger = Triggers.YourUpkeep
        effect = CreateTokenEffect(
            count = DynamicAmounts.attachmentsOnSelf(),
            power = 2,
            toughness = 2,
            colors = setOf(Color.WHITE),
            creatureTypes = setOf("Cat"),
        )
    }

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "12"
        artist = "Todd Lockwood"
        imageUri = "https://cards.scryfall.io/normal/front/1/9/1964ca48-3260-4e2d-9014-984c1efc9a43.jpg?1783941744"
    }
}
