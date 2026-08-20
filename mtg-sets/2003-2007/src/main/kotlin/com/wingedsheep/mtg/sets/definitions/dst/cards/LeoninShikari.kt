package com.wingedsheep.mtg.sets.definitions.dst.cards

import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.EquipAbilitiesAtInstantSpeed

/**
 * Leonin Shikari — Darksteel #6
 * {1}{W}
 * Creature — Cat Soldier
 * 2/2
 * You may activate equip abilities any time you could cast an instant.
 */
val LeoninShikari = card("Leonin Shikari") {
    manaCost = "{1}{W}"
    colorIdentity = "W"
    typeLine = "Creature — Cat Soldier"
    oracleText = "You may activate equip abilities any time you could cast an instant."
    power = 2
    toughness = 2

    // The unconditional static grant lifts equip's normal sorcery-speed activation restriction.
    staticAbility {
        ability = EquipAbilitiesAtInstantSpeed
    }

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "6"
        artist = "Wayne England"
        flavorText = "Her instinct is as sharp as her blade."
        imageUri = "https://cards.scryfall.io/normal/front/5/6/56b2a0f5-e340-4a85-aa5c-340e7f8231b0.jpg?1783944454"
        ruling(
            "2004-12-01",
            "An equip ability uses the keyword ability \u201cequip.\u201d Normally, equip abilities can be activated only any time you could cast a sorcery."
        )
        ruling(
            "2004-12-01",
            "Leonin Shikari allows you to move your Equipment around during the combat phase or in response to a spell or ability. You\u2019re still subject to any other restrictions on activating equip abilities."
        )
    }
}
