package com.wingedsheep.mtg.sets.definitions.dka.cards

import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Patterns
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.KeywordAbility
import com.wingedsheep.sdk.scripting.effects.DrawCardsEffect


/**
 * Faithless Looting (DKA #87), the canonical definition for its earliest real printing.
 *
 * Current Oracle: "Draw two cards, then discard two cards. Flashback {2}{R}."
 */
val FaithlessLooting = card("Faithless Looting") {
    manaCost = "{R}"
    colorIdentity = "R"
    typeLine = "Sorcery"
    oracleText = "Draw two cards, then discard two cards.\nFlashback {2}{R} (You may cast this card from your graveyard for its flashback cost. Then exile it.)"
    spell {
        effect = Effects.Composite(
            DrawCardsEffect(2),
            Patterns.Hand.discardCards(2)
        )
    }
    keywordAbility(KeywordAbility.flashback("{2}{R}"))
    metadata {
        rarity = Rarity.COMMON
        collectorNumber = "87"
        artist = "Gabor Szikszai"
        flavorText = "\"Avacyn has abandoned us! We have nothing left except what we can take!\""
        imageUri = "https://cards.scryfall.io/normal/front/a/1/a1b0da17-d595-441d-811c-a2d28d2bb232.jpg?1783940820"
        ruling(
            "2016-06-08",
            "You draw two cards and discard two cards all while Faithless Looting is resolving. " +
                "Nothing can happen between the two, and no player may choose to take actions."
        )
    }
}
