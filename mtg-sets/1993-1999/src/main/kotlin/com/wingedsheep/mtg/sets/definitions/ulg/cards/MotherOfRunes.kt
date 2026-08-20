package com.wingedsheep.mtg.sets.definitions.ulg.cards

import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Targets
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity

/**
 * Mother of Runes — Urza's Legacy #14
 * {W} · Creature — Human Cleric · 1/1
 * {T}: Target creature you control gains protection from the color of your choice until end of turn.
 */
val MotherOfRunes = card("Mother of Runes") {
    manaCost = "{W}"
    colorIdentity = "W"
    typeLine = "Creature — Human Cleric"
    oracleText = "{T}: Target creature you control gains protection from the color of your choice until end of turn."
    power = 1
    toughness = 1

    activatedAbility {
        cost = Costs.Tap
        val creature = target("target creature you control", Targets.CreatureYouControl)
        effect = Effects.ChooseColorThen(
            Effects.GrantProtectionFromChosenColor(creature)
        )
    }

    metadata {
        rarity = Rarity.UNCOMMON
        collectorNumber = "14"
        artist = "Scott M. Fischer"
        imageUri = "https://cards.scryfall.io/normal/front/0/b/0b1a46ab-95cb-4c24-924f-fc2afd4fcac7.jpg?1783946251"
    }
}
