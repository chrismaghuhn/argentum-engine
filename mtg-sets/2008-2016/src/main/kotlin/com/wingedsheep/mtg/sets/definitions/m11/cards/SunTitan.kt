package com.wingedsheep.mtg.sets.definitions.m11.cards

import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import com.wingedsheep.sdk.scripting.targets.TargetObject

/**
 * Sun Titan
 * {4}{W}{W}
 * Creature — Giant
 * 6/6
 * Vigilance
 * Whenever this creature enters or attacks, you may return target permanent card with mana value
 * 3 or less from your graveyard to the battlefield.
 */
val SunTitan = card("Sun Titan") {
    manaCost = "{4}{W}{W}"
    colorIdentity = "W"
    typeLine = "Creature — Giant"
    oracleText =
        "Vigilance\n" +
            "Whenever this creature enters or attacks, you may return target permanent card with " +
            "mana value 3 or less from your graveyard to the battlefield."
    power = 6
    toughness = 6

    keywords(Keyword.VIGILANCE)

    triggeredAbility {
        trigger = Triggers.EntersBattlefield
        optional = true
        val permanentCard = target(
            "target permanent card with mana value 3 or less from your graveyard",
            TargetObject(
                filter = TargetFilter(
                    GameObjectFilter.Permanent.manaValueAtMost(3),
                    zone = Zone.GRAVEYARD,
                ),
            ),
        )
        effect = Effects.Move(permanentCard, Zone.BATTLEFIELD, fromZone = Zone.GRAVEYARD)
    }

    triggeredAbility {
        trigger = Triggers.Attacks
        optional = true
        val permanentCard = target(
            "target permanent card with mana value 3 or less from your graveyard",
            TargetObject(
                filter = TargetFilter(
                    GameObjectFilter.Permanent.manaValueAtMost(3),
                    zone = Zone.GRAVEYARD,
                ),
            ),
        )
        effect = Effects.Move(permanentCard, Zone.BATTLEFIELD, fromZone = Zone.GRAVEYARD)
    }

    metadata {
        rarity = Rarity.MYTHIC
        collectorNumber = "35"
        artist = "Todd Lockwood"
        imageUri = "https://cards.scryfall.io/normal/front/d/8/d8db2b8e-dce9-49b7-833f-381ee55288cb.jpg?1783941831"
    }
}
