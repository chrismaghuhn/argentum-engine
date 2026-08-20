package com.wingedsheep.mtg.sets.definitions.m20.cards

import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.TriggerBinding

/**
 * Moldervine Reclamation — M20 #214
 * {3}{B}{G} · Enchantment
 *
 * Whenever a creature you control dies, you gain 1 life and draw a card.
 */
val MoldervineReclamation = card("Moldervine Reclamation") {
    manaCost = "{3}{B}{G}"
    colorIdentity = "BG"
    typeLine = "Enchantment"
    oracleText = "Whenever a creature you control dies, you gain 1 life and draw a card."

    triggeredAbility {
        trigger = Triggers.leavesBattlefield(
            filter = GameObjectFilter.Creature.youControl(),
            to = Zone.GRAVEYARD,
            binding = TriggerBinding.ANY,
        )
        effect = Effects.Composite(
            Effects.GainLife(1),
            Effects.DrawCards(1),
        )
        description = "Whenever a creature you control dies, you gain 1 life and draw a card."
    }

    metadata {
        rarity = Rarity.UNCOMMON
        collectorNumber = "214"
        artist = "Antonio José Manzanedo"
        imageUri = "https://cards.scryfall.io/normal/front/6/1/618d21fe-8e3d-4887-b2e4-b92194ba1902.jpg?1783932949"
    }
}
