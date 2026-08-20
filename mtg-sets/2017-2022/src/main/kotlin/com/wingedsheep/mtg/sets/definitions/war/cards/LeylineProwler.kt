package com.wingedsheep.mtg.sets.definitions.war.cards

import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.AbilityCost
import com.wingedsheep.sdk.scripting.TimingRule

/**
 * Leyline Prowler
 * {1}{B}{G}
 * Creature — Nightmare Beast
 * 2/3
 * Deathtouch, lifelink
 * {T}: Add one mana of any color.
 */
val LeylineProwler = card("Leyline Prowler") {
    manaCost = "{1}{B}{G}"
    colorIdentity = "BG"
    typeLine = "Creature — Nightmare Beast"
    power = 2
    toughness = 3
    oracleText = "Deathtouch, lifelink\n{T}: Add one mana of any color."

    keywords(Keyword.DEATHTOUCH, Keyword.LIFELINK)

    activatedAbility {
        cost = AbilityCost.Tap
        effect = Effects.AddAnyColorMana(1)
        manaAbility = true
        timing = TimingRule.ManaAbility
        description = "{T}: Add one mana of any color."
    }

    metadata {
        rarity = Rarity.UNCOMMON
        collectorNumber = "202"
        artist = "YW Tang"
        imageUri = "https://cards.scryfall.io/normal/front/c/5/c56b4e8f-d48e-4bb0-883d-29f978033f65.jpg?1783933395"
    }
}
