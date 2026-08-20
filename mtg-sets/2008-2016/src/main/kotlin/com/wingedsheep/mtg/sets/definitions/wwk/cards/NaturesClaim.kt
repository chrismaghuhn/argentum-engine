package com.wingedsheep.mtg.sets.definitions.wwk.cards

import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import com.wingedsheep.sdk.scripting.targets.TargetPermanent

/**
 * Nature's Claim
 * {G}
 * Instant
 * Destroy target artifact or enchantment. Its controller gains 4 life.
 */
val NaturesClaim = card("Nature's Claim") {
    manaCost = "{G}"
    colorIdentity = "G"
    typeLine = "Instant"
    oracleText = "Destroy target artifact or enchantment. Its controller gains 4 life."

    spell {
        val target = target("target", TargetPermanent(filter = TargetFilter.ArtifactOrEnchantment))
        effect = Effects.Destroy(target)
            .then(Effects.GainLife(4, EffectTarget.TargetController))
    }

    metadata {
        rarity = Rarity.COMMON
        collectorNumber = "108"
        artist = "Daarken"
        imageUri = "https://cards.scryfall.io/normal/front/6/4/64ae5a91-ac54-4222-832e-d7a740a3f7cb.jpg?1783942043"
    }
}
