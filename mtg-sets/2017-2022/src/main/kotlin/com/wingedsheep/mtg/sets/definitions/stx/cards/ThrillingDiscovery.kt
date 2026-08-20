package com.wingedsheep.mtg.sets.definitions.stx.cards

import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Patterns
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.effects.IfYouDoEffect
import com.wingedsheep.sdk.scripting.effects.MayEffect

/**
 * Thrilling Discovery — Strixhaven #243
 * {R}{W}
 * Sorcery
 * You gain 2 life. Then you may discard two cards. If you do, draw three cards.
 */
val ThrillingDiscovery = card("Thrilling Discovery") {
    manaCost = "{R}{W}"
    colorIdentity = "RW"
    typeLine = "Sorcery"
    oracleText = "You gain 2 life. Then you may discard two cards. If you do, draw three cards."
    spell {
        effect = Effects.Composite(
            listOf(
                Effects.GainLife(2),
                MayEffect(
                    effect = IfYouDoEffect(
                        action = Patterns.Hand.discardCards(2),
                        ifYouDo = Effects.DrawCards(3),
                    ),
                    descriptionOverride = "You may discard two cards. If you do, draw three cards.",
                ),
            )
        )
    }
    metadata {
        rarity = Rarity.COMMON
        collectorNumber = "243"
        artist = "Campbell White"
        flavorText = "\"This must be the lost city of Zantafar, where my people lived before becoming nomads. " +
            "Think of the history we'll learn here!\"\n—Quintorius, Lorehold mage-student"
        imageUri = "https://cards.scryfall.io/normal/front/b/a/bac1f45e-1884-490e-a94f-f7d312f0e229.jpg?1783927286"
    }
}
