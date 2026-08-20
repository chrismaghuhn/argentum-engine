package com.wingedsheep.mtg.sets.definitions.c19.cards

import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Conditions
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.KeywordAbility
import com.wingedsheep.sdk.scripting.effects.ConditionalEffect
import com.wingedsheep.sdk.scripting.effects.MayEffect
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import com.wingedsheep.sdk.scripting.targets.TargetObject

/**
 * Sevinne's Reclamation
 * {2}{W}
 * Sorcery
 * Return target permanent card with mana value 3 or less from your graveyard to the battlefield.
 * If this spell was cast from a graveyard, you may copy this spell and may choose a new target for
 * the copy.
 * Flashback {4}{W}
 */
val SevinnesReclamation = card("Sevinne's Reclamation") {
    manaCost = "{2}{W}"
    colorIdentity = "W"
    typeLine = "Sorcery"
    oracleText = "Return target permanent card with mana value 3 or less from your graveyard to the battlefield. " +
        "If this spell was cast from a graveyard, you may copy this spell and may choose a new target for the copy.\n" +
        "Flashback {4}{W} (You may cast this card from your graveyard for its flashback cost. Then exile it.)"

    spell {
        val permanentCard = target(
            "target permanent card with mana value 3 or less from your graveyard",
            TargetObject(filter = TargetFilter.PermanentInYourGraveyard.manaValueAtMost(3)),
        )
        effect = Effects.Move(permanentCard, Zone.BATTLEFIELD, fromZone = Zone.GRAVEYARD)
            .then(
                ConditionalEffect(
                    condition = Conditions.WasCastFromZone(Zone.GRAVEYARD),
                    effect = MayEffect(
                        Effects.CopyTargetSpell(target = EffectTarget.Self),
                        descriptionOverride = "You may copy this spell and choose new targets for the copy",
                    ),
                ),
            )
    }

    keywordAbility(KeywordAbility.flashback("{4}{W}"))

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "5"
        artist = "Zoltan Boros"
        imageUri = "https://cards.scryfall.io/normal/front/7/e/7e68f4df-88ce-4e09-a03c-7edf40bff167.jpg?1783932814"

        ruling(
            "2019-08-23",
            "The copy that Sevinne's Reclamation creates is created on the stack, so it's not \"cast.\" " +
                "Abilities that trigger when a player casts a spell won't trigger. The copy wasn't cast " +
                "from a graveyard, so it won't make another copy of itself."
        )
        ruling(
            "2019-08-23",
            "If you cast Sevinne's Reclamation from your graveyard, any abilities that trigger as the " +
                "permanent card returns to the battlefield will resolve before the copy of Sevinne's " +
                "Reclamation resolves but after new targets for the copy have been chosen."
        )
        ruling(
            "2019-08-23",
            "If the target card is an illegal target by the time Sevinne's Reclamation tries to resolve, " +
                "the spell doesn't resolve. You won't copy it if you cast it from a graveyard."
        )
        ruling("2024-06-07", "A permanent card is an artifact, battle, creature, enchantment, land, or planeswalker card.")
    }
}
