package com.wingedsheep.mtg.sets.definitions.znr.cards

import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.GrantKeyword
import com.wingedsheep.sdk.scripting.filters.unified.GroupFilter

/**
 * Kor Blademaster — Zendikar Rising #21
 *
 * Double strike
 * Equipped Warriors you control have double strike.
 */
val KorBlademaster = card("Kor Blademaster") {
    manaCost = "{1}{W}"
    colorIdentity = "W"
    typeLine = "Creature — Kor Warrior"
    power = 1
    toughness = 1
    oracleText = "Double strike\nEquipped Warriors you control have double strike."

    keywords(Keyword.DOUBLE_STRIKE)

    staticAbility {
        ability = GrantKeyword(
            Keyword.DOUBLE_STRIKE,
            GroupFilter(GameObjectFilter.Creature.withSubtype("Warrior").youControl().equipped())
        )
    }

    metadata {
        rarity = Rarity.UNCOMMON
        collectorNumber = "21"
        artist = "Darren Tan"
        imageUri = "https://cards.scryfall.io/normal/front/f/c/fc6d6049-5599-47eb-ae58-a8eb96927ece.jpg?1783929416"
    }
}
