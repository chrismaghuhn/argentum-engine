package com.wingedsheep.mtg.sets.definitions.cns.cards

import com.wingedsheep.sdk.dsl.Conditions
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity

/**
 * Deathreap Ritual — Conspiracy #44
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
        collectorNumber = "44"
        artist = "Steve Argyle"
        flavorText = "\"All who set foot in Paliano are pawns in someone's play for power.\"\n—Marchesa, the Black Rose"
        imageUri = "https://cards.scryfall.io/normal/front/4/e/4ee2c16c-4985-478d-bf68-fdd1e33cdb7e.jpg?1783939372"
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
