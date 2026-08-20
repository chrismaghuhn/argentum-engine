package com.wingedsheep.mtg.sets.definitions.nph.cards

import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Targets
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.targets.EffectTarget

/**
 * Beast Within
 * {2}{G}
 * Instant
 *
 * Destroy target permanent. Its controller creates a 3/3 green Beast creature token.
 */
val BeastWithin = card("Beast Within") {
    manaCost = "{2}{G}"
    colorIdentity = "G"
    typeLine = "Instant"
    oracleText = "Destroy target permanent. Its controller creates a 3/3 green Beast creature token."

    spell {
        val permanent = target("target permanent", Targets.Permanent)
        effect = Effects.Composite(
            listOf(
                Effects.Destroy(permanent),
                Effects.CreateToken(
                    power = 3,
                    toughness = 3,
                    colors = setOf(Color.GREEN),
                    creatureTypes = setOf("Beast"),
                    controller = EffectTarget.TargetController,
                ),
            ),
        )
    }

    metadata {
        rarity = Rarity.UNCOMMON
        collectorNumber = "103"
        artist = "Dave Allsop"
        imageUri = "https://cards.scryfall.io/normal/front/c/e/ce5b6d19-22e3-4f57-8f4d-a17e982286c7.jpg?1783941304"
        ruling(
            "2021-03-19",
            "If the target permanent is an illegal target by the time Beast Within tries to resolve, " +
                "the spell won't resolve. No player creates a Beast token. If the target is legal but " +
                "not destroyed (most likely because it has indestructible), its controller does create " +
                "a Beast token."
        )
    }
}
