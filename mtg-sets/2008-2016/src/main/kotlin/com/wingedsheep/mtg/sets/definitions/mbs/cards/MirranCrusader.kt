package com.wingedsheep.mtg.sets.definitions.mbs.cards

import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.KeywordAbility

/**
 * Mirran Crusader
 * {1}{W}{W}
 * Creature — Human Knight
 * Double strike, protection from black and from green
 * 2/2
 */
val MirranCrusader = card("Mirran Crusader") {
    manaCost = "{1}{W}{W}"
    colorIdentity = "W"
    typeLine = "Creature — Human Knight"
    oracleText = "Double strike, protection from black and from green"
    power = 2
    toughness = 2
    keywords(Keyword.DOUBLE_STRIKE)
    keywordAbility(KeywordAbility.protectionFrom(Color.BLACK, Color.GREEN))
    metadata {
        rarity = Rarity.RARE
        collectorNumber = "14"
        artist = "Eric Deschamps"
        imageUri = "https://cards.scryfall.io/normal/front/a/a/aaf7a821-3587-4aad-8411-fca5c96ab5c4.jpg?1783941390"
    }
}
