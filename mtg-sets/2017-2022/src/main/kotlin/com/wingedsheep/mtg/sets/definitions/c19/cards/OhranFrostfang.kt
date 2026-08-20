package com.wingedsheep.mtg.sets.definitions.c19.cards

import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.GrantKeyword
import com.wingedsheep.sdk.scripting.TriggerBinding
import com.wingedsheep.sdk.scripting.events.DamageType
import com.wingedsheep.sdk.scripting.events.RecipientFilter
import com.wingedsheep.sdk.scripting.filters.unified.GroupFilter

/**
 * Ohran Frostfang — Commander 2019 #33
 * {3}{G}{G} · Snow Creature — Snake · 2/6
 *
 * Attacking creatures you control have deathtouch.
 * Whenever a creature you control deals combat damage to a player, draw a card.
 */
val OhranFrostfang = card("Ohran Frostfang") {
    manaCost = "{3}{G}{G}"
    colorIdentity = "G"
    typeLine = "Snow Creature — Snake"
    power = 2
    toughness = 6
    oracleText = "Attacking creatures you control have deathtouch.\n" +
        "Whenever a creature you control deals combat damage to a player, draw a card."

    val attackingCreaturesYouControl =
        GroupFilter(GameObjectFilter.Creature.attacking().youControl())

    staticAbility {
        ability = GrantKeyword(Keyword.DEATHTOUCH, attackingCreaturesYouControl)
    }

    triggeredAbility {
        trigger = Triggers.dealsDamage(
            damageType = DamageType.Combat,
            recipient = RecipientFilter.AnyPlayer,
            sourceFilter = GameObjectFilter.Creature.youControl(),
            binding = TriggerBinding.ANY,
        )
        effect = Effects.DrawCards(1)
        description = "Whenever a creature you control deals combat damage to a player, draw a card."
    }

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "33"
        artist = "Torstein Nordstrand"
        imageUri = "https://cards.scryfall.io/normal/front/9/e/9eb08e11-c247-404c-9f40-a12cb7087d0c.jpg?1783932803"
    }
}
