package com.wingedsheep.mtg.sets.definitions.ths.cards

import com.wingedsheep.sdk.dsl.Filters
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.CantBeBlockedExceptBy
import com.wingedsheep.sdk.scripting.GameObjectFilter

/**
 * Prowler's Helm — Theros #219
 * {2} · Artifact — Equipment
 *
 * Equipped creature can't be blocked except by Walls.
 * Equip {2}
 */
val ProwlersHelm = card("Prowler's Helm") {
    manaCost = "{2}"
    colorIdentity = ""
    typeLine = "Artifact — Equipment"
    oracleText = "Equipped creature can't be blocked except by Walls.\nEquip {2}"

    staticAbility {
        ability = CantBeBlockedExceptBy(
            blockerFilter = GameObjectFilter.Creature.withSubtype("Wall"),
            filter = Filters.EquippedCreature,
        )
    }

    equipAbility("{2}")

    metadata {
        rarity = Rarity.UNCOMMON
        collectorNumber = "219"
        artist = "Igor Kieryluk"
        flavorText = "\"The youths prattle on about heroic deeds, but avoiding the noose is a feat more daring than their entire careers.\"\n—Basarios the Blade"
        imageUri = "https://cards.scryfall.io/normal/front/c/1/c100a22c-bf34-42b7-9339-4733698c0935.jpg?1783939718"
    }
}
