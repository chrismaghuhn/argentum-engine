package com.wingedsheep.mtg.sets.definitions.tmp.cards

import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Counters
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Targets
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import com.wingedsheep.sdk.scripting.targets.TargetCreature

/**
 * Bounty Hunter
 * {2}{B}{B}
 * Creature — Human Archer Minion
 * 2/2
 *
 * {T}: Put a bounty counter on target nonblack creature.
 * {T}: Destroy target creature with a bounty counter on it.
 */
val BountyHunter = card("Bounty Hunter") {
    manaCost = "{2}{B}{B}"
    colorIdentity = "B"
    typeLine = "Creature — Human Archer Minion"
    power = 2
    toughness = 2
    oracleText = "{T}: Put a bounty counter on target nonblack creature.\n" +
        "{T}: Destroy target creature with a bounty counter on it."

    activatedAbility {
        cost = Costs.Tap
        val target = target(
            "target nonblack creature",
            TargetCreature(filter = TargetFilter.Creature.notColor(Color.BLACK)),
        )
        effect = Effects.AddCounters(Counters.BOUNTY, 1, target)
    }

    activatedAbility {
        cost = Costs.Tap
        val target = target(
            "target creature with a bounty counter on it",
            TargetCreature(filter = TargetFilter(GameObjectFilter.Creature.withCounter(Counters.BOUNTY))),
        )
        effect = Effects.Destroy(target)
    }

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "110"
        artist = "Brian Snõddy"
        imageUri = "https://cards.scryfall.io/normal/front/9/8/98319fd3-0aad-4fc3-bb83-3c027d0ed652.jpg?1783946646"
    }
}
