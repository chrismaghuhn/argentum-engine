package com.wingedsheep.mtg.sets.definitions.m21.cards

import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.TriggerBinding
import com.wingedsheep.sdk.scripting.events.DamageType
import com.wingedsheep.sdk.scripting.events.RecipientFilter
import com.wingedsheep.sdk.scripting.references.Player
import com.wingedsheep.sdk.scripting.targets.EffectTarget

/**
 * Hooded Blightfang (M21 #104)
 * {2}{B}
 * Creature — Snake, 1/4
 *
 * Deathtouch
 * Whenever a creature you control with deathtouch attacks, each opponent loses 1 life and you
 * gain 1 life.
 * Whenever a creature you control with deathtouch deals damage to a planeswalker, destroy that
 * planeswalker.
 */
val HoodedBlightfang = card("Hooded Blightfang") {
    manaCost = "{2}{B}"
    colorIdentity = "B"
    typeLine = "Creature — Snake"
    power = 1
    toughness = 4
    oracleText = "Deathtouch\n" +
        "Whenever a creature you control with deathtouch attacks, each opponent loses 1 life " +
        "and you gain 1 life.\n" +
        "Whenever a creature you control with deathtouch deals damage to a planeswalker, " +
        "destroy that planeswalker."

    keywords(Keyword.DEATHTOUCH)

    triggeredAbility {
        trigger = Triggers.attacks(
            filter = GameObjectFilter.Creature.youControl().withKeyword(Keyword.DEATHTOUCH),
            binding = TriggerBinding.ANY,
        )
        effect = Effects.Composite(
            Effects.LoseLife(1, EffectTarget.PlayerRef(Player.EachOpponent)),
            Effects.GainLife(1),
        )
    }

    triggeredAbility {
        trigger = Triggers.dealsDamage(
            damageType = DamageType.Any,
            recipient = RecipientFilter.Matching(GameObjectFilter.Planeswalker),
            sourceFilter = GameObjectFilter.Creature.youControl().withKeyword(Keyword.DEATHTOUCH),
            binding = TriggerBinding.ANY,
        )
        effect = Effects.Destroy(EffectTarget.DamageRecipient)
    }

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "104"
        artist = "Uriah Voth"
        imageUri = "https://cards.scryfall.io/normal/front/a/c/ac38a51f-9a3b-451c-b72d-6d4e0b296fbd.jpg?1783930706"
    }
}
