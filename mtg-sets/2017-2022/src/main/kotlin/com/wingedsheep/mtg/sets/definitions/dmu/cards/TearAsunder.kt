package com.wingedsheep.mtg.sets.definitions.dmu.cards

import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Targets
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.KeywordAbility

/**
 * Tear Asunder — Dominaria United #183 (canonical printing).
 * {1}{G} · Instant
 *
 * Kicker {1}{B} (You may pay an additional {1}{B} as you cast this spell.)
 * Exile target artifact or enchantment. If this spell was kicked, exile target nonland permanent
 * instead.
 *
 * Oracle verified against Scryfall DMU #183 on 2026-08-17.
 */
val TearAsunder = card("Tear Asunder") {
    manaCost = "{1}{G}"
    colorIdentity = "BG"
    typeLine = "Instant"
    oracleText = "Kicker {1}{B} (You may pay an additional {1}{B} as you cast this spell.)\n" +
        "Exile target artifact or enchantment. If this spell was kicked, exile target nonland " +
        "permanent instead."

    keywordAbility(KeywordAbility.kicker("{1}{B}"))

    spell {
        val baseTarget = target("target artifact or enchantment", Targets.ArtifactOrEnchantment)
        effect = Effects.Exile(baseTarget)

        val kickedTarget = kickerTarget("target nonland permanent", Targets.NonlandPermanent)
        kickerEffect = Effects.Exile(kickedTarget)
    }

    metadata {
        rarity = Rarity.UNCOMMON
        collectorNumber = "183"
        artist = "Dave Kendall"
        imageUri = "https://cards.scryfall.io/normal/front/6/2/629aa907-9533-4681-9bf2-9e56450a4cc2.jpg?1783921294"
    }
}
