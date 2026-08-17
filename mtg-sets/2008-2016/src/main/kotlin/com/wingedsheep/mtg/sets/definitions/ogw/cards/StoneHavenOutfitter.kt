package com.wingedsheep.mtg.sets.definitions.ogw.cards

import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.ModifyStats
import com.wingedsheep.sdk.scripting.TriggerBinding
import com.wingedsheep.sdk.scripting.filters.unified.GroupFilter

/**
 * Stone Haven Outfitter
 * {1}{W}
 * Creature — Kor Artificer
 * 2/2
 *
 * Equipped creatures you control get +1/+1.
 * Whenever an equipped creature you control dies, draw a card.
 */
val StoneHavenOutfitter = card("Stone Haven Outfitter") {
    manaCost = "{1}{W}"
    colorIdentity = "W"
    typeLine = "Creature — Kor Artificer"
    power = 2
    toughness = 2
    oracleText = "Equipped creatures you control get +1/+1.\n" +
        "Whenever an equipped creature you control dies, draw a card."

    staticAbility {
        ability = ModifyStats(
            powerBonus = +1,
            toughnessBonus = +1,
            filter = GroupFilter(GameObjectFilter.Creature.youControl().equipped()),
        )
    }

    triggeredAbility {
        trigger = Triggers.leavesBattlefield(
            filter = GameObjectFilter.Creature.youControl().equipped(),
            to = Zone.GRAVEYARD,
            binding = TriggerBinding.ANY,
        )
        effect = Effects.DrawCards(1)
    }

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "37"
        artist = "Jason Rainville"
        imageUri = "https://cards.scryfall.io/normal/front/e/1/e1b98e63-f23d-432a-86ae-f88bdad2f648.jpg?1783937922"
    }
}
