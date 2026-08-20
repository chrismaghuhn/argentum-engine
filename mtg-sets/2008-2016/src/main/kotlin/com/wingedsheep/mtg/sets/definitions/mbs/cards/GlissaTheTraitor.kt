package com.wingedsheep.mtg.sets.definitions.mbs.cards

import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.TriggerBinding
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import com.wingedsheep.sdk.scripting.targets.TargetObject

/**
 * Glissa, the Traitor
 * {B}{G}{G}
 * Legendary Creature — Phyrexian Zombie Elf
 * 3/3
 * First strike, deathtouch
 * Whenever a creature an opponent controls dies, you may return target artifact card from your
 * graveyard to your hand.
 */
val GlissaTheTraitor = card("Glissa, the Traitor") {
    manaCost = "{B}{G}{G}"
    colorIdentity = "BG"
    typeLine = "Legendary Creature — Phyrexian Zombie Elf"
    power = 3
    toughness = 3
    oracleText = "First strike, deathtouch\n" +
        "Whenever a creature an opponent controls dies, you may return target artifact card from " +
        "your graveyard to your hand."

    keywords(Keyword.FIRST_STRIKE, Keyword.DEATHTOUCH)

    triggeredAbility {
        trigger = Triggers.leavesBattlefield(
            filter = GameObjectFilter.Creature.opponentControls(),
            to = Zone.GRAVEYARD,
            binding = TriggerBinding.ANY,
        )
        optional = true
        val target = target(
            "target",
            TargetObject(
                filter = TargetFilter(GameObjectFilter.Artifact.ownedByYou(), zone = Zone.GRAVEYARD),
            ),
        )
        effect = Effects.Move(target, Zone.HAND)
    }

    metadata {
        rarity = Rarity.MYTHIC
        collectorNumber = "96"
        artist = "Chris Rahn"
        imageUri = "https://cards.scryfall.io/normal/front/7/5/755e0fbf-4f00-4b05-a535-27e78e96d6b6.jpg?1783941371"
    }
}
