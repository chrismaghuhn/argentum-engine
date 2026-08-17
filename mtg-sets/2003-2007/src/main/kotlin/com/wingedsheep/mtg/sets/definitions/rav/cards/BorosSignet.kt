package com.wingedsheep.mtg.sets.definitions.rav.cards

import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.TimingRule


/**
 * Boros Signet (RAV #255).
 *
 * Current Oracle: "{1}, {T}: Add {R}{W}."
 *
 * The RAV printing is the card's earliest real-expansion printing. Its activated mana ability
 * composes the generic mana-payment and tap costs with the two colored mana effects.
 */
val BorosSignet = card("Boros Signet") {
    manaCost = "{2}"
    colorIdentity = "WR"
    typeLine = "Artifact"
    oracleText = "{1}, {T}: Add {R}{W}."
    activatedAbility {
        cost = Costs.Composite(Costs.Mana("{1}"), Costs.Tap)
        effect = Effects.Composite(Effects.AddMana(Color.RED, 1), Effects.AddMana(Color.WHITE, 1))
        manaAbility = true
        timing = TimingRule.ManaAbility
    }
    metadata {
        rarity = Rarity.COMMON
        collectorNumber = "255"
        artist = "Greg Hildebrandt"
        flavorText = "\"Have you ever held a Boros signet? There's a weight to it that belies its size—a weight of strength and of pride.\"\n—Agrus Kos"
        imageUri = "https://cards.scryfall.io/normal/front/1/b/1bae1f86-4639-4424-b47b-fdc826bf6e97.jpg?1783943601"
    }
}
