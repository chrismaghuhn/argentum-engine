package com.wingedsheep.mtg.sets.definitions.rtr.cards

import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import com.wingedsheep.sdk.scripting.targets.TargetObject

/**
 * Abrupt Decay — Return to Ravnica #141 (canonical printing).
 * {B}{G} · Instant
 *
 * This spell can't be countered.
 * Destroy target nonland permanent with mana value 3 or less.
 *
 * Oracle verified against Scryfall RTR #141 on 2026-08-17.
 */
val AbruptDecay = card("Abrupt Decay") {
    manaCost = "{B}{G}"
    colorIdentity = "BG"
    typeLine = "Instant"
    cantBeCountered = true
    oracleText = "This spell can't be countered.\n" +
        "Destroy target nonland permanent with mana value 3 or less."

    spell {
        val target = target(
            "target nonland permanent with mana value 3 or less",
            TargetObject(
                filter = TargetFilter(GameObjectFilter.NonlandPermanent.manaValueAtMost(3)),
            ),
        )
        effect = Effects.Destroy(target)
    }

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "141"
        artist = "Svetlin Velinov"
        imageUri = "https://cards.scryfall.io/normal/front/3/b/3b1e92b4-6e53-4dba-a572-c67e01965ac5.jpg?1783940344"
    }
}
