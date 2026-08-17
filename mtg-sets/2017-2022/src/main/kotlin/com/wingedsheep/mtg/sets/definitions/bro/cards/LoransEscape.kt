package com.wingedsheep.mtg.sets.definitions.bro.cards

import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import com.wingedsheep.sdk.scripting.targets.TargetPermanent

/**
 * Loran's Escape — The Brothers' War #14
 * {W} · Instant
 *
 * Target artifact or creature gains hexproof and indestructible until end of turn. Scry 1.
 */
val LoransEscape = card("Loran's Escape") {
    manaCost = "{W}"
    colorIdentity = "W"
    typeLine = "Instant"
    oracleText = "Target artifact or creature gains hexproof and indestructible until end of turn. Scry 1."

    spell {
        val target = target(
            "target artifact or creature",
            TargetPermanent(filter = TargetFilter(GameObjectFilter.CreatureOrArtifact))
        )
        effect = Effects.Composite(
            Effects.GrantHexproof(target),
            Effects.GrantKeyword(Keyword.INDESTRUCTIBLE, target),
            Effects.Scry(1),
        )
    }

    metadata {
        rarity = Rarity.COMMON
        collectorNumber = "14"
        artist = "Matt Stewart"
        imageUri = "https://cards.scryfall.io/normal/front/3/7/3765610d-a0c1-4de9-a81b-ffa3eb06454b.jpg?1783920128"
    }
}
