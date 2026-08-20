package com.wingedsheep.mtg.sets.definitions.clb.cards

import com.wingedsheep.sdk.model.Printing
import com.wingedsheep.sdk.model.Rarity

/**
 * Fire Diamond showcase reprint in Commander Legends: Battle for Baldur's Gate. Canonical
 * CardDefinition lives in Mirage.
 */
val FireDiamondClbShowcaseReprint = Printing(
    oracleId = "97b477d8-2e05-475e-8ed6-7d680cb21cd9",
    name = "Fire Diamond",
    setCode = "CLB",
    collectorNumber = "445",
    scryfallId = "81fa8865-c600-4d8d-976d-789cd565802e",
    artist = "Phil Stone",
    imageUri = "https://cards.scryfall.io/normal/front/8/1/81fa8865-c600-4d8d-976d-789cd565802e.jpg?1783922619",
    releaseDate = "2022-06-10",
    rarity = Rarity.COMMON,
    frameEffects = listOf("showcase"),
)
