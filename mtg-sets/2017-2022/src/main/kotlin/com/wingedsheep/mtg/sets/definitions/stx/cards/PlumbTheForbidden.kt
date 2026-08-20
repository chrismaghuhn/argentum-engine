package com.wingedsheep.mtg.sets.definitions.stx.cards

import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.DynamicAmounts
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.targets.EffectTarget

/**
 * Plumb the Forbidden — Strixhaven: School of Mages #81.
 *
 * The optional variable sacrifice is a cast-time payment choice. The completed payment is linked
 * to the copy rider through the shared cost-paid trigger rail; the copy count is read from the
 * selected permanents retained on the spell's cast payload.
 */
val PlumbTheForbidden = card("Plumb the Forbidden") {
    manaCost = "{1}{B}"
    colorIdentity = "B"
    typeLine = "Instant"
    oracleText = "As an additional cost to cast this spell, you may sacrifice one or more creatures. " +
        "When you do, copy this spell for each creature sacrificed this way.\n" +
        "You draw a card and lose 1 life."

    additionalCost(
        Costs.additional.SacrificePermanents(
            filter = GameObjectFilter.Creature,
            minCount = 0,
        )
    )

    spell {
        effect = Effects.Composite(
            listOf(
                Effects.DrawCards(1, EffectTarget.Controller),
                Effects.LoseLife(1, EffectTarget.Controller),
            )
        )
        costPaidLinkedTrigger(
            effect = Effects.CopyTargetSpell(
                target = EffectTarget.TriggeringEntity,
                copies = DynamicAmounts.permanentsSacrificedThisWay(),
            ),
            descriptionOverride = "When you do, copy this spell for each creature sacrificed this way.",
        )
    }

    metadata {
        rarity = Rarity.UNCOMMON
        collectorNumber = "81"
        artist = "Andrey Kuzinskiy"
        flavorText = "Strixhaven forbade all magic from the Blood Age, so that is where Extus began " +
            "his research."
        imageUri = "https://cards.scryfall.io/normal/front/5/0/5034227f-3b8a-45bf-917c-c2cbd98f2192.jpg?1783927362"
        ruling(
            "4/16/2021",
            "If you sacrifice a creature with a magecraft ability to cast Plumb the Forbidden, " +
                "that creature's ability won't trigger."
        )
        ruling(
            "4/16/2021",
            "If you copy Plumb the Forbidden multiple times, each magecraft ability of a creature " +
                "you control will trigger that many times."
        )
    }
}
