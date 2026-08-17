package com.wingedsheep.mtg.sets.definitions.j22.cards

import com.wingedsheep.sdk.core.Counters
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Targets
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.TimingRule
import com.wingedsheep.sdk.scripting.TriggerBinding
import com.wingedsheep.sdk.scripting.events.RecipientFilter
import com.wingedsheep.sdk.scripting.targets.EffectTarget

/**
 * Termination Facilitator — Jumpstart 2022 #28 (canonical printing, only printing).
 * {1}{B} · Creature — Human Assassin · 1/3
 *
 * {T}: Put a bounty counter on target creature or planeswalker. Activate only as a sorcery.
 * Whenever a creature or planeswalker an opponent controls with a bounty counter on it is dealt
 * damage, destroy it.
 *
 * Oracle verified against Scryfall J22 #28 on 2026-08-17. The damage observer uses the damaged
 * permanent as its triggering entity, so the destroy effect remains independent of the damage
 * source and uses the same named-counter infrastructure as Chevill and Bounty Hunter.
 */
val TerminationFacilitator = card("Termination Facilitator") {
    manaCost = "{1}{B}"
    colorIdentity = "B"
    typeLine = "Creature — Human Assassin"
    power = 1
    toughness = 3
    oracleText = "{T}: Put a bounty counter on target creature or planeswalker. Activate only as a " +
        "sorcery.\n" +
        "Whenever a creature or planeswalker an opponent controls with a bounty counter on it is " +
        "dealt damage, destroy it."

    activatedAbility {
        cost = Costs.Tap
        timing = TimingRule.SorcerySpeed
        val target = target("target creature or planeswalker", Targets.CreatureOrPlaneswalker)
        effect = Effects.AddCounters(Counters.BOUNTY, 1, target)
    }

    triggeredAbility {
        trigger = Triggers.dealsDamage(
            recipient = RecipientFilter.Matching(
                GameObjectFilter.CreatureOrPlaneswalker
                    .opponentControls()
                    .withCounter(Counters.BOUNTY),
            ),
            binding = TriggerBinding.ANY,
        )
        effect = Effects.Destroy(EffectTarget.TriggeringEntity)
    }

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "28"
        artist = "Justine Cruz"
        imageUri = "https://cards.scryfall.io/normal/front/5/d/5db59164-f0b1-4b5a-b821-ad0b2e1612d1.jpg?1783919185"
    }
}
