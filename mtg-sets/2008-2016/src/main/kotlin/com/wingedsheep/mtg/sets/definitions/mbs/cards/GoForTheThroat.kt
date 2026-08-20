package com.wingedsheep.mtg.sets.definitions.mbs.cards

import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import com.wingedsheep.sdk.scripting.targets.TargetCreature

/**
 * Go for the Throat
 * {1}{B}
 * Instant
 * Destroy target nonartifact creature.
 */
val GoForTheThroat = card("Go for the Throat") {
    manaCost = "{1}{B}"
    colorIdentity = "B"
    typeLine = "Instant"
    oracleText = "Destroy target nonartifact creature."

    spell {
        val target = target("target", TargetCreature(filter = TargetFilter.Creature.nonartifact()))
        effect = Effects.Destroy(target)
    }

    metadata {
        rarity = Rarity.UNCOMMON
        collectorNumber = "43"
        artist = "David Rapoza"
        imageUri = "https://cards.scryfall.io/normal/front/1/c/1c665cfc-7e9a-444b-96b5-e8e4ef57a98a.jpg?1783941384"
    }
}
