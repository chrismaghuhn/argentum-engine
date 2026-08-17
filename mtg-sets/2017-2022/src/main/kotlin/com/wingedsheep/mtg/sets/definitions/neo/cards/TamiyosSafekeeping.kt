package com.wingedsheep.mtg.sets.definitions.neo.cards

import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import com.wingedsheep.sdk.scripting.targets.TargetPermanent

/**
 * Tamiyo's Safekeeping
 * {G}
 * Instant
 * Target permanent you control gains hexproof and indestructible until end of turn. You gain 2 life.
 */
val TamiyosSafekeeping = card("Tamiyo's Safekeeping") {
    manaCost = "{G}"
    colorIdentity = "G"
    typeLine = "Instant"
    oracleText = "Target permanent you control gains hexproof and indestructible until end of turn. You gain 2 life."
    spell {
        val target = target("target permanent", TargetPermanent(filter = TargetFilter.PermanentYouControl))
        effect = Effects.Composite(
            Effects.GrantHexproof(target),
            Effects.GrantKeyword(Keyword.INDESTRUCTIBLE, target),
            Effects.GainLife(2)
        )
    }
    metadata {
        rarity = Rarity.COMMON
        collectorNumber = "211"
        artist = "Aurore Folny"
        imageUri = "https://cards.scryfall.io/normal/front/f/d/fd4b7ee2-de65-4288-872d-486065a4f226.jpg?1783923839"
    }
}
