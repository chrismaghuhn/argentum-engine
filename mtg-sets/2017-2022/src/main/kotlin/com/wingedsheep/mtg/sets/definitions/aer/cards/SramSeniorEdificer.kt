package com.wingedsheep.mtg.sets.definitions.aer.cards

import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.GameObjectFilter

/**
 * Sram, Senior Edificer — Aether Revolt #23
 *
 * Whenever you cast an Aura, Equipment, or Vehicle spell, draw a card.
 */
val SramSeniorEdificer = card("Sram, Senior Edificer") {
    manaCost = "{1}{W}"
    colorIdentity = "W"
    typeLine = "Legendary Creature — Dwarf Advisor"
    power = 2
    toughness = 2
    oracleText = "Whenever you cast an Aura, Equipment, or Vehicle spell, draw a card."

    triggeredAbility {
        trigger = Triggers.youCastSpell(
            spellFilter = GameObjectFilter.Any.withAnySubtype("Aura", "Equipment", "Vehicle")
        )
        effect = Effects.DrawCards(1)
    }

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "23"
        artist = "Chris Rahn"
        imageUri = "https://cards.scryfall.io/normal/front/1/b/1b323e2c-59dd-4d70-9a48-b10f807bb818.jpg?1783936777"
    }
}
