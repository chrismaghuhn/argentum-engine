package com.wingedsheep.mtg.sets.definitions.one.cards

import com.wingedsheep.sdk.model.Printing
import com.wingedsheep.sdk.model.Rarity

/**
 * Phyrexian Arena reprints in Phyrexia: All Will Be One.
 *
 * The canonical CardDefinition lives in Apocalypse, its earliest real printing.
 */
val PhyrexianArenaOneReprint = Printing(
    oracleId = "ee579a32-a048-4335-b966-231ba731cdea",
    name = "Phyrexian Arena",
    setCode = "ONE",
    collectorNumber = "104",
    scryfallId = "54f69d43-de01-46a8-b102-b47e23e0e947",
    artist = "Martina Fačková",
    imageUri = "https://cards.scryfall.io/normal/front/5/4/54f69d43-de01-46a8-b102-b47e23e0e947.jpg?1783918042",
    releaseDate = "2023-02-10",
    rarity = Rarity.RARE,
)

val PhyrexianArenaOneBundleReprint = Printing(
    oracleId = "ee579a32-a048-4335-b966-231ba731cdea",
    name = "Phyrexian Arena",
    setCode = "ONE",
    collectorNumber = "283",
    scryfallId = "3b18d219-efde-4cc0-b955-cb71ead88023",
    artist = "Martina Fačková",
    imageUri = "https://cards.scryfall.io/normal/front/3/b/3b18d219-efde-4cc0-b955-cb71ead88023.jpg?1783917968",
    releaseDate = "2023-02-10",
    rarity = Rarity.RARE,
    isPromo = true,
)

val PhyrexianArenaOneExtendedArtReprint = Printing(
    oracleId = "ee579a32-a048-4335-b966-231ba731cdea",
    name = "Phyrexian Arena",
    setCode = "ONE",
    collectorNumber = "384",
    scryfallId = "fb0ecf3a-ac0e-45a6-98ae-8c043c636252",
    artist = "Martina Fačková",
    imageUri = "https://cards.scryfall.io/normal/front/f/b/fb0ecf3a-ac0e-45a6-98ae-8c043c636252.jpg?1783917928",
    releaseDate = "2023-02-10",
    rarity = Rarity.RARE,
    frameEffects = listOf("extendedart"),
)
