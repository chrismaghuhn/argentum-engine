package com.wingedsheep.mtg.sets.definitions.cmr.cards

import com.wingedsheep.sdk.model.Printing
import com.wingedsheep.sdk.model.Rarity

/**
 * Fleshbag Marauder reprints in Commander Legends. Canonical CardDefinition lives in Shards of Alara.
 * CMR printed both the main (#128) and extended-art (#648) collector numbers.
 */
val FleshbagMarauderReprint = Printing(
    oracleId = "4b1bf05e-753e-4350-a913-894cf3cecc0c",
    name = "Fleshbag Marauder",
    setCode = "CMR",
    collectorNumber = "128",
    scryfallId = "4002b3a4-e00e-44ed-8989-d553e5d7d6c8",
    artist = "Mark Zug",
    imageUri = "https://cards.scryfall.io/normal/front/4/0/4002b3a4-e00e-44ed-8989-d553e5d7d6c8.jpg?1783928836",
    releaseDate = "2020-11-20",
    rarity = Rarity.COMMON,
)

val FleshbagMarauderReprintExtended = Printing(
    oracleId = "4b1bf05e-753e-4350-a913-894cf3cecc0c",
    name = "Fleshbag Marauder",
    setCode = "CMR",
    collectorNumber = "648",
    scryfallId = "712c8708-cd43-4f1e-b520-ef90cf2ba72d",
    artist = "Mark Zug",
    imageUri = "https://cards.scryfall.io/normal/front/7/1/712c8708-cd43-4f1e-b520-ef90cf2ba72d.jpg?1783928618",
    releaseDate = "2020-11-20",
    rarity = Rarity.COMMON,
)
