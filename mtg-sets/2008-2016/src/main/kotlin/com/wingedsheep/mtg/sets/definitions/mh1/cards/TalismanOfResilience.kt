package com.wingedsheep.mtg.sets.definitions.mh1.cards

import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.TimingRule
import com.wingedsheep.sdk.scripting.references.Player
import com.wingedsheep.sdk.scripting.targets.EffectTarget

/**
 * Talisman of Resilience (MH1 #234)
 * {2}
 * Artifact
 * {T}: Add {C}.
 * {T}: Add {B} or {G}. This artifact deals 1 damage to you.
 */
val TalismanOfResilience = card("Talisman of Resilience") {
    manaCost = "{2}"
    colorIdentity = "BG"
    typeLine = "Artifact"
    oracleText = "{T}: Add {C}.\n{T}: Add {B} or {G}. This artifact deals 1 damage to you."

    activatedAbility {
        cost = Costs.Tap
        effect = Effects.AddColorlessMana(1)
        manaAbility = true
        timing = TimingRule.ManaAbility
    }

    activatedAbility {
        cost = Costs.Tap
        effect = Effects.AddMana(Color.BLACK)
            .then(Effects.DealDamage(1, EffectTarget.PlayerRef(Player.You)))
        manaAbility = true
        timing = TimingRule.ManaAbility
    }

    activatedAbility {
        cost = Costs.Tap
        effect = Effects.AddMana(Color.GREEN)
            .then(Effects.DealDamage(1, EffectTarget.PlayerRef(Player.You)))
        manaAbility = true
        timing = TimingRule.ManaAbility
    }

    metadata {
        rarity = Rarity.UNCOMMON
        collectorNumber = "234"
        artist = "Lindsey Look"
        imageUri = "https://cards.scryfall.io/normal/front/c/c/cc25a254-edd2-4817-ab80-7373239da7d2.jpg?1783933070"
    }
}
