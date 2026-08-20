package com.wingedsheep.mtg.sets.definitions.arc.cards

import com.wingedsheep.sdk.model.Printing
import com.wingedsheep.sdk.model.Rarity

/**
 * Infest reprint in Archenemy. The canonical CardDefinition lives in Onslaught's `cards/`
 * package (the earliest real printing); this file contributes only presentation data.
 */
val InfestReprint = Printing(
    oracleId = "d6850616-7db5-4141-9ab0-ae8d1f08114f",
    name = "Infest",
    setCode = "ARC",
    collectorNumber = "19",
    scryfallId = "ddc3fb0d-53a6-4a03-a927-c64723ebd7ef",
    artist = "Karl Kopinski",
    imageUri = "https://cards.scryfall.io/normal/front/d/d/ddc3fb0d-53a6-4a03-a927-c64723ebd7ef.jpg?1783941914",
    releaseDate = "2010-06-18",
    rarity = Rarity.UNCOMMON,
)
