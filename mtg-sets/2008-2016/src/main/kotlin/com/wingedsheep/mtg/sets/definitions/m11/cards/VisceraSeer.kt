package com.wingedsheep.mtg.sets.definitions.m11.cards

import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.GameObjectFilter

/**
 * Viscera Seer (M11 #120)
 * {B}
 * Creature — Vampire Wizard
 * 1/1
 * Sacrifice a creature: Scry 1.
 */
val VisceraSeer = card("Viscera Seer") {
    manaCost = "{B}"
    colorIdentity = "B"
    typeLine = "Creature — Vampire Wizard"
    power = 1
    toughness = 1
    oracleText = "Sacrifice a creature: Scry 1. (Look at the top card of your library. You may put that card on the bottom.)"

    activatedAbility {
        cost = Costs.Sacrifice(GameObjectFilter.Creature)
        effect = Effects.Scry(1)
    }

    metadata {
        rarity = Rarity.COMMON
        collectorNumber = "120"
        artist = "John Stanko"
        imageUri = "https://cards.scryfall.io/normal/front/6/1/6179f847-e334-4f7f-9a4e-0013942a394f.jpg?1783941810"
    }
}
