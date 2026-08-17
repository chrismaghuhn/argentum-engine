package com.wingedsheep.mtg.sets.definitions.ncc.cards

import com.wingedsheep.sdk.dsl.Conditions
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity

/**
 * Deathreap Ritual — New Capenna Commander #336
 * {2}{B}{G} · Enchantment
 *
 * Morbid — At the beginning of each end step, if a creature died this turn, you may draw a card.
 */
val DeathreapRitual = card("Deathreap Ritual") {
    manaCost = "{2}{B}{G}"
    colorIdentity = "BG"
    typeLine = "Enchantment"
    oracleText = "Morbid — At the beginning of each end step, if a creature died this turn, you may draw a card."

    triggeredAbility {
        trigger = Triggers.EachEndStep
        interveningIf = Conditions.CreatureDiedThisTurn
        optional = true
        effect = Effects.DrawCards(1)
    }

    metadata {
        rarity = Rarity.UNCOMMON
        collectorNumber = "336"
        artist = "Steve Argyle"
        flavorText = "\"All who set foot in Paliano are pawns in someone's play for power.\"\n—Marchesa, the Black Rose"
        imageUri = "https://cards.scryfall.io/normal/front/1/0/10a8b65c-d7b3-48b9-9400-158a60310367.jpg?1783923228"
        ruling(
            "2020-08-07",
            "If a creature didn't die before a turn's end step begins, Deathreap Ritual's ability doesn't trigger at all. " +
                "The creature may have died before Deathreap Ritual entered the battlefield, however.",
        )
        ruling(
            "2020-08-07",
            "You draw one card when the ability resolves, not one card per creature that died during the turn.",
        )
    }
}
