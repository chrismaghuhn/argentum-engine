package com.wingedsheep.mtg.sets.definitions.sos.cards

import com.wingedsheep.sdk.model.Printing
import com.wingedsheep.sdk.model.Rarity

/**
 * Swords to Plowshares faces in the two Secrets of Strixhaven Emeritus of Truce printings.
 * Scryfall exposes the physical card as `Emeritus of Truce // Swords to Plowshares`; the
 * printing rows retain Swords' oracle identity so the face is discoverable with the canonical
 * Swords to Plowshares definition.
 */
val SwordsToPlowsharesSos13Reprint = Printing(
    oracleId = "b1544f21-7e98-461b-aed5-e748b0168c52",
    name = "Swords to Plowshares",
    setCode = "SOS",
    collectorNumber = "13",
    scryfallId = "9869a753-5e41-4098-ab41-e75b4396ec50",
    artist = "Aleksi Briclot",
    imageUri = "https://cards.scryfall.io/normal/front/9/8/9869a753-5e41-4098-ab41-e75b4396ec50.jpg?1783903705",
    releaseDate = "2026-04-24",
    rarity = Rarity.MYTHIC,
)

val SwordsToPlowsharesSos309Reprint = Printing(
    oracleId = "b1544f21-7e98-461b-aed5-e748b0168c52",
    name = "Swords to Plowshares",
    setCode = "SOS",
    collectorNumber = "309",
    scryfallId = "bfd607c0-7ed7-4a4e-abdd-508080f40ef2",
    artist = "Aleksi Briclot",
    imageUri = "https://cards.scryfall.io/normal/front/b/f/bfd607c0-7ed7-4a4e-abdd-508080f40ef2.jpg?1783903600",
    releaseDate = "2026-04-24",
    rarity = Rarity.MYTHIC,
)
