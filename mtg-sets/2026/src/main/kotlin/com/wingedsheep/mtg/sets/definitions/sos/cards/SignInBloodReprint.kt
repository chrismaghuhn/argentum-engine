package com.wingedsheep.mtg.sets.definitions.sos.cards

import com.wingedsheep.sdk.model.Printing
import com.wingedsheep.sdk.model.Rarity

/**
 * Sign in Blood faces in the two SOS `Scheming Silvertongue // Sign in Blood` prepare printings.
 * Scryfall exposes the physical prepare card under the front-face name and a separate
 * prepare-layout oracle ID. These rows retain Sign in Blood's canonical oracle identity so the
 * face is discoverable through the existing M10 CardDefinition, matching the existing
 * SwordsToPlowsharesReprint pattern.
 */
val SignInBloodSos99Reprint = Printing(
    oracleId = "c6207f6a-a624-4754-88f5-dbe700c841ff",
    name = "Sign in Blood",
    setCode = "SOS",
    collectorNumber = "99",
    scryfallId = "fe85a124-0d8b-4a29-8df1-65888a39147f",
    artist = "Anna Steinbauer",
    imageUri = "https://cards.scryfall.io/normal/front/f/e/fe85a124-0d8b-4a29-8df1-65888a39147f.jpg?1783903675",
    releaseDate = "2026-04-24",
    rarity = Rarity.RARE,
)

val SignInBloodSos329Reprint = Printing(
    oracleId = "c6207f6a-a624-4754-88f5-dbe700c841ff",
    name = "Sign in Blood",
    setCode = "SOS",
    collectorNumber = "329",
    scryfallId = "1c4c2765-2fb0-43f2-9e50-934405d108d2",
    artist = "Anna Steinbauer",
    imageUri = "https://cards.scryfall.io/normal/front/1/c/1c4c2765-2fb0-43f2-9e50-934405d108d2.jpg?1783903594",
    releaseDate = "2026-04-24",
    rarity = Rarity.RARE,
)
