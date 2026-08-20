package com.wingedsheep.mtg.sets.definitions.cmr.cards

import com.wingedsheep.sdk.model.Printing
import com.wingedsheep.sdk.model.Rarity

/** Acidic Slime reprints in CMR. The canonical CardDefinition lives in M10. */
val AcidicSlimeCmrDraftReprint = Printing(
    oracleId = "21f45043-5419-4019-8b6c-e5294bd5f549",
    name = "Acidic Slime",
    setCode = "CMR",
    collectorNumber = "421",
    scryfallId = "ea04b4f0-b8c4-43aa-8a2b-b72c22fb4517",
    artist = "Karl Kopinski",
    imageUri = "https://cards.scryfall.io/normal/front/e/a/ea04b4f0-b8c4-43aa-8a2b-b72c22fb4517.jpg?1783928709",
    releaseDate = "2020-11-20",
    rarity = Rarity.UNCOMMON,
)

val AcidicSlimeCmrCommanderReprint = Printing(
    oracleId = "21f45043-5419-4019-8b6c-e5294bd5f549",
    name = "Acidic Slime",
    setCode = "CMR",
    collectorNumber = "673",
    scryfallId = "551afb4c-17e8-424f-ace3-b2c184b16b2a",
    artist = "Karl Kopinski",
    imageUri = "https://cards.scryfall.io/normal/front/5/5/551afb4c-17e8-424f-ace3-b2c184b16b2a.jpg?1783928610",
    releaseDate = "2020-11-20",
    rarity = Rarity.UNCOMMON,
    frameEffects = listOf("extendedart"),
)
