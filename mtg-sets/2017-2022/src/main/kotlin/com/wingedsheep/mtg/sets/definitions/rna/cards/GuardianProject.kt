package com.wingedsheep.mtg.sets.definitions.rna.cards

import com.wingedsheep.sdk.dsl.Conditions
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.TriggerBinding

/**
 * Guardian Project (RNA #130)
 * {3}{G}
 * Enchantment
 *
 * Whenever a nontoken creature you control enters, if it doesn't have the same name as another
 * creature you control or a creature card in your graveyard, draw a card.
 */
val GuardianProject = card("Guardian Project") {
    manaCost = "{3}{G}"
    colorIdentity = "G"
    typeLine = "Enchantment"
    oracleText = "Whenever a nontoken creature you control enters, if it doesn't have the same " +
        "name as another creature you control or a creature card in your graveyard, draw a card."

    triggeredAbility {
        trigger = Triggers.entersBattlefield(
            filter = GameObjectFilter.Creature.nontoken().youControl(),
            binding = TriggerBinding.ANY,
        )
        interveningIf = Conditions.TriggeringEntityNameNotSharedWithControlledCreatureOrGraveyard
        effect = Effects.DrawCards(1)
    }

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "130"
        artist = "Chris Rallis"
        flavorText = "Simic's strength comes from its diversity."
        imageUri = "https://cards.scryfall.io/normal/front/c/c/ccad6ce0-ddf0-458d-bdae-3d7805fdc775.jpg?1783933671"
    }
}
