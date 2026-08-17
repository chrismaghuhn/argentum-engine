package com.wingedsheep.mtg.sets.definitions.mh1.cards

import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Targets
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.targets.EffectTarget

/**
 * Generous Gift
 * {2}{W}
 * Instant
 * Destroy target permanent. Its controller creates a 3/3 green Elephant creature token.
 */
val GenerousGift = card("Generous Gift") {
    manaCost = "{2}{W}"
    colorIdentity = "W"
    typeLine = "Instant"
    oracleText = "Destroy target permanent. Its controller creates a 3/3 green Elephant creature token."

    spell {
        val target = target("target", Targets.Permanent)
        effect = Effects.Composite(
            listOf(
                Effects.Destroy(target),
                Effects.CreateToken(
                    power = 3,
                    toughness = 3,
                    colors = setOf(Color.GREEN),
                    creatureTypes = setOf("Elephant"),
                    controller = EffectTarget.TargetController,
                ),
            ),
        )
    }

    metadata {
        rarity = Rarity.UNCOMMON
        collectorNumber = "11"
        artist = "Kev Walker"
        imageUri = "https://cards.scryfall.io/normal/front/9/8/983f4711-20a8-4023-8201-9a74deab10be.jpg?1783933163"
    }
}
