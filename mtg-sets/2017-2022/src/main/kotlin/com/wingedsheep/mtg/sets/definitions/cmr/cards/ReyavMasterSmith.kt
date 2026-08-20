package com.wingedsheep.mtg.sets.definitions.cmr.cards

import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.Duration
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.TriggerBinding
import com.wingedsheep.sdk.scripting.targets.EffectTarget

/**
 * Reyav, Master Smith — Commander Legends #290
 *
 * Whenever a creature you control that's enchanted or equipped attacks, that creature gains
 * double strike until end of turn.
 */
val ReyavMasterSmith = card("Reyav, Master Smith") {
    manaCost = "{R}{W}"
    colorIdentity = "RW"
    typeLine = "Legendary Creature — Dwarf Artificer"
    power = 2
    toughness = 2
    oracleText = "Whenever a creature you control that's enchanted or equipped attacks, that creature gains double strike until end of turn."

    triggeredAbility {
        trigger = Triggers.attacks(
            filter = GameObjectFilter.Creature.youControl().equipped(),
            binding = TriggerBinding.ANY,
        )
        effect = Effects.GrantKeyword(
            Keyword.DOUBLE_STRIKE,
            EffectTarget.TriggeringEntity,
            Duration.EndOfTurn,
        )
    }

    metadata {
        rarity = Rarity.UNCOMMON
        collectorNumber = "290"
        artist = "Scott Murphy"
        imageUri = "https://cards.scryfall.io/normal/front/9/0/90307dd6-196d-4d51-9b3f-6ff339882d31.jpg?1783928769"
    }
}
