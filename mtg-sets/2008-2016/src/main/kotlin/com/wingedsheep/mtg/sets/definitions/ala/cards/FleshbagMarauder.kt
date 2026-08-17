package com.wingedsheep.mtg.sets.definitions.ala.cards

import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.references.Player
import com.wingedsheep.sdk.scripting.targets.EffectTarget

/**
 * Fleshbag Marauder
 * {2}{B}
 * Creature — Zombie Warrior
 * 3/1
 * When this creature enters, each player sacrifices a creature of their choice.
 */
val FleshbagMarauder = card("Fleshbag Marauder") {
    manaCost = "{2}{B}"
    colorIdentity = "B"
    typeLine = "Creature — Zombie Warrior"
    oracleText = "When this creature enters, each player sacrifices a creature of their choice."
    power = 3
    toughness = 1

    triggeredAbility {
        trigger = Triggers.EntersBattlefield
        effect = Effects.Sacrifice(
            filter = GameObjectFilter.Creature,
            target = EffectTarget.PlayerRef(Player.Each),
        )
    }

    metadata {
        rarity = Rarity.UNCOMMON
        collectorNumber = "76"
        artist = "Pete Venters"
        flavorText = "Grixis is a world where the only things found in abundance are death and decay. " +
            "Corpses, whole or in part, are the standard currency among necromancers and demons."
        imageUri = "https://cards.scryfall.io/normal/front/f/7/f71e4391-04a8-4df8-9d52-3a3480bcd5b6.jpg?1783942567"
        ruling(
            "2020-11-10",
            "When its ability resolves, you may sacrifice Fleshbag Marauder itself. If you control no " +
                "other creatures, you'll have to sacrifice Fleshbag Marauder.",
        )
        ruling(
            "2020-11-10",
            "As Fleshbag Marauder's ability resolves, first the player whose turn it is chooses a creature " +
                "to sacrifice, then each other player in turn order does the same knowing the choices made " +
                "by players who chose before them. Then all those creatures are sacrificed simultaneously.",
        )
    }
}
