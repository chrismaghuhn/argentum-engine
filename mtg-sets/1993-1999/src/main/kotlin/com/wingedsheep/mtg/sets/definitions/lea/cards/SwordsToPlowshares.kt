package com.wingedsheep.mtg.sets.definitions.lea.cards

import com.wingedsheep.sdk.dsl.DynamicAmounts
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import com.wingedsheep.sdk.scripting.targets.TargetCreature

/**
 * Swords to Plowshares — Limited Edition Alpha #40
 * {W} · Instant
 *
 * Exile target creature. Its controller gains life equal to its power.
 */
val SwordsToPlowshares = card("Swords to Plowshares") {
    manaCost = "{W}"
    colorIdentity = "W"
    typeLine = "Instant"
    oracleText = "Exile target creature. Its controller gains life equal to its power."

    spell {
        val target = target("target creature", TargetCreature(filter = TargetFilter.Creature))
        effect = Effects.Exile(target)
            .then(Effects.GainLife(DynamicAmounts.targetPower(), EffectTarget.TargetController))
    }

    metadata {
        rarity = Rarity.UNCOMMON
        collectorNumber = "40"
        artist = "Jeff A. Menges"
        imageUri = "https://cards.scryfall.io/normal/front/3/8/386ea9eb-abc1-4862-aa2d-8fb808d79490.jpg?1783948709"
    }
}
