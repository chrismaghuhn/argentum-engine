package com.wingedsheep.mtg.sets.definitions.bro.cards

import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Targets
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import com.wingedsheep.sdk.scripting.targets.TargetPermanent

/**
 * Loran of the Third Path — The Brothers' War #12
 * {2}{W} · Legendary Creature — Human Artificer · 2/1
 *
 * Vigilance
 * When Loran enters, destroy up to one target artifact or enchantment.
 * {T}: You and target opponent each draw a card.
 */
val LoranOfTheThirdPath = card("Loran of the Third Path") {
    manaCost = "{2}{W}"
    colorIdentity = "W"
    typeLine = "Legendary Creature — Human Artificer"
    power = 2
    toughness = 1
    oracleText = "Vigilance\n" +
        "When Loran enters, destroy up to one target artifact or enchantment.\n" +
        "{T}: You and target opponent each draw a card."

    keywords(Keyword.VIGILANCE)

    triggeredAbility {
        trigger = Triggers.EntersBattlefield
        target(
            "up to one target artifact or enchantment",
            TargetPermanent(optional = true, filter = TargetFilter.ArtifactOrEnchantment),
        )
        effect = Effects.Destroy(EffectTarget.ContextTarget(0))
        description = "When Loran enters, destroy up to one target artifact or enchantment."
    }

    activatedAbility {
        cost = Costs.Tap
        target("target opponent", Targets.Opponent)
        effect = Effects.Composite(
            Effects.DrawCards(1),
            Effects.DrawCards(1, EffectTarget.ContextTarget(0)),
        )
        description = "You and target opponent each draw a card."
    }

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "12"
        artist = "Steven Belledin"
        imageUri = "https://cards.scryfall.io/normal/front/5/9/59faa45d-868b-4bc7-934c-0e077642e129.jpg?1783920128"
    }
}
