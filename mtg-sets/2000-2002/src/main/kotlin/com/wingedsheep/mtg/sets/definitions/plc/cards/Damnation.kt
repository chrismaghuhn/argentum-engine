package com.wingedsheep.mtg.sets.definitions.plc.cards

import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.filters.unified.GroupFilter
import com.wingedsheep.sdk.scripting.targets.EffectTarget

/**
 * Damnation
 * {2}{B}{B}
 * Sorcery
 * Destroy all creatures. They can't be regenerated.
 */
val Damnation = card("Damnation") {
    manaCost = "{2}{B}{B}"
    colorIdentity = "B"
    typeLine = "Sorcery"
    oracleText = "Destroy all creatures. They can't be regenerated."

    spell {
        effect = Effects.ForEachInGroup(
            GroupFilter(GameObjectFilter.Creature),
            Effects.Destroy(EffectTarget.Self),
            noRegenerate = true,
        )
    }

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "85"
        artist = "Kev Walker"
        imageUri = "https://cards.scryfall.io/normal/front/2/6/26c68473-70ca-40ba-b5c6-71ec30f88a2c.jpg?1783943151"
    }
}
