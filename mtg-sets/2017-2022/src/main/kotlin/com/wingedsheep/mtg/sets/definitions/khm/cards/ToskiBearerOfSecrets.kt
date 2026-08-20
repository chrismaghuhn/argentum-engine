package com.wingedsheep.mtg.sets.definitions.khm.cards

import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.MustAttack
import com.wingedsheep.sdk.scripting.TriggerBinding
import com.wingedsheep.sdk.scripting.events.DamageType
import com.wingedsheep.sdk.scripting.events.RecipientFilter

/**
 * Toski, Bearer of Secrets — Kaldheim #197
 * {3}{G} · Legendary Creature — Squirrel · 1/1
 *
 * This spell can't be countered.
 * Indestructible
 * Toski attacks each combat if able.
 * Whenever a creature you control deals combat damage to a player, draw a card.
 */
val ToskiBearerOfSecrets = card("Toski, Bearer of Secrets") {
    manaCost = "{3}{G}"
    colorIdentity = "G"
    typeLine = "Legendary Creature — Squirrel"
    power = 1
    toughness = 1
    cantBeCountered = true
    oracleText = "This spell can't be countered.\n" +
        "Indestructible\n" +
        "Toski attacks each combat if able.\n" +
        "Whenever a creature you control deals combat damage to a player, draw a card."

    keywords(Keyword.INDESTRUCTIBLE)

    staticAbility {
        ability = MustAttack()
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
        collectorNumber = "197"
        artist = "Jason Rainville"
        imageUri = "https://cards.scryfall.io/normal/front/f/9/f9e79b59-94c8-4697-bf88-f0a0433170f5.jpg?1783928203"
    }
}
